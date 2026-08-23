# Time-Sweep Findings: kyo-scheduler test suites

Scope: every `*.scala` under `kyo-scheduler/**/src/test/` (jvm-native, jvm, shared).
Method: grep for `elapsed|timed\(|\.toMillis|\.toNanos|nanoTime|currentTimeMillis|assert\(.*(millis|seconds)|under \d|within|budget|sleep|Thread\.sleep`, then read every hit's file in full to classify the actual assertion.

Files with at least one hit: `InternalClockTest.scala`, `util/SleepTest.scala`, `regulator/ConcurrencyTest.scala`, `WorkerTest.scala`, `BlockingMonitorTest.scala`, `SchedulerTest.scala`, `WorkerConcurrentRunTest.scala`, `TestTimer.scala`, `top/ReporterTest.scala`. Files with zero hits (`InternalTimerTest.scala`, `TestExecutors.scala`, `TestTask.scala`, `WorkerQueueTest.scala`, `regulator/AdmissionTest.scala`, `regulator/RegulatorTest.scala`, `top/PrinterTest.scala`, `top/StatusFileTest.scala`, `util/MovingStdDevTest.scala`, `util/XSRandomTest.scala`, `SchedulerSingletonTest.scala`, `util/SingletonTest.scala`, `shared/TaskTest.scala`) were still opened and read in full to confirm no timing dependence, since the grep only catches the vocabulary, not the pattern.

Ranked most-flaky-risk first.

---

## Findings

### 1. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/util/SleepTest.scala:97-100` — HIGH RISK
Test: `"probe jitter stays below regulator threshold under blocking load"`

```scala
val multiplier = if (isWindows) 200 else 50
val threshold  = Concurrency.defaultConfig.jitterUpperThreshold * multiplier
assert(
    stddev < threshold,
    s"Sleep jitter stddev=${stddev.toLong}ns (avg=${avg.toLong}ns) exceeds regulator threshold ${threshold.toLong}ns"
)
```
Why wall-clock-based: `stddev` is the standard deviation of 100 real `Sleep(1)` calls measured via `System.nanoTime()` while up to 50 real daemon threads hammer `Thread.sleep(1)` concurrently to create fd/scheduler contention. The whole test exists to characterize real OS-timer jitter under real thread pressure, then compares that jitter to a hard multiplier-scaled threshold. The `multiplier` split (50 vs 200 for Windows) is itself visible evidence of prior flake-driven tuning — exactly the pattern that keeps needing a wider band as CI noise changes.
Suggested replacement: this test's *purpose* (verify `Sleep` doesn't get pathologically noisier than blocking `Thread.sleep` under the same load) can't be made fully deterministic because it is inherently measuring real timer behavior. If kept, structure it as a robust statistic instead of a single hard band: run the 100-sample measurement N times (e.g. 5) and require a majority (e.g. 3/5) to be under threshold, so one noisy CI sample doesn't fail the whole test. At minimum, if a single-shot band must remain, log it as a known-soft assertion and widen it generously (last resort) rather than periodically re-tuning the multiplier per platform.

### 2. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/regulator/ConcurrencyTest.scala:55-66` — HIGH RISK
Test: `"probe jitter stays below regulator threshold with real sleep"`

```scala
Thread.sleep(5000)

concurrency.stop()
val totalDiff = concurrencyDiff.get()

assert(
    totalDiff >= -8,
    s"Concurrency regulator reduced workers by $totalDiff, " +
        "indicating excessive probe jitter"
)
```
Why wall-clock-based: runs the real `Concurrency` regulator against a real `Sleep`-based probe for a fixed 5 real seconds, then asserts the cumulative worker-count adjustment the regulator produced from real OS jitter over that window is no worse than `-8`. On a loaded/oversubscribed CI runner, real probe jitter is exactly what spikes, which is exactly what would push `totalDiff` below `-8` for reasons that have nothing to do with a regression — this is the textbook "measured elapsed/jitter compared to a numeric band" anti-pattern the domain caution calls out.
Suggested replacement: the deterministic sibling tests ("up"/"down"/"noop" in the same file) already exercise the regulator's decision logic against an injected `TestTimer`-driven jitter value with exact expected `updates` lists — that is the correct pattern and already covers the logic this test duplicates with real time. If a real-OS-integration smoke check is still wanted, loop the 5s measurement a few times and require most runs to clear a much looser bound (or just assert the regulator doesn't crash / stays finite), rather than a single hard `-8` floor from one real 5-second sample.

### 3. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/InternalClockTest.scala:23-24` — HIGH RISK
Test: `"currentMillis"`

```scala
val startMillis = clock.currentMillis()
Thread.sleep(100)
val endMillis     = clock.currentMillis()
val elapsedMillis = endMillis - startMillis
assert(elapsedMillis >= 50)
assert(elapsedMillis <= 300)
```
Why wall-clock-based: sleeps 100ms of real time, then asserts the clock's own reported elapsed value falls in a `[50, 300]` band. Both bounds are real-time dependent: a scheduler-thread starvation (very plausible on a loaded/oversubscribed CI box, or after a GC pause) can push the clock's actual tick past 300ms even though the clock is behaving correctly, and a fast/skewed environment could in principle also read low. This is a direct instance of the flagged pattern (elapsed-time diff vs numeric band).
Suggested replacement: assert against clock *ticks*/*update count* rather than the wall-clock delta magnitude — e.g. sample `currentMillis()` in a poll loop until it changes at all (deterministic: "the clock advances"), or expose/assert that the underlying ticker thread is alive and producing monotonically non-decreasing values across N samples, without pinning the magnitude to a real-time band.

### 4. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/util/SleepTest.scala:19,27,35` — HIGH RISK (three related tests)
Tests: `"sleeps for at least the requested duration"`, `"sleeps for a reasonable upper bound"`, `"handles zero"`

```scala
assert(elapsed >= ms - tolerance, s"Sleep($ms) returned after only ${elapsed}ms")      // line 19
assert(elapsed < ms * 10, s"Sleep($ms) took ${elapsed}ms, expected < ${ms * 10}ms")     // line 27
assert(elapsed < 200, s"Sleep(0) took ${elapsed}ms")                                    // line 35
```
Why wall-clock-based: each measures a single real `Sleep(...)` call's elapsed `System.nanoTime()` delta against a numeric bound (a lower bound for the first, `500ms` for the second, `200ms` for the third). These are the most classic instance of the flagged pattern — a single real timing sample compared to a hardcoded band, with no retries. The `200ms` "handles zero" bound and the `500ms` "reasonable upper bound" are both plausible to blow through under CI scheduler contention, GC pause, or a noisy-neighbor VM host, independent of any real bug in `Sleep`.
Suggested replacement: for the lower-bound test, this is inherent to what's being verified (does `Sleep` actually sleep) — keep it, but only as a sanity floor with a very generous bound, not a hair-trigger one (current version is already reasonably loose). For the upper-bound tests, replace a single-shot band with either (a) a retry-until-N-consecutive-passes loop (tolerates one bad sample from OS jitter without masking a real regression across many samples), or (b) drop the strict upper-bound assertion entirely and keep only the lower-bound "did it actually sleep" check, since an unbounded-but-finite overshoot under host contention is not itself a `Sleep` bug.

### 5. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/InternalClockTest.scala:15` — MEDIUM RISK
Test: `"stop"`

```scala
val initialMillis = clock.currentMillis()
clock.stop()
Thread.sleep(10)
val finalMillis = clock.currentMillis()
assert(finalMillis < initialMillis + 10)
```
Why wall-clock-based: proves the clock's background ticker actually stops updating after `stop()`, by sleeping 10ms of real time and checking the reported value hasn't grown past a 10ms allowance. If the ticker thread had *just* ticked immediately before `stop()` raced it, or if there's any scheduling jitter in when `initialMillis` was sampled versus when the ticker thread's last write lands, the band could be tight enough to false-fail on a loaded runner (lower risk than #3 since the band tracks the sleep 1:1, but still a real-time comparison for something that could instead be an exact-state check).
Suggested replacement: expose (or assert via a package-private hook) that the ticker thread/task is no longer scheduled after `stop()`, or sample `currentMillis()` several times across a longer real interval and assert the value is exactly constant (a fixed value across N samples) rather than bounded by an additive real-time margin — "never changes again" is a deterministic property this reduces to.

### 6. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/BlockingMonitorTest.scala:623-624` — MEDIUM-HIGH RISK
Test: `"does not interrupt blocked thread without needsInterrupt"` (`"interrupt dispatch"` group)

```scala
// But without needsInterrupt, no Thread.interrupt should be dispatched
assert(!task.needsInterrupt())
Thread.sleep(50)
assert(!interrupted.get(), "blocked thread without needsInterrupt must not be interrupted")
```
Why wall-clock-based: this is "fixed sleep then assert a state settled" applied to a *negative* outcome — it sleeps a fixed 50ms and then asserts an interrupt never arrived. Unlike the rest of the file (which correctly uses `eventually(...)` to poll for *positive* state), a negative assertion after a blind sleep is structurally a race: it only proves "didn't happen within 50ms," which is weaker than the claim being tested, and if the monitor's scan cadence is ever slower under CI load, the window narrows further without the test knowing.
Suggested replacement: rather than a single fixed-wait snapshot, actively poll/hold across the monitor's own reported cycle count (e.g. `scheduler.blockingMonitor.cycles`, already exposed and used elsewhere in this same file's "wake backpressure" test) for a bounded number of scan cycles, asserting the flag stays false at every observed cycle — this proves the negative held across N real observed monitor passes rather than "for at least 50ms of wall time."

### 7. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/BlockingMonitorTest.scala:975-982` — MEDIUM RISK
Test: `"race safety — no spurious interrupt to successor task"` (`"interrupt storms"` group)

```scala
// Let the monitor run several cycles with the second task active
Thread.sleep(1000)

// The second task should NOT have received any spurious interrupts
assert(
    !spuriousInterrupt.get(),
    "successor task on same worker must not receive spurious Thread.interrupt()"
)
```
Why wall-clock-based: same shape as #6 — a fixed real sleep (1000ms) meant to "let the monitor run several cycles," followed by a one-shot negative assertion with no polling. Under CI contention the monitor may get far fewer cycles in that window than intended, silently weakening the test (it would still pass, just without having exercised the race it claims to guard), while under different load it could also legitimately need more than 1000ms if a spurious interrupt were delayed — either direction breaks the "exactly 1000ms of coverage" assumption.
Suggested replacement: poll `scheduler.blockingMonitor.cycles` until it has advanced by a fixed number of cycles (not a fixed wall-clock duration) before asserting the negative, e.g. `eventually` on a cycle-count delta rather than `Thread.sleep(1000)`, giving a cycle-count-based (not clock-based) guarantee that the race window was actually exercised.

### 8. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/WorkerTest.scala:979` — MEDIUM RISK
Test: `"fatal Throwable from a task wedges the worker (BUG REPRODUCER for FatalFiberTest cascade)"`

```scala
// Ordering settle (no clean event to await): the worker must finish tearing down the
// dead thread before task2 is enqueued, otherwise the enqueue races the death window and
// task2 is lost. Kept as an honest settle.
Thread.sleep(200)
```
Why wall-clock-based: the comment is candid that this is a real race — a fixed 200ms sleep is used to make it likely the dying worker's teardown finishes before `task2` is enqueued. The subsequent assertion is wrapped in `eventually(...)`, but per the comment's own reasoning, if teardown takes longer than 200ms under CI load, `task2` is genuinely lost (not merely observed late), so `eventually` cannot recover it — it will run out its own budget and fail. That makes this a real flake vector despite the polling wrapper downstream, because the polling can't fix a lost enqueue, only a delayed one.
Suggested replacement: this needs a real synchronization signal for "the dying worker's teardown completed," not a duration guess. If `Worker` doesn't expose one, add a test-only hook (e.g. a `Runnable`/callback fired at the end of the thread's teardown path, or poll an already-exposed state field until it leaves `Running`) and gate the `task2` enqueue on that instead of `Thread.sleep(200)`.

### 9. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/BlockingMonitorTest.scala:695` — LOW-MEDIUM RISK
Test: `"stale interrupt from pool thread cleared on worker mount"` (`"interrupt dispatch"` group)

```scala
Thread.sleep(50) // worker goes idle, returns thread to pool
```
Why wall-clock-based: the test's whole premise (a *stale* interrupt flag left on a pooled thread by `task1` must be cleared before `task2` mounts) depends on `task2` actually landing on the *same* pooled thread `task1` used. The 50ms sleep is a guess at how long it takes the worker to go idle and the thread to return to `TestExecutors.cached`'s pool. If it's too short, `task2` still lands on the same thread (cached pools reuse promptly) most of the time, but under CI contention the assumption can silently fail — the test then passes for the wrong reason (a fresh thread has no stale flag either) rather than failing loudly, which is a coverage-loss risk more than an outright CI-red risk.
Suggested replacement: poll the worker's own state (or the pool) until the worker has actually gone idle/returned the thread, instead of a fixed 50ms — e.g. an `eventually` on a worker status flag — so the same-thread assumption is verified, not merely assumed.

### 10. `kyo-scheduler/jvm-native/src/test/scala/kyo/scheduler/BlockingMonitorTest.scala:1085-1090` — LOW-MEDIUM RISK
Test: `"wake backpressure"` (`"stress"` group)

```scala
val cyclesAdded = scheduler.blockingMonitor.cycles - cyclesBefore

assert(
    cyclesAdded < 200,
    s"1000 wake() calls triggered $cyclesAdded monitor scans — should coalesce, not scan per call"
)
```
Why flagged despite counting scans, not wall time: the test's own comment explains the intentional design choice ("Counting scans... makes this immune to CI scheduling jitter"), which is the right instinct and is why this ranks lowest of the flagged items. But `cyclesAdded` is still the outcome of a real race between a tight 1000-iteration `notifyInterrupt()` producer loop and the monitor thread's real wall-clock scheduling via `LockSupport`'s permit model — under heavy CI contention (producer loop itself slowed by scheduler pressure), the monitor thread could get more real opportunities to fire between calls than on a quiet box, pushing `cyclesAdded` up. It is a throughput/rate-shaped threshold (scans per burst) even though it avoids an explicit nanoTime measurement.
Suggested replacement: if it has ever been seen to flake, either widen the bound generously (last resort, since 200 vs. a max possible 1000 already has headroom) or replace the count comparison with a coalescing-ratio assertion that's insensitive to absolute scheduling speed (e.g. `cyclesAdded` must be a small fraction of the call count rather than a fixed absolute ceiling). No evidence of past flakiness found in this sweep; keeping as a documented low-risk watch item rather than an active fix demand.

---

## Minor / informational (not independently flaky, but timing-adjacent)

- `BlockingMonitorTest.scala:726` (`"interrupts multiple blocked tasks on different workers"`) and `:906` / `:919` (`"blocked vs active — correct discrimination"`): `Thread.sleep(10)` / `Thread.sleep(30)` / `Thread.sleep(100)` used only to "let the monitor establish a baseline" before an `eventually(...)`-gated assertion with a multi-second budget. A too-short sleep here just means the monitor takes longer to converge; the eventual assertion still has ample retry budget to reach the correct state. Not flagged as a standalone risk, but worth knowing they exist if the file is revisited.

---

## Reviewed & cleared

Confirmed either (a) driven entirely by a controlled/virtual clock (`TestTimer`), (b) asserting on state/count/ordering reached via bounded polling (`eventually`, or a hand-rolled poll-until-deadline loop) rather than a wall-clock magnitude, or (c) containing no timing dependence at all:

- `regulator/RegulatorTest.scala` — entirely `TestTimer`-driven (`timer.advanceAndRun(...)`), asserts exact `probes`/`updates` sequences. No real time anywhere.
- `regulator/AdmissionTest.scala` — entirely `TestTimer`-driven, asserts exact `percent()` values and statistical-tolerance checks on `admission.reject()` sampling (tolerance is on sample-count statistics, not wall-clock).
- `regulator/ConcurrencyTest.scala` "up" / "down" / "noop" (lines 13-38) — `TestTimer`-driven, exact `probes`/`updates` assertions. (Only the fourth test in this file, "probe jitter stays below regulator threshold with real sleep", is flagged above as #2.)
- `TestTimer.scala` — the virtual-clock test fixture itself; no real time.
- `InternalTimerTest.scala` — schedules real `1.nano` delays but only awaits a `CountDownLatch` with no timeout and no timing assertion; purely a "does it fire at all" check.
- `WorkerTest.scala` — all `"live"` and `"checkAvailability"` sections use `eventually(...)` (15s timeout / 50ms interval `PatienceConfig`) polling on state (`load()`, `executions`, `checkAvailability(...)`, flags), not on elapsed magnitude. The `Thread.sleep(50)` in `afterEach` is inter-test cleanup, not an assertion. (Only line 979 is flagged above as #8.)
- `WorkerConcurrentRunTest.scala` — all three probes use `System.currentTimeMillis()`-derived deadlines purely as busy-wait give-up safety valves; every actual assertion is on counts/state (`ran.get() == total`, `maxConcurrentRun == 1`, `stranded == 0`), never on how long anything took.
- `SchedulerTest.scala` — all assertions gated by `eventually(...)` on state (`loadAvg()`, `executions`, `busyFiberTraces()`, `probesSent`, `served` latch), including the two "stress"-flavored tests (`"blocked carriers under host CPU load..."`, `"an adequately sized dedicated timer pool keeps the regulator firing"`) which use explicit poll-loops with `nanoTime()` deadlines and assert on the loop's own observed state, not on the elapsed value — both have comments explicitly calling out "poll interval, not a fixed sleep."
- `top/ReporterTest.scala` — polls a file's content in a bounded `Thread.sleep(10)` loop up to a 5s deadline, then asserts on file *content*, not on how long it took to appear.
- `top/PrinterTest.scala`, `top/StatusFileTest.scala` — pure formatting/IO tests, no timing.
- `util/MovingStdDevTest.scala`, `util/XSRandomTest.scala` — pure numeric/statistical tests, no wall-clock dependence (`XSRandomTest`'s tolerance is on RNG distribution counts, not time).
- `WorkerQueueTest.scala` — pure data-structure tests (heap ordering, concurrency safety via `Future.get()`), no timing assertions anywhere, including its `"concurrency"` section (busy-spins `while (queue.poll() == null) {}` with no deadline/timeout at all, and blocks on `future.get()` with no timeout — waits indefinitely rather than racing a clock).
- `TaskTest.scala` (shared) — pure bit-packing/state logic, no timing.
- `SchedulerSingletonTest.scala`, `util/SingletonTest.scala` — singleton-identity and concurrent-init-safety tests; the only time-shaped API used is `future.get(5, TimeUnit.SECONDS)`, a generous safety-net timeout on an otherwise-unbounded wait, not a magnitude assertion.
- `TestExecutors.scala`, `TestTask.scala` — shared fixtures, not test classes; no assertions.

## Summary

10 findings ranked by risk, all in `util/SleepTest.scala` (4), `regulator/ConcurrencyTest.scala` (1), `InternalClockTest.scala` (2), `BlockingMonitorTest.scala` (4, one shared with the wake-backpressure count-threshold), and `WorkerTest.scala` (1). The rest of the swept surface (`RegulatorTest`, `AdmissionTest`, `WorkerConcurrentRunTest`, `SchedulerTest`, `WorkerQueueTest`, `TaskTest`, the `top`/`util` leaf tests, the two singleton tests) already follows the deterministic pattern this sweep is checking for: virtual clocks, bounded state-polling (`eventually` or hand-rolled poll loops), or assertions on counts/ordering rather than elapsed magnitude.
