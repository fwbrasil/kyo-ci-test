package kyo

// SCRATCH, to be deleted: does Scope.acquireRelease lose the resource whenever the acquire parks AFTER producing its
// value, independent of Scope.run? If yes, the ScopeInterruptTest drain-await leaf is one instance of a general
// property of "nothing is delivered at an abandoned join", not a Scope.run defect.
class ScratchAbandonedJoinTest extends kyo.test.Test[Any]:

    "an acquire that joins a promise after producing its value, interrupted at that join" in {
        for
            gate     <- Promise.init[Unit, Any]
            entered  <- Latch.init(1)
            released <- AtomicBoolean.init(false)
            fiber    <- Fiber.initUnscoped {
                Scope.run {
                    Scope.acquireRelease(
                        Sync.defer("handle").map(h => entered.release.andThen(gate.get).andThen(h))
                    )(_ => released.set(true)).andThen(Async.never)
                }
            }
            _ <- entered.await
            _ <- assertEventually(gate.waiters.map(_ >= 1))
            _ <- fiber.interrupt
            _ <- gate.complete(Result.succeed(()))
            _ <- fiber.getResult
            r <- Abort.run[Timeout](Async.timeout(2.seconds)(assertEventually(released.get)))
        yield assert(r.isSuccess, "the value produced before the join never reached the bracket")
    }

    "the same acquire with the join BEFORE the value is produced" in {
        for
            gate     <- Promise.init[Unit, Any]
            entered  <- Latch.init(1)
            released <- AtomicBoolean.init(false)
            fiber    <- Fiber.initUnscoped {
                Scope.run {
                    Scope.acquireRelease(
                        entered.release.andThen(gate.get).andThen(Sync.defer("handle"))
                    )(_ => released.set(true)).andThen(Async.never)
                }
            }
            _ <- entered.await
            _ <- assertEventually(gate.waiters.map(_ >= 1))
            _ <- fiber.interrupt
            _ <- gate.complete(Result.succeed(()))
            _ <- fiber.getResult
            r <- Abort.run[Timeout](Async.timeout(2.seconds)(assertEventually(released.get)))
        yield assert(!r.isSuccess, "nothing was produced before the abandonment, so no release is owed")
    }

end ScratchAbandonedJoinTest
