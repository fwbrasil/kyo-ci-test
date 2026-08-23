# Time / threshold-based-assertion sweep: kyo-test

Scope: every `*.scala` under `kyo-test/{runner,snapshot,api,prop}/**/src/test/`, all subprojects
(shared, jvm, js-wasm, native, jvm-native, sbt-publish). Analysis only; no edits, no runs.

Method: grepped broadly for `elapsed`, `timed(`, `.toMillis`, `.toNanos`, `nanoTime`,
`currentTimeMillis`, `assert(...millis|seconds)`, `within`, `budget`, `Async.sleep`,
`Thread.sleep`, `eventually`, `deadline`, `poll`, `settle`, plus a second pass on every file
containing a `.millis`/`.seconds`/`.second` literal and every file containing `Duration(`. Read
every hit's actual assertion and surrounding test in full before classifying. 101 test files in
scope; every file that matched any pattern was opened and read.

## ANTI-PATTERN findings (ranked by flake risk, highest first)

### 1. `runner/shared/src/test/scala/kyo/RunnerSelfTest.scala:199` (fixture at `:38-48`) — MODERATE-HIGH risk

Test: `"parallelism enabled: concurrent execution"`

```scala
if Platform.isJVM then
    assert(
        RunnerSelfFixtures.parallelMaxSeen.get() > 1,
        s"expected concurrent execution but max seen was ${RunnerSelfFixtures.parallelMaxSeen.get()}"
    )
```

Driven by the fixture:

```scala
class ParallelSuite extends TestBase[Any]:
    private def track(using kyo.test.AssertScope): Unit < (Async & Abort[Throwable] & Scope) =
        Sync.defer {
            val now = parallelActive.incrementAndGet()
            var cur = parallelMaxSeen.get()
            while now > cur && !parallelMaxSeen.compareAndSet(cur, now) do cur = parallelMaxSeen.get()
        }.andThen(Async.sleep(30.millis)).andThen(Sync.defer {
            parallelActive.decrementAndGet(): Unit
        }).andThen(succeed)
    for i <- 0 until 8 yield s"leaf$i" in track
```

Why wall-clock-based: 8 leaves each hold an "in-flight" counter open for a fixed
`Async.sleep(30.millis)` window, hoping the scheduler overlaps at least two of them so
`parallelMaxSeen` observes `> 1`. This is the classic "fixed sleep widens a race window, then
assert an outcome that depends on real overlap" shape. Per this file's own class doc, these
leaves run through `TestRunner.runReport`, which submits to the **same process-global LeafPool**
every other suite uses (`kyo/RunnerSelfTest.scala:143-146`: "runs OFF the process-global pool...
submits the sub-suite's leaves to the SAME global pool"). Under CI contention from other
concurrently-running suites, the pool can serialize this suite's 8 leaves enough that no two
30ms windows overlap, in which case `parallelMaxSeen` never exceeds 1 and the assertion fails.

The sibling test in `runner/shared/src/test/scala/kyo/test/runner/RunnerTest.scala:201-221`
("Scenario 1: all leaves run, bounded by the process-global pool") explicitly documents why this
exact assertion is NOT safe to make through the shared global pool:

> "Real concurrency cannot be observed deterministically through the shared global pool without a
> sleep or a barrier through the pool (which deadlocks under cross-suite competition), so peak > 1
> is intentionally NOT asserted here. globalK is computed in-test... The dedicated, deterministic
> concurrency-reaches-k proof lives in LeafPoolTest."

`RunnerSelfTest.scala`'s "parallelism enabled" test does the exact thing that comment says not to
do, on the exact same shared pool.

Deterministic replacement: `runner/shared/src/test/scala/kyo/test/runner/internal/LeafPoolTest.scala:47-86`
already demonstrates the correct pattern for this codebase — a dedicated `LeafPool` instance
(not the shared global pool) driven by a `Channel`-based "started" barrier plus a `Promise`
release latch, so all `k` workers are provably in flight simultaneously with zero reliance on
timing. `ParallelSuite`'s `track` should use the same started-signal-channel + release-promise
shape instead of `Async.sleep(30.millis)`, or the assertion should be dropped in favor of the
already-existing deterministic proof in `LeafPoolTest`, mirroring what `RunnerTest.scala`
Scenario 1 chose to do.

### 2. `runner/jvm/src/test/scala/kyo/test/runner/LeakCheckTest.scala:275` — LOW-MODERATE risk

Test: `"scheduler probe reports Busy with a frame while a fiber spins, and drains after cleanup"`

```scala
stop.set(true)
val _ = Sync.Unsafe.evalOrThrow(fiber.interrupt)
Thread.sleep(200)
val drained = minLoad(10)   // minLoad samples loadAvg() 10x with Thread.sleep(20) between samples

assert(drained <= ambient + 0.5, s"after cleanup the spinner's load should be gone (ambient=$ambient drained=$drained)")
```

Why wall-clock-based: after stopping the spinner fiber, the test does a fixed `Thread.sleep(200)`
("wait long enough for the interrupt/scheduler to settle"), then samples load for another fixed
200ms window and asserts the minimum sample is back near the pre-spinner `ambient` baseline. This
is the "sleep N then assert settled" shape verbatim. The same file defines and correctly uses a
polling helper, `awaitTrue(timeoutMs)(cond)` (`LeakCheckTest.scala:31-41`), for the mirror-image
wait ("wait until the spinner IS observed busy", used at lines 251, 294, 330, 335, 355, 358, 383,
403) — the cleanup-side wait at line 261/275 is the one place in the file that reverts to a fixed
sleep instead of polling to the same deterministic condition.

Mitigating factor: `Scheduler.get.loadAvg()` (`kyo-scheduler/jvm-native/.../Scheduler.scala:392`)
is an instantaneous queued+executing-task count, not a smoothed/decaying OS load average, and the
assertion is calibrated against a freshly-measured `ambient` baseline (not an absolute 0), which
meaningfully reduces the real flake risk versus a naive fixed-threshold check. Still, on a very
heavily loaded CI runner a 400ms total window (200ms sleep + 200ms sampling) is a guess, not a
guarantee, that the interrupt has fully propagated and the worker has gone idle.

Deterministic replacement: replace the fixed `Thread.sleep(200)` + `minLoad(10)` pair with the
same `awaitTrue`-style polling loop already used elsewhere in this file, e.g.
`awaitTrue(2000)(LeakCheck.loadAvg() <= ambient + 0.5)`, so the test converges as soon as the
condition holds instead of assuming 400ms is enough.

### 3. `runner/shared/src/test/scala/kyo/test/runner/RunnerTest.scala:161-167` (`RTDetachedAfterSuite`), exercised at `:354-369` — LOW-MODERATE risk

```scala
class RTDetachedAfterSuite extends TestBase[Any]:
    "detached-fails-after" in Sync.defer {
        Fiber.initUnscoped {
            Async.sleep(500.millis).andThen(Abort.run[Throwable](assert(false, "detached fiber asserted after the leaf")))
        }
    }.andThen(succeed)
end RTDetachedAfterSuite
```

Test assertion (`:360-369`) only checks the leaf stays `Passed`. Why wall-clock-based: the
`500.millis` sleep exists solely to make the detached fiber's `assert(false)` land AFTER the
runner has scored and closed the leaf's `AssertScope`, so the record takes the "closed" branch
(a stderr warning) instead of the "still open" branch (which — per the neighboring
`RTDetachedDuringSuite` test at `:130-138` — flips the leaf to `Failed`). If the sleep is not
long enough relative to how fast the runner scores+closes the leaf, the leaf's result would
non-deterministically flip to `Failed` under this exact test. The file's own comment
(`RunnerTest.scala:154-160`) admits this directly:

> "Full determinism through the runner is not achievable here (the close happens on the runner
> timeline, not the leaf's), so the sleep makes the after-body ordering as reliable as possible."

Mitigating factor: the leaf body returns essentially instantly (no waiting), so the runner's
score+close step is sub-millisecond in practice against a 500ms budget — a very large margin,
making outright flakiness unlikely in practice, but the mechanism is a race by the code's own
admission, not a deterministic ordering guarantee.

Deterministic replacement: this file already contains the fix pattern one test below it.
`"AssertScope: an after-leaf leak in the global collector becomes a synthetic failed leaf (GOAL B
mechanism)"` (`RunnerTest.scala:371-407`) sidesteps exactly this race by enqueueing directly into
the process-global `kyo.test.AssertScope.leakedAfterClose` collector instead of racing a real
detached fiber's wakeup, and says so explicitly: "Rather than racing a real detached fiber's
wakeup against the suite's drain point, enqueue a leak directly into the process-global
collector... deterministic." `RTDetachedAfterSuite` should either be re-expressed the same way
(inject directly into the collector after a run, if the "Passed" invariant it wants to pin can be
expressed that way), or the runner should expose a test-only hook that signals when it closes the
leaf's scope, and the detached fiber should await that hook instead of sleeping.

## Borderline — real-elapsed assertions with large margins, cleared but noted

### `runner/jvm/src/test/scala/kyo/test/runner/LeakCheckTest.scala:156` and `:175`

```scala
// :139-157, "awaitSchedulerIdle returns Accounted without spending the budget..."
val budget  = 2_000_000_000L   // 2s
val started = java.lang.System.nanoTime()
val verdict = LeakCheck.awaitSchedulerIdle(budgetNanos = budget, settleNanos = 20_000_000L, pollNanos = 1_000_000L, ...)
val elapsed = java.lang.System.nanoTime() - started
...
assert(elapsed < budget / 2, s"Accounted must settle without spending the budget, took ${elapsed / 1000000}ms")

// :159-176, "awaitSchedulerIdle still spends the budget and reports Busy when work is unaccounted"
val budget = 200_000_000L   // 200ms
...
assert(elapsed >= budget, s"an unaccounted fork must still get its full budget, took ${elapsed / 1000000}ms")
```

These are textually exactly the flagged shape (`assert(elapsed < N)` / `assert(elapsed >= N)` on
a measured `nanoTime()` delta). Classified as legitimate rather than flagged because:

- `LeakCheck.awaitSchedulerIdle` is a production mechanism that genuinely operates on real
  wall-clock time by design (it polls actual JVM/scheduler state from outside any fiber or
  virtual-clock-controlled effect, so there is no virtual clock to substitute here without
  changing what the function does).
- The `elapsed < budget / 2` check has a ~25-50x safety margin (needs ~20-40ms in practice
  against a 1000ms threshold), and `elapsed >= budget` is a floor a correctly-implemented polling
  loop essentially cannot undershoot (poll loops overshoot their deadline, they do not finish
  early). Neither reads as a brittle band that would flake on a slow runner.

Still worth a note for completeness: if `LeakCheck.awaitSchedulerIdle` / `awaitFdDrain` are ever
given an injectable "now"/"park" function (the way `loadNow` and `allAccounted` are already
injected), these two tests could be made fully hermetic with a fake clock, removing the residual
real-time dependency entirely. Not urgent given the margins.

## Reviewed & cleared

Everything else that matched the grep sweep was read and is a legitimate deterministic test, a
legitimate timing-*feature* test (the special-caution carve-out) with generous margins, or a
false-positive keyword match. Listed by file:

- **`api/shared/src/test/scala/kyo/test/ScheduleDurationTest.scala`** — all `Duration`/`.millis`/
  `.seconds` literals are constructed test data compared for equality (`d == 50L.millis`); no real
  time elapses.
- **`api/shared/src/test/scala/kyo/test/TestBaseTest.scala`** — `Async.sleep(1.millis)` is a
  trivial exercise of the async leaf path, not a threshold; `.timeout(5L.seconds)` is a config
  equality check, no elapsed measurement.
- **`api/shared/src/test/scala/kyo/test/internal/AssertTest.scala:211`** — false positive; "within
  kyo.*" refers to a package-namespace guard, not timing.
- **`api/{jvm,js-wasm,native}/src/test/scala/kyo/test/internal/ApiTestSleep.scala`**,
  **`runner/{jvm,js-wasm,native}/src/test/scala/kyo/test/runner/TestSleep.scala`** — platform
  `sleep()` shim objects with no assertions of their own; grepped the whole tree and found no test
  file actually calling them, so nothing to classify as a test anti-pattern here.
- **`runner/shared/src/test/scala/kyo/TestApiSelfTest.scala`** — `Async.sleep(1.millis)` trivial
  exercise; `.timeout == Maybe(5L.seconds)` is a config equality check.
- **`runner/shared/src/test/scala/kyo/RunnerSelfTest.scala:56` / `:216-230`** — `.timeout(10L.millis)`
  vs. a `500.millis` body sleep: legitimate feature test of the `.timeout` decorator (huge margin,
  asserts on the resulting `TimedOut` status, not on a measured duration).
- **`runner/shared/src/test/scala/kyo/RunnerSelfTest.scala`** (all other leaves) — deterministic;
  the cross-suite pool bound is asserted as `peak <= globalK`, not `peak == k` or any timing value.
- **`runner/shared/src/test/scala/kyo/test/runner/RunnerTest.scala:59,171,257-265,424-447`** —
  `RTTimeoutSuite` (`.timeout(50.millis)` vs. a 30s sleep) and `RTHeartbeatSuite`
  (`Async.sleep(1.second)` vs. a 50ms heartbeat interval) are legitimate feature tests of the
  timeout and heartbeat decorators with large margins; assertions are on status/non-emptiness
  (`beats.nonEmpty`, `beats.forall(_._2.toMillis > 0)`), not on a measured numeric band.
- **`runner/shared/src/test/scala/kyo/test/runner/RunnerTest.scala:201-221`** ("Scenario 1") —
  explicitly does NOT assert `peak > 1` for documented flakiness reasons; only asserts the
  deterministic `peak <= globalK` bound. Positive exemplar.
- **`runner/shared/src/test/scala/kyo/test/runner/internal/LeafPoolTest.scala`** — fully
  deterministic; concurrency windows are established via a `Channel` start-barrier plus a
  `Promise` release latch, explicitly documented as "not by timing." Positive exemplar; this is
  the pattern findings #1-3 above should converge on.
- **`runner/jvm/src/test/scala/kyo/test/runner/CliConcurrencyTest.scala`** — uses
  `CountDownLatch`/`await(5, SECONDS)` for deterministic handoff; the 5s bound is a hang-safety
  net, never asserted on itself. Assertions are on `codeA`/`codeB` values set before the latch
  counts down.
- **`runner/jvm/src/test/scala/kyo/test/runner/StrandedOpCheckTest.scala`** — `settleNanos =
  5_000_000L` is an internal sampling-window parameter fed into the function under test; all
  assertions are structural (report contains/doesn't contain a name), never on elapsed time.
- **`runner/shared/src/test/scala/kyo/LiveCoverageTest.scala:133-143,374-390`** — `assertEventually`
  feature tests: one polls a counter to `>= 3` (deterministic completion condition, no time
  assertion), the other pairs `.timeout(200.millis)` with an always-false condition to prove
  `TimedOut` fires; asserts on result kind only.
- **`runner/shared/src/test/scala/kyo/SelfTestsRunnerTest.scala`** — no timing content; matched
  only via keyword scan of the whole tree, false positive.
- **`runner/jvm/src/test/scala/kyo/test/runner/internal/EventBuilderTest.scala`**,
  **`runner/shared/src/test/scala/kyo/test/runner/{CombinedReporterStrictTest,CombinedReporterTest,
  PathChunkTest,TapReporterTest}.scala`**, **`runner/shared/src/test/scala/kyo/test/runner/
  internal/SummaryTest.scala`**, **`runner/shared/src/test/scala/kyo/test/runner/
  ConsoleReporterTest.scala`** — all `Duration`/`.millis`/`.seconds` values are constructed literal
  data (`TestResult.Passed(1L.millis)`, `TestResult.TimedOut(5L.seconds)`, etc.) fed into
  formatting/reporting code and asserted on rendered text (`"15ms"`, `"30s"`) or structural fields;
  zero real time elapses in any of these tests.
- **`runner/jvm/src/test/scala/kyo/test/runner/SbtFrameworkTest.scala`**,
  **`sbt-publish/src/test/scala/kyo/test/sbt/SbtKyoTestPluginTest.scala`** — no timing content at
  all (initial "second"/"pollute" keyword hits were false positives on unrelated words).
- **`runner/shared/src/test/scala/kyo/test/runner/ArgsTest.scala:25`** and
  **`runner/shared/src/test/scala/kyo/test/runner/internal/ArgsTest.scala`** — `--randomize`
  (bare) uses `currentTimeMillis` as a seed source in the implementation, but the test only
  asserts `.isDefined`, never a value derived from real time.
- **`runner/shared/src/test/scala/kyo/test/runner/GlobTest.scala`,
  `runner/shared/src/test/scala/kyo/test/runner/internal/GlobTest.scala`** — "within a segment"
  hits are glob-matching prose, unrelated to timing.
- **`snapshot/**`** (all files) — grepped for every timing keyword with zero hits beyond
  `java.lang.System.nanoTime()` used purely as a unique-ID source for scratch directory names
  (`SnapshotGoldenTest.scala:36,60,374,381`; `SnapshotSchemaTest.scala:38,122`;
  `SnapshotStoreBytesTest.scala:23`; `SnapshotUpdateModeTest.scala:32`;
  `SnapshotForeignPackageTest.scala:29`) and one record field value; never used for a timing
  assertion.
- **`prop/**`** (all files including `GenFilterBudgetTest.scala`, `GenTest.scala`) — "budget" here
  means the property-generator's retry-attempt cap (an integer counter), not a wall-clock budget;
  no timing assertions anywhere in the prop module.

## Summary

Three genuine findings, none catastrophically flaky but all real races by construction, ranked by
risk: (1) `RunnerSelfTest.scala`'s parallelism-overlap assertion on the shared global pool
(moderate-high — contradicts a documented decision made one file over), (2)
`LeakCheckTest.scala`'s fixed-sleep-then-measure cleanup check (low-moderate — same file has the
right polling pattern one function away), (3) `RunnerTest.scala`'s `RTDetachedAfterSuite` timed
race (low-moderate — same file has the right deterministic-injection pattern one test away). Two
borderline real-elapsed assertions in `LeakCheckTest.scala` are cleared for now given large
margins but noted for future hermetic-clock work. Every other timing-flavored hit in the 101-file
scope is either a legitimate feature test of a timing decorator/mechanism with a large margin, or
constructed `Duration` literal data with no real time involved.
