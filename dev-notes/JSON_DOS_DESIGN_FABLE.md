# Fable design: deterministic JSON scalar DoS limits and the de-clocking of the `JsonTest` DoS block

> Companion to `JSON_DOS_TIMING_REPORT.md`. This is the design Fable produced (analysis only). It is
> the input to implementation; no code has been changed yet.

## 0. Summary and the key discovery

The failing test block falls back to wall-clock assertions because kyo-schema-json has deterministic
DoS limits for structure (`maxDepth`) and collections (`maxCollectionSize`) but none for numeric
scalars. Core recommendation: **Option A — add a numeric-scalar length limit plus a decimal-exponent
cap to the JSON decoder, and rewrite the affected tests as deterministic rejections**, with the
remaining tests reformulated as exact-outcome assertions (Option B). **No test needs removal (Option C
is exercised zero times).**

Decisive grounding fact: **kyo already ships this defense in the YAML codec.**
`YamlReader` (`kyo-schema-yaml/shared/src/main/scala/kyo/internal/yaml/YamlReader.scala`) enforces:

- `MaxNumericScalarLength = 4096` (line 1970): numeric scalars longer than 4096 chars throw
  `LimitExceededException("Numeric scalar length", len, max)` (lines 671-675);
- `MaxDecimalExponent = 10000` (line 1969): BigDecimal exponents with magnitude above 10000 throw
  `LimitExceededException("Numeric scalar exponent", exp, 10000)` (lines 688-704, from `bigDecimal()` at 428).

So the design ports an existing in-repo policy from YAML to JSON, hoists the length limit into the
shared `Codec` layer as a sibling of `maxDepth`/`maxCollectionSize`, and reuses YAML's exact
`LimitExceededException` message strings so the two codecs converge. It also closes a real asymmetry:
today YAML rejects `1e100000` and 100k-digit decimals while JSON accepts them.

### Decoder facts the classifications rest on (verified in source)

- `JsonReader.bigDecimal()` (`JsonReader.scala:373-380`) is `val s = string(); BigDecimal(s)`. BigDecimal's
  JSON wire format is a **quoted string** (`JsonWriter.bigDecimal` writes `writeQuotedString`,
  `JsonWriter.scala:174-176`); `string()` (149-172) requires a leading `"`. So an **unquoted** numeric
  input decoded as `BigDecimal` fails today with `ParseException("Expected '\"'")` in microseconds
  (matters for test :244).
- `string()` is O(n). The only super-linear scalar work in JSON decode is `BigDecimal(String)`/`BigInt(String)`
  on the Harmony ports (Native and Scala.js) — O(n²) in digit count. `Instant.parse`/`Duration.parse`
  and string decoding are linear.
- `double()`/`float()` (254/230) use `FastFloat.scanNumberEnd` + Eisel-Lemire, O(n), huge inputs bail to
  `readNumber()` + `String.toDouble`, also O(n), yielding `Infinity` deterministically per IEEE 754.
- `readNumber()` (484-504) reads an unbounded token into a fresh String. Called by int/long/short/byte/float/double
  fallbacks, `readStructure()`, and `skip()` (345).
- `skip()` deliberately does not enforce limits; the "skip handles extremely deep nesting in unknown
  field" test (`JsonTest.scala:1938-1950`, depth 100000, asserts `isSuccess`) pins that skipped content is
  exempt. The design preserves this for numbers.
- Limits plumbing: `Codec.Reader` holds `private[kyo] var maxDepth/maxCollectionSize`, `resetLimits(...)`,
  `checkDepth()` (`final protected`), `checkCollectionSize()` (`final def`) — `Codec.scala:84-131`.
  `Codec.decodeFully`/`readFully` (32-44, 71-82, `private[kyo]`) thread them; `Json.decode`/`decodeBytes`
  (`Json.scala:62-87`) expose them, defaults `Codec.DefaultMaxDepth=512`/`DefaultMaxCollectionSize=100000`
  (`Codec.scala:59,62`). Rejections surface as `Result.Failure(LimitExceededException)`
  (`SchemaException.scala:149-151`), the pattern the depth test already asserts (`JsonTest.scala:1745-1751`).
- The established in-repo "does not hang" without timing pattern is already in this file at lines 224-233:
  decode, match on `Result`, `succeed("...terminated...; reaching this branch is the proof")`.

## 1. Per-test classification table

Lines are current worktree positions in `kyo-schema-json/shared/src/test/scala/kyo/JsonTest.scala`.
Limit strings match YamlReader: `"Numeric scalar length"`, `"Numeric scalar exponent"`.

### 1a. The report's seven tests

| # | Test (line) | Option | Replacement assertion | Grounding |
|---|---|---|---|---|
| 1 | "number with many digits does not cause DoS" (:244) | B | Rename to "1000-digit BigDecimal decodes exactly (under number length limit)". `val digits = "0." + "1"*1000; assert(Json.decode[BigDecimal]("\""+digits+"\"").getOrThrow == BigDecimal(digits))` | Current input is UNQUOTED, so today it fails instantly at `string()`'s quote check — the timing assert passes vacuously and never exercises the many-digit parse. Rewrite quotes the input (real wire format), pins exact value under the 4096 limit. |
| 2 | "huge exponent BigDecimal does not hang" (:1758, `"1e-100000000"`) | A (exponent cap) | `Json.decode[BigDecimal]("\"1e-100000000\"")` → `Result.Failure(e: LimitExceededException)`, `assert(e.limit == "Numeric scalar exponent")` | 12-char token, scale 1e8: amplification bomb. YAML already rejects this class. Fallback B: assert Success with `v.precision==1 && v.scale==100000000` + the :224 note. |
| 3 | "million-digit number does not cause DoS" (:1767, `1`+`0`*999999 as Double) | A (length cap) | `Json.decode[Double]("1"+"0"*999999)` → `Failure(LimitExceededException)`, `e.limit == "Numeric scalar length"` | Token length 1e6 > 4096; check after `scanNumberEnd`, before parse. |
| 4 | **"BigDecimal with many fractional zeros does not DoS" (:1865) — the red CI test** | A (length cap) | `val json = "\"0."+"0"*100000+"1\""; assert(Json.decode[BigDecimal](json).isFailure)` then match `Failure(e: LimitExceededException)`, `e.limit == "Numeric scalar length"` | The one input doing O(n²) work today (100002-char coefficient, 2.1s local / 5.166s CI Native). Cap checks `s.length` before `BigDecimal(s)`; rejection instant + identical on JVM/JS/Native. |
| 5 | "huge positive exponent BigDecimal does not hang" (:1875, `"1e1000000000"`) | A (exponent cap) | Same as #2; `e.limit == "Numeric scalar exponent"` | 12-char token, scale −1e9. Exponent accumulator must use YAML's early-stop loop so a 10-digit exponent cannot overflow Int. |
| 6 | "object with unexpected large number field does not DoS" (:1884, MTPerson w/ skipped 100001-digit field) | B | Rename to "...decodes by skipping it". `assert(Json.decode[MTPerson](json).getOrThrow == MTPerson("Alice", 30))` | Big number lands in `skip()`→scan-only, never converted. Keep exempt from the limit to match the pinned skip-is-exempt precedent (:1938), via a scan-only `skipNumber()`. |
| 7 | "small exponent notation does not amplify into huge BigDecimal" (:1895, `"1e100000"`, CVE-2020-26882) | A (exponent cap) + boundary control | (1) `Json.decode[BigDecimal]("\"1e100000\"")` → `Failure(LimitExceededException)`, `e.limit == "Numeric scalar exponent"`. (2) Positive control under cap: `val v = Json.decode[BigDecimal]("\"1e9999\"").getOrThrow; assert(Json.encode(v) == "\"1E+9999\"")` | Rejection IS the anti-amplification defense; boundary leg proves under-cap values stay compact. `BigDecimal.toString` scientific form is Javadoc-specified, stable across JVM/JS/Native. |

### 1b. Five additional wall-clock tests in the same file (premise reaches them)

| Test (line) | Option | Replacement |
|---|---|---|
| "object with many fields does not exhaust memory" (:1918, 100k fields, `elapsed<10000`) | B | Drop clock; `assert(Json.decode[Map[String,Int]](json).getOrThrow.size == 100000)` |
| "large array does not cause DoS" (:1927, 100k ints) | B | `val d = Json.decode[List[Int]](json).getOrThrow; assert(d.size==100000 && d.head==0 && d.last==99999)` |
| "very long string does not cause DoS" (:1957, 1M chars) | B | `assert(Json.decode[String](longStr).getOrThrow.length == 1000000)` (no string-length limit added; see §2.6) |
| "string with many escapes does not cause DoS" (:1966) | B | `assert(Json.decode[String](escapedStr).getOrThrow == "\n"*100000)` |
| "string with many unicode escapes does not cause DoS" (:1975) | B | Delete the two timing lines; assert `Json.decode[String](unicodeStr).getOrThrow == "A"*100000` |

### Option C: exercised for zero tests. Every test admits A or B; removal authorization goes unused.

## 2. Option A design: numeric-scalar limits

### 2.1 Names/homes/API

Shared (`Codec.scala`): `object Codec` gains `inline val DefaultMaxNumberLength = 4096` (scaladoc,
beside 59-62). `Codec.Reader` gains `private[kyo] var maxNumberLength` (beside 92-93), a 3-arg
`resetLimits(maxDepth, maxCollectionSize, maxNumberLength)`, and
`final protected def checkNumberLength(length: Int): Unit` throwing
`LimitExceededException("Numeric scalar length", length, maxNumberLength)` (message equals YAML's).
`decodeFully`/`readFully` gain a `maxNumberLength: Int` parameter (no default — `private[kyo]`, limits
threaded explicitly).

JSON public API (`Json.scala`): `inline val DefaultMaxNumberLength = Codec.DefaultMaxNumberLength`;
`decode`/`decodeBytes` gain `maxNumberLength: Int = DefaultMaxNumberLength` (trailing, defaulted) +
`@param` scaladoc (also complete the missing `@param`s on `decode`).

JSON-local exponent cap (`object JsonReader`): `private val MaxDecimalExponent = 10000` + private
`checkDecimalExponent(s: String): Unit`, a direct port of `YamlReader.checkDecimalExponent`
(688-704): find `e`/`E`, skip sign, accumulate digits WITH the early-stop guard
(`while ... && exponent <= MaxDecimalExponent`, bounding work and preventing Int overflow), throw
`LimitExceededException("Numeric scalar exponent", exponent, MaxDecimalExponent)` when magnitude
exceeds cap (either sign).

Why length is a public knob but the exponent cap is not: length has legitimate variance (a 16384-bit
integer as decimal is 4933 digits, needing a raised limit), so users get the same escape hatch
`maxCollectionSize` gives; exponent magnitude beyond 1e4 has no defensible use (DECIMAL128 max exponent
6144), and YAML ships the knob-less cap already.

### 2.2 Semantics

- Caps **raw token characters**, not decoded precision/scale. Unquoted literals: scanned token byte
  length (ASCII, bytes==chars). BigDecimal/BigInt from JSON strings: `s.length`, checked **before**
  conversion. Sign/point/exponent chars count — a token-size bound, one comparison, zero parsing.
- Scale/precision of the decoded value isn't capped by the length limit; the exponent cap handles the
  one way a tiny token yields a pathological value. Under-cap values round-trip unchanged.
- **Always reject, never truncate** (truncation silently alters values; no kyo limit truncates).
  `LimitExceededException` is a `DecodeException` → surfaces as `Result.Failure`, never a thrown
  exception at the public API. Exactly-at-limit passes (`>` comparison), matching `checkDepth`.

### 2.3 Default `4096` justification

Parity with YAML's `MaxNumericScalarLength=4096`. Headroom: DECIMAL128 needs 34 digits; a 4096-bit
crypto integer is 1234 decimal digits, 8192-bit is 2467 — under 4096; binary128 exact expansions
(~11k) and 16384-bit ints (4933) are first casualties, and have the knob. Bounded worst case: Harmony
O(n²) at n=4096 is ~1.7e7 digit-ops, sub-ms even on no-JIT Native. Sits between `DefaultMaxDepth=512`
and `DefaultMaxCollectionSize=100000`, 2-5 orders below corpus attack inputs (1e5-1e6 digits).

### 2.4 Enforcement hooks in `JsonReader`

| Site | Hook |
|---|---|
| `readNumber()` (484) | After scan loop / `pos==start` error, before copy: `checkNumberLength(pos - start)`. Covers int/long/short/byte/float/double fallbacks + `readStructure()` numeric branch. |
| `double()` (254) / `float()` (230) | Unquoted branch, after `scanNumberEnd`: `checkNumberLength(end - start)` before parse. |
| `bigDecimal()` (373) | After `val s = string()`: `checkNumberLength(s.length)` then `checkDecimalExponent(s)`, both before `BigDecimal(s)`. |
| `bigInt()` (364) | After `val s = string()`: `checkNumberLength(s.length)` before `BigInt(s)`. |
| `skip()` (345) | Replace `discard(readNumber())` with new private `skipNumber()` (scan loop, advances pos, no copy, NO limit check) — preserves the pinned skip-is-exempt precedent (:1938), drops a pointless allocation. |
| int()/long() fast paths (174/202) | No change; pathological tokens fall back to `readNumber` which enforces. |
| `string()`, `instant()`, `duration()`, `bytes()`, `char()` | Unchanged (see §2.6). |

Optional companion: `captureValue()` (626) builds its sub-reader with default limits rather than the
parent's — propagate the parent's three limits (two-line fix) so custom limits work inside captured
sum-type values.

### 2.5 Propagation / cross-platform

Threading follows existing limits: `Json.decode`→`Codec.decodeFully`→`reader.resetLimits(maxDepth,
maxCollectionSize, maxNumberLength)`; pooled-reader reuse covered (resetLimits on every decode). Other
internal `resetLimits`/`decodeFully`/`readFully` sites pass `Codec.DefaultMaxNumberLength`
(behavior-neutral since their readers don't call `checkNumberLength` yet). Two `resetLimits` overrides
(`YamlReader.scala:76`, `YamlEventReader.scala:14`) gain + forward the third parameter. Every check is
an integer comparison; `LimitExceededException` is shared; nothing depends on parse speed/JIT/platform
BigDecimal internals. Tests stay in `shared/`, no platform conditionals; Success-side assertions rely
only on spec-pinned behavior (Javadoc `BigDecimal.toString`; IEEE-754 overflow-to-`Infinity`).

### 2.6 Deliberately NOT limited

No general string-length limit. String decoding is O(n) over in-memory input; long strings (base64,
embedded docs, multi-MB LLM content in kyo-ai/kyo-mcp) are legitimate, so a default cap is a real
regression with no DoS payoff. String DoS tests pin exact O(n) outcomes. Mirrors YAML (its 4096 cap
applies only to numeric-looking scalars).

## 3. Blast radius

1. JSON decode rejects materialized numeric tokens > 4096 chars (default), across all numeric targets +
   `Structure.Value`. Acceptability: same contract as `maxCollectionSize` (already rejects well-formed
   JSON exceeding a resource bound, with a per-call escape hatch); YAML already rejects this exact class
   at this threshold. In-repo `Json.decode` callers (kyo-ai/mcp/http/browser/lsp/pod/jsonrpc-http) use
   defaults and decode protocol-scale numbers; none carries > 4096-char numerics.
2. JSON BigDecimal rejects exponent magnitudes > 10000 (knob-less, matching YAML). `"1e100000"` now
   fails; under-cap values round-trip.
3. Failure-mode shifts: a 5000-digit token → `Int` now fails `LimitExceededException` instead of
   `ParseException` — still `Result.Failure(DecodeException)`; only concrete-subtype matchers (none
   outside tests) notice.
4. Encode untouched → encode/decode asymmetry for absurd values, same precedent as collections; knob
   restores symmetry.
5. Signatures: `decode`/`decodeBytes` gain a trailing defaulted param — source-compatible; binary
   change tolerated (`mimaFailOnProblem := false` for kyo-schema-json, `build.sbt:3292-3297`).
   `Codec.*` are `private[kyo]`.
6. Other codecs: no behavior change this step (pass default; unused inherited var).
7. Test suites: sweep found no existing Success-expecting test decodes a numeric token > 4096 chars or
   exponent > 10000. Nearest: `"1"*100` BigInt (:1072-1082), 29-digit BigDecimals (:182-201, :1044),
   `1e-10000` (:225, exponent at cap and failure-tolerant). Hash-collision + skip-depth pins unaffected.

## 4. Recommendation and concrete edit list

Recommended: Option A machinery (shared `maxNumberLength` default 4096 + JSON-local exponent cap
10000) + tests rewritten per §1 (five A-rewrites, two B in the report block, five B in the sibling
block, zero removals) + new limit self-tests. Zero wall-clock/sleep anywhere in `JsonTest`.

1. `kyo-schema/shared/src/main/scala/kyo/Codec.scala`: `DefaultMaxNumberLength = 4096`;
   `Reader.maxNumberLength`; 3-arg `resetLimits`; `checkNumberLength`; `maxNumberLength` param on
   `decodeFully`/`readFully`.
2. `kyo-schema-json/shared/src/main/scala/kyo/Json.scala`: `DefaultMaxNumberLength` mirror;
   `maxNumberLength` param on `decode`/`decodeBytes`; complete `@param` scaladoc.
3. `kyo-schema-json/shared/src/main/scala/kyo/internal/JsonReader.scala`: hooks per §2.4;
   `skipNumber()`; `checkDecimalExponent` + `MaxDecimalExponent=10000` ported from YAML;
   optional `captureValue` propagation.
4. Arity-only updates (pass `Codec.DefaultMaxNumberLength`, zero behavior change): `Yaml.scala:812,1042,1755`;
   `YamlReader.scala:76` + `YamlEventReader.scala:14` overrides; `Ion.scala:25,213,223`;
   `IonBinary.scala:63`; `Protobuf.scala:172`; `MsgPack.scala:124`.
5. `JsonTest.scala`: rewrite the 12 tests per §1 (delete every `currentTimeMillis`/`elapsed`); add limit
   self-tests in the depth-test style (boundary 4096 ok / 4097 fails; configurability `maxNumberLength=10`
   fails, `=200` ok; exponent boundary `1e10000` ok / `1e10001` fails).
6. Separable follow-ups (not required for green): unify YAML's `checkNumericScalar` onto the shared
   `checkNumberLength`; add `checkNumberLength` before BigDecimal/BigInt conversions in
   `MsgPackReader.bigDecimal` (338) and `BsonReader` string branches (258+); expose the knob on other
   codecs' decode configs.
