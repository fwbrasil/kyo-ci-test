# Determinism audit: real-clock use in tests

Full sweep of the test suites for real-clock dependence, classified against `DETERMINISTIC_TESTS.md`. 88
candidate files (currentTimeMillis / nanoTime / Thread.sleep / nowMonotonic) plus a supplementary
elapsed-threshold sweep. Most of the suite is already deterministic (barrier/latch coordination,
`awaitCondition`/`eventually` state-polls, injected virtual clocks); what remains is a small CONVERT set,
a set of judgment-call flags, and a set of legitimate real-I/O deviations.

## A. CONVERT (flaky or banned-shape; fix to deterministic)

Barrier / count / drop conversions (no user judgment needed):

| Site | Shape | Fix |
|---|---|---|
| kyo-core RetryTest:62 | `currentTimeMillis - start >= 15` | **DONE** withTimeControl fork-and-advance (9ab9228a89) |
| kyo-scheduler BlockingMonitorTest:814 | `Thread.sleep(20)` baseline | **DONE** `awaitMonitorCycles(3)` (in-file barrier) |
| kyo-core ClockTest:417 | `Async.sleep(2.millis)` settle before read | drop (assertEventually backstops) |
| kyo-stm STMTest:1312 | writer `Async.sleep(20.millis)`, `assert(count>=2)` (flips if reader starved) | latch, the sibling at :1666 already uses it |
| kyo-compat FromCompletionStageTest:44 | daemon `Thread.sleep(50)` then complete | fork consumer + latch; drop sleeping thread |
| kyo-ffi GuardCloseStressTest:165 | `nanoTime+2s` busy-wait for cross-thread state | latch the close() path counts down |
| kyo-stats MachineSamplerTest:100,158,203 | `advance(Zero, 100.millis)` **real** settle inside withTimeControl | `awaitPendingSleepers(n)` |
| kyo-stats MachineSamplerTest:166 | `advance(Zero, 500.millis)` real settle | barrier on existing `readCount`/`parkedThread` |
| kyo-stats MachineSamplerTest:108 | redundant real settle after `fiber.get` | drop |
| kyo-tasty TestClasspathsTest:48,53 | `assert(elapsed >= 0)` (vacuous, non-monotonic) | delete; keep `symbols.length > 0` |
| kyo-data unsafe-queue stress sleeps | `Thread.sleep(200/300/1000)` soak windows | bound producers by fixed op count + latch |

Unsafe-queue files with the stress-sleep pattern: SpmcUnboundedUnsafeQueueTest:102,147;
SpmcUnsafeQueueTest:68,108,151; MpmcUnsafeQueueTest:95,143; MpscUnboundedUnsafeQueueTest:51,93,139;
MpscUnsafeQueueTest:61,123. Two of these genuinely flip and are top priority: **MpmcUnsafeQueueTest:95**
(`assert(consumed+remaining>0)` fails on a starved runner) and **MpscUnsafeQueueTest:61** (sleep is
misplaced before the start latch, so the real window is ~0, a test-effectiveness bug). The rest assert
speed-independent conservation invariants (no flip) but are the banned sleep-for-a-window shape.

## B. CONVERT (perf/complexity guards using the real clock) — DECIDED: remove the wall-clock asserts

Decision (maintainer): in scope, **remove the wall-time assertions**, keep the content/correctness asserts
already present + the suite timeout as the hang canary. A real complexity guard, if wanted later, is an
operation-count invariant, never wall time.

| Site | Assertion | Correctness already covered by | Audit recommendation |
|---|---|---|---|
| kyo-markdown MarkdownTest:454 | `elapsed < 30000` "not linear" | heading/title/table content asserts | drop wall-time assert |
| kyo-website DocsMarkdownTest:658-667 | `elapsed < 30000` "not linear" | content asserts (664-666) | drop wall-time assert |
| kyo-tasty StandardClasspathFidelityTest:83-85 | `median < 5.seconds` cold-init | `symbols.size >= 80000` (:72) + sibling exact-count | drop; the class docstring itself admits slow-runner risk |
| kyo-pod ContainerItTest:1327,2041 | `elapsed > 1000` / `firstMs*2 < lastMs` (incremental streaming) | — | prove incrementality structurally (entry 1 delivered while later still pending), not client wall-clock |
| kyo-test RunnerTest:460 | `heartbeat.toMillis > 0` | heartbeats fired + path | assert heartbeats were emitted, not that each real elapsed > 0 |

Separate flag (not pure timing): kyo-http HttpServerResilienceTest:195,267 `assert(bug.get()==0)` uses a
broad `isDriverClosed` substring match that the code's own comment says also matches benign per-connection
RSTs, so under real-time churn it can go timing-sensitive. Worth narrowing the match to whole-driver closes.

## C. Real clock unavoidable (no virtual-time seam) — but still remove threshold dependence

"No `withTimeControl` seam" does not mean "leave it as a timing assertion." The split is on the **pass
condition**:

- **C1 label-only:** the pass condition is already a **state/event**; the timeout is only a hang-canary
  ceiling a slow runner cannot flip. Add an explicit `// deviation:` label; no behavior change.
- **C2 still fix:** the pass condition is a **threshold on measured real time**. Fix it to assert
  state/structure/monotonicity, or widen to a catastrophic-only bound, even without `withTimeControl`.
  Known C2 sites: PosixTest:90/94 (`time()` skew `<= 5s`) and :134-150 (window `<= 30s`) -> assert
  positivity + monotonicity (drop the skew/window thresholds, or widen to ~1 day); ConnectDeadlineStrandTest
  tight 2s connect bound -> assert the connect completes (dropped-wakeup hangs into the suite timeout)
  rather than "within 2s".

C1 (label-only) sites:

- Real OS sockets / kernel poller: ConnectDeadlineStrandTest, PosixTransportHandshakeLivenessTest,
  PosixTransportConnectUnixDeadlineTest, IoUring* driver tests, PollerIoDriver* tests, NioTransportTest,
  JsIoDriverTest, ConnectionPoolTest reaper (:140-192, binds `Clock.live` by design).
- Real subprocess: RuntimeExecutorTest, OrchestratorTest, NativeLoaderForkStressTest.
- Raw threads / below-the-effect-system scheduler: WorkerConcurrentRunTest, SchedulerTest, WorkerTest,
  SleepTest, InternalClockTest, ReporterTest, GuardCloseRaceTest, GuardCoreDrainTimeoutTest,
  StreamCoreExtensionsTest (livelock watchdog), the unsafe-queue real-thread soaks (UnsafeQueueBaseTest:536).
- Platform clock / regulator under test: ClockTest:18-31 (live vs java.time), UpdateHistoryTest,
  compat TimeTest, regulator ConcurrencyTest (real sleep-probe jitter), native PosixTest (`time()` binding).
- Real browser/CDP: BrowserDownloadTest.

Deviation needing a decision (batch flagged it as **not** an accepted deviation as written): the
StandardClasspathFidelityTest perf assertion above lives in section B.

**Special case, needs a production seam:** kyo-net ConnectionPoolTest:116-138 ("idle-timeout eviction during
poll") depends on raw `System.nanoTime()` strictly advancing (`elapsed > 0`) between release and poll. On JS
(coarse `performance.now`) two reads can be equal, so eviction can silently not happen and the test flips.
Fixing it deterministically requires injecting a `Clock` seam into `ConnectionPool` (it currently reads
`System.nanoTime()` directly at ConnectionPool.scala:132,203,272) so the test can drive it under
`withTimeControl`. That is a production change, flagged for your call.
