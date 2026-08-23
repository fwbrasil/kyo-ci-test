# Report: wall-clock DoS tests in kyo-schema-json `JsonTest`

## 1. Purpose and premise

Campaign goal: three consecutive green full-matrix CI runs on branch `kyo-compat-external-bindings`
(fork `fwbrasil/kyo-ci-test`). Standing premise for the campaign: complete-and-correct fixes only,
and **tests must not depend on sleeps or wall-clock thresholds**. Timing/threshold tests are flaky by
construction; elsewhere in this campaign such tests were reworked into deterministic form (e.g.
`Async.timeout` driven by `Clock.withTimeControl`; `SignalTest` re-synced on a `waiters == 1` point
instead of a race). This report exists because the next failure pushed me toward threshold-tuning,
which violates that premise, and the correct path needs a design decision.

## 2. Immediate trigger

Run `31960149769` (HEAD `af3016ebb5`) went red on three legs:

| Leg | Module | Failure | Status |
|-----|--------|---------|--------|
| arm64-JVM | kyo-coreJVM | `SignalTest` 2m hang (unsound ghost-waiter sync) | FIXED deterministically, `42b214ecb1` |
| windows-JVM | kyo-stats-machineJVM | `MachineSampler` "0 bytes/op" flake (per-window JIT one-off) | FIXED, `8d2c2d113d` |
| linux-x64-Native | kyo-schema-jsonNative | `JsonTest` DoS test wall-clock threshold | OPEN, this report |

Native legs fired **no crash and no retry** (the NoInherit native fix holds); the only native failure
is this deterministic-looking assertion.

The failing assertion:

```
[FAIL] cross-library regressions > BigDecimal with many fractional zeros does not DoS  (5.3s)
  elapsed < 5000
  // message: BigDecimal fractional zeros took 5166ms
```

## 3. The failing test, and the pattern it belongs to

`kyo-schema-json/shared/src/test/scala/kyo/JsonTest.scala:1865`

```scala
"BigDecimal with many fractional zeros does not DoS" in {
    val json    = "\"0." + "0" * 100000 + "1\""
    val start   = java.lang.System.currentTimeMillis()
    val result  = Json.decode[BigDecimal](json)
    val elapsed = java.lang.System.currentTimeMillis() - start
    assert(elapsed < 5000, s"BigDecimal fractional zeros took ${elapsed}ms")
}
```

It is one of a block of roughly seven wall-clock DoS tests, each shaped as
`start = currentTimeMillis; decode(pathological input); assert(elapsed < 5000)`:

- `:246` "number with many digits does not cause DoS" (`0.` + `"1"*1000`)
- `:1757` "huge exponent BigDecimal does not hang"
- `:1767` "million-digit number does not cause DoS"
- `:1865` "BigDecimal with many fractional zeros does not DoS"  ← the failing one
- `:1875` "huge positive exponent BigDecimal does not hang" (`1e1000000000`)
- `:1884` "object with unexpected large number field does not DoS"
- `:1895` "small exponent notation does not amplify into huge BigDecimal" (two `elapsed` asserts)

**Provenance:** this block is pre-existing on `origin/main` (`JsonTest` last touched 2026-07-31,
`cfbb3d038f`), not introduced by this branch. It is still the campaign's to fix, since it is what is
red, but it is not branch-new work.

## 4. Why it fails, and why only on Native

- BigDecimal is decoded as a JSON string, then `BigDecimal(String)`:
  `JsonReader.bigDecimal()` (`kyo-schema-json/shared/src/main/scala/kyo/internal/JsonReader.scala:373`)
  is `val s = string(); BigDecimal(s)` → `new java.math.BigDecimal(s)`.
- For `"0.[100000 zeros]1"` the coefficient is 100002 chars. On the JVM, `java.math.BigDecimal(String)`
  has an optimized long/BigInteger path (milliseconds). On scala-native, `java.math.BigDecimal` /
  `BigInteger` are the Apache-Harmony reimplementation with classic O(n^2) decimal-string parsing, so
  a 100k-digit coefficient is seconds.
- Measured healthy parse: **2.1s** local Native (Apple silicon, unloaded); **5.166s** on the CI
  linux-x64 Native runner (load ~3.3). Same healthy parse, ~2.5x slower on the slower/loaded CI box,
  crossing the 5000ms line by 3%. This is a bounded parse, not a hang or a DoS.
- For contrast, the sibling `hash-colliding keys are rejected by collection size limit` legitimately
  takes 15.9s local / 27.2s CI and passes, because it asserts a deterministic outcome
  (`result.isFailure`) and carries no time bound. The suite already tolerates work far slower than 5s
  when the assertion is deterministic.

So the failure is not a code defect and not a real DoS. It is a wall-clock line sitting too close to
the healthy no-JIT Native parse time, tripped by CI slowness/load.

## 5. The deterministic model that already exists in this module

kyo-schema-json already has deterministic DoS defenses, with deterministic tests:

- `maxDepth` (default `Codec.DefaultMaxDepth = 512`) and `maxCollectionSize`
  (default `Codec.DefaultMaxCollectionSize = 100000`) are `Json.decode` parameters
  (`kyo-schema-json/shared/src/main/scala/kyo/Json.scala:64-65,83-84`).
- Their tests assert a deterministic outcome, e.g. "hash-colliding keys are rejected by collection
  size limit" asserts `result.isFailure`. No timing.

**The gap:** there is no deterministic limit on *scalar token length* (number or string). `string()`
(`JsonReader:149`) and `readNumber()` (`JsonReader:484`) read unbounded-length tokens. So "a giant
scalar does not DoS" has no deterministic outcome to assert, and the tests fall back to wall-clock.
The scalar DoS protection is, in effect, incidental (the parse happens to be O(n^2)-bounded), not a
real bounded-work defense.

## 6. The tension (why the obvious fix is wrong)

The move I was about to make, `val bound = if Platform.isNative then 20000 else 5000`, is
threshold-tuning. It relocates the flake to a larger arbitrary number, keeps the test time-dependent,
and does not make "does not DoS" true by construction. It violates the campaign premise and must not
be the fix.

## 7. What Fable is asked to design

Design the correct path to make the `JsonTest` DoS block deterministic and green on JVM/JS/Native with
zero wall-clock dependence. Evaluate at least these options, per test:

- **Option A — deterministic scalar-length limit.** Add a scalar-length DoS limit
  (max token characters, sibling to `maxDepth`/`maxCollectionSize`) to the decoder that rejects
  pathologically long number/string tokens before the O(n^2) work; rewrite the affected DoS tests to
  assert deterministic rejection. Decisions needed: default value; whether it caps raw token chars vs
  decoded precision/scale; reject vs truncate; public API shape (new `Json.decode` param and
  `Codec.Default*`); cross-platform behavior; blast radius on existing decode call sites.
- **Option B — deterministic invariant without a new limit.** Reformulate each DoS test around a
  deterministic property (e.g. the decoded value's bounded shape, that the reader consumed the entire
  input, a specific parse `Result`). Identify which tests admit such an invariant and which do not.
- **Option C — removal (explicit fallback, per the user).** For any test whose only possible assertion
  is "it was fast", a pure performance property with no deterministic proxy, remove the test. A
  time-measurement test that cannot be made deterministic is not worth keeping. Identify precisely
  which tests fall here.

Per the user: "at the limit, we will remove the tests if they must be based on measuring time." So
Option C is on the table wherever A/B cannot yield a deterministic assertion.

**Deliverable:** a written design (analysis only; no code changes, no builds, no test runs) that:
1. Classifies each of the ~7 tests into A / B / C with the exact deterministic assertion (or removal
   justification).
2. If a new limit is proposed, specifies its API, default, semantics, and blast radius on
   `Json.decode` and the `Codec` defaults, in kyo conventions.
3. Notes any behavior change to public decoding and whether it is acceptable.

## 8. Constraints

- No sleeps, no wall-clock thresholds in the final tests.
- Tests are in `shared/` and must pass on JVM, JS, and Native; no moving them to a platform folder to
  dodge Native.
- If adding a limit, match the existing DoS-limit design (`maxDepth` / `maxCollectionSize`).
- kyo conventions (CONTRIBUTING.md): `Result` / `Maybe`, explicit public return types, public
  scaladoc, no symbolic operators in core, etc.
- This touches `origin/main` DoS-defense code; keep the change coherent and complete, not a patch over
  one test.

## Appendix: key references

- Failing test + block: `kyo-schema-json/shared/src/test/scala/kyo/JsonTest.scala:246, 1757-1911`
- Decoder scalar reads: `kyo-schema-json/shared/src/main/scala/kyo/internal/JsonReader.scala:149 (string), 373 (bigDecimal), 484 (readNumber)`
- Existing limits: `kyo-schema-json/shared/src/main/scala/kyo/Json.scala:64-65,83-84`; `kyo-schema/shared/src/main/scala/kyo/Codec.scala:59,62`
- Measurements: local Native 2.1s (pass), CI linux-x64 Native 5.166s (fail at 5000ms bound); sibling hash-collision DoS test 15.9s local / 27.2s CI (passes, deterministic `isFailure`).
