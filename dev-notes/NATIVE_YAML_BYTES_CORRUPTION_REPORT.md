# kyo-schema-yaml Native: YamlEventReader bytes() over-reads adjacent heap (recurring)

## Symptom (the CI failure to fix)

Full-matrix streak run `32533077972`, leg **linux-x64 Native**, `kyo.internal.yaml.YamlEventReaderTest`:

```
[FAIL] YamlEventReader › reads scalar primitives directly from event values  (514ms) *** FAILED ***
    observed.bytesValue == bytes.toSeq
    // message: bytesValue actual=ArraySeq(1, 2, 3, 0, 0, 0, 0, 0, -80, 124, 59, 78, 42, 86, 0, 0,
       0, 0, 0, 0, 0, 0, 0, 0, -16, 89, 89, 61, 41, 127, 0, 0, ...hundreds more...)
--- YamlEventReaderTest: 9 passed, 1 failed
[error] (kyo-schema-yamlNative / Test / test) sbt.TestsFailedException: Tests unsuccessful
```

The expected value is a **3-byte** array `Array[Byte](1, 2, 3)`. The actual is an `ArraySeq` whose first
three bytes are correct (`1, 2, 3`) followed by hundreds of bytes of heap garbage. The tail is not random:
it is 8-byte-aligned little-endian words that read as heap pointers (`-80,124,59,78,42,86,0,0` =
`0x0000562a4e3b7cb0`) interleaved with small struct-shaped counts (`1,0,0,0, 8,0,0,0`). This is
**uninitialized/adjacent heap being copied out as if it were the decoded byte content**. Native-only; the
JVM/JS/Wasm legs of the same run passed. Intermittent: the leaf passes on most native runs and this leaf's
other 9 assertions (long/float/short/byte/char/bigInt/bigDecimal/instant/duration) all passed this run.

## Recurrence (this is not the first time)

- Task #46 ("Fix intermittent linux-x64 Native YamlEventReaderTest value mismatch") is open and predates
  this session.
- The test itself was already instrumented for this exact bug by a prior session: commit
  `9e619fec2e [kyo-schema-yaml] name the field and print the actual value on scalar-read mismatch` split
  the assertion per-field precisely because an earlier CI failure "shows only the expected tuple, hiding
  which field diverged." That per-field diagnostic is what let this run pinpoint `bytesValue`.
- So the failure has surfaced across multiple native CI runs and remains unfixed.

## Not caused by the in-flight branch work

- COH is a JVM flag; the Native binary has no COH. The recent COH-decouple commit (`4e689bfec4`) and the
  execStream fix (`481f725911`) touch kyo-pod and the JVM/driver opts only. `kyo-schema-yaml` and `kyo-data`
  are untouched. This is an independent, platform-specific (scala-native 0.5.12) defect.

## The decode path (exact)

Test (`YamlEventReaderTest.scala:120-145`):
```scala
def reader(value: String): YamlEventReader = YamlEventReader(scalarDocument(value))
val bytes = Array[Byte](1, 2, 3)                                   // expected
bytesValue = reader(Base64.getEncoder.encodeToString(bytes)).bytes().toArray.toSeq  // "AQID" -> decode
assert(observed.bytesValue == bytes.toSeq, ...)                   // Seq(1,2,3)
```

`YamlEventReader.bytes()` (`YamlEventReader.scala:91`) delegates to `inner.bytes()`.

`YamlReader.bytes()` (`YamlReader.scala:412-417`):
```scala
def bytes(): Span[Byte] =
    val value = string()                                          // the scalar text, should be "AQID"
    try Span.from(java.util.Base64.getDecoder.decode(value))
    catch case e: IllegalArgumentException => error(s"Invalid Base64: ${e.getMessage}")
```

`YamlReader.string()` (`YamlReader.scala:263-270`) returns the scalar text via
`scalarValue() match { case ScalarValue.Str(value) => value ; ... }`.

## Structural facts that localize the corrupted quantity to a LENGTH

`Span` is a zero-overhead alias for `Array` (`Span.scala:36`): `opaque type Span[+A] = Array[? <: A]`.
There is no separate length field; `Span.length == underlyingArray.length`.

- `Span.from[A: ClassTag](array: Array[A])` (`Span.scala:205-207`) copies: `new Array[A](array.length)` +
  `System.arraycopy(array, 0, copy, 0, array.length)`. Result length == input array length.
- `Span.toArray` (`Span.scala:916-921`) copies: `val size = self.length; new Array[A](size)` +
  `System.arraycopy(self, 0, copy, 0, size)`. Result length == self.length.

Neither introduces a length; both faithfully copy `array.length` elements. Base64 `"AQID"` decodes to
exactly the 3 bytes `[1,2,3]` (verified: A=0 Q=16 I=8 D=3 -> `00000001 00000010 00000011`). So for the
result to be hundreds of bytes with a correct `[1,2,3]` prefix, **`java.util.Base64.getDecoder.decode(value).length`
must itself be huge** (an over-sized array whose first 3 bytes are the real decode and whose tail is
uninitialized heap), OR **`value` (the scalar String) is longer than "AQID"** so the decode legitimately
produces more bytes.

## Two candidate root causes

**Hypothesis A: scala-native `java.util.Base64.Decoder.decode` mis-sizes its output array.**
It allocates the output array from a length estimate that, on this path, is a garbage/uninitialized value;
it decodes the real 3 bytes into the front and returns the over-sized array without trimming, so the tail
is whatever heap those pages held. This fits the evidence best: the tail is 8-byte-aligned heap pointers
(what an uninitialized `Array[Byte]` over old object memory looks like), and the `[1,2,3]` prefix is
exactly correct. Intermittent = the estimate is usually right, occasionally garbage (uninitialized local /
memory-layout dependence). scala-native javalib is not vendored in this repo, so its Base64 source must be
read from the scala-native 0.5.12 distribution to confirm.

**Hypothesis B: `scalarValue()` / `string()` returns a corrupted-length String on Native.**
If the YAML scalar parser builds the `ScalarValue.Str` from a char/byte span whose end index is an
uninitialized/garbage value, `value` is `"AQID"` plus trailing chars, and Base64 legitimately decodes the
extra chars into more bytes. Against this: base64-decoding arbitrary trailing ASCII would (a) often throw
`IllegalArgumentException` (not seen; the catch would have produced an `error(...)`), and (b) produce dense
data, not 8-byte-aligned pointer words. So B is possible but weaker than A. It is still worth ruling out by
printing `value` and `value.length` at the failure.

## Why bytes and not the other scalar fields

The 9 other fields (`long`, `float`, `short`, `byte`, `char`, `bigInt`, `bigDecimal`, `instant`,
`duration`) all parse the scalar String and convert via `toLong`/`toDouble`/`BigInt(...)`/`parse(...)` etc.,
which validate their input and would throw (not silently corrupt) on a too-long String. Only `bytes()`
takes the String into `Base64.decode` and then treats the raw decoded array length as authoritative, so
only it can surface an over-sized-array corruption as a value mismatch. This is consistent with either
hypothesis but especially with A (the corruption lives inside `decode`).

## Reproduction plan (Native, the failing platform)

The mechanism is memory-layout dependent, so reproduce on a real native binary and loop:
1. `scripts/build.sh --env podman --arch x86 sbt 'kyo-schema-yamlNative/testOnly kyo.internal.yaml.YamlEventReaderTest'`
   (or a host native run), looped enough to hit the intermittent case; or a focused native `main` that runs
   `Base64.getDecoder.decode("AQID").length` in a tight loop and asserts `== 3`, which isolates Hypothesis A
   from the YAML layer entirely.
2. If the isolated `decode("AQID").length` ever != 3 on native, the bug is scala-native Base64 (Hypothesis A):
   the kyo-side fix is to stop trusting the decoder's array length, e.g. decode into a known-length target or
   validate/trim to the base64-implied output length before `Span.from`.
3. If `decode` is always correct but `value.length` != 4 at the failure, the bug is the scalar reader
   (Hypothesis B): fix the scalar span end-index handling in the native path.

## Open questions for the held-out reviewer

1. In scala-native 0.5.12's `java.util.Base64.Decoder`, how is the output array length computed for a
   no-padding 4-char input like `"AQID"`, and is there any path where that length is read from an
   uninitialized/garbage value or over-allocated and returned untrimmed? (Confirm/deny Hypothesis A from the
   scala-native javalib source.)
2. Is there a known scala-native 0.5.x issue for `Base64.Decoder.decode` (or `Arrays.copyOf`/`System.arraycopy`
   sizing) returning over-sized arrays? Cross-check against upstream.
3. If A is confirmed, is the correct fix kyo-side (defensively bound the decoded length before `Span.from`),
   or is it a scala-native bug to report upstream and work around here (the standing mandate requires a
   root fix on our side regardless)?
4. Rule out Hypothesis B: could `scalarValue()`/the YAML scalar span produce a `Str` whose backing length is
   uninitialized on native? Where is the scalar's end index set, and is it always initialized before use?
5. Minimal, deterministic fix and the native reproduction that proves it (before/after).
