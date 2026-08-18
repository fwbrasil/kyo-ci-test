package kyo.scheduler.regulator

import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.TestTimer
import kyo.scheduler.util.Sleep
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration.*

class ConcurrencyTest extends AnyFreeSpec with NonImplicitAssertions {

    "up" in new Context {
        loadAvg = 0.9
        jitter = jitterLowerThreshold

        timer.advanceAndRun(regulateInterval * 2)
        assert(probes == 192)
        assert(updates == List(1, 2))
    }

    "down" in new Context {
        loadAvg = 0.9
        jitter = jitterUpperThreshold * 10

        timer.advanceAndRun(regulateInterval * 2)
        assert(probes == 134)
        assert(updates == List(-1, -2))
    }

    "noop" in new Context {
        loadAvg = 0.9
        jitter = (jitterUpperThreshold * 1.7).toInt

        timer.advanceAndRun(regulateInterval * 2)
        assert(probes == 184)
        assert(updates.isEmpty)
    }

    "probe jitter stays below regulator threshold with real sleep" in {
        val config          = Concurrency.defaultConfig
        val timer           = TestTimer()
        val concurrencyDiff = new AtomicInteger(0)
        val probes          = new AtomicInteger(0)

        val concurrency = new Concurrency(
            () => 0.9,
            diff => { val _ = concurrencyDiff.addAndGet(diff) },
            ms => {
                Sleep(ms)
                val _ = probes.incrementAndGet()
            },
            () => System.nanoTime(),
            timer,
            config
        )

        // Virtual scheduling around a real probe: advanceAndRun fires the collect and regulate
        // tasks on this thread, so every host gets the same number of real Sleep probes and the
        // same number of regulation decisions, and only the measured jitter comes from the real
        // clock. Letting wall time drive the schedule made the decision budget depend on host
        // speed instead: a host slow enough to stretch the wait into one extra regulation cycle
        // added a step large enough to break the floor on its own.
        val cycles = 4
        timer.advanceAndRun(config.regulateInterval * cycles)
        concurrency.stop()

        val expectedProbes = cycles * (config.regulateInterval / config.collectInterval).toInt
        val status         = concurrency.status().regulator
        assert(probes.get() == expectedProbes, s"expected $expectedProbes probes, got ${probes.get()}")
        assert(status.probesCompleted == expectedProbes.toLong, "every probe should have completed")
        assert(status.adjustments == cycles.toLong, s"expected $cycles regulation cycles, got ${status.adjustments}")

        // With stable jitter and high load, the regulator should
        // be scaling UP or staying neutral, not reducing workers.
        // Steps escalate as 1, 2, 3, 5 across the four cycles, so a total under the floor takes
        // a reduction in at least three of them: sustained jitter above the threshold rather
        // than one noisy cycle.
        val totalDiff = concurrencyDiff.get()
        assert(
            totalDiff >= -8,
            s"Concurrency regulator reduced workers by $totalDiff, " +
                s"indicating excessive probe jitter (measured ${status.measurementsJitter.toLong}ns)"
        )
    }

    trait Context {
        val timer                = TestTimer()
        var loadAvg: Double      = 0.8
        var jitter: Long         = 0
        var probes               = 0
        var updates              = Seq.empty[Int]
        val collectWindow        = 200
        val collectInterval      = 10.millis
        val regulateInterval     = 1000.millis
        val jitterUpperThreshold = 1000000
        val jitterLowerThreshold = 800000
        val loadAvgTarget        = 0.8
        val stepExp              = 1.3

        val concurrency =
            new Concurrency(
                () => loadAvg,
                diff => updates :+= diff,
                _ => {
                    probes += 1
                    if (probes % 2 == 0)
                        timer.advance(jitter.nanos)
                },
                () => timer.currentNanos,
                timer,
                Config(
                    collectWindow,
                    collectInterval,
                    regulateInterval,
                    jitterUpperThreshold,
                    jitterLowerThreshold,
                    loadAvgTarget,
                    stepExp
                )
            )
    }
}
