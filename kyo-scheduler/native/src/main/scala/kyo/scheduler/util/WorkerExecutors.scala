package kyo.scheduler.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Native worker/clock/timer executors that keep the scheduler's OS-thread population static and serialize thread initialization.
  *
  * scala-native 0.5.12 has a GC race in `MutatorThread_init`: a thread that begins while a collection is in flight can claim a heap block
  * the collector is concurrently reclaiming, and the newcomer's freshly written object then overlaps another thread's live object. The window
  * opens only when a thread starts DURING allocation load; it surfaces as intermittent silent heap corruption (an array's length-header word
  * overwritten by a pointer, observed on CI as a `YamlEventReader` bytes over-read) and native SIGSEGVs. Eliminate the geometry: pre-start the
  * core worker threads while the main thread is still the only allocator, never reap them, and route every scheduler thread creation through
  * one shared latch so at most one `MutatorThread_init` is ever in flight (including the growth path). The JVM keeps the historical
  * cached-pool behavior (see the jvm variant); this is a native-only workaround for the upstream defect, whose fix is promised but unreleased.
  */
private[scheduler] object WorkerExecutors {

    // One shared init gate across worker, clock, and timer threads. Each `newThread` waits (bounded) for the previously created scheduler
    // thread to reach `run()`, which happens after its `MutatorThread_init` at OS-thread entry, so no two scheduler threads initialize
    // concurrently. `MALLOC`-cheap: a latch handoff per thread creation, and creation is rare (pre-start, then only blocked-carrier growth).
    private val gateLock                     = new Object
    private var previousInit: CountDownLatch = new CountDownLatch(0)

    private def gated(base: ThreadFactory): ThreadFactory =
        new ThreadFactory {
            def newThread(r: Runnable): Thread =
                gateLock.synchronized {
                    val prev = previousInit
                    // Bounded so a stalled predecessor cannot deadlock startup; init is sub-millisecond.
                    prev.await(1, TimeUnit.SECONDS)
                    val done = new CountDownLatch(1)
                    previousInit = done
                    base.newThread(new Runnable {
                        def run(): Unit = {
                            done.countDown()
                            r.run()
                        }
                    })
                }
        }

    def worker(factory: ThreadFactory, coreWorkers: Int, maxWorkers: Int): ExecutorService = {
        val core = Math.max(1, coreWorkers)
        val max  = Math.max(core, maxWorkers)
        val exec = new ThreadPoolExecutor(core, max, Long.MaxValue, TimeUnit.NANOSECONDS, new SynchronousQueue[Runnable], gated(factory))
        exec.allowCoreThreadTimeOut(false)
        exec.prestartAllCoreThreads()
        exec
    }

    def clock(factory: ThreadFactory): ExecutorService =
        Executors.newSingleThreadExecutor(gated(factory))

    def timer(corePoolSize: Int, factory: ThreadFactory): ScheduledExecutorService = {
        val exec = new ScheduledThreadPoolExecutor(corePoolSize, gated(factory))
        exec.prestartAllCoreThreads()
        exec
    }
}
