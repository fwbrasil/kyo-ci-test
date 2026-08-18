package kyo.scheduler

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

    "currentMillis" in {
        // The clock publishes whatever its time source returns, so a source the test controls pins
        // the reported value exactly, with no reference to real time. `source` is an AtomicLong the
        // test moves; `currentMillis()` must catch up to each value it is set to, which also proves
        // the update loop keeps resampling rather than latching the first reading. Moving the source
        // forward and seeing the report follow is the monotonic property stated as an exact target
        // rather than a bracket against the system clock.
        val source   = new AtomicLong(1_000L)
        val executor = Executors.newSingleThreadExecutor(Threads("test-internal-clock"))
        val clock    = new InternalClock(executor, () => source.get())
        try {
            assert(awaitValue(clock, 1_000L) == 1_000L, "the clock did not publish its source's initial value")
            source.set(2_000L)
            val advanced = awaitValue(clock, 2_000L)
            assert(advanced == 2_000L, "the clock did not resample its source after it moved")
            assert(advanced > 1_000L, s"the report did not move forward with the source, $advanced")
        } finally {
            clock.stop()
            executor.shutdownNow()
            ()
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

    /** Reads the clock until it publishes exactly `target`.
      *
      * The deadline is a give-up valve for an update thread that never reaches the value, not a
      * bound anything is asserted against.
      */
    private def awaitValue(clock: InternalClock, target: Long): Long = {
        val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
        var current  = clock.currentMillis()
        while (current != target) {
            assert(System.nanoTime() < deadline, s"the clock never published $target, last saw $current")
            Thread.`yield`()
            current = clock.currentMillis()
        }
        current
    }
}
