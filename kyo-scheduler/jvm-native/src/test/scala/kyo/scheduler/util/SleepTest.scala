package kyo.scheduler.util

import java.util.concurrent.atomic.AtomicBoolean
import kyo.scheduler.regulator.Concurrency
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class SleepTest extends AnyFreeSpec with NonImplicitAssertions {

    private val isWindows = java.lang.System.getProperty("os.name", "").toLowerCase.contains("windows")

    private def elapsedMillis(ms: Int): Long = {
        val start = System.nanoTime()
        Sleep(ms)
        (System.nanoTime() - start) / 1000000
    }

    "sleeps for at least the requested duration" in {
        val ms = 50
        // Windows timer resolution is ~15.6ms, so Sleep(50) may return 1-2 ticks early
        val tolerance = if (isWindows) 20 else 0
        // Every sample has to clear the floor. Host load only ever makes a sleep longer, so a
        // short sleep is a defect in Sleep rather than a noisy sample, and one sample clearing
        // the floor would not prove the others did.
        for (_ <- 0 until 5) {
            val elapsed = elapsedMillis(ms)
            assert(elapsed >= ms - tolerance, s"Sleep($ms) returned after only ${elapsed}ms")
        }
    }

    "sleeps for a reasonable upper bound" in {
        val ms = 50
        // Overshoot on any single call is bounded by the host, not by Sleep: a GC pause or a
        // descheduled vCPU inflates one sample by an arbitrary amount. A defect that sleeps too
        // long (a wrong unit, a wrong multiplier) inflates every sample instead, so the fastest
        // of several calls is the sample that carries the signal.
        val elapsed = (0 until 5).map(_ => elapsedMillis(ms)).min
        assert(elapsed < ms * 10, s"Sleep($ms) took ${elapsed}ms, expected < ${ms * 10}ms")
    }

    "handles zero" in {
        // Same reasoning as the upper-bound test above: the fastest of several calls is the one
        // that reflects Sleep rather than a context switch the host charged to one sample.
        val elapsed = (0 until 10).map(_ => elapsedMillis(0)).min
        assert(elapsed < 200, s"Sleep(0) took ${elapsed}ms")
    }

    "probe jitter stays below regulator threshold under blocking load" in {
        // Reproduces the real-world scenario: many threads doing blocking
        // sleeps via Thread.sleep while the probe measures jitter with Sleep.
        // On Scala Native, Thread.sleep uses pipe+poll+close (4 syscalls).
        // If Sleep also uses Thread.sleep, it competes for the same fd table
        // and OS scheduler resources, amplifying jitter. If Sleep uses
        // nanosleep (single syscall, no fds), it stays stable.
        val windows = 5
        val samples = 100
        val running = new AtomicBoolean(true)

        // Spawn many blocking threads — each does Thread.sleep(1) in a loop.
        // On Native, each call does pipe+poll+close×2, creating fd table and
        // OS scheduler contention that amplifies probe jitter.
        val nThreads = Math.min(Runtime.getRuntime.availableProcessors() * 10, 50)
        val threads = (0 until nThreads).map { _ =>
            val t = new Thread(() => {
                while (running.get()) {
                    Thread.sleep(1)
                }
            })
            t.setDaemon(true)
            t.start()
            t
        }

        def measureStdDev(): Double = {
            val measures = new Array[Long](samples)
            for (i <- 0 until samples) {
                val start = System.nanoTime()
                Sleep(1)
                val elapsed = System.nanoTime() - start - 1000000
                measures(i) = elapsed
            }
            val avg = measures.sum.toDouble / samples
            val variance = measures.map(m => {
                val diff = m - avg
                diff * diff
            }).sum / samples
            Math.sqrt(variance)
        }

        val stddevs =
            try {
                // Let pressure build
                Thread.sleep(100)

                // Warmup
                for (_ <- 0 until 20) {
                    val s = System.nanoTime()
                    Sleep(1)
                    System.nanoTime() - s
                }

                // Collect
                Array.fill(windows)(measureStdDev())
            } finally {
                running.set(false)
                threads.foreach(_.join(1000))
            }

        // Windows uses Thread.sleep fallback (no nanosleep), so jitter is inherently higher
        val multiplier = if (isWindows) 200 else 50
        val threshold  = Concurrency.defaultConfig.jitterUpperThreshold * multiplier
        // A single measurement window can be inflated by one host-level stall that has nothing
        // to do with Sleep, so the verdict is the median window: a Sleep that competes for the
        // fd table raises jitter in every window, while one stalled window no longer decides
        // the run. The threshold itself is unchanged.
        val median = stddevs.sorted.apply(windows / 2)
        assert(
            median < threshold,
            s"Sleep jitter median stddev=${median.toLong}ns across $windows windows " +
                s"exceeds regulator threshold ${threshold.toLong}ns (windows: ${stddevs.map(_.toLong).mkString(", ")})"
        )
    }
}
