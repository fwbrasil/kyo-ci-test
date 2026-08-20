# Determinism ledger

Authoritative, mechanically-grounded list of every real-clock / sleep / threshold site in the test tree,
each with a status. Built from a full sweep (`Thread.sleep`, `Async.sleep`, `System.nanoTime`,
`currentTimeMillis`, `Clock.now`/`nowMonotonic`, `elapsed`/`toMillis`/`toNanos`, `LockSupport.park`).
Supersedes DETERMINISM_AUDIT.md (which missed sites). Rule reference: DETERMINISTIC_TESTS.md.

Legend: **FIXED** committed this session · **TO-FIX** confirmed violation, open · **INSPECT** needs a
read before classifying · **DEVIATION** real seam (thread/subprocess/OS/platform clock) with a
state/ordering pass condition and at most a catastrophic-only ceiling; keep, label · **FINE** no action.

## The `Thread.sleep` sites (25) — the clearest signal (a raw block, never virtual)

| Site | Shape | Class | Status |
|---|---|---|---|
| kyo-data MpmcUnboundedUnsafeQueueTest:112,156,195,243,285 | `Thread.sleep(200)` soak window (5 tests) | FIXED | op-count + producersDone latch (the miss); validating |
| kyo-data UnsafeQueueBaseTest:536 | `Thread.sleep(testDurationMs)` shared soak helper | DEVIATION | real-thread invariant soak; pass condition is post-join clean shutdown (state), not the window; not flaky. (A conversion attempt broke the framework's must-assert contract and was reverted.) |
| kyo-scheduler BlockingMonitorTest:595,662,715,753,801,842,882,955 | `Thread.sleep(10-60s)` | DEVIATION | the sleeping task IS the blocked thread the monitor detects; barrier-started, interrupted at teardown |
| kyo-scheduler WorkerTest:32 | `Thread.sleep(50)` afterEach settle | DEVIATION | teardown hygiene, gates no assertion; leaked workers self-exit on the captured stop flag |
| kyo-scheduler SchedulerTest:201 | `while blk0<4 && nanoTime<deadline do sleep(5)` | DEVIATION | state poll (blk0>=4) + 10s catastrophic ceiling, real carriers |
| kyo-scheduler SchedulerTest:240 | `while probes<=10 && nanoTime<deadline do sleep(10)` | DEVIATION | state poll (probesSent>10) + 3s catastrophic ceiling, real regulator |
| kyo-scheduler ReporterTest:51 | `while empty && millis<deadline do sleep(10)` | DEVIATION | state poll (file content) + 5s ceiling, real subprocess |
| kyo-core StreamCoreExtensionsTest:62 | watchdog thread `sleep(2000)` then forceStop | FIXED | latch-gated watchdog: force-stops only after a catastrophic 60s of non-completion (real livelock); validating |
| kyo-core IOPromiseBlockingTest:41 | `while waiters()==0 do sleep(1)` | DEVIATION | cross-thread state poll, below effect system, no ceiling (suite timeout) |
| kyo-browser BrowserLauncherCleanupJvmTest:126 | `awaitCondition` loop `sleep(stepMs)` w/ deadline | DEVIATION | state poll (cond) + deadline ceiling, real browser process |
| kyo-ffi GuardCloseStressTest:39 | `Thread.sleep(1-5ms)` interleave jitter | FINE | simulates a callback body to create stress pressure; gates no assertion |
| kyo-test LeakCheckTest:23,38 | `Thread.sleep(20/10)` loadAvg sampling | DEVIATION | samples a real OS load metric over time; no virtual seam |
| kyo-test LeakCheckTest:296 | `try Thread.sleep(60000)` | DEVIATION | a thread blocking is the leak subject under test |
| kyo-ffi GuardCloseStressTest (deadline) | 2s busy-wait deadline | FIXED | b5ef783dbb (spin until state; suite-timeout canary) |
| kyo-data Mpmc/Mpsc/Spmc/SpmcUnbounded/MpscUnbounded soaks | `Thread.sleep(200/300/1000)` | FIXED | acd43abd34 (op-count + conservation) |
| kyo-stats-machine MachineSamplerTest settles | `advance(Zero, realDelay)` | FIXED | acd43abd34 (awaitPendingSleepers + park latch) |

## Clock-in-assertion candidates (from the nanoTime/currentTimeMillis/elapsed sweep)

| Site | Shape | Class | Status |
|---|---|---|---|
| kyo-http UnsafeServerDispatchTest:1664,1668 | assert closed-state around an idle timeout | FINE | under `Clock.withTimeControl`; exact `tc.advance` brackets the timeout (model virtual-time test) |
| kyo-scheduler BlockingMonitorTest:115 | `assert(nanoTime < deadline, "did not complete N scans")` | DEVIATION | pass condition is N scans done; deadline catastrophic |
| kyo-scheduler InternalClockTest:66,82 | `assert(nanoTime < deadline, "clock stopped publishing")` | DEVIATION | pass condition is an update published; deadline catastrophic |
| kyo-scheduler WorkerTest:614-911 | `assert(!worker.checkAvailability(currentTimeMillis()))` | FINE | currentTime is an API input; assertion is on the boolean state |
| kyo-test LeakCheckTest:160,182 | `assert(clock.get() </>= budget)` | FINE | `clock` is the framework virtual/accounted clock, not real |
| kyo-core ClockTest:299 | `assert(elapsed == Duration.Zero)` | FINE | under withTimeControl, exact |
| kyo-ffi GuardCoreHazardsTest:228 | `closeWithPolicy(5.seconds.toNanos) == Clean` | FINE | toNanos is an API input; assertion on outcome |
| kyo-compat TimeTest:81 | `assert(!ran.get(), "... before delay elapsed")` | INSPECT | assertion on a boolean; confirm under time control |
| kyo-doctest OrchestratorTest:482 | doctest string `assert(nanoTime > 0)` | FINE | test data (a doctest body), trivially true |
| kyo-ffi-it PosixTest time() | skew/window tolerance | FIXED | b5ef783dbb (bracketing) |
| kyo-net ConnectDeadlineStrandTest | 2s connect deadline | FINE (reverted) | already state-based (timedOut==0); deadline is an API input |
| kyo-net ConnectionPoolTest:116 | poll-eviction needs nanoTime advancing | DEVIATION | holds on JVM/Native/Node; production `>=` fix only with a repro |

## Bulk categories confirmed FINE (spot-checked, no per-line action)

- **Async.sleep (311 sites):** overwhelmingly under `Clock.withTimeControl` (virtual, fine) or legitimate
  effect composition driven by fork-and-advance / barriers. Not raw blocks.
- **Clock.now / nowMonotonic (94):** under withTimeControl, or `Clock.live`-bound by design (ConnectionPool
  reaper), or logging.
- **Duration arithmetic** (`5.millis.toMillis`, `.toNanos` as API inputs): exact, not measured elapsed.
- **TestSleep / ApiTestSleep** (kyo-test): the runner's own platform sleep primitive (infra, not a test).

## Method for every TO-FIX / INSPECT

Pass condition must be state / ordering / bracketing / count, never a magnitude comparison on a measured
quantity. Soak windows → fixed op count + a done latch (consumers drain until producers-done and empty).
Settles → a barrier on the event. A real seam that cannot be virtualized stays a DEVIATION only if its
assertion is state and any ceiling is catastrophic-only; label it `// deviation:`.
