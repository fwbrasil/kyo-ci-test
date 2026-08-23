# Fable CI Analysis: validation of CI_FAILURE_ANALYSIS.md

Scope: static analysis only. Every claim below is grounded in file:line of this worktree
(branch `kyo-compat-external-bindings`, merge-base `2e9bb02d40`). Where I disagree with
`CI_FAILURE_ANALYSIS.md` I say so explicitly.

## Verdict summary

| Item | Report's claim | My finding |
|---|---|---|
| F1 is branch-untouched code | claimed | **Confirmed by file-level diff** (see 2) |
| F1 mechanism: mutex held across a blocking bounded put | hypothesis | **Refuted, twice over** (see 1a) |
| F1 is a real hang, not CI slowness | open | **Confirmed**: per-iteration 120s timeout (see 1b) |
| F1 "correct fix": don't hold the mutex across the put | proposed | **Wrong direction: it breaks the invariant under test** (see 1d) |
| F2-F4: ui/browser timing races, not the kyo-http pool change | claimed, uncited | **Confirmed with the missing citation**: CDP is WebSocket and WS bypasses the pool (see 3) |
| Dependency read (jsonrpc deps; ui/browser deps) | claimed | **Confirmed from build.sbt** (see 2) |
| Comment hygiene of branch diffs | asked | **One violation** (em-dash, kyo-pod) plus pre-existing items to sweep opportunistically (see 4) |

## 1. F1: the JsonRpcHandlerProgressPolicyTest hang

### The actual fiber/channel topology

Test case at `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerProgressPolicyTest.scala:461-518`.
Per repetition (fresh transports and handlers each time, built inside the test body at :484-488):

- Caller A -> handler B: **1** request envelope, via A's `writerChannel` (capacity 64,
  `JsonRpcEndpointImpl.scala:249`) -> A's `writerLoop` fiber (`JsonRpcEndpointImpl.scala:785-813`)
  -> `aToB` channel (capacity 64, `JsonRpcTransport.scala:41-42,50`) -> B's Exchange reader.
- B's handler forks 10 sink fibers via `Async.zip` = `collectAll(seq, 10)` (`Async.scala:730`)
  = `Fiber.internal.foreachIndexed` (`Fiber.scala:725-778`). Each sink runs
  `monoMutex.run { compare; set; writerChannel.put(SendEnvelope) }`
  (`ProgressEngine.scala:135-140`, put at :118). **At most 10** progress envelopes enter
  B's `writerChannel` (capacity 64).
- B's `writerLoop` drains them into `bToA` (capacity 64). A's Exchange reader drains `bToA`;
  progress notifications are routed with a **non-blocking** `ch.unsafe.offer` into the
  per-call `progChan` (capacity 64, `CallEngine.scala:367`; offer at
  `JsonRpcEndpointImpl.scala:395-405`), so A's reader can never park on progress delivery.
- B's response is enqueued from the handler fiber's `onComplete` hook as
  `writerChannel.unsafe.offer(WriterMsg.SuppressIfCancelled(...))`
  (`JsonRpcEndpointImpl.scala:611-616`), drained by B's `writerLoop` (:795-810,
  suppress=false here since no cancel is ever sent), delivered to A, which completes the
  Exchange pending promise; `pending.result` is `fiber.get` of the forked call fiber
  (`CallEngine.scala:412,431`). Exchange registers the pending promise **before** send
  (`Exchange.scala:265-277`), so a fast reply cannot beat registration.

Total messages per repetition: **at most 12** across four capacity-64 channels, each with a
dedicated, always-runnable drain fiber.

### 1a. The report's deadlock mechanism is refuted

`CI_FAILURE_ANALYSIS.md` (F1, "Deadlock hypothesis") proposes: sink holds `monoMutex` across a
bounded `writerChannel.put`; if the writer drain stalls or is slow, the put blocks while holding
the mutex, the other sinks block on the mutex, `Async.zip` never joins. Two independent refutations:

1. **Quantitative.** `writerChannel.put` can only block when the channel holds 64 items. This
   test puts at most 10 progress items plus 1 response into a fresh 64-capacity channel per
   repetition. The put in `ProgressEngine.scala:118` never waits in this scenario, so nothing
   is ever "held across a blocking put". The numbers rule the mechanism out entirely.
2. **Structural.** Even at capacity this shape is backpressure, not deadlock. A hold-and-block
   deadlock needs a cycle: the drain's progress must depend on the mutex holder. It does not.
   B's `writerLoop` never acquires `monoMutex`; its only suspension is `transport.send` =
   `bToA.put` (`InMemoryTransport.scala:11`), and `bToA`'s consumer (A's reader) never parks
   (progress is `offer`, response handling is promise completion). "A mutex held across a
   bounded put" is only a deadlock smell when the consumer side can wait on the lock; here it
   cannot. The report's "classic hold-and-block deadlock shape" framing does not survive the
   topology.

### 1b. It IS a genuine hang, not aggregate slowness

The kyo-test runner stamps the suite default timeout (120s,
`kyo-test/api/shared/src/main/scala/kyo/test/internal/TestBase.scala:493-495`) into the leaf
builder when no explicit `.timeout` is set
(`kyo-test/runner/shared/src/main/scala/kyo/test/runner/TestRunner.scala:151-153`), wraps it
around each attempt (`TestRunner.scala:440-443`), and only then applies `repeat`
(`TestRunner.scala:445-447`: `Kyo.foreachDiscard(0 until repeat)(_ => timed)`). So the 120s
budget is **per repetition**, sequentially, not shared across the 100. A TimedOut leaf at ~2m
means one repetition sat for its full 120 seconds. One repetition is a millisecond-scale
scenario; that is a lost completion, not a slow CI box. "Genuinely flaky test" is not a
tenable reading: nothing in the test body waits on wall-clock timing; every await is
event-driven. The test is a correct stress leaf; the hang is a product/runtime bug.

### 1c. Where the stall most plausibly is

Every kyo-jsonrpc link on the chain checks out statically (registration-before-send,
pre-registration of `pendingInbound` before fork at `JsonRpcEndpointImpl.scala:535-565`,
suppress=false path, non-blocking progress routing). Two findings remain:

- **A real latent bug adjacent to, but not causing, this failure**: responses are enqueued with
  a **discarded non-blocking offer**: `discard(writerChannel.unsafe.offer(...))` at
  `JsonRpcEndpointImpl.scala:611-616` (also :628-630, :653-658, :674-679, :702-707). If
  `writerChannel` is ever full at reply time, the response is silently dropped and, with the
  default `requestTimeout = Duration.Infinity` (`JsonRpcHandler.scala:193`), the caller hangs
  forever. That is exactly F1's symptom class, but it cannot fire here (11 messages vs
  capacity 64, live drain). It CAN fire on a busy endpoint (>64 queued outbound messages).
  This deserves a fix and a regression test regardless of F1.
- **The remaining suspects are kyo-core wakeup machinery**, exercised at high contention by
  this leaf (10 contending fibers x 100 reps x it being the only Meter in play,
  `maxInFlight` defaults Absent so `maxInFlightGuard` is a no-op, `JsonRpcHandler.scala:192`):
  1. `Meter.Base` park/wake (`Meter.scala:404-616`). This exact component has a track record of
     precisely this failure class: five consecutive fixes on main at/before the merge-base
     (`1909d8fa71` "fix Meter deadlock when an interrupt races a permit", `b086306299` "fix a
     lost wakeup when a semaphore waiter is interrupted mid-acquisition", `278bced7cf`,
     `cb64e532df`, `6a74bdb3d1`). I walked the current park/wake/retire/giveBack invariants
     (whole-word CAS at :453/:465, queue-before-register at :461-464, retire re-wake at
     :590-592, waiter-field-only giveBack at :597-599) and could not construct a losing
     interleaving statically, but a five-fix history earns it prime-suspect status.
  2. `Channel` put->take wakeup for B's parked `writerLoop` (`writerChannel.take` at
     `JsonRpcEndpointImpl.scala:788`). A missed take-wakeup stalls the writer with no channel
     full and every offer succeeding: response never sent, caller hangs. Matches the symptom
     with zero full buffers.
  3. The `foreachIndexed` join (`Fiber.scala:736-774`): pending-counter completion. Looked
     sound; least likely.

  Static analysis cannot pick among these; a thread/fiber dump at hang time settles it in one
  observation. The report's planned next step (loop the test, jstack on hang) is exactly right;
  its diagnosed mechanism should be discarded before it drives a fix.

### 1d. The "correct fix" question: the report's proposed direction is wrong

The report asks for a fix that keeps wire monotonicity "WITHOUT holding a mutex across a
blocking put (e.g., decide-under-lock then put outside, or a single serialized emitter fiber)".

**Decide-under-lock-then-put-outside is incorrect.** Wire order equals put order (FIFO channel,
single drain). If sink X passes the gate with 5 and sink Y then passes with 10, both put
outside the lock, the scheduler may order Y's put before X's: the wire shows 10 then 5, which
is precisely the violation the test asserts against
(`JsonRpcHandlerProgressPolicyTest.scala:507-512`). Monotonicity requires the compare-and-set
and the enqueue to be atomic with respect to each other; that is what the current comment
block (`ProgressEngine.scala:101-105`) already explains was learned the hard way.

**A single serialized emitter fiber is the same serialization with more parts**: sinks must
hand values to the emitter in decision order, which again requires an atomic decide+handoff,
i.e. a lock or an equivalent ordered queue with the same blocking behavior.

**The correct position**: the ProgressEngine construction is correct as-is, because the drain
side never depends on the mutex (no cycle, 1a). There is no deadlock to fix there. The real
fixes for F1's family are (i) whatever the fiber dump shows is losing the wakeup (likely in
kyo-core), and (ii) the discarded response `offer` (1c), which is a genuine
silent-reply-drop-under-load bug in unchanged code, fixed by giving the reply enqueue the same
guaranteed-delivery treatment the progress path already has (a blocking put from an effectful
context, or an unbounded/priority lane for replies), with a reproduce-first regression test.

## 2. Branch linkage: confirmed none for F1, and none for F2-F4

Full list of main-source files the branch changes (`git diff --name-only 2e9bb02d40..HEAD`,
filtered to `src/main`): kyo-http (HttpClientBackend, Http1ClientConnection), kyo-net
(IoUringDriver), kyo-pod (5 files), kyo-reactive-streams (StreamSubscriber), kyo-sql/-mysql/
-postgres (8 files), kyo-test/api (AssertMacro). **Zero** main-source changes in kyo-core,
kyo-kernel, kyo-prelude, kyo-data, kyo-combinators, kyo-jsonrpc
(`git diff 2e9bb02d40..HEAD -- kyo-jsonrpc/shared/src/main` is empty), kyo-ui, kyo-browser.

- kyo-jsonrpc deps (build.sbt:2324-2330): kyo-prelude, kyo-core, kyo-schema-json, kyo-net.
  Of these only kyo-net changed, and that diff is a debug-dump string (adds the creation-site
  frame position to a diagnostics line, `IoUringDriver.scala:1387-1391` hunk): no behavior.
  The io_uring driver is not even on the in-memory-transport test's path.
- kyo-test/api IS on F1's execution path (the harness compiles/runs the test), and its
  AssertMacro change is the one non-obvious candidate the report did not examine. Verdict:
  benign. The diff gates power-assert instrumentation behind
  `KYO_TEST_POWER_ASSERT`/`kyo.test.powerAssert` and otherwise expands `assert(cond)` to a
  plain `if !cond` with the same record-then-throw contract. Compile-time expansion only;
  `$cond` is still evaluated exactly once; no effect or scheduling change. F1 hangs before any
  assertion runs (`pending.result` at test :490 never completes).
- `StreamSubscriber` adds a `subscribed` promise and a `private[interop] awaitSubscribed`;
  kyo-reactive-streams is not a dependency of kyo-jsonrpc, kyo-ui, or kyo-browser. No path.
- kyo-sql custody and kyo-pod changes: not dependencies of any failing module. No path.
- F2-F4 modules: kyo-ui depends on kyo-core, kyo-http, kyo-browser(test) (build.sbt:2886-2891);
  kyo-browser on kyo-http, kyo-jsonrpc, kyo-jsonrpc-http (build.sbt:2766-2770). kyo-http IS
  branch-changed, so the report's "unlikely cause" needed the transport-level argument it
  lacked; see 3.
- The three failing ui test files (`RealisticInteractionItTest.scala`, `AnchorTest.scala`,
  `TodoScenarioItTest.scala`) are untouched by the branch; the branch touched only the
  already-hardened siblings (ChatScenarioItTest, HtmlRendererTest, IframeTest) and
  kyo-browser test files.

The report's dependency read is correct. Its "pre-existing" label for F1 is, this time,
supported by the strongest static evidence available: every source file on the failing path is
byte-identical to the merge-base. (Per repo policy the definitive bar remains a reproduction on
the base commit; the report already owns that as an open step. Note the hang is equally
reachable from main, which runs this leaf on linux-x64: fixing it protects main.)

## 3. F2-F4: verdict validated, with the citation the report was missing

The question was whether CDP-over-http in kyo-browser touches the branch's kyo-http
streaming-pool change. It does not, for three independent reasons:

1. **CDP is WebSocket, not HTTP request/response.** `CdpBackend.initUnscoped` builds its
   transport with `JsonRpcHttpTransport.webSocket(url, ...)`
   (`kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:192-196`), which adapts
   an `HttpWebSocket` frame stream (`kyo-jsonrpc-http/src/main/scala/kyo/JsonRpcHttpTransport.scala:22-57`).
2. **WebSocket connections bypass the pool entirely.** `HttpClientBackend.connectWebSocket`:
   "Bypasses the HTTP connection pool, WS connections aren't poolable"
   (`kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala:685`). The
   branch's diff touches `sendStreaming`/`buildBodyStream`/`readContentLengthStream`/
   `releasingConn`/`sendViaBackend`, i.e. the pooled request path; the WebSocket section has no
   hunks.
3. **The behavioral change is scoped to pooled streaming-RESPONSE routes.** For buffered
   routes and HEAD, `sendViaBackend` returns `bodyOutcome = Absent` and `releasingConn`'s
   Absent branch is the pre-branch `pool.release` (diff at HttpClientBackend.scala:1053-1056);
   the caller-scoped (non-pooled) streaming path passes `Absent` explicitly (diff at :238-241).
   Only a pooled streaming-response route defers reuse until body drain. The ui IT tests drive
   Chrome, and Chrome fetches the page with its own network stack; the only kyo-http-client
   traffic in the loop is CDP discovery (small buffered JSON) and the WS session.

The failure signatures (fill -> value-signal assert timeout; focus -> signal -> text timeout;
element detached mid-click by a reactive re-render) are application-level DOM/signal timing in
the page under test, the same family as the ChatScenario/Iframe/HtmlRenderer flakes the branch
already hardened, surfaced now only because the branch re-enabled windows in CI (the report's
methodology correction on the vacuity of "not seen on main" for windows is sound: main dropped
windows in `1e1c0f4d65`). Verdict: **report confirmed**. Fix by hardening the interactions
(settle/retry around fill->assert and click-on-reactive-node) as done for ChatScenario.

## 4. Comment hygiene in the branch's changed sources

Hard violation (branch-added line):

- `kyo-pod/shared/src/main/scala/kyo/internal/ShellBackend.scala:148`:
  `/** Builds behavior-related flags: ... Pure — no effects. */` uses an em-dash on a
  branch-added line (repo rule: no em/en-dashes anywhere, including comments). It copies the
  style of three pre-existing siblings at :107, :119, :134; a fix should sweep all four
  ("Pure; no effects." or "Pure, no effects.").

Assessed and acceptable (branch-added):

- The large new comment blocks in `HttpClientBackend.scala` (the `bodyOutcome` contract at
  `buildBodyStream`, the CAS-transfer note in `releasingConn`, the abandonment note on the
  chunked stream wrapper) are load-bearing ownership/invariant documentation stated in the
  present tense with consequences ("a branch that leaves it pending would turn every response
  ... into a connection checked out forever"). No process narration, no incident tags. Same for
  the `Http1ClientConnection.scala:131-136` scaladoc distinguishing buffered vs streaming
  DecoderState ownership, and the `StreamSubscriber` `subscribed`/`awaitSubscribed` comments.
- kyo-pod's references to "flaky database fixture", "constrained runner", "loaded CI runner"
  (`ContainerPredef.scala:62,81,411`, `Container.scala:821`, `HttpContainerBackend.scala:497`)
  read as domain documentation, not process leakage: kyo-pod's purpose is container fixtures
  for tests, and these comments document the observed daemon/runtime failure modes the code
  classifies and handles. Keep.
- `AssertMacro.scala` new header comment: technical (cost of instrumentation, the Zinc
  caveat). Fine.

Pre-existing (main, not branch-introduced; flag for cleanup only if these files are touched):

- `ProgressEngine.scala:101-105`: "A lock-free gate decided 'proceed' separately from the
  writer-channel put, so two concurrent values could both pass the gate and then race on the
  put ..." is past-tense narration of a rejected/previous implementation. The content is
  genuinely load-bearing (it is the exact reason 1d's "put outside the lock" is wrong), so it
  should be kept but rephrased into timeless form ("A lock-free gate that decided ... WOULD let
  a smaller value follow a larger one"). Confirmed pre-existing: the branch diff for
  kyo-jsonrpc main is empty.
- `TestBase.scala:490` "(raised from 60s after legitimate tests intermittently exceeded 60s
  under CI load ...)": change-history narration in a scaladoc; pre-existing on main.

## 5. Explicit disagreements with CI_FAILURE_ANALYSIS.md

1. **F1 mechanism.** The mutex-across-bounded-put deadlock hypothesis is refuted by capacity
   arithmetic and by the absence of any wait cycle (1a). Keep the verdict (real bug in
   branch-untouched code), discard the mechanism.
2. **F1 fix direction.** "Do not hold the mono mutex across a blocking channel put" (report,
   next-steps 2) would break the monotonicity invariant the test exists to enforce (1d). The
   fix belongs at the actual stall point (identify via fiber dump) plus the discarded response
   `offer` (1c), not in ProgressEngine's locking.
3. **"Deadlock-vs-flake" framing.** The per-repetition 120s timeout (1b) already settles
   "flaky-slow vs hung": one iteration hung. The open question is only WHERE, not WHETHER.
4. Everything else in the report (branch footprint, dependency read, windows-vacuity
   methodology correction, F2-F4 family diagnosis, doctest red herring, PidsLimit status) is
   consistent with the code as far as static analysis can check it.
