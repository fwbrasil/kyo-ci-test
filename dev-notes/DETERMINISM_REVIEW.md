# Held-out review of the determinism campaign

A held-out Opus agent (fresh context, not a fork, so it could not inherit the author's
rationalizations; analysis-only, no edits or runs) independently verified every claim in
`DETERMINISM_CAMPAIGN_REPORT.md` against the tree. Its findings, and the path taken from them.

## A. The two broken conversions (both have clean deterministic fixes)

### A1. `UnsafeQueueBaseTest.concurrentTest` (kyo-data)

The reverted conversion deleted `assert(!t.isAlive, ...)`, the only assertion the shared helper
itself evaluated. That broke must-assert for the **5 bare leaves** (pollAfterIsEmpty, noDataLoss,
highContention, singleElementPingPong, concurrentDrainNoLoss); the other 5 "passed" only because
their `(...).map { t => assert(!failure.get()); t }` runs at BUILD time (inside `body`, before the
threads start), where `failure` is always false. So the invariant assertions were never checked
after the soak in the entire history of the file. The report's "~161 leaves broke" was wrong; the
truth is worse in a different way (near-all concurrent leaves asserted nothing meaningful).

Correct conversion (the template already existed in the author's own
`MpmcUnboundedUnsafeQueueTest.xaddUniqueness`): give the helper a post-join `check` thunk. `Thread.join`
establishes happens-before, so a post-join read of a plain flag/counter is safe and meaningful. Move
every invariant assertion there; delete the build-time `.map{assert}`; bound each worker by a fixed op
count that self-terminates; keep the bounded join as a catastrophic-only hang canary plus the
`!isAlive` must-assert backstop.

Per-leaf `check`:
- memory-visibility (happensBefore_poll/peek): `assert(!failure.get())` PLUS a consumer-only
  `observed` counter and `assert(observed > 0)` so "never saw a bad value" is not vacuous. `failure`
  and `observed` are consumer-write only, so the queue stays the sole producer→consumer channel: no
  added barrier masks the visibility bug.
- size invariants: `assert(!failure.get())` + `observed > 0`.
- noDataLoss / concurrentDrainNoLoss: `assert(consumed + remaining == offered)` after draining the
  remainder post-join. **Bug fix:** noDataLoss incremented `offered` even on a failed offer, so
  conservation could never hold on a bounded queue; count only successful offers.
- contention/ping-pong (no counters): drain post-join, then `assert(q.isEmpty() && q.size() == 0)` to
  verify size/emptiness accounting survived the soak.

### A2. `MachineSamplerTest` teardown leaf (kyo-stats-machine)

Root cause was NOT the arming fence (`awaitPendingSleepers(2)`; the sibling leaves use the same fence
and pass). It was deleting the post-`fiber.get` `advance(Zero, 500.millis)`, which is a real 500 ms
sleep. `close()` runs only in the LIFO-last `Scope.ensure` finalizer (`MachineSampler.scala:151-155`);
`Abort.run(fiber.get)` resolves on the interrupted result BEFORE that finalizer completes, so the
close marker was not recorded and `count(_ == "close") == 1` failed. The comment claiming "fiber.get
resolves only after every Scope finalizer runs" was false and was the direct cause of the red.

Correct deterministic fix (the idiom the sibling parked-disk leaf already uses): release a latch from
the `close()` callback and await it before snapshotting. No wall clock.

## B. Per-deviation verdict

GENUINE (real seam virtual time cannot cover; state/give-up-valve pass condition, sleep is the subject
or a poll of real state):
- BlockingMonitorTest sleeps (595/662/715/753/801/842/882/955): the sleeping task IS the TIMED_WAITING
  thread the blocking monitor detects.
- BlockingMonitorTest:115 acrossMonitorCycles: window measured in monitor SCANS (op-count), 60 s is a
  give-up valve for a dead monitor thread.
- InternalClockTest:66/82: polls a real clock update thread; 30 s catastrophic give-up.
- SchedulerTest:201/240: polls real carrier/regulator state below the effect system.
- ReporterTest:51: polls a file written by the reporter's OS thread; asserts content.
- IOPromiseBlockingTest:41: waits for a raw thread to register its waiter before interrupting it.
- BrowserLauncherCleanupJvmTest:126: polls real Chrome subprocess liveness post-scope.
- LeakCheckTest:23/38/296: samples the real OS load-average metric / a leaked non-daemon thread.

REWARD-HACK, must CONVERT (three of the author's DEVIATION labels were wrong):
- **UnsafeQueueBaseTest:536** ("not flaky → deviation"): a 200 ms wall-clock soak window is exactly what
  the campaign exists to kill, and the fix is proven in the same PR. See A1.
- **ConnectionPoolTest:116** ("no repro → don't fix"): demanding a repro before removing a real-clock
  pass condition is the banned move. The pass depends on `System.nanoTime()` advancing between two
  adjacent reads. Fix: seam the pool's monotonic time source and drive it with `withTimeControl` at an
  exact boundary (preferred), or make the idle-timeout boundary inclusive (`>`→`>=` on the poll path
  AND `<=`→`<` on the sweep path together, so the two stay consistent).
- **WorkerTest:32** ("afterEach settle"): a 50 ms settle is itself a flaky guess. Track the workers
  `createWorker` builds and join/await an exit latch in `afterEach`.

## C. General recipe (real-thread concurrent soak → deterministic)

1. Bound each worker by a fixed op count and self-terminate; keep ops non-blocking so no worker waits
   on another (no deadlock in bounded/pooled configs).
2. Terminate by draining, not by clock; count only SUCCESSFUL operations.
3. Join with a catastrophic-only bounded join, then `assert(!t.isAlive)` (the must-assert backstop).
4. Assert the invariant AFTER join, on state the join made visible. Never inside the thread-construction
   lambda (build-time = vacuous).
5. Do not add a per-iteration shared atomic both roles touch on a memory-visibility test; keep
   failure/observed counters single-role. Shared atomics are fine on conservation tests.
6. Make negatives non-vacuous: pair "never saw a bad value" with proof the check ran (`observed > 0`).

## D. Sequencing

Convert-and-validate one at a time; commit each after its own green run. MachineSampler first (isolated),
then the UnsafeQueueBase helper redesign (validate full kyo-data on JVM AND Native, the leaves are
`.notJs`), then ConnectionPool (production change → full kyo-net run), then WorkerTest.

## E. Where the report/ledger was self-serving or wrong

1. "~161 leaves broke" is wrong; it was the 5 bare leaves, and the other 5 asserted vacuously.
2. The ledger's `UnsafeQueueBaseTest:536` DEVIATION was stale and self-serving.
3. The `MachineSampler.scala:102` comment was false and directly caused the red.
4. The `ConnectionPoolTest:116` "no repro" gate is the banned move.
5. The Section B assertion-removals (MarkdownTest, DocsMarkdownTest, StandardClasspathFidelityTest,
   RunnerTest) were only compile-checked; each needs a read to confirm it still asserts something real.
6. In the author's favor: the Thread.sleep census IS complete; the gap was classification, not coverage.
