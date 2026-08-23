# Phase 2 re-sweep: cases the first pass wrongly LEFT

## DONE (committed)
- ProcessTest x4 (f08cfd8be9); HubTest:354 + ForeachTest x3 + GuardDrain:64 (b62b75a6ed);
  MutationSettlementTest x3 (bffc8514a2); ResolverTest:1658 (8946b9018a); WorkerTest:983
  (8519cea407); BrowserConfigTest:71/93 + CdpBackendLifecycleJvmTest:244 + BrowserHistoryTest:418
  (6d8994d8a0); RaceZipTest:71 + MeterTest:94 peak-concurrency (f6f6356416); BlockingMonitorTest
  x4 :516/:581/:647/:840 (38ac21620e); KyoFinagleSchedulerServiceTest:72 (ddb190e965).
- BrowserLauncherTest:78 + BrowserIsolateTest:1172 - edited, validating.
- Browser Tier-2 (Capture x3, VerifyRead x3, Cookie x2, CdpIntegration:234, CdpLifecycle:161,
  SettlementTest x9) - delegated to subagent, reviewing next.

## LEFT (recorded)
- ResolverTest :976/:1398/:1882: cancellation has no synchronous client event; :976/:1398
  vacuous-constant assertions are a separate test-quality issue (need negative-obs window or hook).
- WorkerTest Thread.sleep(200): load-bearing ordering settle (dropping it hung the test); no event.
- BlockingMonitorTest :722/:802 (multi-task) + :691 (thread-recycling): trailing eventually already
  robust; barriers there are fiddly/risky. :617 negative-obs window (leave).
- CompilerPoolTest:649: robust lower bound + typed CompilerUnresponsiveException; withTimeControl
  conversion is deep/risky. Leave.
- BrowserSettlementTest :969/:1053/:1183: need genuine restructures (server counter / eval-img /
  borderline) - SKIP for now.
- BrowserSettlementTest :713 ("Sibling Async.zip fibers see their own withConfig scope"): the
  detectable-hang conversion was WRONG and hung (validated: STUCK). This isolation test compares two
  fibers' timing budgets (the tight fiber must use its own 100ms config, not leak the sibling's 2s);
  the sibling's config cannot be made infinite without breaking the sibling's own positive #slow
  match. Reverted to the original bounded config + `<= 2400` generous 3x envelope (a borderline
  measurement subject). The multiple-runs rule caught this before commit.
- kyo-pod ContainerItTest:149 + ContainerOrchestrationItTest:477: UNVALIDATABLE in this
  environment, reverted. Both tests need the HTTP unix-socket container backend; this macOS host
  runs podman as a REMOTE connection (empty `podman machine list`, no local api.sock, `/run/podman/
  podman.sock` is the in-VM path), so `findSocket` finds nothing and the tests early-return / never
  register (the CLI-backend ContainerItTest passes: 222/0). Compile-green is not runtime-green for a
  container test, so not committed. Intended conversions to APPLY on a socket-enabled (Linux) env:
  * :477 (ContainerOrchestrationItTest "init completes ... healthcheck fails ... gone mid-retry"):
    set the healthcheck `retrySchedule = Schedule.fixed(100.millis).take(1000000)` (effectively
    infinite) so a non-short-circuiting NotFound retry hangs into the leaf timeout; capture the
    init result and `assert(result.isFailure, ...)`; drop the `elapsedMs < 2000` ceiling + t0/t1.
  * :149 (ContainerItTest "Meter limits concurrent HTTP operations"): fork the 6 execs, then
    `assertEventually(Abort.run[Closed](meter.availablePermits).map(_.exists(_ == 0)))` (the
    semaphore drops to 0 available permits iff exec routes through the meter), then get all + assert
    success; drop the `elapsed >= 1000` floor. NOTE: the original floor is a ROBUST lower bound (not
    a genuine flake), so leaving :149 as-is is also acceptable.
  * ContainerItTest:2720 (exec-on-stopped "without retries"): LEAVE - exec's retry schedule is
    internal to the backend and not test-configurable, so the detectable-hang technique needs a
    production hook. ContainerOrchestrationItTest:499 (stopTimeout): LEAVE - real-container
    stopTimeout wall-clock measurement subject.



Aggressive re-audit (bar: convertible unless a true measurement subject). ~55 newly-found
convertible sites. Techniques: 1 outcome, 2 detectable-hang, 3 completion-event, 4 withTimeControl,
5 observe-effect. Execute Tier 1 first, validate per file (>=3 runs for concurrency), commit per module.

## TIER 1 — clean, high-confidence

### kyo-core ProcessTest (4) — outcome already asserted, drop the ceiling
- :350 `assert(elapsed < 5.seconds)` — line 349 asserts `result == Absent` (waitFor(200ms) beat the 60s sleep). Drop elapsed.
- :419 same — line 418 `result == Absent`. Drop.
- :366 `assert(elapsed < 5.seconds)` — `out.nonEmpty` + stream completed. Drop elapsed.
- :436 same — `out.nonEmpty`. Drop.

### kyo-browser internal/MutationSettlementTest (3) — effect already asserted
- :325-326 `assert(elapsedMs >= 200)` — line 328 `actual == "updated"` is the effect. Drop floor.
- :380-381 `assert(elapsedMs >= 200)` — line 383 `actual == "queued-write"`. Drop floor.
- :131 `assert(elapsed < 600)` — line 126 `outText == "before"`. Drop ceiling.

### kyo-caliban ResolversTest (4) — Phase-1 re-check; genuine client-observable events
- :1658 `Async.sleep(100.millis)` — afterInit increments `counter`; :1661 asserts `counter==1`. -> `assertEventually(counter.get()==1)` (mcp pattern).
- :976 `Async.sleep(200.millis)` — VACUOUS (asserts constant "ok"). After client `complete`, re-subscribe id "forever"; assert a `next` arrives (proves complete cancelled). Pattern at :1866.
- :1398 `Async.sleep(100.millis)` — VACUOUS (constant string). legacy graphql-ws `stop`; same re-subscribe event.
- :1882 `Async.sleep(50.millis)` — gap after an already-received `complete` (:1880). Likely deletable (the complete IS the barrier); confirm server ordering.

### kyo-compat ForeachTest (3) — peak-concurrency counter (pattern at :176-190)
- :22 `out.size==5 && elapsed<500` — instrument active/peak AtomicInteger, assert `peak==5`.
- :203 `elapsed<500` — same, `peak==5`.
- :191 `elapsed>=150` — already asserts `peak<=2`; strengthen to `peak==2`, drop floor.

### kyo-scheduler WorkerTest:983 — completion event (pattern at :973)
- :983 `Thread.sleep(500); assert(task2.executions==1)` -> `eventually(assert(task2.executions==1))`.

### kyo-ffi GuardDrainTimeoutConfigTest:64
- :64 `assert(elapsedMs<500)` — line 58 already `closedLatch.await(500,MS)==true` + `closeResult==TimedOut`. Drop :64.

### kyo-core HubTest:354
- :354 `assert(elapsed>=8.millis && result==(1 to 10))` — `result==(1 to 10)` carries slow-consumer delivery. Drop floor.

### kyo-browser internal/CdpBackendLifecycleJvmTest:244
- :244 `assert(elapsedMs<2000)` — :247 `slowResult==Success(Failure(BrowserConnectionLostException))` carries it. Drop ceiling.

### kyo-browser BrowserHistoryTest:418 — detectable hang
- :418 `assert(elapsed<1500.millis)` — /slow handler sleeps 2s; make it 1.hour; assert reload completes.

## TIER 2 — outcome/detectable-hang/effect (drop ceiling, rely on typed outcome + leaf timeout)

kyo-browser BrowserSettlementTest: :581 (outer->1.hour), :713 (sibling->1.hour), :629 helper (outer->1.hour),
:553 (drop ceiling), :344 (drop ceiling), :413 (drop <=12000), :1388 (waitForStable(1.hour)), :467 (drop floor, out=="c"),
:1135 (drop <=3000), :969 (server /ping counter==3), :1053 (eval img loaded), :1183 (borderline relative).
kyo-browser BrowserConfigTest :71/:93 (drop ceiling, typed Failure).
kyo-browser BrowserCaptureTest :90 (drop, Success(img)), :124 (timeout->1.hour), :650 (drop, typed Failure).
kyo-browser BrowserVerifyReadTest :105/:106 (drop absolutes, keep relative A<B), :460 (waitForStable(1.hour)).
kyo-browser BrowserCookieTest :603 (loadSchedule->infinite), :550 (drop floor, Present carries).
kyo-browser BrowserIsolateTest:1172 (drop <1500). BrowserLauncherTest:78 (drop, typed Failure).
kyo-browser BrowserCoreTest:663 (grace-window->1.hour if configurable, else leave).
kyo-browser BrowserSnapshotConfigLocalTest:57 (relative). CdpBackendIntegrationTest :234 (drop/timeout), :248 (borderline).
kyo-browser CdpBackendLifecycleTest:161 (drop floor).
kyo-compat RaceZipTest:71 (peak==2), MeterTest:94 (drop elapsed / peak==2).
kyo-scheduler BlockingMonitorTest :516 (eventually), :581 (blocked-detection barrier at :487).
kyo-scheduler-finagle KyoFinagleSchedulerServiceTest:72 (eventually{raise;assert} idempotent).
kyo-compiler CompilerPoolTest:649 (withTimeControl on stuckTimeout).
kyo-pod ContainerItTest:2720 (retry->infinite), ContainerOrchestrationItTest:477 (retry->infinite, also assert unasserted Failure), ContainerItTest:149 (meter.availablePermits<2).
kyo-config DynamicFlagConcurrencyTest:67 (rely on completion; low value).

## TIER 3 — DoS/complexity ceilings — ASSESSED: LEAVE ALL
kyo-parse ParseTest:1502, kyo-markdown MarkdownTest:454, kyo-website DocsMarkdownTest:667,
kyo-schema-json JsonTest :250,:1762,:1772,:1870,:1879,:1890,:1901,:1908,:1923,:1932,:1962,:1971.
These are legitimate anti-blowup MEASUREMENT SUBJECTS, not flaky thresholds: a 5-30s bound on a
sub-10ms operation catches an algorithmic-complexity regression orders of magnitude slower
(ParseTest's O(n^2) "takes minutes" vs <1s fixed). The 500x margin means normal variance never
trips them; the bound IS the anti-DoS assertion, and dropping it would remove the property under
test. (A few JsonTest ones discard `result`, mildly vacuous on the result axis, but the elapsed
bound is the intended DoS check.) Not a reliability target.

## TRUE leaves
ClockTest Sleep/:265,:280,:290,:300 + TimeShift:319-332 (circular / measurement); direct CoreTest:64 (real sleep, withTimeControl deadlocks direct-style);
SleepTest, AdmissionTest, InternalClockTest (measurement subjects); net PosixTransportAcceptEmfileTest:154 (spin-rate sampling), ConnectDeadlineStrandTest:54 (already outcome);
compat TimeTest:68/75/91 (real-clock contract); STMStressTest:168 (done carries it, attempts is soft guard); caliban ResolversTest:461 (slow-resolver subject), :1191 (no client event; vacuous-constant = separate test-quality issue);
pod ContainerItTest:2857 (event-gated line count), ContainerOrchestrationItTest:499 (real-container stopTimeout measurement); BlockingMonitorTest:507 (self-adjusting eventually); kyo-data queue soaks (don't-touch raw-thread).

## Test-quality issues surfaced (not sleep/threshold edits)
- ResolversTest:976/:1398/:1191 assert constant strings (VACUOUS, still LIVE - NOT fixed). Only the
  afterInit test :1658 was converted; these three were LEFT because the plan's "re-subscribe the id"
  barrier depends on caliban WS cancellation ordering with no synchronous client event. They provide
  false green (pass even if cancellation is broken) and need a real de-vacuum: a server-side
  active-subscription counter the client can poll, or a bounded negative-observation window asserting
  no further `next` arrives after `complete`. (Corrected: an earlier draft of this ledger wrongly said
  :976/:1398 were "fixed as part of the conversion"; the code shows they were left.)
- ContainerOrchestrationItTest:474 discards the init result it should assert.
- UnsafeQueueBaseTest concurrent harness: invariant asserts are built into the returned Seq[Thread] at
  thread-construction time, BEFORE the threads run, so the memory-visibility/size-invariant tests never
  assert their invariant (false green). Surfaced, needs a redesign (assert after join).

## Final scan (confirmation the campaign is complete)

Scanned ALL test files for the clearest still-convertible pattern - a sleep immediately followed by
a POSITIVE assert. Zero remain. The four matches are all legitimate leaves:
- NioIoDriverTest:768 `assert(!p.done())`, UnsafeServerDispatchTest:1101 `assert(!received.get())`,
  BlockingMonitorTest:624 `assert(!interrupted.get())` - NEGATIVE-observation windows (sleep then
  confirm nothing happened; no event exists for "nothing happened", so the bounded wait is honest).
- RunnerSelfTest:31 `Async.sleep(1.millis).andThen(assert(2+2==4))` - test-runner self-test, the
  sleep is the subject.
Everything with a genuine event/effect/outcome has been converted and validated; every remaining
sleep/threshold is a recorded leave (measurement subject, negative-observation window, no-event
settle, robust lower bound, or socket-dependent pod test).
