# Unified design: one numeric-scalar DoS policy across every kyo-schema codec

> Supersedes the JSON-only plan in `JSON_DOS_DESIGN_FABLE.md` (which it reuses verbatim for the
> `JsonTest` rewrites, §7). Analysis only; no code changed yet. Produced by Fable on the unified brief.

## 0. Thesis and verified groundwork

Root cause of the `JsonTest` wall-clock block is policy drift: the shared `Codec.Reader`
(`kyo-schema/shared/src/main/scala/kyo/Codec.scala:84-131`) unified depth (`checkDepth` →
`LimitExceededException("Nesting depth", ...)`) and collection size (`checkCollectionSize` →
`"Collection size"`) across all codecs, but the numeric-scalar caps were born inside `YamlReader` as
private constants (`MaxNumericScalarLength = 4096`, `MaxDecimalExponent = 10000`,
`YamlReader.scala:1969-1970`) and never joined the shared layer. Every other format is unprotected.
Fix: hoist YAML's two caps into `Codec.Reader` as first-class siblings, converge YAML onto them,
enforce at each format's numeric-materialization sites.

Two corrections to the mapped groundwork, from reading the readers:

1. **Protobuf is NOT exempt.** `ProtobufReader.bigInt()/bigDecimal()` (`ProtobufReader.scala:381-397`)
   parse `BigInt(s)/BigDecimal(s)` from length-prefixed string payloads (proto3 has no
   arbitrary-precision numeric; `Protobuf.scala:495-511` maps both to `string`). Same O(n²) hazard as
   JSON/MsgPack/Bson.
2. **Parse timing splits the formats into three architectures**, which decides where hooks live:
   - *Streaming* (JSON, MsgPack, Protobuf): reader methods scan on demand; `resetLimits` runs before
     any read → instance hooks see configured limits.
   - *Lazy tree* (Ion text): `IonTextParser` built on first value access, after `resetLimits`, and it
     already threads limit vars into its constructor → the new limit threads identically.
   - *Eager tree* (Ion binary, BSON): whole document parsed in the constructor. Ion binary parses
     BEFORE `resetLimits` (`IonBinary.scala:61-64`); BSON parses eagerly but receives `Bson.Config` at
     construction and calls `resetLimits(config...)` in its own body. Consequence: checks needed DURING
     Ion-binary parsing can only use a constant/config, not a per-call `resetLimits` parameter → an
     argument for constant-not-knob on the exponent cap (§2).

A test currently PINS YAML's odd `min(maxCollectionSize, 4096)` coupling: `YamlParserTest.scala:187-195`
rejects `1e999999999` as `"Numeric scalar length"` only because it passes `maxCollectionSize = 8`,
abusing the collection knob as a de-facto number-length knob (because no dedicated knob existed). §3
handles it.

## 1. Shared layer: `kyo-schema/shared/src/main/scala/kyo/Codec.scala`

### 1.1 Constants (`object Codec`, beside 59-62)
- `inline val DefaultMaxNumberLength = 4096` — max chars of a numeric scalar's textual form (number
  tokens in text formats; text-encoded BigInt/BigDecimal payloads in binary formats). Binary-native
  numerics (fixed-width ints/floats, Ion-binary integers, BSON Decimal128) are linear-time, exempt.
- `inline val MaxDecimalExponent = 10000` — max magnitude of a decimal exponent when decoding
  BigDecimal; NOT configurable (a compact `1e100000` amplifies into arrays proportional to the
  exponent, play-json CVE-2020-26882). 10000 exceeds every standardized decimal (IEEE 754 decimal128
  adjusted exponent maxes at 6144).

4096 keeps value-parity with the shipping YAML cap, so YAML convergence is behavior-neutral at
defaults.

### 1.2 Static helpers (`object Codec`, `private[kyo]`) — so non-`Reader` parsers (IonTextParser,
IonBinaryReader.Parser) share one implementation and message strings:
- `checkNumberLength(length: Int, maximum: Int)(using Frame)` → `LimitExceededException("Numeric scalar length", length, maximum)`
- `checkDecimalExponent(value: String)(using Frame)` (text form) and `checkDecimalExponent(exponent: Int)(using Frame)` (decoded form).
  The text overload ports `YamlReader.checkDecimalExponent` verbatim: find `e`/`E` (callers
  pre-normalize Ion `d`/`D`), skip one sign, accumulate digits WITH the early-stop guard
  (`while ... && exponent <= MaxDecimalExponent`, preventing Int overflow), throw on magnitude over cap
  (either sign). The Int overload checks `math.abs(exponent)` (guarding `Int.MinValue`).

### 1.3 `Codec.Reader` members (beside 92-131)
- `private[kyo] var maxNumberLength: Int = DefaultMaxNumberLength`
- `resetLimits(maxDepth, maxCollectionSize, maxNumberLength)` (extended)
- `final protected def checkNumberLength(length)` → `Codec.checkNumberLength(length, maxNumberLength)`
- `final protected def checkDecimalExponent(value: String)` / `(exponent: Int)` → the static helpers.
`final protected` matches `checkDepth`. `Codec.decodeFully`/`readFully` gain `maxNumberLength: Int`
(no default; `private[kyo]`, threaded explicitly).

## 2. Design decision: exponent cap = FIXED SHARED CONSTANT (recommended), length cap = KNOB

- **Legitimacy asymmetry**: number length has real legitimate variance (16384-bit integer as decimal
  = 4933 digits, over the default; users need the escape hatch, same as `maxCollectionSize`). Exponent
  magnitude beyond 1e4 has no defensible use (decimal128 tops at 6144).
- **Precedent**: YAML shipped the knob-less constant.
- **Architecture**: Ion-binary parses eagerly before `resetLimits`, so a per-call exponent knob
  couldn't reach it without restructuring; a constant is visible everywhere including eager parsers.
  (The length cap doesn't hit this because binary-native numerics are exempt from it.)
- **Reversibility**: promoting a constant to a knob later is additive; retiring a knob is a break.
- Escape hatch remains: decode as `String`, parse with user `MathContext`.
Reject-not-truncate for both caps (typed `LimitExceededException` → `Result.Failure`). At-limit passes.

## 3. YAML convergence (`kyo-schema-yaml`)
- `checkNumericScalar` (`YamlReader.scala:671-675`) → body `checkNumberLength(value.length)`, DROPPING
  the `math.min(maxCollectionSize, MaxNumericScalarLength)` coupling.
- `checkPotentialNumericScalar` (677-686): keep (YAML-specific pre-resolution triage), delegate to
  shared check.
- `checkDecimalExponent` (688-704) + companion constants (1969-1970): delete; `bigDecimal()` (:428)
  calls the inherited shared check.
- Plumbing: `ReaderConfig` gains `maxNumberLength` (`Yaml.scala:147-153`), `Yaml.DefaultMaxNumberLength`
  mirror, three `resetLimits` sites (`Yaml.scala:812,1042,1755`) + two overrides
  (`YamlReader.scala:76-79`, `YamlEventReader.scala:14-17`) thread it; explicit-limit helpers
  (`Yaml.scala:1673-1807`) gain the third param.
- Dropping the coupling is acceptable: semantically a number's length isn't a collection size; neutral
  at defaults (`min(100000,4096)=4096`); the one edge (user set `maxCollectionSize < 4096` relying on
  it) now gets 4096 (bounded loosening, still sub-ms) and can express intent via `maxNumberLength`.
  Sole in-repo dependent `YamlParserTest.scala:187-195` switches `maxCollectionSize=8` →
  `maxNumberLength=8` (same rejection, same message, right knob). `:211-217` (exponent) untouched.

## 4. Per-format hooks (all six readers + wrappers)

- **JSON** (`JsonReader.scala`, streaming): `readNumber()`:484 `checkNumberLength(pos-start)` before
  copy; `double()`:254/`float()`:230 `checkNumberLength(end-start)` after `scanNumberEnd`; `bigDecimal()`:373
  `checkNumberLength(s.length)`+`checkDecimalExponent(s)` before `BigDecimal(s)`; `bigInt()`:364
  `checkNumberLength(s.length)`; `skip()`:345 new scan-only `skipNumber()` (NO check, preserves
  skip-exempt precedent :1938); `captureValue()`:626 propagate parent limits to sub-reader.
- **YAML**: hooks pre-exist; only shared delegation + plumbing change (§3).
- **MsgPack** (`MsgPackReader.scala`, streaming binary): `bigInt()`:332-336 and `bigDecimal()`:338-342
  check length (+exponent) on the text payload before conversion; fixed-width numerics exempt.
- **BSON** (`BsonReader.scala`, eager, config-carried limits): `bigInt()`:248 / `bigDecimal()`:261
  StringValue branches check; Int32/Int64/Double branches exempt; Decimal128 :272 exempt BY
  CONSTRUCTION (fixed 16-byte, ≤34 digits, exponent within ±6144) — document it.
- **Ion text** (`IonReader.scala`, lazy): `IonTextParser` ctor gains `maxNumberLength`, passed at the
  one construction site `IonReader.value`:289; `parseNumber`:532-547 `Codec.checkNumberLength(cleaned.length, maxNumberLength)`
  (covers hex/bin/decimal/plain/float), and in the DecNum branch `Codec.checkDecimalExponent(cleaned.replace('d','E').replace('D','E'))`;
  `bigDecimal()`:219 needs no hook (pre-checked at parse; IntNum wrap is O(1)).
- **Ion binary** (`IonBinaryReader.scala`, eager before resetLimits): `Parser.readDecimal`:458-468
  after the isValidInt guard `Codec.checkDecimalExponent(exponent.toInt)` against the CONSTANT (the
  reason §2 chose constant); `readInt`/VarInt magnitude readers exempt (BigInteger(int, byte[]) is O(n),
  no radix conversion) — document; reader methods :210-216/:261 no hooks (pre-validated / O(1) wraps).
- **Protobuf** (`ProtobufReader.scala`, streaming binary): `bigInt()`:381-388 and `bigDecimal()`:390-397
  check on the string payload; varint/fixed-width exempt.
- **Wrappers** (`YamlEventReader`, `SchemaSerializer.TransformAwareReader`, in-memory value readers):
  no hooks, only forward `resetLimits` where they already forward the existing two.

## 5. Public API per format (knob = `maxNumberLength` only; exponent cap is the shared constant, in no signature)

- **Json**: `Json.DefaultMaxNumberLength` mirror; `maxNumberLength: Int = DefaultMaxNumberLength` on
  `decode`:62 and `decodeBytes`:81; scaladoc @params.
- **Yaml**: `Yaml.DefaultMaxNumberLength`; `ReaderConfig.maxNumberLength`:147-153; explicit-limit
  overloads :1750-1807 gain the param; pipeline :807-816 reads it from config.
- **Ion**: `Ion.DefaultMaxNumberLength`; `Ion.Config` field :75-80; `newReader`:25 passes it; decode
  overloads :182-223 gain param → `Codec.readFully`.
- **IonBinary**: `DefaultMaxNumberLength`; `decode`/`decodeBytes`:56-75 gain param (length cap inert for
  binary-native numerics per §4.6, scaladoc says so honestly).
- **MsgPack**: `decode`:119-125 gains `maxNumberLength = Codec.DefaultMaxNumberLength`.
- **Bson**: `Bson.DefaultMaxNumberLength`; `Bson.Config` field :57-60; convenience overload :111-118
  gains param into config.
- **Protobuf**: `decode`:72 gains param; internal :169-172 threads to `resetLimits`.
All trailing defaulted params / defaulted case-class fields → source-compatible for existing callers.

## 6. Blast radius (decode-side only; encode untouched everywhere)

1. JSON rejects numeric tokens > `maxNumberLength` and BigDecimal exponents > 10000 (precedent:
   `maxCollectionSize` + YAML already reject these classes). Skipped unknown-field content stays exempt.
2. YAML: ZERO change at defaults; sole edge is the `maxCollectionSize < 4096` decoupling (§3).
3. MsgPack/BSON/Protobuf: newly reject text-encoded BigInt/BigDecimal over the caps; kyo's own writers
   emit `value.toString`, so a round-trip of a >4096-digit value now fails on decode — same
   encode/decode asymmetry as collections, same remedy (raise the knob). Fixed-width/Decimal128
   unchanged.
4. Ion text: rejects tokens > cap and `d`/`D` exponents > 10000. Ion binary: rejects decimals with
   stored exponent magnitude > 10000; integer payloads deliberately un-capped.
5. Failure-mode shifts: absurd numerics that were `ParseException` now fail earlier as
   `LimitExceededException` — still `Result.Failure(DecodeException)`.
Compatibility: trailing defaulted members → source-compatible; binary descriptors change but MiMa is
report-only for these modules (`mimaFailOnProblem := false`, `build.sbt:3292-3297`).
Swept: no existing success-expecting test decodes a numeric token > 4096 chars or exponent > 10000;
only `YamlParserTest.scala:187-195` needs the knob switch (`:211-217` is the cross-format template).

## 7. Tests (all deterministic; zero wall-clock/sleep)

- **JSON**: the `JSON_DOS_DESIGN_FABLE.md` §1 plan carries over unchanged (five A-rewrites, two B in
  the report block, five B in the sibling block, boundary + knob + exponent-boundary self-tests, zero
  removals).
- **Per-format self-tests** (each in the format's own shared suite, modeled on
  `YamlParserTest.scala:187-217` and the JSON depth test): YAML knob switch + a decoupling pin
  (small `maxCollectionSize`, long number, assert NO length rejection); MsgPack/BSON/Protobuf oversized
  text-encoded BigInt/BigDecimal → `"Numeric scalar length"` and `1e100000` payload →
  `"Numeric scalar exponent"` + a raised-knob test; BSON Decimal128 max-exponent value asserts SUCCESS
  (pins the exemption); Ion text oversized plain/hex/decimal rejected, `1d100000` exponent-rejected,
  at-cap `1d10000` succeeds; Ion binary hand-built decimal exponent 10001 rejected / 10000 succeeds /
  large-magnitude binary integer SUCCEEDS (pins the binary-int exemption).

## 8. Recommendation and cross-format edit list

Recommended: the unified design above — shared `maxNumberLength` knob (default 4096) + fixed shared
`MaxDecimalExponent = 10000`, YAML converged, all six readers hooked, public surfaces per §5, tests
per §7.

1. `kyo-schema/.../Codec.scala` — constants §1.1; static helpers §1.2; Reader var + wrappers + 3-arg
   `resetLimits`; `decodeFully`/`readFully` gain `maxNumberLength`.
2. `kyo-schema-json/.../Json.scala` — mirror + params + scaladoc.
3. `kyo-schema-json/.../internal/JsonReader.scala` — six hooks §4.1 (incl. `skipNumber()`, `captureValue` propagation).
4. `kyo-schema-yaml` — `YamlReader.scala` converge + delete private machinery + `resetLimits` override;
   `YamlEventReader.scala:14-17` forward; `Yaml.scala` ReaderConfig field, mirror, thread
   `812/1042/1755` + explicit-limit family.
5. `kyo-schema-msgpack` — `MsgPackReader.scala:332-342` hooks; `MsgPack.scala:119-125` param.
6. `kyo-schema-bson` — `BsonReader.scala:243-276` StringValue hooks + Decimal128 comment; `Bson.scala`
   Config field, mirror, overload param.
7. `kyo-schema-ion` — `IonReader.scala` IonTextParser ctor arg + `parseNumber` hooks + site :289;
   `IonBinaryReader.scala` `readDecimal` exponent check + exemption comments; `Ion.scala` Config field,
   mirror, `newReader`, decode overloads; `IonBinary.scala` param.
8. `kyo-schema-protobuf` — `ProtobufReader.scala:381-397` hooks; `Protobuf.scala:72/169-172` threading.
9. Arity-only call-site updates for `resetLimits`/`decodeFully`/`readFully` — `Json.scala:67,86`;
   `Yaml.scala:812,1042,1755`; `Ion.scala:25,213,223`; `IonBinary.scala:63`; `Protobuf.scala:172`;
   `MsgPack.scala:124`; `BsonReader.scala:42`; `IonBinaryReader.scala:245`.
10. Tests — `JsonTest.scala` rewrites + self-tests; `YamlParserTest.scala:187-195` knob switch +
    decoupling pin; new self-tests in MsgPack, Bson, Protobuf, Ion text, Ion binary suites.
