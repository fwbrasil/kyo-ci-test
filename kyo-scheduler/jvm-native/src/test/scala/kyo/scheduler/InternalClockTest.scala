package kyo.scheduler

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class InternalClockTest extends AnyFreeSpec with NonImplicitAssertions {

    "stop" in {
        // The update loop exits once it observes the stop flag, so a terminated executor is the
        // exact signal that no further update can be published and the reported time is frozen.
        // The clock runs on its own executor here because that termination is what proves it,
        // and the shared pool never terminates.
        val executor = Executors.newSingleThreadExecutor(Threads("test-internal-clock"))
        val clock    = new InternalClock(executor)
        try {
            val ticked = awaitTick(clock, clock.currentMillis())
            clock.stop()
            executor.shutdown()
            assert(executor.awaitTermination(30, TimeUnit.SECONDS), "the update loop should exit after stop()")
            val frozen = clock.currentMillis()
            assert(frozen >= ticked)
            assert(clock.currentMillis() == frozen, "a stopped clock reports the last published timestamp")
        } finally {
            clock.stop()
            executor.shutdownNow()
            ()
        }
    }

    "currentMillis" in withClock { clock =>
        // A published tick is a System.currentTimeMillis reading, so system readings taken around
        // the window it was sampled in bracket it. That pins the reported time to real time
        // without depending on how long anything took: the assertions hold whether a tick lands
        // in a microsecond or after a scheduling stall.
        //
        // The bracket is asserted on the second tick because the update thread samples the system
        // clock and publishes it as two steps, and can be descheduled in between. Observing the
        // first tick only proves its publish came after `previous` was read, not its sample, so
        // it can carry a value taken before the watch started. The second tick's sample follows
        // the first tick's publish in the update thread's own program order, so it is provably
        // taken after `systemBefore`.
        for (_ <- 0 until 5) {
            val systemBefore = System.currentTimeMillis()
            val previous     = clock.currentMillis()
            val first        = awaitTick(clock, previous)
            val second       = awaitTick(clock, first)
            val systemAfter  = System.currentTimeMillis()
            assert(first > previous, s"clock went backwards, from $previous to $first")
            assert(second > first, s"clock went backwards, from $first to $second")
            assert(second >= systemBefore, s"clock reported $second, sampled before the watch started at $systemBefore")
            assert(second <= systemAfter, s"clock reported $second, ahead of the system clock's $systemAfter")
        }
    }

    /** Reads the clock until it publishes a value other than `previous`.
      *
      * The deadline is a give-up valve for an update thread that died, not a bound anything is
      * asserted against.
      */
    private def awaitTick(clock: InternalClock, previous: Long): Long = {
        val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
        var current  = clock.currentMillis()
        while (current == previous) {
            assert(System.nanoTime() < deadline, s"the clock stopped publishing updates at $previous")
            Thread.`yield`()
            current = clock.currentMillis()
        }
        current
    }

    private def withClock[A](testCode: InternalClock => A): A = {
        val clock = new InternalClock(TestExecutors.cached)
        try testCode(clock)
        finally clock.stop()
    }
}
