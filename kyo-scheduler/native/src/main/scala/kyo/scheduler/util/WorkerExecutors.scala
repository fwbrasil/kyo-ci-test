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

/** Native worker/clock/timer executors that keep the scheduler's OS-thread population static and serialize thread init.
  *
  * scala-native 0.5.12 has a GC race in `MutatorThread_init`: a thread starting during a collection can claim a block the collector is
  * reclaiming, silently corrupting the heap (seen on CI as a `YamlEventReader` over-read) and crashing with SIGSEGVs. Fix: pre-start the
  * core workers while the main thread is the only allocator, never reap them, and gate every scheduler thread creation through one latch
  * so at most one `MutatorThread_init` runs at a time. Native-only workaround for the unreleased upstream fix.
  */
private[scheduler] object WorkerExecutors {

    // One shared init gate across worker, clock, and timer threads. Each `newThread` waits (bounded) for the previous scheduler
    // thread to reach `run()` (after its `MutatorThread_init`), so no two initialize concurrently. Creation is rare and cheap.
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
