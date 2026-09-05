# A permanent read strand on JS + kqueue

Written for a reader with no prior context. Line numbers are against `origin/main` at `0f0d7cf21c`, where
the defect reproduces; it is NOT introduced by the ci-stabilization branch.

## The failure

`kyo-net`'s `TransportResilienceTest`, leaf "a mix of healthy and abruptly-closed connections keeps healthy
ones round-tripping (isolation)", run on **kyo-netJS with the kqueue backend**, hangs forever.

The leaf opens 200 connections at concurrency 40 against a server that takes one 3-byte message from every
accepted connection and then either echoes it or abruptly closes. With the per-leaf cap raised from 60s to
420s it logged `[STUCK]` at 1m, 2m, 3m, 4m and 5m and never completed. It is a permanent strand, not slowness.

## It is exclusive to one cell

Same suite, same `PosixTransport` and `PollerIoDriver` sources, varying only the backend and runtime:

| leaf | JS+kqueue (macOS) | JS+epoll | JS+io_uring | JS+node | JVM+kqueue (same mac) |
|---|---|---|---|---|---|
| isolation | **hangs forever** | 77ms | 81ms | 61ms | 87ms |
| interrupting in-flight reads | **30.0s** | 115ms | 120ms | 1.0s | 128ms |

The Linux figures come from running kyo-netJS inside a Linux container: 24 passed, 0 failed, whole suite.

Two consequences. First, CI structurally cannot see this: kqueue exists only on macOS and BSD, and the matrix
is linux (epoll, io_uring) plus windows. Second, neither "JS" nor "kqueue" alone explains it, because
JVM+kqueue is clean on the same host. The failing cell is the intersection.

CONFOUND, stated because it limits the table: kqueue exists only on macOS and epoll only on Linux, so the
backend axis is inseparable from the OS axis. The JVM row is what rescues the comparison.

## What is measured

All by instrumenting the driver in a throwaway worktree and reading the hang dump.

1. **The parked reads hold readable data.** A non-blocking `MSG_PEEK` per pending read at hang time split
   exactly in half: 20 entries at `peek=3` and 20 at `peek=-1` (EAGAIN). The leaf's payload is `"iso"`,
   exactly 3 bytes. So half the stranded fds are sitting on the message with the driver never dispatching a
   read. The EAGAIN half are cascade victims: client reads waiting for an echo that cannot come, because the
   server-side socket holding their message never got its read edge. This also makes `peerClosed=false`
   correct on every stranded entry rather than symptomatic.

2. **The socket is EMPTY when the read is armed.** Instrumenting every read arm with a `MSG_PEEK` at arm
   time, against the 52 stranded reads in one dump:

   ```
    0  armed with bytes already buffered, no read event
   50  armed on an EMPTY socket, no read event
    0  armed and a read event followed
    2  never armed for that owner
   ```

3. **No read event is ever delivered for those fds.** Separating "no event" from "event tagged with another
   owner": 50 stranded fds saw **no EVFILT_READ event for that fd at all**, and 0 saw one tagged with a
   different owner.

So: the read is armed on an empty socket, the bytes arrive afterwards, and kqueue delivers zero read events
for that fd ever after.

## Six dead hypotheses

Listed so none is retried. Each was killed, not merely doubted.

1. **libuv threadpool starvation.** `UV_THREADPOOL_SIZE=64` changed nothing (30.1s vs 30.0s). Caveat: the
   variable was not independently verified to reach the Node process, so this one is strong but unconfirmed.
2. **The JS poll budget.** `JsPollBudgetMs` 50 to 1, a 50x cut. The interrupt leaf stayed at exactly 30.0s.
3. **Peer-close grace.** `NetConfig.DefaultPeerCloseGrace` 30s to 1s. Neither symptom moved, despite 30.0s
   matching that constant exactly.
4. **"epoll participates in the driver's missed-edge recovery and kqueue does not."** This rested on a grep
   count of 4 references versus 0. All four `EpollPollerBackend` hits are scaladoc and comments; the recovery
   (`missedReads`, `readMightHaveMore`) lives in the backend-agnostic `PollerIoDriver`. Comment density read
   as functional participation.
5. **The recycled-fd stale-event drop.** One run showed 9 stale drops coinciding with 9 stranded reads, which
   was written up as a proven root cause. It was then disproved by writing the fix it implies (on a stale drop
   whose current owner holds a pending read with a different id, force a speculative read) and running the
   leaf: it still stranded, and the recovery fired **zero** times. A run that strands with none of the
   implicated drops present is a counterexample.
6. **Bytes landed before the knote attached.** Killed by measurement 2 above: the socket is empty at arm time.

Also checked and found symmetric, each of which would have been a mechanism if broken: kqueue maps `EV_EOF`
to `PollFlags.Eof` exactly as epoll maps `EPOLLRDHUP`; `backendIsEpoll` is only a regular-file guard.

## The live lead, and the inference that was wrong

An earlier note claimed "all 5994 `registerRead` calls returned `rc=0`, so the read filter was successfully
attached". **That inference is wrong.** `KqueuePollerBackend.change` (`:153-171`) does not call the kernel. It
encodes the change into a per-driver changelist buffer and returns 0 unconditionally, and its own comment says
"the changelist batches changes until the next poll submits them". `rc=0` means STAGED, not ARMED.

A staged read registration that never reaches the kernel produces exactly the measured signature: zero read
events for that fd forever, on a socket that later holds data. Two loss paths are visible in that same
function and its comment:

- the batch is flushed and reset when `nChanges >= MaxEvents` (`:164-167`), so any failure or partial
  application there drops staged changes silently while `change` still returns 0;
- the comment names "terminalTeardown's drain, which has no following poll to flush the batch at all", a path
  where staged changes are known never to be submitted.

## Questions I want judged

1. Is staged-but-never-submitted the right reading of the evidence, or is there a better one that also
   explains all three measurements?
2. What plausibly makes the JS runtime differ at the changelist-submission level specifically, given the
   backend code is shared with JVM? The JS poll parks with a 50ms budget on a libuv worker rather than
   indefinitely on its own thread, but the budget itself was ruled out as the mechanism.
3. Is there a confound or a measurement artifact in the three findings above that I have missed?
4. Strategic: CI cannot observe this defect, and fixing it resets an in-progress three-green streak. Is the
   right call to fix now, or to record it precisely and return after the streak is banked?

## The mechanism, measured

The submission logging above was run. It resolves the fork.

`man kevent`: an error while processing a changelist element is reported as an `EV_ERROR` event **if there is
room in the eventlist**; otherwise `kevent` returns -1 and **stops processing at that element**. Every entry
after the failing one is never applied, and the caller learns nothing about which.

The overflow flush passes `nevents = 0`. There is never room, so it always takes the -1 path, and the rc is
discarded. The poll-side submission (`:246`) passes `nevents = MaxEvents`, gets per-entry receipts, and
processes the whole changelist. That asymmetry is the defect: the flush is silent precisely because it is the
only submission with no eventlist room.

Measured on `kyo-netJS`, macOS/kqueue, two runs:

| | run A | run B |
|---|---|---|
| overflow flushes | 194 | 184 |
| flushes returning an error | 2 | 2 |
| errno | 9 (EBADF) | 9 (EBADF) |

`keventNow` returns `Ffi.Outcome[Int]`, an opaque `Long` packed as `if errno == 0 || value >= 0 then value
else -errno` (`kyo-ffi/.../Ffi.scala:148`), so the observed `rc=-9` is `-EBADF`. An earlier note in this file
read that raw packed value as a plain return code; it is `-errno`.

Both failed batches were full 64-slot batches (`FLUSH seq=51 n=64 rc=-9`, `seq=52 n=64 rc=-9`). Their slot
dumps hold 96 `EVFILT_READ` arms (`EV_ADD | EV_CLEAR`) and 32 `EVFILT_WRITE` arms
(`EV_ADD | EV_CLEAR | EV_ENABLE`): **128 interest changes staged into batches whose submission failed and
whose rc was thrown away.**

### What this does not yet show, and how it is being settled

Two gaps, stated rather than papered over:

1. A failed flush is not sufficient for a strand. The instrumented run above **passed** (73ms): the per-event
   `println` perturbs timing enough to mask the hang, while the two EBADF flushes still occurred.
2. With `nevents = 0` the kernel returns no per-entry receipt, so the failing slot index is not observable at
   all. Which of the 128 entries were dropped cannot be read off this measurement.

The instrumentation that would close the per-fd chain is the same instrumentation that destroys the
phenomenon, so the chain was not pursued by tracing. Causation was settled by intervention instead:
the uninstrumented leaf at baseline, then the same leaf with the flush no longer discarding its rc. A strand
that reproduces without the fix and disappears with it is stronger evidence than a correlational trace, and it
does not rest on combining measurements taken from different runs.

## Resolved

| | baseline | with the fix |
|---|---|---|
| isolation leaf, kqueue | STUCK, then TIMEOUT at 1m, in 3 of 3 runs | PASS in 3 of 3 runs, 79 to 106ms |
| whole suite | 15 passed, 1 timed out, 1m41s | 16 passed, 0 failed, 12.5s |

The suite total collapsing from 1m41s to 12.5s also accounts for the second row of the exclusivity table: the
interrupt leaf's 30.0s was `Transport.DefaultConnectTimeout` (`Transport.scala:152`) rescuing connect
writable-arms dropped by the same flush. That is why cutting `DefaultPeerCloseGrace` moved nothing; 30s
matched two different constants and the wrong one was tested.

### The fix

Flush with `EV_RECEIPT` on every entry and an eventlist sized to the batch. Both halves are load-bearing:
room for receipts stops the kernel aborting at the first rejected entry, and `EV_RECEIPT` (which forces a
receipt per change, success included) fills the eventlist exactly, so the call cannot consume a pending
readiness event. Without that second half the fix would trade this strand for a worse one, since read
interest is edge-triggered and a consumed event is gone.

A rejected entry has no caller left to fail, so each is recorded with its registering handle's id and drained
by the driver after every change drain, failing the pending read, accept, or write through the same contract
as the existing synchronous `register failed` branches. The owner id is what makes that safe: an fd closed
between staging and submission may already have been recycled, and failing by fd number alone would kill
whichever connection now holds it.

`KqueuePollerBackendTest` gained a permanent guard. Its negative control is exact: against pre-fix sources it
fails with `expected 52 to fire, got List()` while the file's other three leaves still pass.

### Found on the way, fixed separately

`PollerBackend.disableWrite` was dead code with no call site, and thirteen comments across the poller sources
and the edge-triggered driver test described it as live, several naming `dispatchWritable` as its caller. It
is deleted and the comments corrected.
