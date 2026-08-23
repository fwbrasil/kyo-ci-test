# Judgment: forked backpressuring put for jsonrpc responses

## Verdict: PASS

The fix is correct, complete, and precedent-consistent. The reproduce-first test is a valid, effectively non-flaky regression guard. No rework required. Two pre-existing observations are routed at the end; neither is introduced or worsened materially by this change.

## 1. Correctness of `enqueueResponse` (JsonRpcEndpointImpl.scala:56-57)

Correct.

- The fork actually runs the put: `Fiber.Unsafe.init` is fire-and-forget with immediate scheduling and drives an effectful body through IOTask (kyo-core/shared/src/main/scala/kyo/Fiber.scala:399-419). The body needs no Local context, so the no-Local-inheritance caveat (Fiber.scala:395-396) is moot.
- Closed handling and teardown: finalizer step 1 closes writerChannel (LifecycleEngine.scala:100), and Channel close flushes parked puts by failing their promises with Closed (Channel.scala:633-636; bounded impl close at :768-770 routes through the same flush). So a forked fiber parked on a full channel is terminated at close, `Abort.run[Closed]` swallows the failure, and the fiber ends. The fiber's lifetime is bounded by (put succeeds | channel closes); it cannot outlive the endpoint indefinitely.
- Interruption: nothing holds the fiber reference, so nothing interrupts it; termination is provided by the close path above, so interruption is not needed for correctness.
- Dead-peer accumulation: one parked put fiber per completed inbound request. This is not a new unboundedness class: every inbound request already spawns an untracked handler fiber with no inbound admission control (JsonRpcEndpointImpl.scala:576); the parked put fiber merely succeeds it. Inbound admission control is a separate, pre-existing concern.
- Race with close between CAS and put: the put fails Closed and is swallowed. Consistent with close semantics generally: even messages already sitting in the channel are dropped at close (the finalizer discards `close()`'s returned backlog at LifecycleEngine.scala:100).

## 2. Ordering

No invariant broken.

- Same-request progress-before-response holds. The progress sink does a blocking `put` from the handler fiber itself (ProgressEngine.scala:118), so every progress envelope is in the channel (or ahead in its FIFO pending-put queue) before the handler completes; `onComplete` fires after completion and the forked response put lands behind. Channel FIFO preserves the order.
- Cross-request response order was already unconstrained (channel backlog, concurrent handlers); JSON-RPC imposes none.
- SuppressIfCancelled semantics are drain-time, not enqueue-time: the writer loop re-reads the suppress flag when it dequeues the message (JsonRpcEndpointImpl.scala:800-815). Delaying the enqueue only widens the window in which a late cancel can still suppress the reply, which is the policy's intent, not a violation.

## 3. The CAS split

Safe.

- At most one enqueue per id: Running->Replying (JsonRpcEndpointImpl.scala:616) and Running->Cancelled (CancellationEngine.scala:113) are mutually exclusive CASes on the same entry, and the mustReply enqueue fires only from the Cancelled branch (JsonRpcEndpointImpl.scala:626-640). The two response paths cannot both fire.
- Cancel between the Replying CAS and the writer drain sets suppress (CancellationEngine.scala:124-127 and :131-134) and the writer honors it at send time. Cancel after the writer removed the entry no-ops with a warn (CancellationEngine.scala:105-110), same as pre-fix.
- Keeping the CAS synchronous in the callback is load-bearing: it guarantees any cancel processed after handler completion sees Replying and takes the suppress path rather than interrupting an already-completed fiber. Forking only the enqueue is exactly the right split.

## 4. Test quality (JsonRpcHandlerBackpressureTest.scala)

Valid guard; two minor notes, neither blocking.

- Pass on fixed code is deterministic: latch/gate synchronization only, no sleeps; with backpressuring puts, all 100 responses must drain once the gate opens, and the 15s caller timeout is ample for 100 in-memory echoes.
- Failure detection on the buggy code is near-certain but not strictly guaranteed: `ran.release` (line 32) precedes the handler's onComplete offer, so in principle a few of the 35 excess offers could slip in after `gate.release` interleaved with drains. Empirically it fails 65/100 delivered; the residual pass probability on buggy code is negligible. If you want it airtight, gate on enqueue attempts rather than handler runs; not required for PASS.
- It asserts the right thing: exact count and exact payload set (lines 52-57), with timeouts excluded by the Success collect, so a dropped response cannot hide.
- Coverage hole: only the SuppressIfCancelled site is exercised. All 5 sites route through the shared helper, so the primary regression (reintroducing offer in the helper) is caught; a per-site revert to `offer` at, say, the gate-reject site (JsonRpcEndpointImpl.scala:709) would escape. The helper centralization is itself the mitigation; acceptable.
- Naming follows the aspect-file convention: `JsonRpcHandlerBackpressureTest` prefixes `JsonRpcHandler.scala`.

## 5. Design

Concur with all three rejections.

- Unbounded writerChannel: removes backpressure for every producer (notify and progress floods included); a slow peer grows memory without bound. The bounded channel is the module's protection; keep it.
- Separate reply lane: a priority merge over two channels breaks the same-request progress-before-response ordering that the single FIFO channel preserves for free, and adds machinery for no additional guarantee over the parked put.
- Fail-fast on offer false: a JSON-RPC request requires a response; dropping it or killing the connection on a transient burst converts recoverable backpressure into a protocol violation. That is the bug, not a fix.
- The chosen fix is the precedent-consistent one: responses now carry the same delivery guarantee as every other producer (CallEngine.scala:320 and :334, ProgressEngine.scala:118), reached via the same `Fiber.Unsafe.init` bridge `notify` already uses (JsonRpcEndpointImpl.scala:118-123).

## Pre-existing observations (routed, not findings against this fix)

1. pendingInbound entry leak on no-reply cancels: the Cancelled entry is removed only in the mustReply branch (JsonRpcEndpointImpl.scala:639); with `expectReplyForCancelledRequest = false` the entry persists until close (only other removals: writer loop :812, finalizer LifecycleEngine.scala:151). A long-lived endpoint accumulates one map entry per cancelled inbound request. Worth a follow-up fix with its own reproducing test.
2. Writer's unconditional `pendingInbound.remove(id)` (JsonRpcEndpointImpl.scala:812) could remove a newer Running entry if a protocol-violating peer reuses an id while the prior response is still queued. The enqueue-to-drain window existed before this change; the fork widens it marginally. A `remove(id, entry)` two-arg CAS remove would close it.
