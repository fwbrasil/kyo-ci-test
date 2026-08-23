# Test Reliability Operating Procedure

Living document for the test-reliability campaign. Read before touching any test.
Update the ledger at the bottom as work proceeds.

## The goal (read this first, every time)

**Improve test reliability by removing timing dependence that causes flakiness.**

"Zero sleeps" and "no magic thresholds" are *proxies* for that goal, not the goal.
A construct is only worth changing if the change makes the test **more reliable**. If a
change would keep the test equally reliable but is just cosmetically sleep-free, or would
make it *less* reliable, do not make it.

Phases:
1. **Sleeps** (current) - fixed-duration waits that race async work.
2. **Threshold-sensitive tests** (next) - assertions/waits on a magic number (elapsed ms,
   iteration counts, "usually enough" quantities).

Both phases share one philosophy: **wait on / assert the genuine event, never a stand-in
for elapsed time.**

## The core principle

A timing construct (a sleep, or a tuned number) may be replaced **only** by a wait for a
**genuine observable condition that means exactly "the awaited thing has happened."**
That replacement needs **zero tuning**. If you are choosing or adjusting a number, you have
not found the event.

## Decision procedure - apply to every sleep, one at a time

1. **Name what the sleep is actually waiting for.** ("let the subscriber subscribe", "let
   the notification arrive", "let the fiber finish", "run the stress for a while".)
2. **Is there an observable condition that means exactly that happened?** Common ones:
   | Waiting for | Genuine barrier |
   |---|---|
   | a fiber to subscribe to a signal | `assertEventually(ref.waiters.map(_ == 1))` |
   | a callback/handler to fire | await the `Promise`/`Latch`/`CPromise` it completes |
   | a notification/message to arrive | `assertEventually(<its own counter/ref>)` |
   | a stream value to be emitted | observe it (Channel/`getResult`), then act on the next |
   | a fiber to finish / cancellation to propagate | `fiber.getResult` |
   | a fiber to be blocked/retrying (STM) | a counter incremented at the top of its body, **`>= 2`** (see note) |
   | an OS process to die | bounded poll of real state (`kill -0`) |
3. **If YES** -> replace with `assertEventually(<that exact condition>)` or await the signal.
   No numbers to tune. Done.
4. **If NO** - the sleep is a *duration* with no crisp event:
   - a stress soak ("run concurrent ops a while, then check invariants"),
   - simulated slow work (models a slow computation to open a contention/abort window),
   - a cooperative yield / scheduler-fairness pause (no `Async.yield` primitive exists),
   - the sleep **is** the test subject ("Async.sleep inside an STM body ..."),
   -> **LEAVE THE SLEEP.** Record it in the ledger with its category. Do not convert.

## Banned replacements (each is WORSE than the sleep)

- **Spin / busy-wait loop** on a coordinating thread: `while !cond do Thread.yield()`.
  Burns a core, still a guess. Never.
- **A magic quantity you tune** (N items, N iterations, N ms) adjusted to make the test pass
  or run fast. The act of tuning is proof it is a timing hack. If you catch yourself changing
  a number and re-measuring, stop and revert.
- **A proxy-for-time condition**: waiting until a counter reaches a value chosen to
  approximate the old duration, rather than the actual event.

## The "don't touch" rule

**If removing a sleep would require a loop or a tuned threshold, do not touch the test.**
An honest sleep is more reliable than a fake-deterministic hack. Leave it and report it.
Touching a raw-thread stress/soak test to remove a sleep is especially dangerous (see the
kyo-data lesson below): making dormant threads actually run can starve the harness.

## Why `assertEventually` is allowed but a spin loop is not

`assertEventually(cond)` suspends via `Async` between checks and returns the instant the
**real** condition holds; its poll interval is not a correctness parameter, and it adds no
fixed delay to the happy path. A `while do yield()` loop pins an OS thread. The condition
inside `assertEventually` must still be the genuine event, never a count picked to mimic time.

## Validation rigor (non-negotiable)

- These are concurrency tests. **One green run proves nothing.** After converting, run the
  affected suite **multiple times** (>= 3, more for the touchiest) and confirm all green.
- Run backgrounded with **full output to a log file** and watch the log; never grep-pipe the
  sbt output (it hides compile progress and self-test `*** FAILED ***` lines mislead).
- **Compile-green is not runtime-green.** Run it. Never report done without a real run.
- Do not run two heavy sbt builds at once while checking a timing test - it perturbs timing.

## Commit discipline

- Commit per file/module once validated. Author **Flavio Brasil <fwbrasil@gmail.com>**;
  run `git config user.email` first, and if it is the CI/bot account use
  `git -c user.name='Flavio Brasil' -c user.email='fwbrasil@gmail.com' commit`.
- No AI attribution / Co-Authored-By / session links, anywhere.
- Never open/interact with PRs. Push only when explicitly asked.

## Mistakes already made in this session (do not repeat)

- Replaced a queue sleep with a spin loop `while consumed < N do Thread.yield()`. Worse.
- Then replaced it with "bounded production" and **tuned the item count** (10000 -> 2000)
  against measured runtime. That is a timing hack wearing a count's clothes.
- "Fixed" the vacuous `MpscUnsafeQueueTest:61` ordering bug by letting its 8 producer threads
  actually spin for 1s -> starved the kyo-test scheduler -> 2-minute timeout. Reverted.
  Lesson: fixing a sleep-adjacent bug in a raw-thread soak is a redesign, not a sleep edit.
- Earlier: proposed leaving load-bearing sleeps as a *recommendation to save work* - that was
  reward-hacking. Leaving a sleep is correct only when it is a genuine duration (category in
  step 4), and it must be recorded, never used to dodge convertible ones.

## Phase 2: threshold-sensitive tests

A test that ASSERTS on a magic number that is a proxy for elapsed time or a "usually passes"
count flakes for the same reason a settle sleep does: under load the wall clock varies, and a
number chosen to sit near the expected value gets crossed. The fix is the same in spirit as
Phase 1 - assert the genuine event/outcome, never a stand-in for elapsed time.

### Decision procedure (per threshold assertion)

1. **Name what the assertion is really trying to verify.**
2. **Is the real property an event or an outcome rather than the elapsed time?**
   - "operation is fast" / "not too slow" (an upper ceiling `elapsed < X`) -> the real property
     is usually that the operation **completed** (await it) or that a **deadline/budget was
     enforced** (it aborted / returned with the right outcome). Assert the OUTCOME
     (completed, or aborted with the expected error), not the wall clock.
   - "a delay happened" (a lower bound `elapsed >= X`) -> verify the delay's **effect** (the
     delayed value arrived only after its trigger; ordering) or make the delay deterministic
     with a virtual clock and assert exactly.
   - "it times out / aborts on a deadline" -> drive the deadline with a **virtual clock**
     (`Clock.withTimeControl`) so the fire is deterministic, and assert the abort outcome.
3. **If YES** -> convert to assert the event/outcome, or use `Clock.withTimeControl`. No magic
   wall-clock number, no tuning.
4. **If NO** -> the test's PURPOSE is to measure real elapsed time or a rate (a benchmark, a
   rate-regime separation, a "the real monotonic clock advanced" check), OR the number is a
   **generous safety envelope** that only trips on a real hang (e.g. `< 5.seconds` guarding a
   sub-millisecond operation) -> LEAVE, record why. A generous envelope 10-100x above the
   expected value is a don't-hang guard, not a flaky threshold; a tight ceiling near the
   expected value is the flaky kind. When a generous envelope really just means "don't hang
   forever," an `Async.timeout` around the operation expresses that intent better than an
   elapsed-time assertion, but this is a refactor, not a reliability fix - do it only if it
   also removes a genuine flake.

### Key tools

- `Clock.withTimeControl` - advance virtual time explicitly; the right tool for delay / timeout
  / deadline / schedule tests. The timing becomes deterministic and the assertion becomes exact.
- Assert the **outcome** (aborted / succeeded, the value, the error), not the wall clock.
- Await the **completion event**.

### Banned (same as Phase 1)

- Tuning the number to make it pass or to hit a runtime.
- A spin / busy-wait loop.
- Widening a ceiling "to be safe" - that is tuning. Either the assertion is on the wrong thing
  (reframe it) or the number is a genuine generous envelope (leave it, do not fiddle).

### Corrections (from a held-out review of this campaign - do not repeat these gaps)

- **A "generous envelope" is only safe to leave if it sits strictly BELOW the smallest value it
  must reject.** The "2x-60x the budget -> leave" heuristic is right for a ceiling guarding a *hang*
  (sub-ms op under `< 5s`), but WRONG for a ceiling meant to *discriminate two finite regimes*:
  widening it "3x for CI tolerance" can push it past the failure value, making the assertion
  vacuous (it passes whether the property holds or not). Before leaving a ceiling, check: does the
  broken case's value still fail it? If the broken value <= the ceiling, it is not generous, it is
  vacuous - fix it, do not leave it. (This is the `:713` isolation bug: `<= 2400ms` above a
  ~2000ms leak.)
- **"Assert the outcome, never elapsed" has one real exception: a wall-clock ceiling IS the correct
  assertion when correct and broken paths share the same OUTCOME and differ ONLY in elapsed time.**
  The classic case is an isolation / config-precedence test where both the correct and the leaked
  path abort with the same exception type; the only discriminator is when. There, neither the
  outcome rewrite (outcomes are identical) nor detectable-hang (the losing schedule cannot be made
  infinite without breaking the winning sibling) applies - use a **discriminating** ceiling
  positioned strictly between the two regimes. This is NOT "the wall clock is the subject"
  (measurement); it is "the wall clock is the only discriminator". Do not mislabel it a measurement
  subject and leave it vacuous.
- **Detectable-hang and unbounded `assertEventually` push correctness into the leaf-timeout config.**
  They are valid ONLY where the leaf has a finite timeout strictly shorter than the "infinite"
  duration (`1.hour` / `Async.never`) and strictly longer than the correct path. Consequences to
  keep in mind: under a debugger the leaf timeout is `Infinity`, so every converted regression hangs
  forever; a suite that lowers its timeout below a converted correct-path duration false-fails; and
  the red-build signal degrades from a precise assertion to a coarse "TimedOut" at the full leaf
  timeout (and one feature regression can hang *every* converted leaf). Use it when the window was
  genuinely CI-flaky; do not use it where a precise, non-flaky assertion is available.
- **The `1.hour` in detectable-hang IS a chosen number, and that is fine** - it is chosen to be
  effectively infinite (>> the leaf timeout), NOT tuned to the expected value. "Never pick a number"
  means never pick one *near the value you are asserting*; picking one you only need to be
  unreachable is allowed. Keep the two distinct.
- **Prefer a precise outcome/effect assertion over detectable-hang; reach for detectable-hang only
  when no finite discriminator exists.** Detectable-hang is correct per-site but has an AGGREGATE
  cost: when a whole family of tests uses it, one real feature regression hangs *every* converted
  leaf to the full leaf timeout (90-120s each) and reports a coarse "TimedOut" with no indication of
  which property broke, instead of one fast, precise red. So it is a residual tool, not a default:
  if a peak/effect counter or a typed-outcome assertion can distinguish correct from broken, use
  that; use detectable-hang for the cases where correct and broken share an outcome and only an
  (unbounded) duration separates them.
- **Before choosing a discriminating wall-clock ceiling (the P2 case), first PROVE the losing
  schedule cannot be made infinite without breaking the winning path - and "cannot" has a precise
  test.** A detectable-hang needs the losing schedule made effectively infinite while the winning
  fiber still succeeds. That is possible whenever the winner's positive event fires at or below its
  own budget, because you raise only the `maxDuration` (the cap), NOT the poll interval:
  `Schedule.fixed(50.millis).maxDuration(1.hour)` still polls every 50ms and matches at 700ms, so
  the winner is unaffected while a leaked never-matching fiber hangs. (Do NOT use `Schedule.fixed(1.hour)`
  for this - that is a one-hour POLL interval and will miss the winner's event, hanging the winner.)
  Only if the winner genuinely has no bounded positive event below the losing cap does the
  discriminating ceiling become the right tool. Getting this wrong (as this campaign initially did on
  the `:713` isolation test - a `<= 1500` ceiling was shipped when a clean `maxDuration(1.hour)`
  detectable-hang was available) leaves a thin, load-sensitive wall-clock margin where a
  hang-based discriminator was available.
- **STM "blocked/retrying" barrier: increment the counter and wait `>= 2`, not `>= 1`.** STM retry is
  schedule-based re-execution, so a counter at the top of the waiter body climbs every retry.
  Waiting `>= 1` only proves the waiter ENTERED its body once (the boundary attempt could read the
  freshly-published value and complete without ever retrying); `>= 2` proves it actually re-ran
  against the unsatisfiable value at least once, which is what "the waiter is genuinely blocked and
  will be woken by the publish" requires. It is reliable because the ~1ms retry schedule reaches 2
  attempts within milliseconds.

---

## Ledger

### Phase 2 converted (threshold -> outcome/event, validated by repeated runs)

| File | Sites | Fix |
|---|---|---|
| kyo-jsonrpc JsonRpcHandlerTest:858 | 1 | drop `elapsed < 100`; "noop" has no responder, so `awaitDrain` completing proves non-blocking |
| kyo-browser BrowserReadTest (fast-path x2) | 2 | make a retry a detectable hang (`Schedule.fixed(1.hour)`); returning at all proves no retry |
| kyo-browser BrowserPerCallScheduleTest | 26 | prove which schedule ran by outcome: losing schedule = `neverSchedule` (hangs the leaf timeout), short winner aborts; assert the abort |
| kyo-browser CdpBackendLifecycleTest:1148 | 1 (deleted) | deleted the `awaitDrain < 5.millis` perf micro-test (impl detail; behavior covered elsewhere) |

Phase 2 left: ClockTest "Sleep" subsection (real sleep; virtual clock would be circular - user
decision), and the generous-envelope / measurement-subject / already-virtual-clock majority (see
PHASE2_THRESHOLD_AUDIT.md).

### Phase 1 converted (event-based barrier, validated by repeated runs)

| File | Sites | Barrier (the genuine event) |
|---|---|---|
| kyo-compat FiberTest | 5 | await the `CPromise` the onComplete callback completes |
| kyo-mcp (3 files) | 10 | `assertEventually(<the notification's own counter>)` / delete no-op-notify sleeps |
| kyo-flow FlowApiTest, FlowTest | 13 | dead time (persist-before-return) / `getResult` |
| kyo-core Exchange/Channel/Process/AsyncPlatformSpecific | ~13 | `awaitDone` / `pendingPuts` / `kill -0` poll / `cf.isCancelled` |
| kyo-core SignalTest (direct-ref) | 4 | `assertEventually(ref.waiters == 1)` |
| kyo-actor ActorTest | 1 | delete (sat on top of `getResult`) |
| kyo-stm STMStressTest | 9 | counter-at-top-of-waiter-body `assertEventually(>= 1 / >= N / >= 2)`; publish-after-retry |
| kyo-zio ZStreamsTest (2 interruption tests) | 4 | park after first element (`ZIO.never`/`Async.never`) + `started`/`finalized` signals; `chunkSize = 1` so the parking pull does not swallow the first element |
| kyo-reactive-streams PublisherToSubscriberTest | 2 | await each subscriber fiber's `getResult` after interrupting the publisher (propagation completes them); removed the self-defeating manual-interrupt "safety net" |
| kyo-http HttpWebSocketTest | 1 | client handler awaits the in-scope promise the server completes from its `closeReason` (stays open until the close is observed) |
| kyo-browser BrowserDownloadTest / BrowserMutationTest | 2 | gate on the downloaded files existing (WillBegin fires before the file lands); drop a settle that sat on top of an existing `Browser.waitFor` guard |

### Left in place (genuine duration - recorded, not skipped)

| File:site | Category |
|---|---|
| kyo-stm STMStressTest:150 | simulated slow-work (elder tx contention window) |
| kyo-stm STMStressTest:1547 | simulated slow-work (slow TMap.fold abort window) |
| kyo-stm STMStressTest:848, :1735 | sleep IS the subject (Async.sleep inside an STM body) |
| kyo-stm STMStressTest:1627 | cooperative yield (no `Async.yield` primitive) |
| kyo-stm STMStressTest:697 | duration soak (sibling count-soak exists) |
| kyo-data queue tests (harness + siblings) | stress-soak durations, correctly ordered - no crisp event |
| kyo-core SignalTest `pollUntil`/`awaitValue` (:827,:836) | bounded poll of the real condition (correct pattern, 1ms poll interval) |
| kyo-caliban ResolversTest (all: :461,:976,:1191,:1398,:1658,:1882) | server-side cleanup settles with no client-observable event, a slow-resolver subject, or a vacuous constant assertion the settle does not affect - nothing to convert |
| kyo-browser (BrowserSettlementTest:1042, BrowserHistoryTest:404, Viewport/Screencast/Emulation/Snapshot 30-60s, DownloadTest:35/316 polls, demos) | slow-server-handler delays (the subject), bounded polls, demo pacing |
| kyo-net PosixTransportAcceptEmfileTest:154 | intentional rate-measurement window (scaladoc: two regimes separated by >10x); a phase-2 threshold candidate, not a settle race |
| kyo-scheduler WorkerTest:983, KyoFinagleSchedulerServiceTest:72 | delicate scheduler-internals settle-then-assert; the event (worker re-arm / fork parked on the promise) is not cleanly observable. WorkerTest's `sleep(500)+assert(task2 ran)` is convertible to a bounded poll of `task2.executions` but sits in scheduler-recovery internals; left pending a focused pass |
| kyo-pod ContainerItTest:3545 | container-based in-container wait (heavy); not yet assessed |

### Reported as genuine bugs (NOT sleep edits - need a separate decision)

- **kyo-data `MpscUnsafeQueueTest:61`** `manyProducersSingleConsumer`: `start.countDown()`
  runs *after* `Thread.sleep(1000)`, so all threads sit parked on the start latch for the
  whole window and are stopped the instant they wake -> queue near-empty -> the per-producer
  FIFO check iterates ~nothing (**vacuous**). Naive fix (countDown first) makes 8 threads spin
  1s and starves the harness (2m timeout). A safe fix is a redesign (fewer threads / shorter
  window / non-spinning), out of scope for a sleep pass.
- **kyo-core `SignalTest` composed-signal streamChanges (12 sleeps)** — BLOCKER, no reliable
  barrier found. `switchMap`/`combineLatest` wait via `Signal.awaitAny = Async.race(a.next,
  b.next)`, which has a subscribe-window between the stream emitting a value and the race
  re-registering on the underlying refs. Observe-then-set (record each emit, set after
  observing the prior) does NOT close that window: a `set` landing in the window is picked up
  by the *next* subscription and skipped, and `assertEventually` then hangs forever. VALIDATED
  flaky: passed 9/10 runs, timed out (2m) on the 10th (`switchMap inside streamChanges`).
  Reverted to the reliable sleep-based original. `streamChanges` is documented to skip values
  under rapid changes, so the sleeps space the sets below the skip threshold - an honest
  duration with no observable "derived-signal re-subscribed" event. A real fix would need a
  production-side observability hook on the derived signal's subscription (a testability change
  to Signal), not a test edit. (The 2 non-composed observe-test settle sleeps, `:883` same-value
  and `:899` interruption, ARE cleanly convertible via in-order-delivery / `getResult` and
  validated green; reverted with the batch, can be re-applied on their own.)
- **kyo-data `UnsafeQueueBaseTest` concurrent harness**: the invariant assertions
  (`assert(!failure.get())`) are built into the `Seq[Thread]` returned by the test body, so
  they run at **thread-construction time, before the threads start** -> the memory-visibility /
  size-invariant concurrent tests never actually assert their invariant (only thread
  termination). Latent test-quality bug, independent of sleeps.

### Pending (apply the decision procedure)

- kyo-zio ZStreamsTest (2 interruption tests: `started`/`finalized` signals + park; `ZIOs.run`
  already interrupt-and-awaits the kyo finalizer, so `fiber.await` should synchronize it),
  kyo-caliban ResolversTest, kyo-browser (Promise-from-handler), kyo-http/net/pod/scheduler,
  kyo-reactive-streams (await subscriber `getResult` after cancellation).
- kyo-core SignalTest: the 2 non-composed observe-test settle sleeps could be re-applied
  (validated clean); the 12 composed ones are the blocker above.

### Lesson banked

Multiple runs are non-negotiable: the composed-signal conversion passed its first 4 runs and
only flaked on the ~10th. A 3-run gate would have shipped it. Touchy concurrency conversions
get many runs before they are trusted.
