# StartTlsUpgradeCloseRaceTest: fd closed 0 times

Groundwork for analysis. Line numbers are against `origin/main` at `8b8e463a86`.

## VERDICT (analysis complete, linchpin independently verified)

TEST RACE, not a production defect. The deferred-close credit is LATE, not stranded; the fd is closed
exactly once, microseconds after the assertion samples the count.

The fact this groundwork missed: the EAGAIN latch fires INSIDE a held dispatch bracket. The handshake never
recvs during an upgrade; it parks a waiter and arms a poll-carrier read, so the spy's `onRecvEagain` fires
from inside `dispatchRead`'s begin/endDispatch bracket. The close fiber therefore becomes runnable while the
poll carrier still holds a guard read holder. Then `freeResources` fails the parked upgrade waiter
(`PosixHandle.scala:770-773`) BEFORE it discharges `fdCloseSink` (`:803-810`). So both of the leaf's
barriers, upgrade settled and close fiber done, can be satisfied with the syscall still pending, and the
test never awaits the poll carrier.

Why 39 of 40 pass: normally the close fiber's own `endDeferredClose` is the last holder, so the free runs
inside `closeHandle`'s call stack and `closeFiber.get` already implies the count. The await-both structure
accidentally covers every path except the bracket-carried one.

The four open questions below are answered NO / YES / NO / LATE respectively: no interleaving strands the
credit, the invariant holds on every path this test reaches, and `freeResources` is reachable only from
guard-mediated points where holders are non-zero, so it cannot run between a claim win and the credit
install. One letter-of-the-invariant exception exists and is out of scope: `IoUringDriver.scala:940`
closeListener issues a bare `requestClose` with no claim, safe today because no claim/credit closer ever
targets a listen fd, but the two fall-through comments overstate their scope and deserve a scope note.

THE FIX WAS ALREADY IN THE REPO AND THIS LEAF WAS NOT USING IT. `RecordingDecorators.scala:86-95` defines
`spy.closed(fd)`, a fiber-parking latch completed inside the counted `close()` at `:236`, whose own comment
says it exists "to await the deferred real close(fd) running (which happens asynchronously on whatever
carrier releases the last guard holder), rather than polling closeCounts". Three leaves already use it
(`IoUringDriverDeferredCloseGuardTest` twice, `PollerIoDriverCloseDuringIoTest:98`); this one used it zero
times. Applied in the bare `spy.closed(serverFd).safe.get.map` form of `PollerIoDriverCloseDuringIoTest`
rather than the sibling's `Async.timeout(5.seconds)` wrapper, since an in-test timeout duplicating the
per-leaf cap is what CONTRIBUTING now forbids.

A production reorder (discharge the credit before failing the waiter) was considered and REJECTED: it would
not close the `failUpgradeHandoff` EOF-dispatch variant, where `out` settles from inside the bracket before
`endDispatch` carries the free, so it cannot make an unbarriered exact read correct.

MERGE ATTRIBUTION: `8b8e463a86` did not introduce this. The race is latent in the test. The scheduler
`Worker` dispatch-state rework shifts exactly the two wake latencies the interleaving needs, plausibly
moving the window from effectively-never to roughly 1 in 120 iterations on the oversubscribed x64 leg. The
parent's single green and the absence of earlier reds are what a window this narrow predicts. Do not chase
`Channel.closeAwaitEmpty`, the Queue diagnostics, or `HandoffRetryExecutor` for this failure.

## The failure

`ci` run 33172334152, leg `build (linux-x64) / build (JVM)`, the only red in an otherwise 9/10 green
matrix on the merge commit:

```
kyo.net.internal.posix.StartTlsUpgradeCloseRaceTest: 0 passed, 1 failed  (21ms)
[iter 3] upgrading fd=78 closed 0 times (expected exactly 1: no double-close, no leak)
```

Load-bearing details:

- **`0`, not `2`.** Nothing double-closed. The fd was never closed at all, by the moment of the read.
- **21ms, at iteration 3 of 40.** A fast assertion failure, not a hang. Iterations 1 and 2 closed correctly.
- The leg runs suites at `parallelism 8`.
- The suite's own `latchFired` counter is barrier-backed, so the close provably landed inside the upgrade
  window; that arm is not in question.

## What the test does

`kyo-net/jvm-native/src/test/scala/kyo/net/internal/posix/StartTlsUpgradeCloseRaceTest.scala:60-135`.

Per iteration, over a real loopback pair with a real poller driver and a `RecordingSocketBindings` spy:

1. `serverPlain = transport.openWith(serverHandle, driver, ...)`, `serverPlain.start()`.
2. `serverUpgrade = transport.upgradeRole(serverPlain, serverTls, ..., isServer = true)`; the engine is
   built and the first handshakeStep submitted before it returns, so the re-handshake is in flight.
3. A fiber waits on `recvSignal` (the server handshake's real `recvNow` EAGAIN) and then calls
   `driver.closeHandle(serverHandle)`.
4. The test awaits the upgrade and the close fiber, calls `serverPlain.close()` as an "idempotent extra",
   then reads `spy.closeCounts.getOrDefault(serverFd, 0)` and asserts `== 1`.

## Which close path this actually takes

Three wrong answers were eliminated by reading, and are recorded so nobody re-walks them:

- NOT `Channel.closeAwaitEmpty` and NOT `Connection.closeFn`. The close under test is
  `driver.closeHandle(serverHandle)` called directly; `Connection.close` only appears afterwards as the
  idempotent extra.
- NOT the engine-FIFO deferral in `closeHandle`'s TLS branch. `handle.tls` becomes `Present` only at
  handshake COMPLETION; `PosixHandle.scala:108` names "the gap after upgradeActive clears but before tls
  is installed", and `upgradeRole` (`PosixTransport.scala:1592`) refuses to start if `tls.isDefined`. The
  close fires mid-handshake, so `tls` is `Absent`.

So `PollerIoDriver.closeHandle` (`:1343`) takes the **plaintext** branch: `deferredPlaintextClose`.

## The mechanism the code already documents

`deferredPlaintextClose` (`PollerIoDriver.scala:1294`):

```scala
val held = handle.beginDeferredClose()
claimAndDeferFdClose(handle)
PosixHandle.close(handle)
if held then discard(handle.endDeferredClose())
```

`claimAndDeferFdClose` (`:1274`) does NOT close the fd. It shuts it down and installs a credit:

```scala
if handle.readFd == handle.writeFd && handle.claimFdClose() then
    discard(sockets.shutdown(handle.readFd, PosixConstants.SHUT_RDWR))
    handle.fdCloseSink = Present(() => discard(takeNow(sockets.close(handle.readFd))))
```

The real `close(fd)` is discharged later by `freeResources`, which reads `fdCloseSink`.

The scaladoc at `:1280-1287` describes our exact symptom as the hazard it guards against:

> a STARTTLS re-handshake close on this driver does `shutdown(SHUT_RDWR)`, whose EOF wakes the poll carrier
> into a reentrant `releaseFailedUpgrade` that reaches `PosixHandle.close`, running `freeResources` while
> `fdCloseSink` is still Absent so the real `close(fd)` never runs (a stranded credit, a `CLOSE_WAIT` fd leak).

## The invariant the guard rests on, and where it is asserted twice

`HandleGuard` (`HandleGuard.scala:40-100`): `acquire` refuses once `CloseBit` is set, so
`beginDeferredClose()` returns **false exactly when another closer already set the close bit**. Both
unprotected fall-throughs then run, justified by the same claim:

> The `beginDeferredClose` false branch (a close already raced) falls through unprotected: a set close bit
> is always preceded by a spent `claimFdClose`, so the claim below is a no-op that installs nothing to strand.

That text appears in TWO places, guarding TWO different closers that race each other on this very path:

1. `PollerIoDriver.deferredPlaintextClose`, `:1289-1298`.
2. `PosixTransport.closeUnwiredHandle`, `:1483-1490`, which is what `releaseFailedUpgrade` calls
   (`:1880-1886` -> `closeUnwiredHandle(handle, handle.driver, connectPhase = false)`).

And `releaseFailedUpgrade` is precisely the reentrant closer the hazard comment names.

If the invariant holds, a losing claim installs nothing and the winner's credit is discharged. If it is
violated, or if the guard can terminalize between a claim win and the credit install despite the hold, the
credit strands and the fd is never closed: `closed 0 times`.

Static enumeration of close-bit setters, for whoever continues:

- `PosixHandle.close(h)` is exactly `h.requestClose()` (`PosixHandle.scala:818-820`).
- `PollerIoDriver.scala:2069` sets it from a read dispatch, and carries a comment saying it "must
  claim-and-defer the fd close the SAME way every other close path here does, before calling
  requestClose", so that site was already found and corrected once.

## Open questions for analysis

1. Can two closers (driver `closeHandle` and the EOF-woken `releaseFailedUpgrade`) both reach their
   unprotected fall-through such that the surviving credit is never read?
2. Is the invariant "a set close bit is always preceded by a spent `claimFdClose`" actually true on every
   path, including `PosixHandle.close` reached from outside the driver?
3. Can `freeResources` run between a claim win and the `fdCloseSink` install despite the guard hold?
4. If the credit is merely late rather than stranded, this is a test race instead: the assertion samples
   `spy.closeCounts` once with no barrier. Those two have identical symptoms at the sampling instant and
   must be told apart before a fix is chosen.

## Evidence gathered so far

| condition | result |
|---|---|
| macOS host | cancelled (posix backend) |
| arm64 Linux container, test alone, 3 runs / 120 iterations | passed |
| arm64 Linux container, full `kyo-netJVM/test`, 236 suites | passed |
| linux-x64 CI, test alone (rung 2, run 33182448851) | passed |
| linux-x64 CI, full JVM leg (rung 3, run 33183380032) | dispatched |
| linux-x64, full JVM leg on main's own ci | FAILED at `[iter 3]` |

Reproducing needs BoringSSL, or the suite silently cancels and looks like a clean run:
`STAGE_BORINGSSL=1 scripts/build.sh --env podman --arch arm sbt "kyo-netJVM/testOnly ..."`.

## The threshold problem in this test

Independent of the defect, and worth fixing either way.

- `assert(closes == 1, ...)` is an exact invariant read with **no barrier** for an asynchronous close. This
  is the immediate defect in the test: it samples a state it never waited for. The correct shape waits for
  the count to reach 1, which separates all three outcomes honestly (a leak never arrives and trips the
  per-leaf timeout; a double-close reaches 2; a slow-but-correct close passes).
- `assert(abortBranch.get() > 0, ...)` over `iterations = 40` is a probabilistic threshold. The test
  controls WHEN the close fires (the EAGAIN latch) but not WHICH side wins, so it stresses 40 times and
  requires the abort branch at least once. The 40 is a magic number.
- `assert(latchFired.get() == iterations, ...)` is genuinely deterministic and should be left alone.

The stress loop itself is defensible: surfacing a latent UAF needs repetition and no barrier substitutes
for it. What is not defensible is `abortBranch > 0` doing double duty as a coverage check and a correctness
claim without saying so.

## Related bugs

Closed, same family (fd lifetime / close races in kyo-net):

- io_uring `processSharedTransport` fd-leak.
- kyo-net `ConnectionPool` not expiring idle connections (fd leak).
- io_uring `CancelIntegrationTest` fd-leak.
- The custody rework: owner-close in `openSocket`, `withCustody`, guarding both cancel sidecars, and the
  `warmUp` / `openDedicated` handover windows.

Open, same family, all in this driver/transport layer:

- Driver interrupt-reclaim wake-deafness and an ineffective 2s cancelTimeout.
- Transport `closeNow` abort-close for the quarantine guarantee.
- Driver `submitConnect` `isClosing()` guard (connect-race).

Open, and the CLOSEST known relative (issue #1885):

- `[kyo-sql][flaky] end-of-run leak check: one ESTABLISHED client socket with an armed io_uring Read
  survives the kyo-sql-tests run`. Probabilistic, linux-arm64 JVM. The leak check finds one leaked
  descriptor after every test passes, and the driver diagnostics show
  `pending(1)=[Read(fd=44,...)] inFlight=[...] closeAfterDrain(0)=[] pendingCloses=0`. The issue's own
  reading: "some kyo-sql code path (or the kyo-net client under it) dropped a connection without closing
  it."
  Same class as ours: a close obligation that is never discharged, leaving the fd open. Different driver
  (io_uring, not poller) and a different observation point (an end-of-run sweep rather than an in-test
  count), so it is not the same code. But `pendingCloses=0` there is notable, because the poller path here
  turns on exactly that registry and its credit, and the io_uring driver has its own
  `registerDeferredClose` that both unprotected fall-throughs claim to mirror.
  ADJUDICATED: NOT the same root cause. Every path that spends `claimFdClose` issues a `shutdown` on the fd
  first (`PollerIoDriver.scala:1276`, `IoUringDriver.scala:1070` and `:1162`, `PosixTransport.scala:1489`
  and `:1192`), and on io_uring that shutdown is load-bearing precisely because it forces the kernel-owned
  recv to EOF so its CQE reaps. #1885's dump shows an ARMED Read still pending on a still-ESTABLISHED
  socket after 47,269 reap cycles, which a spent claim would have destroyed. It also shows
  `closeAfterDrain=0` and `pendingCloses=0`, so no close was ever registered. The dump is consistent with
  exactly one state: no closeHandle, no claim, no CloseBit, ever. That is a missing `close()` at the
  ownership layer (kyo-sql or the kyo-net client's connection custody), the family of the already-fixed
  ConnectionPool idle-expiry bug, and the productive next step there is custody tracing at the Connection
  layer, not further audit of the driver credit protocol. The two drivers were compared and implement the
  same protocol correctly, each taking the guard hold before the claim.
  Caveat on provenance: the poller arm of that argument was verified directly here; the io_uring line
  citations come from the analysis and were not independently re-read.

Open, adjacent, and ruled NOT this:

- kyo-core `Queue.close` versus an in-flight `offer` (another agent, branch `kyo-core-close-drain`). Its
  signature is an infinite spin in `MpscUnsafeQueue.spin$1` and a bare `while activeOffers.get() > 0 do ()`
  in `close`, i.e. a HANG. Ours is a 21ms assertion failure that reached its assertion. Its
  `acquireRelease` arm does leak a resource, which is a matching symptom in kind, but neither `Connection`
  nor the transport's `openWith` registers on a `Scope`, and the close under test is a direct
  `driver.closeHandle`. That work does land on every MPSC `Channel` and every `Scope` close, so it is the
  first place to look if the intermittent linux-x64 Native `ChannelTest`/`QueueTest` HANGS recur.
- `[actor] Opt-in per-actor dead-letter queue for unprocessed messages on shutdown` (#1690). Concerns an
  actor's unprocessed message backlog at shutdown, and is a feature request for a typed dead-letter sink,
  not a defect: `Actor.close()` already returns the backlog. Item delivery semantics, not fd lifetime. No
  shared code or mechanism with this failure.
- kyo-core `Sync.ensure` does not run its finalizer when the body short-circuits via `Abort`. The test uses
  `Sync.ensure` for driver and client-fd teardown, but that finalizer covers `clientFd`, not the `serverFd`
  under assertion. Owned separately and not to be fixed here.

## What main's history says about ownership

Not one test has failed twice across main's recent reds; eight distinct tests over five red runs. Relevant
to attribution here:

- `e20e5fa1b7` is `8b8e463a86~1`, the parent of the merge, and its full matrix passed all ten legs.
- `StartTlsUpgradeCloseRaceTest` appears in no earlier red.

That ordering points at the merge, but the merge touches none of the code above: it changed
`Channel.closeAwaitEmpty`, added `Queue.diagnosticState`/`offersRejected`, reworked the scheduler's
`Worker` dispatch state, and added the Native `HandoffRetryExecutor`. The plausible coupling is timing
(the scheduler change shifts when carriers run), not logic. A latent unbarriered race also flips red or
green run to run regardless of the merge, which is why the parent's single green cannot settle it.
