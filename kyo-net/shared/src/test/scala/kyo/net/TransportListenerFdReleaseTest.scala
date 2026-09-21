package kyo.net

import kyo.*

/** Cross-backend proof that closing an idle listener releases its OS listen fd, and that [[Listener.released]] is the signal for it.
  *
  * Closing a server while it is registered with the driver's poller for accept interest must release the listening socket even when the driver
  * is otherwise idle. On the NIO floor the selector waits with no timeout and the real `kill()` (the `nd.close(fd)`) runs only inside a selector
  * pass, so unless the listener close wakes the selector and the driver reports that pass, the socket leaks in LISTEN indefinitely (intermittent,
  * last-server-biased: later connection activity wakes the selector and masks it, the last server in an idle suite does not). io_uring hands
  * the close to its reap carrier and Node to the server handle's close, so on every backend the release is later than `close()` returning.
  *
  * Release is asserted through the public API, with no JVM-only fd counting: close, await `released`, then re-bind the port exactly once. A
  * fresh `listen` on the same port does a synchronous `bind()` BEFORE it registers accept interest, so a descriptor still open makes that bind
  * fail (address in use) without the re-listen's own activity waking the original selector and masking it. A `released` that never completes
  * shows as the leaf's timeout. Run over every registered backend via [[eachBackend]].
  */
class TransportListenerFdReleaseTest extends Test:

    import AllowUnsafe.embrace.danger

    "closing an idle listener releases its listen fd so the port can be re-bound once released completes" - eachBackend { transport =>
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
            val port = listener.port
            assert(!listener.released.done(), "released completed before close was called")
            // Idle close: no client ever connected, so nothing other than the close itself can wake the driver.
            listener.close()
            listener.released.safe.get.andThen {
                transport.listen("127.0.0.1", port, 16)(_ => ()).safe.get.map { reListener =>
                    reListener.close()
                    reListener.released.safe.get.andThen(assert(reListener.port == port))
                }
            }
        }
    }

    // Awaiting a fiber links the awaiter's interrupt to it. `released` reports a fact about the descriptor, so an awaiter that gives up,
    // a caller's timeout or an interrupted fiber, must not be able to settle it: every other awaiter would then see the release "done"
    // with the descriptor still open.
    "an awaiter that is interrupted does not settle released" - eachBackend { transport =>
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
            val released = listener.released.safe
            for
                awaiter <- Fiber.initUnscoped(released.get)
                _       <- assertEventually(released.waiters.map(_ >= 1))
                _       <- awaiter.interrupt
                _       <- awaiter.getResult
                early   <- released.done
                _       <- Sync.Unsafe.defer(listener.close())
                result  <- released.getResult
            yield
                assert(!early, "an interrupted awaiter settled released before the listener was even closed")
                assert(result == Result.succeed(()), s"released must complete with success once the descriptor is gone, got $result")
            end for
        }
    }

end TransportListenerFdReleaseTest
