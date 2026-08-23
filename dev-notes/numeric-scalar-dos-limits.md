# Design Doc: Unified Numeric-Scalar DoS Limits for kyo-schema Codecs

**Author:** kyo maintainers
**Status:** Draft
**Reviewers:** TBD
**Last updated:** 2026-08-04 (file/line references as of branch `kyo-compat-external-bindings`)

## Abstract

kyo-schema enforces two decode-time DoS limits uniformly across every codec — nesting depth (`maxDepth`) and collection size (`maxCollectionSize`) — via shared machinery in `Codec.Reader`. A third limit class, numeric-scalar caps, exists only as private constants inside the YAML reader; JSON, MsgPack, BSON, Protobuf, and both Ion readers have no numeric DoS defense at all. This gap surfaced as a block of wall-clock-threshold DoS tests in `JsonTest` that flaked on slow CI runners and have since been removed as a stopgap. This doc proposes hoisting the numeric caps into the shared `Codec` layer as first-class siblings of the existing limits — a configurable `maxNumberLength` (default 4096) and a fixed `MaxDecimalExponent` (10000) — converging YAML onto them, and enforcing them at each format's numeric-materialization sites, with deterministic rejection tests replacing the removed timing tests.

## 1. Context and scope

### 1.1 The problem

Decoding a `BigDecimal` or `BigInt` from text costs O(n²) in digit count on Scala Native and Scala.js, whose `java.math` implementations are Apache-Harmony-derived. A JSON payload like `"0.<100000 zeros>1"` therefore takes multiple seconds to parse on Native (measured: 2.1 s on an unloaded arm64 machine, 5.166 s on a loaded CI runner). Separately, a compact token like `"1e100000"` (8 bytes) decodes into a `BigDecimal` whose ordinary arithmetic allocates arrays proportional to the exponent — the data-amplification vector of play-json CVE-2020-26882, and of circe #1040/#1363, spray-json #287, jsoniter-scala #282, json4s #554.

kyo-schema's defense posture against decode-time DoS is deliberately *deterministic and structural*: `Codec.Reader` (`kyo-schema/shared/src/main/scala/kyo/Codec.scala:84-131`) carries `maxDepth` and `maxCollectionSize` as mutable limit fields, `checkDepth()` / `checkCollectionSize()` helpers that throw `LimitExceededException("Nesting depth" | "Collection size", actual, maximum)`, and `resetLimits(...)` threaded through the shared `decodeFully`/`readFully` entry points. Every codec participates. Tests assert `Result.Failure(LimitExceededException)` — no timing.

The numeric-scalar limit class never joined this layer. It exists only inside `YamlReader` (`kyo-schema-yaml/shared/src/main/scala/kyo/internal/yaml/YamlReader.scala`) as private machinery:

- `MaxNumericScalarLength = 4096` (line 1970), enforced by `checkNumericScalar` (line 671) with an incidental coupling: the effective cap is `math.min(maxCollectionSize, 4096)`;
- `MaxDecimalExponent = 10000` (line 1969), enforced by `checkDecimalExponent` (line 688) in `bigDecimal()` (line 428).

Because JSON had no such limit, its DoS regression tests had no deterministic outcome to assert and fell back to wall-clock thresholds (`assert(elapsed < 5000)`). A healthy-but-slow Native parse crossed the threshold on a loaded CI runner, producing a flaky failure with no code defect behind it. The timing tests have been removed to green CI (see §8); this doc specifies the proper replacement.

### 1.2 In scope

- A shared numeric-scalar limit mechanism in `Codec.Reader`, matching the design of `maxDepth`/`maxCollectionSize`.
- Enforcement in all six readers: JSON, YAML, MsgPack, BSON, Protobuf, Ion text, Ion binary (YAML by convergence onto the shared mechanism).
- Public API additions (`maxNumberLength` knob and `Default*` mirrors) on each format's decode entry points, following each format's existing limit-parameter convention.
- Deterministic tests: rejection self-tests per format, boundary tests, and the reinstatement of the removed JSON DoS coverage in deterministic form.

### 1.3 Out of scope

- Encode-side changes of any kind.
- Streaming/incremental decoding, memory accounting, or any limit not expressible as a per-value check at a materialization site.
- Changes to the depth/collection limit semantics.

## 2. Goals and non-goals

### Goals

- One numeric-scalar DoS policy, defined once in `kyo-schema`, enforced by every built-in codec.
- Bounded worst-case decode work per numeric value, by construction: no input may trigger super-linear parse work past the cap, and no compact input may decode into an amplification-bomb `BigDecimal`.
- Deterministic, cross-platform (JVM/JS/Native) rejection semantics: `Result.Failure(LimitExceededException)` with stable `limit` strings, so tests assert outcomes, never elapsed time.
- Behavior-neutral YAML convergence at default settings (YAML already enforces 4096/10000).
- Escape hatch for legitimate outliers (large crypto integers as decimal text) via a per-decode knob, mirroring the `maxCollectionSize` contract.

### Non-goals

- **No general string-length limit.** String decoding is O(n) in input the caller already holds; long strings (base64 blobs, LLM payloads in kyo-ai/kyo-mcp) are legitimate and common. The removed "very long string" tests are replaced by exact-outcome assertions, not by a limit.
- **No cap on binary-native numeric payloads** (fixed-width ints/floats, Ion-binary integers, BSON Decimal128): their construction is linear in payload bytes with no super-linear conversion — see §3.4.
- **Not a performance/latency SLO.** This is a bounded-work correctness property, not a benchmark; no test may assert wall-clock time or sleep.
- **No encode-side rejection.** A program may still encode a value whose decode would exceed the default limit; §7.2 discusses this asymmetry.

## 3. Design

### 3.1 Threat model

Two attacker-controlled cost classes, both reachable through every text codec and through the text-encoded bignum payloads of the binary codecs:

1. **Quadratic parse**: `BigDecimal(String)` / `BigInt(String)` are O(n²) in digit count on the Harmony-derived `java.math` used by Scala Native and Scala.js. A 100k-digit coefficient costs seconds; a 1M-digit one, minutes. The JVM is merely faster, not immune (its own parse is super-linear for huge inputs).
2. **Exponent amplification** (CVE-2020-26882 class): `BigDecimal("1e100000")` is cheap to construct (compact scale representation) but any subsequent arithmetic (`v + 1`, `toPlainString`, comparisons across scales) expands to allocations proportional to the exponent. The decoder currently hands this bomb to user code.

The defense: reject the textual form before conversion (class 1), and reject exponents beyond a fixed bound (class 2). Rejection, never truncation — truncation silently corrupts values, and every existing kyo limit rejects with a typed `DecodeException`.

### 3.2 Shared mechanism in `Codec`

In `object Codec` (beside `DefaultMaxDepth = 512` / `DefaultMaxCollectionSize = 100000`, `Codec.scala:59-62`):

```scala
/** Default maximum character length of a numeric scalar's textual form during decoding (DoS limit),
  * shared by every built-in codec. Applies to number tokens in text formats and to text-encoded
  * BigInt/BigDecimal payloads in binary formats, checked before conversion. Binary-native numeric
  * encodings (fixed-width ints/floats, Ion binary integers, BSON Decimal128) decode in linear time
  * and are not subject to this limit.
  */
inline val DefaultMaxNumberLength = 4096

/** Maximum magnitude of a decimal exponent accepted when decoding a BigDecimal (DoS limit), shared
  * by every built-in codec and not configurable (see design rationale): a compact token such as
  * "1e100000" otherwise decodes into a value whose ordinary arithmetic allocates storage
  * proportional to the exponent. 10000 exceeds every standardized decimal format (IEEE 754
  * decimal128 adjusted exponents reach 6144).
  */
inline val MaxDecimalExponent = 10000
```

Static single-implementation helpers in `object Codec` (`private[kyo]`), so that internal parsers that are not `Reader` subclasses (Ion's `IonTextParser`, Ion binary's eager `Parser`) share one implementation and one set of message strings:

```scala
private[kyo] def checkNumberLength(length: Int, maximum: Int)(using Frame): Unit
private[kyo] def checkDecimalExponent(value: String)(using Frame): Unit   // textual exponent
private[kyo] def checkDecimalExponent(exponent: Int)(using Frame): Unit   // already-decoded exponent
```

The text overload ports `YamlReader.checkDecimalExponent` (lines 688-704) exactly, including its early-stop accumulation loop (`while ... && exponent <= MaxDecimalExponent`) so a 10-digit exponent cannot overflow `Int`; magnitude is checked regardless of sign (both directions amplify). The `Int` overload checks `math.abs` with an `Int.MinValue` guard. Failures throw `LimitExceededException("Numeric scalar length" | "Numeric scalar exponent", actual, maximum)` — the exact strings YAML already emits, making convergence observable-behavior-neutral.

On `Codec.Reader` (beside the existing limit fields and checks):

```scala
private[kyo] var maxNumberLength: Int = DefaultMaxNumberLength

private[kyo] def resetLimits(maxDepth: Int, maxCollectionSize: Int, maxNumberLength: Int): Unit

final protected def checkNumberLength(length: Int): Unit          // delegates to the static helper
final protected def checkDecimalExponent(value: String): Unit
final protected def checkDecimalExponent(exponent: Int): Unit
```

`final protected` matches `checkDepth` (called only from reader subclasses; `checkCollectionSize` is public only because `Schema.scala` invokes it externally). `Codec.decodeFully` and `Codec.readFully` gain a `maxNumberLength: Int` parameter with no default: they are `private[kyo]`, and the existing doctrine in that file is that limits are threaded explicitly rather than "trusted to pair up."

### 3.3 Parse-architecture framing and hook placement

The six readers fall into three architectures, which dictates where checks can live:

- **Streaming** (JSON `JsonReader`, MsgPack `MsgPackReader`, Protobuf `ProtobufReader`): reader methods scan bytes on demand, and `resetLimits` always runs before the first read. Hooks are instance checks inside the reader methods, immediately before the `String → BigDecimal/BigInt` (or number-token) conversion.
- **Lazy tree** (Ion text `IonReader`): the document is parsed on first value access (`IonReader.scala:286-297`), *after* `resetLimits`, by an `IonTextParser` that already receives the reader's `maxDepth`/`maxCollectionSize` as constructor arguments. `maxNumberLength` threads identically; hooks go in the parser's `parseNumber`.
- **Eager tree** (Ion binary `IonBinaryReader`, BSON `BsonReader`): the whole document is parsed at reader construction. BSON receives its `Bson.Config` at construction and calls `resetLimits` in its own constructor body, so instance checks at materialization sites work. Ion binary parses **before** `resetLimits` runs (`IonBinaryReader.scala:313-315`, `IonBinary.scala:61-64`), so any check needed during its parse can only reference a constant or construction-time value — this constrains the exponent-cap design (§4) and is satisfied because the exponent cap is a fixed constant.

### 3.4 Per-format hooks

| Format / reader | Hook sites | Notes |
|---|---|---|
| **JSON** `JsonReader` | `readNumber()` (:484): `checkNumberLength(pos - start)` before the token copy. `double()`/`float()` (:254/:230): `checkNumberLength(end - start)` after `FastFloat.scanNumberEnd`, before parse. `bigDecimal()` (:373): `checkNumberLength(s.length)` then `checkDecimalExponent(s)` before `BigDecimal(s)`. `bigInt()` (:364): `checkNumberLength(s.length)` before `BigInt(s)`. | `skip()` gets a new scan-only `skipNumber()` (no allocation, **no check**): skipped unknown-field content is deliberately exempt from all limits, matching `skipObject`/`skipArray` (no depth check) and the pinned skip-depth test. `captureValue()` should also propagate the parent's limits to its sub-reader (Ion binary already does; JSON currently resets to defaults — pre-existing gap). |
| **YAML** `YamlReader` | Hooks pre-exist; converge them (§3.5). All numeric reads already funnel through `resolveScalarValue` (:608-615) and `numberString`; `float`/`double` re-check (:292/:305); `bigDecimal` checks the exponent (:428). | No new sites needed; only delegation to the shared checks and plumbing of the now-real knob. |
| **MsgPack** `MsgPackReader` | `bigInt()` (:332): `checkNumberLength(s.length)`. `bigDecimal()` (:338): `checkNumberLength(s.length)` + `checkDecimalExponent(s)`. | Everything else exempt: fixed-width ints/floats; length-prefixed strings are O(n). |
| **BSON** `BsonReader` | `bigInt()` `StringValue` branch (:248-252): length check. `bigDecimal()` `StringValue` branch (:261-265): length + exponent checks. | `Int32Value`/`Int64Value`/`DoubleValue` branches exempt (fixed-width). `Decimal128Value` (:272) exempt **by construction**: 16-byte format, ≤ 34 digits, adjusted exponent within ±6144 < 10000 — document with a comment. |
| **Protobuf** `ProtobufReader` | `bigInt()` (:381): length check. `bigDecimal()` (:390): length + exponent checks. | proto3 has no arbitrary-precision numeric; kyo maps both to `string` (`Protobuf.scala:495-511`) — Protobuf shares the text-parse hazard in full. Varint/fixed-width numerics exempt. |
| **Ion text** `IonTextParser` | Constructor gains `maxNumberLength` (threaded from the reader var at the single construction site, `IonReader.scala:289`). `parseNumber` (:532-547): one `Codec.checkNumberLength(cleaned.length, maxNumberLength)` covering all branches (radix ints, decimals, plain ints, floats); `Codec.checkDecimalExponent` on the `d/D→E`-normalized token in the `DecNum` branch. | `IonReader.bigDecimal()` needs no hook: `DecNum` was checked at parse; `IntNum → BigDecimal(v)` wraps an existing `BigInt` in O(1). |
| **Ion binary** `IonBinaryReader.Parser` | `readDecimal` (:458-468): after the `isValidInt` guard, `Codec.checkDecimalExponent(exponent.toInt)` against the fixed constant — valid at eager-parse time precisely because the cap is a constant, not a knob. | `readInt`/VarInt/magnitude readers (`BigInt(1, bytes)`) exempt **deliberately**: `BigInteger(int, byte[])` is linear with no radix conversion; payload is bounded by input the caller accepted — same policy as long strings. The timestamp path's internal `readDecimal` inherits the check harmlessly. |
| **Wrappers / in-memory** | `YamlEventReader` and schema-layer wrapper readers forward `resetLimits` (now three-arg) and delegate numeric methods; in-memory value readers materialize from constructed Scala values — no hooks. | |

### 3.5 YAML convergence

- `checkNumericScalar` body becomes `checkNumberLength(value.length)`, **dropping the `math.min(maxCollectionSize, MaxNumericScalarLength)` coupling**. Rationale: a number's textual length is not a collection size; the `min` was a shortcut from when `maxCollectionSize` was the only available knob. At defaults the change is arithmetically neutral (`min(100000, 4096) = 4096`). The one edge case — a caller who set `maxCollectionSize < 4096` and relied on it also tightening numeric scalars — now gets the 4096 default unless they set `maxNumberLength`, a bounded loosening (worst case at 4096 is sub-millisecond) with a strictly more expressive replacement.
- `checkPotentialNumericScalar` stays (YAML-specific pre-resolution triage), delegating to the shared check.
- Private `checkDecimalExponent` and both companion constants are deleted; `bigDecimal()` calls the inherited shared check. Message strings and thresholds are identical, so observable behavior at defaults does not change.
- One existing test depends on the coupling: `YamlParserTest.scala:187-195` rejects an 11-char token by passing `maxCollectionSize = 8` — the collection knob used as a de-facto number-length knob because none existed. It is rewritten to use `maxNumberLength = 8` (same rejection, same `"Numeric scalar length"` assertion, right knob), plus a new test pinning the decoupling (small `maxCollectionSize`, long number, no length rejection). `YamlParserTest.scala:211-217` (exponent cap) is untouched and becomes the cross-format template.

## 4. Key decision: exponent cap as a fixed constant, not a knob

**Recommendation: fixed shared constant (`Codec.MaxDecimalExponent = 10000`); only `maxNumberLength` is a knob.**

- **Legitimacy asymmetry.** Number length has genuine legitimate variance: a 16384-bit integer as decimal text is 4933 digits — over the default — so users need the same escape hatch `maxCollectionSize` provides. Exponent magnitude beyond 10⁴ has no defensible use: IEEE 754 decimal128, the largest standardized decimal, reaches adjusted exponent 6144; nothing physical or financial approaches 10^10000. A knob nobody should turn is API noise multiplied across seven decode surfaces.
- **Architecture.** The Ion-binary parser runs eagerly before `resetLimits` (§3.3), so a per-call exponent knob cannot reach the one place Ion binary needs the check without restructuring reader construction. A constant is visible everywhere, including eager parsers.
- **Precedent.** YAML has shipped the knob-less 10000 cap in production without complaint.
- **Reversibility.** Promoting a constant to a knob later is additive and non-breaking; retiring a knob is a break. Start constrained.
- **Escape hatch remains**: decode the field as `String` and parse with a caller-controlled `MathContext`.

## 5. API surface

`maxNumberLength` follows each format's existing limit-parameter convention. All additions are trailing defaulted parameters or defaulted case-class fields — source-compatible for every existing caller.

| Format | Change |
|---|---|
| Json | `Json.DefaultMaxNumberLength` mirror; `maxNumberLength: Int = DefaultMaxNumberLength` on `decode` and `decodeBytes` (+ complete the `@param` scaladoc on `decode`, which currently documents neither existing limit) |
| Yaml | `Yaml.DefaultMaxNumberLength` mirror; `ReaderConfig` gains `maxNumberLength: Int = DefaultMaxNumberLength`; explicit-limit public overloads gain the defaulted param |
| Ion | `Ion.DefaultMaxNumberLength` mirror; `Ion.Config` gains the field; `newReader` passes it; explicit-limit `decode`/`decodeString` overloads gain the param |
| IonBinary | mirror + param on `decode`/`decodeBytes` (scaladoc states honestly that the length cap is inert for binary-native integers; the exponent cap is what bites) |
| MsgPack | param on `decode`, passed through `Codec.readFully` |
| Bson | `Bson.DefaultMaxNumberLength` mirror; `Bson.Config` gains the field; the `(maxDepth, maxCollectionSize)` convenience overload gains the param |
| Protobuf | param on `decode`, threaded to `resetLimits` |

Binary compatibility: method descriptors and case-class synthetics (`apply`/`copy`/`unapply` of `ReaderConfig`, `Ion.Config`, `Bson.Config`) change. The build runs MiMa in report-only mode for these modules (`mimaCheck(false)` → `mimaFailOnProblem := false`), so this is tolerated per current project policy; call it out in the PR description regardless.

## 6. Alternatives considered

1. **Port YAML's policy into JSON only.** Fixes the visible failure but perpetuates the drift that caused it: three formats' worth of private near-copies of the same policy, with MsgPack/BSON/Protobuf/Ion still exposed through their text-encoded bignum payloads. Rejected; the drift is the root cause.
2. **Tune or platform-split the wall-clock thresholds** (e.g. `if isNative then 20000 else 5000`). Relocates the flake to a larger arbitrary number, keeps tests time-dependent, and does not make "does not DoS" true by construction. Violates the standing no-timing-tests premise. Rejected.
3. **Remove the time-based tests with no replacement defense.** This is the current stopgap (§8 below) and it is honest about what the timing tests actually proved (very little — several passed vacuously or asserted incidental O(n²)-boundedness). But it leaves JSON and the binary/Ion formats with no numeric DoS defense and no regression coverage — exactly the state this doc exists to end. Acceptable only as an interim.
4. **Make the exponent cap a per-call knob.** Symmetric-looking, but blocked by Ion binary's eager parse (§4) and unjustified by any legitimate use; rejected in favor of the fixed constant.
5. **Cap decoded precision/scale instead of raw token length.** Computing precision on an already-materialized Harmony `BigDecimal` can itself be O(n²) (`precision()` may stringify the unscaled value), i.e. the check would pay the cost it exists to prevent. Raw textual length before conversion is O(1), attacker-independent, and conservative. Rejected.

## 7. Cross-cutting concerns

### 7.1 Security / DoS

The two threat classes of §3.1 are closed at every decode boundary: no text-to-bignum conversion runs on more than `maxNumberLength` characters, and no decoded `BigDecimal` carries an exponent magnitude above 10000, regardless of format. Skipped (unknown-field) content remains exempt by design: it is only ever scanned linearly, never converted, and the exemption is already pinned for depth. Worst-case per-value work at the default cap is ~1.7×10⁷ digit operations (n = 4096, Harmony O(n²)) — sub-millisecond even on no-JIT Native.

### 7.2 Backward compatibility

Decode newly rejects: numeric tokens over 4096 chars (all text formats; text-encoded bignums in MsgPack/BSON/Protobuf) and BigDecimal exponents over 10000 (everywhere, including Ion binary's native decimals). This mirrors the accepted `maxCollectionSize` precedent: default decodes already reject well-formed-but-oversized input, with a per-call escape hatch — and YAML has enforced these exact thresholds in production, so "kyo accepts this input" was never uniformly true. Encode is untouched, so a round-trip asymmetry exists for pathological values (a 10k-digit BigInt encodes but no longer decodes at defaults); the same asymmetry already exists for collections beyond `maxCollectionSize`, with the same remedy. Failure-mode shifts (e.g. `LimitExceededException` where an absurd token previously drew `ParseException`) stay within `Result.Failure(DecodeException)`. Sweeps of `JsonTest`, the cross-format suites, and the per-format suites found no existing success-expecting test beyond either cap; the only test edit forced by convergence is the `YamlParserTest` knob switch (§3.5).

### 7.3 Cross-platform correctness

Every check is an integer comparison on a length or exponent the reader computes itself — bit-identical on JVM, JS, and Native. Nothing depends on parse speed, JIT state, or platform `BigDecimal` internals. `LimitExceededException` is shared code. All tests live in `shared/` with no platform conditionals; the Success-side assertions rely only on spec-pinned behavior (Javadoc-specified `BigDecimal.toString`, IEEE-754 overflow-to-`Infinity`), which the Harmony ports implement.

### 7.4 Testing strategy

The proper tests return **with** the limits, all deterministic:

- **JSON** (reinstating the removed DoS coverage in deterministic form): length-cap rejections for the 100k-fractional-zeros BigDecimal and the million-digit Double (`Result.Failure(e: LimitExceededException)`, `e.limit == "Numeric scalar length"`); exponent-cap rejections for `"1e-100000000"`, `"1e1000000000"`, `"1e100000"` (`e.limit == "Numeric scalar exponent"`); the CVE boundary control `"1e9999"` → decodes and re-encodes to exactly `"\"1E+9999\""` (compactness as the structural non-amplification proof); a quoted 1000-digit decimal decoding to its exact value (under-limit success); the skipped-oversized-field document decoding to exactly `MTPerson("Alice", 30)` (skip exemption pin, mirroring the skip-depth pin).
- **Boundary and knob tests** (JSON, template for others): 4096-digit token succeeds / 4097 rejects; `"1e10000"` succeeds (at cap) / `"1e10001"` rejects; `maxNumberLength = 10` rejects a 100-digit token that `maxNumberLength = 200` accepts.
- **YAML**: the knob-switch rewrite plus the decoupling pin (§3.5).
- **MsgPack / BSON / Protobuf**: per-format oversized-payload and exponent rejection self-tests through their string-encoded bignum paths; BSON additionally pins that a max-exponent Decimal128 **succeeds** (the by-construction exemption).
- **Ion text**: oversized plain/hex/decimal tokens rejected; `1d100000` rejected, `1d10000` accepted.
- **Ion binary**: hand-built decimal with exponent 10001 rejected at parse; 10000 accepted; a large binary integer still succeeds (pins the deliberate integer exemption).

No test asserts elapsed time or sleeps, anywhere.

## 8. Current state / stopgap

To unblock CI, the wall-clock DoS tests in `kyo-schema-json/shared/src/test/scala/kyo/JsonTest.scala` (the `assert(elapsed < N)` block: many-digit number, huge positive/negative exponent BigDecimals, million-digit number, fractional-zeros BigDecimal, skipped-large-field, exponent amplification, and the timing halves of the large-object/array/string tests) were **removed without replacement defense**. Until this design lands:

- JSON, MsgPack, BSON, Protobuf, and both Ion readers have **no numeric-scalar DoS defense**; only YAML is protected (via its private constants).
- There is no regression coverage for the numeric DoS class in JSON.
- The round-trip risk introduced later by this design (encode succeeds, decode rejects) exists only for pathological inputs and is the accepted `maxCollectionSize`-style trade.

## 9. Rollout / migration plan

Phased, each phase independently shippable and green:

1. **Phase 1 — shared layer + JSON**: `Codec` constants/helpers/`Reader` members/`decodeFully`/`readFully` threading (arity-only updates at all internal call sites, passing `Codec.DefaultMaxNumberLength`); `JsonReader` hooks; `Json.decode`/`decodeBytes` params; the full JSON test suite of §7.4. This restores the removed DoS coverage deterministically.
2. **Phase 2 — YAML convergence**: delete the private machinery, delegate to shared checks, add the `ReaderConfig` field and mirror, rewrite the one coupled test, add the decoupling pin. Behavior-neutral at defaults.
3. **Phase 3 — binary/Ion enforcement**: MsgPack, BSON, Protobuf hooks + params; Ion text parser threading + hooks; Ion binary exponent check; per-format self-tests.

## Appendix A: concrete edit list (file:line, as of this branch)

1. `kyo-schema/shared/src/main/scala/kyo/Codec.scala` — constants beside :59-62; static helpers in `object Codec`; `Reader` var beside :92-93, three-arg `resetLimits` (:111), `final protected` checks beside :118-131; `decodeFully` (:32) and `readFully` (:71) gain `maxNumberLength`.
2. `kyo-schema-json/shared/src/main/scala/kyo/Json.scala` — mirror beside :22-25; params + scaladoc on `decode` (:62) and `decodeBytes` (:81).
3. `kyo-schema-json/shared/src/main/scala/kyo/internal/JsonReader.scala` — `readNumber` (:484), `double` (:254), `float` (:230), `bigDecimal` (:373), `bigInt` (:364); new `skipNumber()` used by `skip()` (:345); `captureValue` (:626) limit propagation.
4. `kyo-schema-yaml/shared/src/main/scala/kyo/internal/yaml/YamlReader.scala` — converge :671-675, :677-686; delete :688-704 and companion :1969-1970; `bigDecimal` (:428) uses shared check; `resetLimits` override (:76-79). `kyo-schema-yaml/.../YamlEventReader.scala` — override (:14-17). `kyo-schema-yaml/shared/src/main/scala/kyo/Yaml.scala` — `ReaderConfig` (:147-153), mirror, call sites :812, :1042, :1755, explicit-limit helper/overload family (:1673-1807).
5. `kyo-schema-msgpack/shared/src/main/scala/kyo/internal/msgpack/MsgPackReader.scala` — :332-342. `kyo-schema-msgpack/shared/src/main/scala/kyo/MsgPack.scala` — decode (:119-125).
6. `kyo-schema-bson/shared/src/main/scala/kyo/internal/bson/BsonReader.scala` — :243-276 StringValue branches + Decimal128 exemption comment. `kyo-schema-bson/shared/src/main/scala/kyo/Bson.scala` — `Config` (:57-60), mirror (:46-48), overload (:111-118).
7. `kyo-schema-ion/shared/src/main/scala/kyo/internal/IonReader.scala` — `IonTextParser` ctor (:312-317), construction site (:289), `parseNumber` (:532-547). `kyo-schema-ion/shared/src/main/scala/kyo/internal/ionbinary/IonBinaryReader.scala` — `readDecimal` (:458-468) + exemption comments (:438-447). `kyo-schema-ion/shared/src/main/scala/kyo/Ion.scala` — `Config` (:75-80), mirror (:58-62), `newReader` (:25), decode overloads (:182-223). `kyo-schema-ion/shared/src/main/scala/kyo/IonBinary.scala` — decode (:56-75).
8. `kyo-schema-protobuf/shared/src/main/scala/kyo/internal/ProtobufReader.scala` — :381-397. `kyo-schema-protobuf/shared/src/main/scala/kyo/Protobuf.scala` — decode (:72), internal threading (:169-172).
9. Arity-only `resetLimits`/`decodeFully`/`readFully` call-site updates — `Json.scala:67,86`; `Yaml.scala:812,1042,1755`; `Ion.scala:25,213,223`; `IonBinary.scala:63`; `Protobuf.scala:172`; `MsgPack.scala:124`; `BsonReader.scala:42`; `IonBinaryReader.scala:245`.
10. Tests — `kyo-schema-json/shared/src/test/scala/kyo/JsonTest.scala` (deterministic DoS suite per §7.4); `kyo-schema-yaml/shared/src/test/scala/kyo/YamlParserTest.scala:187-195` knob switch + decoupling pin; new self-tests in the MsgPack, BSON, Protobuf (kyo-schema-tests), Ion text, and Ion binary suites.
