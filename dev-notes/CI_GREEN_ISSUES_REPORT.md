# CI Green Campaign: Issues and Root Causes

Branch: `kyo-compat-external-bindings`. Goal: two consecutive fully-green full-matrix CI runs
(linux-x64, linux-arm64, windows-x64 x JVM/JS/Native/Wasm) on fork `fwbrasil/kyo-ci-test`.

Full-matrix run #1 (HEAD `c4401de4c3`) surfaced two distinct failure classes. Both are
**pre-existing** (present on `origin/main`, not introduced by this branch's container/CI work).

---

## Prior committed work on this HEAD (context, not an issue)

- `6fd2a3269e` cap the run-phase sbt driver heap (`-J-Xmx6G`) for out-of-JVM targets (JS/Wasm/Native).
  The real cause of the earlier container-suite exhaustion was memory (12GB driver heap), proven by the
  RESMON telemetry now integrated into `scripts/ci-monitor.sh`.
- `a752ad8d89` revert `exit_command_delay=0` to podman's default (that override was a misdiagnosis).
- `c4401de4c3` remove the now-dormant exec reap-race recovery machinery from kyo-pod.

Full-matrix run #1 confirmed **linux-x64 JVM/JS/Wasm all green** (the io_uring/http socket leak, task #20,
did NOT reproduce; the C1-C5 custody rework holds). Native jobs still running at time of writing.

---

## Issue 1 — Aeron `TopicInvariantsTest` concurrent-stream timeout (FIXED)

**Symptom.** `TopicInvariantsTest › "two concurrent Topic.stream consumers get distinct subscriptions
(per-emit safety)"` timed out (2m) on **linux-arm64 JVM** and **windows-x64 JVM**. Passes on linux-x64
and locally (sub-second on all platforms).

**Root cause.** The test publishes sentinel `-1` probes until both consumers signal first receipt (latches),
then publishes the real batch. A `maxProbes = 2000` (~10s) ceiling gave up probing before a slow Aeron IPC
image connected on the emulated arm64 / windows runners, so the real batch published into a half-connected
state; the unconnected consumer then hangs forever (Aeron never redelivers) until the 2m test timeout. That
2m timeout is the *same* failure the ceiling was meant to avoid, so the ceiling only added false failures.

**Fix (committed `b4004ef2f2`).** Remove the ceiling; probe until both consumers receive, with the test-level
timeout as the only backstop for a genuinely unconnectable image. Validated locally: 13/0.

**Status.** Fixed; pending CI confirmation on arm64/windows.

---

## Issue 2 — kyo-http connection-pool response-stream desync on Scala.js (ROOT-CAUSED, FIX PENDING)

**Symptom.** `ContainerItTest` (and the other kyo-pod IT suites) fail `s == Container.State.Running` for
the **`[podman] › http`** backend on **Scala.js (JS + Wasm)**. `[podman] › shell`, `[docker] › http`,
`[docker] › shell` all pass; the JVM passes. Failure gradient: reliable on linux-arm64 (JS+Wasm), flaky on
linux-x64 Wasm, never on fast linux-x64 JS. Runner is healthy throughout (availMB 5.5GB+, conmon=0), so it
is not resource exhaustion, and it is delay-independent.

**Established facts.**

- podman returns the container inspect as `HTTP/1.1 200 OK`, `Content-Type: application/json`,
  `Transfer-Encoding: chunked`, a 4213-byte JSON body with `State.Status = "running"` (verified by curl on
  the exact CI runner, stable across repeats). So podman's response is correct.
- `kyo.HttpClient.getText` returns a body that `Json.decode[InspectResponse]` decodes to an **all-defaults**
  object (`State.Status == ""` -> `parseState` -> `Stopped`). An empty string cannot decode (that path
  aborts), so the returned string is a **complete, valid JSON object that is not the inspect body** (a
  different response's body, e.g. create's `{"Id":..}` or wait's `{"StatusCode":0}`, all of which decode to
  an all-defaults `InspectResponse` because unknown fields are ignored).
- kyo-http's client and kyo-net's JS transport are **unchanged from `origin/main`**; the chunked decoder
  (`ChunkedBodyDecoder`) is correct and independently fragmentation-tested (`ChunkedBodyDecoderTest`
  covers size-line / data / header-data split-across-reads).

**Root cause (Fable analysis, high confidence).** A response-stream **desync on a reused pooled connection**,
not byte loss within one response:

1. For **streaming** routes, `HttpClientBackend.releasingConn` (`:1008-1019`) returns the connection to the
   idle pool as soon as `use` (= `responseFiber.safe.use(f)`) produces a value. For streaming routes that
   value is produced at **headers time** (`sendStreaming` `:167-183` completes `resultPromise` with a
   response wrapping a *lazy* body stream from `buildBodyStream`).
2. `buildBodyStream` (`:581-603`, chunked branch) launches a **background `IOTask`** running
   `ChunkedBodyDecoder.readStreaming` against the connection's `inbound` channel and its connection-scoped
   `chunkedDecoderState`, delivering to `decodedCh`, then `closeAwaitEmpty`. When the body did not fully
   arrive in `lastBodySpan` (i.e. the multi-read case), that IOTask suspends on `inbound.safe.take`.
3. So in the multi-read case the connection sits in the **idle pool with a live taker registered on its
   `inbound` channel and body bytes still arriving**. The next request checked out from that connection
   registers its own taker BEHIND the stale one in the channel's FIFO `takes` queue; the first span(s) of the
   new response are delivered to the stale streaming decoder and consumed/discarded. The new request then
   sees a mid-stream suffix -> wrong/empty body. Once the stream shifts by exactly one whole response, every
   subsequent exchange completes "successfully" one response behind -> the all-defaults decode we observe.
4. **Single-read passes** because the whole body arrives with the headers as `lastBodySpan`; the streaming
   decoder finishes from `initialBytes` and never arms a taker on `inbound`, so the pooled connection is
   clean. **JVM passes** because the pump/decoder run ahead of the next checkout and the window closes; on
   single-threaded Node the interleaving is deterministic (matches "consistent across repeats").
5. In kyo-pod the desync **creator** is a streaming call whose body is always multi-read: `nativePull`
   (`POST /images/create` NDJSON progress, `HttpContainerBackend.scala:1443`), plus `logStreamInternal`
   (`:988`) and `exportFs` (`:1190`). The **victim** is the next buffered `inspect`/`state` on the reused
   connection. The image-pull NDJSON stream in the DB-fixture setup is the most probable first poisoner.

Secondary defect at the same seam: a subsequent buffered request calls `conn.http1.chunkedDecoderState`
(`:434`), which `reset()`s the **connection-scoped** `DecoderState` (`Http1ClientConnection.scala:131-135`)
out from under a still-running background streaming decode using the same mutable object (`:596`).

Related latent issues Fable flagged (same class): the **streaming** Content-Length path
(`readContentLengthStream` `:629-641`) shares the early-release defect; `readLoopUnsafe` (`:483-507`) can fold
an over-read span's excess into the body; `offerOrLog`'s `putFiber` fallback (`Http1ClientConnection.scala
:140-148`) is not FIFO-safe against a parked headers put.

**Invariant to enforce.** A connection enters the idle pool only with `inbound` empty and zero registered
takers (i.e. its response body fully consumed).

---

## Fix design questions for the connection-pool desync (Issue 2)

Confirmed code shape (all in `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala`
unless noted):

- `releasingConn(key, conn)(use)` (`:1008-1019`) — one `AtomicBoolean released` gates release-vs-discard;
  `pool.release` on `use` success, `pool.discard` on failure/panic/interrupt.
- `poolWithImpl` (`:1149-1174`) wires `releasingConn(key, conn)(responseFiber.safe.use(f))` for both the
  pooled-hit and fresh-connect paths.
- `sendStreaming` (`:140-200`) builds the lazy body stream; `buildBodyStream` (`:577-626`) owns the background
  chunked/content-length body IOTask whose terminal point (`readStreaming ... .andThen(closeAwaitEmpty)`,
  `:598-599`) is the true "body fully drained" signal.
- Buffered routes are already safe: `readBufferedBody` completes `resultPromise` only after the full body, so
  `releasingConn` releases after consumption.

The correct fix (Option 1) is to **defer a streaming route's pool release/discard to body-stream completion**:
release on `Done`, discard on `Closed`/`HttpMalformedBodyException`/`HttpPayloadTooLargeException` or on stream
abandonment (a finalizer on the handed-out stream). Option 2 ("always discard streaming connections") is
**wrong**: streams are handed out of `sendWithConfig` and consumed by the caller AFTER `releasingConn`
returns, so discarding at `f`-completion would close the connection mid-body and truncate the handed-out
stream.

Open questions the fix must answer:

1. **Obligation transfer / mutual exclusion.** The `released` CAS lives in `releasingConn`; the body IOTask
   lives in `buildBodyStream`, several layers down. How should the single release-vs-discard decision be
   shared so exactly one of {releasingConn-on-failure, body-IOTask-on-Done, stream-finalizer-on-abandon}
   fires? (Thread the CAS + key + pool down to `buildBodyStream`? Attach a completion obligation to
   `HttpConnection`? A per-request release token?)
2. **Abandonment.** A handed-out stream that the caller never fully drains (or drains partially then drops,
   or is interrupted by `Async.timeoutWithError`) must **discard** the connection, never pool it. Where does
   the finalizer attach so it fires on drop/interrupt as well as normal completion?
3. **Per-decode `DecoderState`.** Give each streaming decode its own `DecoderState` (or gate
   `chunkedDecoderState` on no decode in flight) so a later request cannot reset it under a live decode.
4. **Scope.** Should the fix also cover the streaming Content-Length path (`readContentLengthStream`), the
   `readLoopUnsafe` over-read fold, and the `offerOrLog` FIFO fallback in the same change, or are those
   separate follow-ups? Which are load-bearing for green CI vs. hygiene?
5. **Regression tests (must run and fail on the JVM with forced fragmentation).**
   - Buffered adversarial byte-split chunked read: assert exact 4213-byte body AND, after completion,
     `inbound.empty()` and zero pending takers.
   - The desync itself: on one connection, serve a chunked *streaming* response across 3+ offers, hand the
     stream out (unconsumed, and slowly-consumed variants), then issue a second *buffered* request on the
     same connection; assert the second request receives its own body, and that a connection cannot be
     acquired from the pool while its previous response body is unconsumed.

**Risk.** This is a connection-lifecycle change in a foundational module; a wrong fix can leak or double-close
connections, or reintroduce the release-vs-discard race `releasingConn` documents. It needs a design that
preserves "exactly one of release/discard fires" across the new body-completion path.

---

## Proposed fix design (Fable analysis)

All edits in `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala` unless noted.
No public API change; every touched member is `private`/`private[kyo]`.

**Master invariant:** a connection enters the idle pool only when its response body is fully consumed
(`inbound` empty, zero registered takers); for every request exactly one of {release, discard} fires, once.

### The obligation (no new class)
A per-request `Promise.Unsafe[Boolean, Any]` (`bodyOutcome`): `true` = body drained, connection reusable;
`false` = undrained/corrupt/close-framed, discard. Created in `sendViaBackend`, threaded as
`Maybe[Promise.Unsafe[Boolean, Any]]`. `Absent` = buffered route (current semantics unchanged).

### 1. Obligation transfer + mutual exclusion
- `sendViaBackend` (:1214) returns `(responseFiber, bodyOutcome)`; it creates `Present(promise)` only for the
  streaming branch (the single routing-decision site, so `poolWithImpl` cannot disagree).
- `poolWithImpl` (:1152, :1161) passes `bodyOutcome` into `releasingConn(key, conn, bodyOutcome)(...)`.
- `releasingConn` (:1008): on `use` success with `Present(done)`, the CAS win **transfers** the decision to a
  `done.onComplete` callback (`true` -> `pool.release`, else `pool.discard`). The `Sync.ensure` finalizer
  (which runs on every completion) then loses the CAS and cannot discard a live body.
- **The load-bearing subtlety:** the streaming success path must win the CAS at defer time and let the
  registered callback act without a second CAS. Merely registering the callback while leaving `released=false`
  would let the ensure-finalizer discard mid-body and truncate the handed-out stream. Three-way exclusivity:
  failure/interrupt -> finalizer wins -> discard (no callback registered); success+buffered -> release (as
  today); success+streaming -> obligation transferred, callback is the unique once-only decider.
- `pool.release`/`pool.discard` are non-suspending, lock-free, and idempotent (registry.remove + close CAS),
  so a client-`closeAll` racing a late discard cannot double-close.

### 2. Completing the obligation on EVERY branch (totality; an uncompleted branch = a checked-out leak)
- parse-failure / panic / `catch`: complete `false`.
- `statusCode >= 400` buffered fallback (:164): complete from `resultPromise` success (`p.completeDiscard(r.isSuccess)`).
- `buildBodyStream` chunked (:591): the background IOTask completes `true`/`false` from the `readStreaming`
  result, before `closeAwaitEmpty` (release must not wait on the consumer draining `decodedCh`).
- Content-Length, all-in-lastBodySpan: `true`. Content-Length deferred: thread into `readContentLengthStream`,
  `true` when drained, `false` on `Closed`. Close-framed (no CL, not chunked): `false` (never reusable).
- Exactly-once holds: one branch per request, single writer + the abandonment finalizer, `completeDiscard`
  no-ops on an already-completed promise.

### 3. Abandonment finalizer (Stream/Scope finalization alone is NOT enough: a never-run stream runs nothing)
- Chunked consumer stream (:603) becomes `Sync.ensure(defer(decodedCh.close()))(... .streamUntilClosed().emit)`
  so drop/abort/interrupt (incl. `Async.timeoutWithError`) closes `decodedCh` -> the background task's
  `output.put` fails `Closed` -> `false` -> discard. Closing the per-request channel never touches the
  connection, so it is always safe; full-drain path's later `close()` is a no-op (no double-close).
- CL branch: wrap the stream body the same way with a `false` finalizer (normal completion wrote `true` first
  and wins by idempotency; interrupt wins -> discard).
- **Documented residual:** a stream handed out and *never run* leaves `bodyOutcome` pending -> the connection
  stays checked out (never pooled, never poisoning) until the body task ends, the peer closes, or the client
  closes. `tryReserve` counts only idle + connecting, so a held-out connection does not block new connections
  to the host. This converts silent corruption into a bounded, observable leak (best achievable without a
  reclamation timeout).

### 4. Per-decode `DecoderState`
- Drop the `conn.http1.chunkedDecoderState` argument at :596 (streaming decode allocates its own state, since
  it outlives the request scope). Keep :434 (buffered decode is intra-request; the connection-scoped reuse is
  preserved). Update the `Http1ClientConnection.chunkedDecoderState` scaladoc (:131) to "buffered decodes only".

### 5. Scope split
- **Load-bearing (ships together):** obligation threading + deferred `releasingConn` decision; ALL FOUR
  `buildBodyStream` branches completed (totality, or a partial fix introduces per-branch leaks); consumer-side
  abandonment hooks; per-streaming-decode `DecoderState`.
- **Hygiene follow-ups (separate, not needed for green):** `readLoopUnsafe` over-read clamp; `offerOrLog` FIFO
  putFiber fallback; close-framed streaming body truncation; 3xx-on-streaming-route mirror of the >=400
  buffered fallback.

### 6. Regression tests (JVM-deterministic, forced fragmentation; shared-source)
- **T1** extend `ChunkedBodyDecoderTest`: call `readBufferedUnsafe` FIRST, then `inbound.offer` each fragment
  from the test thread (delivery runs callbacks inline) at adversarial splits (after CRLFCRLF, mid-size-line,
  size/data boundary, mid-data, data-CRLF `\r`|`\n`, terminal `0\r\n\r`|`\n`); assert exact body + `inbound.empty` +
  zero pending takers.
- **T2** new `HttpClientBackendStreamingTest` via `Connection.inMemoryPair()` (test plays the server): assert
  `bodyOutcome` completes `true` only after the terminal offer with `inbound` clean; partial-body + dropped
  consumer completes `false`; a following `sendBuffered` on the same conn after `true` gets its own body.
- **T3** extend `HttpClientTest` (reproduce-first, black-box): streaming route that emits one chunk, awaits a
  `Latch`, then the rest; consume `take(1)`, then (latch still held, first body mid-flight) hit a plain route
  and assert its own body. Fails deterministically on current main; passes with the fix; a drained variant
  asserts the first connection is reused.
- **T4** interruption variant: wrap streaming consumption in `Async.timeout`, then assert the plain request
  gets its own body (exercises finalizer -> `decodedCh.close()` -> `Closed` -> discard).

### 7. Residual risks (explicit)
1. The CAS-transfer subtlety (section 1) is the one spot an implementor can silently reintroduce the bug;
   T3-drained-reuse + T4 catch a wrong version (mid-body discard truncates the handed-out stream).
2. Double-decision excluded by the single CAS + once-only `IOPromise` callbacks + idempotent `completeDiscard`;
   double-close excluded by the idempotent discard chain.
3. Leak-not-corruption is the accepted end-state for never-run streams (documented in scaladoc).
4. `Sync.ensure` over an `Emit` computation is type-legal and kernel-supported; a re-run of a chunked stream
   value sees a closed `decodedCh` and yields an ended stream (acceptable).
5. On JS the new paths run inline on completion edges (no new suspension points; release/discard
   non-suspending), so no new single-threaded reentrancy hazard; single-read degenerates to today's immediate
   release.

