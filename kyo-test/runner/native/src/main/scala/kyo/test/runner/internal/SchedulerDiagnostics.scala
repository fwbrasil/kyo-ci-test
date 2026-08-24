package kyo.test.runner.internal

import java.util.concurrent.atomic.AtomicBoolean
import kyo.scheduler.Scheduler
import kyo.scheduler.top.Printer

/** Native-only leaf-timeout instrumentation (diagnostic branch, thrown away after one CI run).
  *
  * On Scala Native a kyo-test leaf timeout emits `kyo.internal.Diagnostics.dumpAll()`, but with nothing registered it printed
  * "(no dumpers registered)". This registers a dumper named "scheduler-workers" so the STUCK-leaf hang report carries the full
  * scheduler worker table (`Printer.apply(Scheduler.get.status())`), a one-line count summary, and a falsification line that tells
  * a parked-fiber hang apart from a stranded worker strand.
  *
  * Cross-thread stack traces are unavailable on Scala Native (`getStackTrace` returns empty cross-thread), so the signal is
  * per-worker state + load + the ThreadPoolExecutor active/pool counts, none of which need another thread's stack.
  */
private[runner] object SchedulerDiagnostics:

    private val registered = new AtomicBoolean(false)

    /** Register the "scheduler-workers" dumper exactly once, even if the runner is created more than once per JVM/Native process. */
    def ensureRegistered(): Unit =
        if registered.compareAndSet(false, true) then
            val _ = kyo.internal.Diagnostics.register("scheduler-workers")(dump = () => dump())
    end ensureRegistered

    private def dump(): String =
        val status = Scheduler.get.status()
        val table  = Printer(status)
        val summary =
            s"currentWorkers=${status.currentWorkers} allocatedWorkers=${status.allocatedWorkers} " +
                s"getActiveCount=${status.activeThreads} getPoolSize=${status.totalThreads}"
        // Check ALL allocated workers, not just id < currentWorkers: the hypothesized strand is a worker at
        // id >= currentWorkers (out of every scan after a regulator shrink), so restricting to the active set
        // would mislabel exactly that case as ALL-IDLE. Any allocated worker that is running or holds queued
        // load means a worker strand is present.
        val allocated = status.workers.filter(_ ne null)
        val allIdle   = allocated.nonEmpty && allocated.forall(w => !w.running && w.load == 0)
        val strandedInactive =
            allocated.exists(w => w.id >= status.currentWorkers && (w.running || w.load > 0))
        val falsification =
            if allIdle then
                "ALL-IDLE: no worker strand, stuck party is a parked fiber (check promise/Latch)"
            else if strandedInactive then
                "STRANDED-INACTIVE: a worker at id >= currentWorkers holds load or is running (out-of-scan strand)"
            else
                "NOT-ALL-IDLE: an active worker is running or holds queued load"
        s"$table\n$summary\n$falsification\n"
    end dump

end SchedulerDiagnostics
