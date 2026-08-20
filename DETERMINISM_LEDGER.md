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
| kyo-data UnsafeQueueBaseTest:536 | `Thread.sleep(testDurationMs)` shared soak helper | FIXED | 5fa45c8600: op-count workers + a post-join `check` thunk (Thread.join happens-before), meaningful per-leaf assertions, and the noDataLoss conservation bug fixed. Validated JVM+Native (1330 each). The prior DEVIATION label was a reward-hack (held-out review A1). |
| kyo-scheduler BlockingMonitorTest:595,662,715,753,801,842,882,955 | `Thread.sleep(10-60s)` | DEVIATION | the sleeping task IS the blocked thread the monitor detects; barrier-started, interrupted at teardown |
| kyo-scheduler WorkerTest:32 | `Thread.sleep(50)` afterEach settle | FIXED | wrap the test executor to count in-flight worker run() invocations (a worker mounts by submitting itself, a Runnable; the isInstanceOf[Worker] filter excludes each worker's InternalClock ticker on the same executor); afterEach spins until the count is 0 (state barrier), with a catastrophic-only nanoTime ceiling. Validated JVM: WorkerTest 49/0. |
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
| kyo-stats-machine MachineSamplerTest settles | `advance(Zero, realDelay)` | FIXED | 001454f4fe: awaitPendingSleepers arming fence + a close-latch barrier (the dropped 500ms settle masked the async close-finalizer gap; held-out review A2). Validated 6/6. |

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
| kyo-compat TimeTest:81 | delay/timeout/race, 50ms vs 5s | FINE | real CIO sleeps, but every pass condition is a 100x-gap ORDERING/boolean (timedOut==None, !ran.get(), winner==shortId), never a magnitude threshold; a slow or starved runner keeps timers deadline-ordered, so 50ms-before-5s cannot flip. Real duration IS the point: proving the cats-effect binding produces genuine real-time suspensions. |
| kyo-doctest OrchestratorTest:482 | doctest string `assert(nanoTime > 0)` | FINE | test data (a doctest body), trivially true |
| kyo-ffi-it PosixTest time() | skew/window tolerance | FIXED | b5ef783dbb (bracketing) |
| kyo-net ConnectDeadlineStrandTest | 2s connect deadline | FINE (reverted) | already state-based (timedOut==0); deadline is an API input |
| kyo-net ConnectionPoolTest:116, :140, :168 | poll/reaper eviction needed a real nanoTime advance / a real-clock wait (up to 5s) | FIXED | Clock seam: ConnectionPool sources the ambient Local clock via `Clock.use` (no `Clock.live` default; user-directed), so all three run under `withTimeControl` with a virtual `advance` plus a discard-latch / awaitPendingSleepers fence. Production `init` is now `< Sync`; the two unsafe callers (HttpClientBackend, SqlConnectionPool) wrap it in evalOrThrow (live in prod). :140/:168 were audit misses. Prior :116 DEVIATION was a reward-hack (review B). Validated JVM: ConnectionPoolTest 14/0, ConcurrencyTest 4/0. |

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
