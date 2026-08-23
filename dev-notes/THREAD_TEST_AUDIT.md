# Audit: `Thread` usage in tests

Swept all test trees (JVM/JS/Native/Wasm) for `Thread.sleep`, `new Thread`/`Thread(`, `Thread.currentThread`, `.join`, `setDaemon`, virtual-thread APIs. ~93 test files touch `Thread`; ~93 real `Thread.sleep` call sites, ~100 `new Thread`/`Thread(`.

The kyo rule "never block a thread; use `Async` suspension" governs **effect/production code and effect-level tests**. It does not govern the tests of the thread-level concurrency primitives themselves: a lock-free queue, a guard close-race, the scheduler, a `Gate`, `IOPromise.block`, or the blocking-detector cannot be exercised through the `Async` abstraction that sits on top of them, they need real OS threads.

## Verdict: usage is overwhelmingly legitimate; no flaky anti-pattern found

Unlike the GC sweep (which surfaced genuinely flaky, fixable tests), `Thread` usage here concentrates exactly where thread-level concurrency is the subject under test.

### Legitimate: primitive-under-test needs real threads

| Module (count of `Thread.sleep` / `new Thread`) | Why real threads are required |
|---|---|
| `kyo-scheduler` (29 / 11) | Tests the work-stealing scheduler, workers, internal clock, regulator, blocking monitor. `BlockingMonitorTest` literally uses `Thread.sleep` as its **subject** ("Thread.sleep — TIMED_WAITING detected as blocked"). |
| `kyo-data` queue tests (18 / 34) | MPMC/MPSC/SPMC lock-free `UnsafeQueue` stress tests: real producer/consumer threads, `Thread.sleep(200)` bounds the stress window. Duration-based stress, not exact-timing sync. |
| `kyo-ffi` (0 / 37) | Guard close/leak thread-safety under real contention (`GuardRegistryStressTest`, `JvmGuardCloseRaceTest`, `GuardCloseStressTest`, `JvmGuardConcurrentCloseTest`). |
| `kyo-test` framework (8 / 3) | The test framework's own platform `sleep` primitive (`ApiTestSleep`/`TestSleep` = `def sleep = Thread.sleep`) and `LeakCheckTest` (spawns a genuinely-blocked non-daemon thread as the leak subject, then interrupts). |
| `kyo-core` (3 / 4) | `GateJvmTest` (real passer thread for the `Gate` primitive), `IOPromiseBlockingTest` (real thread for `IOPromise.block`), plus the freeze-repro below. |
| `kyo-net` (0 / 7) | Transport/driver concurrency. Note: the kyo-net posix suites are the *model* of the effect-level rule, their scaladocs explicitly document "Anti-flakiness: no `Thread.sleep`, no busy-spin", waiting via `awaitCondition`/promises. |

### Effect-level tests already comply

No effect-level (Fiber/Async business-logic) test uses `Thread.sleep` as a synchronization hack. This is enforced culturally, kyo-net's suites carry explicit "no Thread.sleep" scaladocs and wait on observable state instead. The grep's kyo-net "Thread.sleep" hits are all such *documentation of absence*, not usage.

## Borderline (defensible, not flaky, could optionally be reconsidered)

- `kyo-core/.../StreamCoreExtensionsTest.scala:62` (`Thread.sleep(2000)` in a daemon watchdog, `.onlyJvm`). Justified freeze-repro: the test deliberately livelocks the scheduler (every worker pinned in `Stream.handleLoop`), so `Async` cannot deliver the interrupt, a raw watchdog thread is the only way to force-stop the producers. Documented in a 9-line comment. It does make the leaf take ~3s and lean on a 2s-vs-3s timing margin.
- `kyo-compat/.../FromCompletionStageTest.scala:44` (`Thread.sleep(50)` completing a `CompletionStage` from a background thread to prove suspension). Minor interop-timing idiom; a controlled completion point would be more deterministic but this is low-risk.
- `kyo-scheduler-finagle/.../KyoFinagleSchedulerServiceTest.scala:72` (`Thread.sleep(100)`), `kyo-browser` launcher cleanup (`Thread.sleep` polling an external browser process). Both wait on external/infra state; acceptable.

## Bottom line

The `Thread` sweep is clean: usage is appropriate for what each suite tests, and the effect layer already follows the no-blocking rule. There is no masked-flaky or should-be-`Async` finding analogous to the GC gauge test. The three borderline items are documented and low-risk; none require a change unless you want the `StreamCoreExtensions` freeze-repro reworked off its fixed-timing watchdog.
