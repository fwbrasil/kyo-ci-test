package kyo.scheduler.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.scheduler.BlockingMonitor
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import scala.scalanative.meta.LinktimeInfo
import scala.util.control.NonFatal

/** Holds the Native Sleep to nanosleep by the descriptors it does not allocate.
  *
  * Scala Native's Thread.sleep parks on a pipe: it creates one, polls the read end for the whole
  * duration, and closes both ends on the way out. Every call in flight therefore pins two
  * descriptors. The concurrency regulator calls Sleep on every jitter probe, so a regression from
  * nanosleep back to Thread.sleep puts that cost on a hot path inside a process already spending its
  * descriptor budget on sockets, and the probe degrades under the contention it caused. Nothing in
  * SleepTest would notice: suspension and return read the same either way, and the descriptors are
  * released by the time a call returns, so a check at rest sees nothing.
  *
  * Linux publishes the live descriptor table at /proc/self/fd, where a pipe end reads back as a
  * symlink to "pipe:[inode]". That makes the property exact rather than statistical: with a thread
  * held inside Sleep, the pipe endpoints the process gained since it entered are either none
  * (nanosleep) or two (Thread.sleep). The probe needs that table, so it runs on Linux Native alone.
  * Windows Sleep is Thread.sleep by design, and macOS does not name pipe descriptors in /dev/fd.
  */
class SleepDescriptorTest extends AnyFreeSpec with NonImplicitAssertions {

    "allocates no pipe descriptor while parked" in {
        if (!LinktimeInfo.isLinux)
            cancel("the descriptor table this probe reads back is published by Linux only")

        val entered  = new AtomicBoolean(false)
        val proceed  = new AtomicBoolean(false)
        val threadId = new AtomicLong(0L)
        val thread = new Thread((() => {
            threadId.set(ThreadUserTime.currentThreadId())
            entered.set(true)
            // Waiting on the barrier by spinning rather than by parking keeps the wait itself free
            // of descriptors, so the baseline taken on the other side of it accounts for everything
            // this thread owns except what Sleep is about to allocate.
            while (!proceed.get()) Thread.onSpinWait()
            // The interrupt at the end of the test is how this thread is released; catching the
            // exception a pipe-based Sleep would raise keeps the release quiet.
            try Sleep(30000)
            catch { case _: InterruptedException => () }
        }): Runnable)
        thread.setDaemon(true)
        thread.start()
        try {
            while (!entered.get()) Thread.onSpinWait()
            val baseline = pipeEndpoints()
            proceed.set(true)
            // Reading the table before the thread is actually inside Sleep would compare against an
            // allocation that has not happened yet, so the read waits on the same blocked-thread
            // signal the scheduler itself acts on: user CPU time that has stopped advancing.
            val detector = new BlockingMonitor(1)
            val ids      = Array(threadId.get())
            eventually(timeout(scaled(Span(30, Seconds)))) {
                detector.sample(ids, 1)
                assert(detector.isBlocked(0), "the probe thread should have reached Sleep")
            }
            val allocated = pipeEndpoints() -- baseline
            assert(
                allocated.isEmpty,
                s"Sleep parked on pipe descriptors instead of nanosleep: $allocated"
            )
        } finally {
            thread.interrupt()
            ()
        }
    }

    /** The pipe endpoints the process currently holds open, named by what they point at.
      *
      * Every open descriptor appears under /proc/self/fd as a symlink to its target, and a pipe end
      * targets "pipe:[inode]". Keying on the target rather than on the descriptor number is what
      * makes a set difference meaningful: numbers are recycled the moment a descriptor closes,
      * inodes are not. A descriptor that closes between the listing and the readlink drops out.
      */
    private def pipeEndpoints(): Set[String] = {
        val entries = new File("/proc/self/fd").list()
        if (entries eq null) Set.empty
        else
            entries.foldLeft(Set.empty[String]) { (acc, fd) =>
                val target =
                    try Files.readSymbolicLink(Paths.get("/proc/self/fd/" + fd)).toString
                    catch { case ex if NonFatal(ex) => "" }
                if (target.startsWith("pipe:")) acc + target else acc
            }
    }
}
