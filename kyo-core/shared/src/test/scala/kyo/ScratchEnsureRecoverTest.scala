package kyo

// SCRATCH: diagnosis only, to be deleted. Does a panic raised inside `Abort.recover(onFail, onPanic)` reach `onPanic` when
// the recover sits inside `Sync.ensure`?
class ScratchEnsureRecoverTest extends kyo.test.Test[Any]:

    val boom = new RuntimeException("boom")

    def mapped(t: Throwable): Nothing < Abort[String] = Abort.fail("mapped")

    "recover inside ensure, panic raised directly" in {
        Abort.run[String](Sync.ensure(()) {
            Abort.recover[Nothing](onFail = (never: Nothing) => never, onPanic = mapped)(Abort.panic(boom): Int < Abort[Nothing])
        }).map(r => assert(r == Result.fail("mapped"), s"got $r"))
    }

    "recover inside ensure, panic raised at a fiber join" in {
        for
            f <- Fiber.initUnscoped(Abort.panic(boom): Int < Abort[Nothing])
            r <- Abort.run[String](Sync.ensure(()) {
                Abort.recover[Nothing](onFail = (never: Nothing) => never, onPanic = mapped)(f.get)
            })
        yield assert(r == Result.fail("mapped"), s"got $r")
    }

    "ensure inside recover, panic raised at a fiber join" in {
        for
            f <- Fiber.initUnscoped(Abort.panic(boom): Int < Abort[Nothing])
            r <- Abort.run[String](
                Abort.recover[Nothing](onFail = (never: Nothing) => never, onPanic = mapped)(Sync.ensure(())(f.get))
            )
        yield assert(r == Result.fail("mapped"), s"got $r")
    }

    "recover inside Sync.Unsafe.ensure inside Sync.Unsafe.defer, panic raised at a fiber join" in {
        for
            f <- Fiber.initUnscoped(Abort.panic(boom): Int < Abort[Nothing])
            r <- Abort.run[String](Sync.Unsafe.defer {
                Sync.Unsafe.ensure(()) {
                    Abort.recover[Nothing](onFail = (never: Nothing) => never, onPanic = mapped)(f.use(identity))
                }
            })
        yield assert(r == Result.fail("mapped"), s"got $r")
    }

    // The joiner has parked before the panic arrives, so it is resumed rather than finding the promise already done.
    "recover inside ensure, joiner parked before the panic arrives" in {
        for
            p <- Promise.init[Int, Any]
            joiner <- Fiber.initUnscoped(Abort.run[String](Sync.ensure(()) {
                Abort.recover[Nothing](onFail = (never: Nothing) => never, onPanic = mapped)(p.get)
            }))
            _ <- assertEventually(p.waiters.map(_ >= 1))
            _ <- Sync.Unsafe.defer(p.unsafe.completeDiscard(Result.Panic(boom)))
            r <- joiner.get
        yield assert(r == Result.fail("mapped"), s"got $r")
    }

    "ensure inside recover, joiner parked before the panic arrives" in {
        for
            p <- Promise.init[Int, Any]
            joiner <- Fiber.initUnscoped(Abort.run[String](
                Abort.recover[Nothing](onFail = (never: Nothing) => never, onPanic = mapped)(Sync.ensure(())(p.get))
            ))
            _ <- assertEventually(p.waiters.map(_ >= 1))
            _ <- Sync.Unsafe.defer(p.unsafe.completeDiscard(Result.Panic(boom)))
            r <- joiner.get
        yield assert(r == Result.fail("mapped"), s"got $r")
    }

end ScratchEnsureRecoverTest
