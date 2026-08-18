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
        // Each published tick is a System.currentTimeMillis reading taken by the update thread
        // after the previous value was read and before the new one was observed, so system
        // readings taken around that window bracket it. Bracketing pins the reported time to
        // real time without depending on how long anything took: the assertions hold whether
        // the tick lands in a microsecond or after a scheduling stall.
        for (_ <- 0 until 5) {
            val systemBefore = System.currentTimeMillis()
            val previous     = clock.currentMillis()
            val tick         = awaitTick(clock, previous)
            val systemAfter  = System.currentTimeMillis()
            assert(tick > previous, s"clock went backwards, from $previous to $tick")
            assert(tick >= systemBefore, s"clock reported $tick, sampled before the watch started at $systemBefore")
            assert(tick <= systemAfter, s"clock reported $tick, ahead of the system clock's $systemAfter")
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
