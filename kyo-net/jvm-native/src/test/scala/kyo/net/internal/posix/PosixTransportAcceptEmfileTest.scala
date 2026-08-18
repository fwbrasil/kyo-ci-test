package kyo.net.internal.posix

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

// This suite lives in jvm-native/src/test because PosixTransport's accept loop runs on JVM-posix and Native; JS uses the Node transport.

/** Reproduce-first guard for the accept-loop spin on `EMFILE` (out of file descriptors).
  *
  * `accept(2)` documents that on a resource error (`EMFILE` / `ENFILE` / `ENOBUFS` / `ENOMEM`) the kernel does NOT dequeue the pending
  * connection: the connection stays in the backlog and the listening socket stays read-ready. A `PosixTransport.acceptAll`
  * `drain` loop that treated only `EAGAIN`/`EWOULDBLOCK` as "drained" would let every other errno fall into an `else ()` arm that stops the drain WITHOUT
  * consuming the backlog entry, and `scheduleNextAccept` would then re-arm read interest on the listen fd. Because the pending connection is still
  * in the backlog, the poller re-fires the listen fd immediately, `acceptNow` returns `EMFILE` again, and the loop re-arms again: a tight CPU
  * spin on the poll-loop carrier that stalls every other connection on the shared driver until a fd frees elsewhere. This is the exact livelock
  * libuv (joyent/libuv #690, #315) and asyncio (Tulip #78) had to special-case. `acceptAll` classifies `EMFILE`/`ENFILE` as resource exhaustion and
  * re-arms after a bounded backoff (`PosixTransport.acceptResourceBackoff`) instead of immediately, breaking the spin while keeping the accept
  * loop alive so accepting resumes once a fd frees; `ECONNABORTED`/`EINTR` are retried in place per the man page.
  *
  * The mechanism: a delegating [[SocketBindings]] decorator injects `EMFILE` for `acceptNow` on the listen fd while a bounded budget is unspent,
  * counting every call. One real client connects, so the listen fd is genuinely read-ready with one backlog entry. The driver's poll loop fires
  * the accept, the transport drains via `acceptNow`, and the EMFILE return drives the loop. If the accept loop spins, the injected `acceptNow`
  * count climbs without bound for ONE pending connection; a loop that handles EMFILE as a backoff re-arm (rather than an immediate one) issues
  * one `acceptNow` per re-arm. The decorator stops injecting once the spin threshold is crossed so a regressed (spinning) build still
  * tears down cleanly (the real accept then succeeds).
  *
  * Completion: nothing here reads a clock. The transport reports every resource-exhaustion re-arm through its `onAcceptResourceBackoff` hook,
  * and the leaf settles on whichever event lands first, each of them a count of accept-loop events rather than a duration:
  *
  *   - the `backoffTarget`-th re-arm, which samples the `acceptNow` count at that exact instant (about one call per re-arm), or
  *   - the spy's spin cap, `spinThreshold` `acceptNow` calls for ONE pending connection, which only a spinning loop ever reaches.
  *
  * Both settle the same promise with the call count, and the assertion reads that count, so a slow or loaded machine changes how long the leaf
  * runs and nothing else. `Async.timeout` is only the deadlock ceiling for an accept loop that does neither (a wedged listener), never the pass
  * condition.
  */
class PosixTransportAcceptEmfileTest extends Test:

    import AllowUnsafe.embrace.danger

    // EMFILE = 24 on both Linux and macOS/BSD (stable POSIX errno). Not defined in PosixConstants (part of the defect: the accept loop has no
    // branch for it), so it is spelled out here.
    private val EMFILE = 24

    // How many resource-exhaustion re-arms the accept loop must perform before the call count is read. Each one is a whole backoff cycle: the
    // poller re-fires the still-ready listen fd, `acceptAll` issues exactly one `acceptNow`, EMFILE comes back, and the loop backs off again.
    // Requiring several proves the cadence repeats rather than the loop having stalled once.
    private val backoffTarget = 3

    // The accept loop issues one acceptNow per backoff re-arm for one pending connection, so the count sampled at the target re-arm is that
    // many. The slack of one absorbs an extra drain (a spurious readiness on the listen fd) without admitting a spin, which issues hundreds.
    private val bound = backoffTarget + 1

    // Spin cap. A spinning loop never backs off, so nothing else would ever settle this leaf: at this many acceptNow calls for ONE pending
    // connection the loop is provably spinning, and the spy settles the leaf with the spun count. Past it the spy stops injecting EMFILE so a
    // regressed build's real accept drains the backlog and the test tears down cleanly.
    private val spinThreshold = 200

    // Deadlock ceiling, not a pass condition: the two settling events above are counts, and no assertion reads elapsed time. This turns an
    // accept loop that neither backs off nor spins (a listener wedged with no re-arm at all) into a failed test instead of a hang.
    private val settleCeiling = 15.seconds

    private def assumePollerReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PosixTransport accept-loop tests need epoll (Linux) or kqueue (macOS/BSD)")

    /** A delegating [[SocketBindings]] that injects `EMFILE` on `acceptNow` while a bounded budget is unspent, counting every call. At the spin
      * threshold it settles `settled` with the call count (the only way a spinning loop, which never reaches a backoff re-arm, terminates this
      * test) and stops injecting, delegating to the real `acceptNow` so the backlog drains and teardown is clean. Every other method delegates to
      * the real bindings (the single controlled injection pattern: one syscall's result is overridden, the rest are real).
      */
    final private class EmfileAcceptSockets(real: SocketBindings, settled: Promise.Unsafe[Int, Any]) extends SocketBindings:
        val acceptNowCalls: AtomicInteger = new AtomicInteger(0)

        def acceptNow(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            val n = acceptNowCalls.incrementAndGet()
            if n >= spinThreshold then
                // Settle from the spin side: a spinning loop never reaches a backoff re-arm, so this is the only event that ends the leaf for
                // it. The promise's own gate makes this idempotent, so whichever of the spin cap and the backoff target lands first owns the
                // outcome, and the real accept below drains the backlog so teardown is clean.
                discard(settled.complete(Result.succeed(n)))
                real.acceptNow(fd, addr, addrlen)
            else
                // The pending connection stays in the backlog (EMFILE does not dequeue it); the listen fd remains read-ready.
                Ffi.Outcome.fromValueErrno(-1L, EMFILE)
            end if
        end acceptNow

        def socket(domain: Int, `type`: Int, protocol: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.socket(domain, `type`, protocol)
        def bind(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.bind(fd, addr, addrlen)
        def listen(fd: Int, backlog: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.listen(fd, backlog)
        def setsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.setsockopt(fd, level, optname, optval, optlen)
        def getsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Buffer[Int])(using
            AllowUnsafe
        ): Ffi.Outcome[Int] =
            real.getsockopt(fd, level, optname, optval, optlen)
        def getsockname(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            real.getsockname(fd, addr, addrlen)
        def getpeername(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int] =
            real.getpeername(fd, addr, addrlen)
        def fstat(fd: Int, buf: Buffer[Byte])(using AllowUnsafe): Ffi.Outcome[Int] =
            real.fstat(fd, buf)
        def shutdown(fd: Int, how: Int)(using AllowUnsafe): Int =
            real.shutdown(fd, how)
        def connect(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any] =
            real.connect(fd, addr, addrlen)
        def connectNow(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Ffi.Outcome[Int] =
            real.connectNow(fd, addr, addrlen)
        def accept(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any] =
            real.accept(fd, addr, addrlen)
        def recv(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            real.recv(fd, buf, len, flags)
        def send(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            real.send(fd, buf, len, flags)
        def sendNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
            real.sendNow(fd, buf, len, flags)
        def recvNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long] =
            real.recvNow(fd, buf, len, flags)
        def read(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any] =
            real.read(fd, buf, count)
        def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any] =
            real.close(fd)
    end EmfileAcceptSockets

    "PosixTransport accept loop" - {

        "does not spin on acceptNow EMFILE while a connection is pending (bounded retry)" in {
            assumePollerReady()
            val settled  = Promise.Unsafe.init[Int, Any]()
            val spy      = new EmfileAcceptSockets(Ffi.load[SocketBindings], settled)
            val backoffs = new AtomicInteger(0)
            val driver   = PollerIoDriver.init()
            val transport = TestTransports.forTesting(
                driver,
                spy,
                backendIsEpoll = false,
                // Sampling the acceptNow count inside the hook, on the carrier that is about to park the loop for the backoff, pins the reading
                // to the target re-arm rather than to whatever the loop had reached at some later instant.
                onAcceptResourceBackoff = () =>
                    if backoffs.incrementAndGet() == backoffTarget then discard(settled.complete(Result.succeed(spy.acceptNowCalls.get())))
            )
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                for
                    listener <- transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get
                    // Registered as soon as the listener is up: the connect below (or its inline assert) can fail before the tail block that
                    // used to hold the only listener.close(), which would leak the listener.
                    _ <- Scope.ensure(Sync.defer(listener.close()))
                    port = listener.port
                    // One real client connect: the listen fd gets exactly one backlog entry, so it is genuinely read-ready and the poll loop
                    // drives the transport's acceptAll -> acceptNow path against the injected EMFILE.
                    clientFd <-
                        val fd = spy.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
                        // Registered immediately for the same reason: the raw client fd's only close used to sit past the same connect/assert.
                        Scope.ensure(Sync.defer(discard(spy.close(fd)))).andThen {
                            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
                            spy.connect(fd, ca, cl).safe.get.map { r =>
                                ca.close()
                                assert(r.value == 0, s"client connect failed errno=${r.errorCode}")
                                fd
                            }
                        }
                    // Settles on the target backoff re-arm or on the spin cap, whichever the accept loop reaches first.
                    outcome <- Abort.run[Timeout](Async.timeout(settleCeiling)(settled.safe.get))
                yield outcome match
                    case Result.Success(count) =>
                        assert(
                            count <= bound,
                            s"accept loop spun: $count acceptNow(EMFILE) calls for ONE pending connection without reaching $backoffTarget " +
                                s"resource-backoff re-arms (bound $bound). EMFILE leaves the connection in the backlog so the listen fd stays " +
                                "read-ready; an immediate re-arm re-fires it at once and the loop livelocks. A bounded-backoff re-arm issues " +
                                "one acceptNow per re-arm."
                        )
                    case Result.Failure(_: Timeout) =>
                        fail(
                            "the accept loop neither backed off nor spun: it never re-armed under EMFILE, so the listener is wedged and the " +
                                "pending connection is never accepted"
                        )
                    case other => fail(s"unexpected accept-loop outcome: $other")
                end for
            }
        }
    }

end PosixTransportAcceptEmfileTest
