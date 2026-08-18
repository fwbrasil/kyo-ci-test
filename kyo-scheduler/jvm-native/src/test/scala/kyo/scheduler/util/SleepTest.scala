package kyo.scheduler.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.scheduler.BlockingMonitor
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Seconds
import org.scalatest.time.Span

/** Covers what Sleep owes a caller: it suspends, and it comes back.
  *
  * Out of scope here: that the Native implementation keeps using nanosleep rather than the
  * pipe-based Thread.sleep. That choice shows itself as timer jitter under thread contention and
  * as pipe descriptors held by every probe in flight, neither of which is a state a test can read
  * back, and the descriptors are closed per call so a leak check at rest sees nothing either. The
  * regulator's use of the real probe stays covered by ConcurrencyTest.
  */
class SleepTest extends AnyFreeSpec with NonImplicitAssertions {

    "suspends the calling thread" in {
        // What Sleep owes its caller is suspension. The scheduler's own detector reports a thread
        // as blocked once its user CPU time stops advancing, which holds for a thread parked
        // inside Sleep and not for one that returned from it or is spinning, so the detector
        // states the property as thread state rather than as elapsed time. The duration passed to
        // Sleep is an input, not a bound: nothing here reads how much of it goes by.
        val entered  = new CountDownLatch(1)
        val returned = new AtomicBoolean(false)
        val threadId = new AtomicLong(0L)
        val thread = new Thread((() => {
            threadId.set(ThreadUserTime.currentThreadId())
            entered.countDown()
            // The interrupt at the end of the test is how this thread is released on platforms
            // where Sleep is interruptible; catching it keeps the release quiet.
            try {
                Sleep(30000)
                returned.set(true)
            } catch {
                case _: InterruptedException => ()
            }
        }): Runnable)
        thread.setDaemon(true)
        thread.start()
        try {
            assert(entered.await(30, TimeUnit.SECONDS), "the sleeping thread should have started")
            val detector = new BlockingMonitor(1)
            val ids      = Array(threadId.get())
            eventually(timeout(scaled(Span(30, Seconds)))) {
                detector.sample(ids, 1)
                assert(detector.isBlocked(0), "a thread inside Sleep should read as blocked")
            }
            assert(!returned.get(), "Sleep returned instead of suspending its caller")
        } finally {
            thread.interrupt()
            ()
        }
    }

    "returns" - {
        "for a zero duration" in assertReturns(0)

        "for a positive duration" in assertReturns(50)
    }

    /** Runs `Sleep(ms)` on its own thread and asserts it hands control back.
      *
      * Completion is the whole observable of a Sleep that returns: how long it took is a property
      * of the host's timer and scheduler, not of Sleep. The await bound is the give-up valve for a
      * Sleep that never returns at all, which is the only failure there is to catch here.
      */
    private def assertReturns(ms: Int): Unit = {
        val returned = new CountDownLatch(1)
        val thread = new Thread((() => {
            Sleep(ms)
            returned.countDown()
        }): Runnable)
        thread.setDaemon(true)
        thread.start()
        val _ = assert(returned.await(30, TimeUnit.SECONDS), s"Sleep($ms) did not return")
    }
}
