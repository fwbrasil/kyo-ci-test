package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.AllowUnsafe.embrace.danger

class MpmcUnboundedUnsafeQueueTest extends UnsafeQueueBaseTest:
    def queueName  = "MpmcUnboundedUnsafeQueue"
    def isBounded  = false
    def nProducers = 3
    def nConsumers = 3
    def testSizes  = Seq(8, 16, 64)
    // Use non-pooled for standard concurrent tests: pooled mode has producer spin-waits
    // that require consumer liveness, which conflicts with the test stop mechanism.
    // Pooled mode is tested separately below.
    def makeQueue[A](size: Int): UnsafeQueue[A] = new MpmcUnboundedUnsafeQueue[A](size, maxPooledChunks = 0)

    "MpmcUnboundedUnsafeQueue-specific" - {

        "noPooling" in {
            val q = new MpmcUnboundedUnsafeQueue[Int](8, maxPooledChunks = 0)
            for i <- 0 until 100 do q.offer(i)
            for i <- 0 until 100 do
                assert(q.poll() == Maybe(i))
        }

        "withPooling" in {
            val q = new MpmcUnboundedUnsafeQueue[Int](8, maxPooledChunks = 4)
            // Fill and drain multiple rounds to exercise pooling
            for round <- 0 until 5 do
                for i <- 0 until 20 do q.offer(round * 20 + i)
                for i <- 0 until 20 do
                    assert(q.poll() == Maybe(round * 20 + i), s"round=$round, i=$i")
            end for
        }

        "allPooledCombinations" in {
            for cs <- Seq(8, 16, 32) do
                for mp <- Seq(0, 1, 2, 4) do
                    val q = new MpmcUnboundedUnsafeQueue[Int](cs, maxPooledChunks = mp)
                    for i <- 0 until 100 do q.offer(i)
                    for i <- 0 until 100 do
                        assert(q.poll() == Maybe(i), s"chunkSize=$cs, maxPooled=$mp, i=$i")
        }

        "chunkSizeSmall" in {
            // Small chunkSize gets rounded to 8 (min)
            val q = new MpmcUnboundedUnsafeQueue[Int](1)
            for i <- 0 until 100 do q.offer(i)
            for i <- 0 until 100 do
                assert(q.poll() == Maybe(i))
        }

        "backwardWalkProducerChunk" in {
            // Force backward walk by filling multiple chunks sequentially
            for mp <- Seq(0, 2) do
                val q = new MpmcUnboundedUnsafeQueue[Int](8, maxPooledChunks = mp)
                // Fill across 5 chunks (40 elements for chunk size 8)
                for i <- 0 until 40 do q.offer(i)
                for i <- 0 until 40 do
                    assert(q.poll() == Maybe(i), s"maxPooled=$mp, i=$i")
                assert(q.poll().isEmpty)
        }

        "nullElementThrowsNPE" in {
            val q = new MpmcUnboundedUnsafeQueue[String](8)
            interceptThrown[NullPointerException] {
                q.offer(null)
            }
        }
    }

    "MpmcUnboundedUnsafeQueue-specific concurrent".notJs - {

        "xaddUniqueness" in {
            val q             = new MpmcUnboundedUnsafeQueue[Long](8)
            val perProducer   = 20000
            val producerCount = 3
            val total         = perProducer * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val consumed      = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup           = new AtomicBoolean(false)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var seq = 1
                    while seq <= perProducer do
                        discard(q.offer(pid * 1000000L + seq))
                        seq += 1
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 3).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producersDone.getCount > 0 || !q.isEmpty() do
                        q.poll() match
                            case Maybe.Present(v) =>
                                if consumed.put(v, java.lang.Boolean.TRUE) != null then
                                    dup.set(true)
                            case _ => Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            (producers ++ consumers).foreach(_.join())

            var r = q.poll()
            while r.isDefined do
                if consumed.put(r.get, java.lang.Boolean.TRUE) != null then dup.set(true)
                r = q.poll()

            assert(!dup.get(), "XADD produced duplicate elements")
            assert(consumed.size == total, s"data loss: consumed=${consumed.size}, total=$total")
        }

        "pooledConcurrentNoDuplicates" in {
            val q             = new MpmcUnboundedUnsafeQueue[Long](8, maxPooledChunks = 4)
            val perProducer   = 20000
            val producerCount = 4
            val total         = perProducer * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val consumed      = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup           = new AtomicBoolean(false)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var seq = 1
                    while seq <= perProducer do
                        discard(q.offer(pid * 1000000L + seq))
                        seq += 1
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producersDone.getCount > 0 || !q.isEmpty() do
                        q.poll() match
                            case Maybe.Present(v) =>
                                if consumed.put(v, java.lang.Boolean.TRUE) != null then
                                    dup.set(true)
                            case _ => Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            (producers ++ consumers).foreach(_.join())

            var r = q.poll()
            while r.isDefined do
                if consumed.put(r.get, java.lang.Boolean.TRUE) != null then dup.set(true)
                r = q.poll()

            assert(!dup.get(), "Pooled mode produced duplicate elements")
            assert(consumed.size == total, s"data loss: consumed=${consumed.size}, total=$total")
        }

        "pooledConcurrentNoDataLoss" in {
            val q             = new MpmcUnboundedUnsafeQueue[Long](8, maxPooledChunks = 4)
            val perProducer   = 20000
            val producerCount = 4
            val total         = perProducer.toLong * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val consumed      = new AtomicLong(0)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var i = 0
                    while i < perProducer do
                        discard(q.offer(pid * 100000000L + i))
                        i += 1
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producersDone.getCount > 0 || !q.isEmpty() do
                        q.poll() match
                            case Maybe.Present(_) => discard(consumed.incrementAndGet())
                            case _                => Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            (producers ++ consumers).foreach(_.join())

            var remaining = 0L
            while q.poll().isDefined do remaining += 1

            assert(
                consumed.get() + remaining == total,
                s"Data loss: consumed=${consumed.get()}, remaining=$remaining, total=$total"
            )
        }

        "pooledPeekConsistency" in {
            val q             = new MpmcUnboundedUnsafeQueue[Long](8, maxPooledChunks = 4)
            val perProducer   = 20000
            val producerCount = 3
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val failure       = new AtomicBoolean(false)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var seq = 1
                    while seq <= perProducer do
                        discard(q.offer(pid * 1000000L + seq)) // always > 0
                        seq += 1
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 3).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producersDone.getCount > 0 || !q.isEmpty() do
                        q.peek() match
                            case Maybe.Present(v) =>
                                if v <= 0 then failure.set(true)
                            case _ =>
                        end match
                        q.poll()
                        Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            (producers ++ consumers).foreach(_.join())

            assert(!failure.get(), "Peek returned invalid value in pooled mode")
        }

        "rotationLockContention" in {
            // Many producers forcing concurrent chunk allocation via ROTATION lock
            val q             = new MpmcUnboundedUnsafeQueue[Long](8, maxPooledChunks = 0)
            val perProducer   = 10000
            val producerCount = 8
            val total         = perProducer * producerCount
            val start         = new CountDownLatch(1)
            val producersDone = new CountDownLatch(producerCount)
            val consumed      = new ConcurrentHashMap[Long, java.lang.Boolean]()
            val dup           = new AtomicBoolean(false)

            val producers = (0 until producerCount).map { pid =>
                val t = new Thread(() =>
                    start.await()
                    var seq = 1
                    while seq <= perProducer do
                        discard(q.offer(pid * 1000000L + seq))
                        seq += 1
                    producersDone.countDown()
                )
                t.setDaemon(true)
                t
            }
            val consumers = (0 until 4).map { cid =>
                val t = new Thread(() =>
                    start.await()
                    while producersDone.getCount > 0 || !q.isEmpty() do
                        q.poll() match
                            case Maybe.Present(v) =>
                                if consumed.put(v, java.lang.Boolean.TRUE) != null then
                                    dup.set(true)
                            case _ => Thread.`yield`()
                    end while
                )
                t.setDaemon(true)
                t
            }

            (producers ++ consumers).foreach(_.start())
            start.countDown()
            (producers ++ consumers).foreach(_.join())

            var r = q.poll()
            while r.isDefined do
                if consumed.put(r.get, java.lang.Boolean.TRUE) != null then dup.set(true)
                r = q.poll()

            assert(!dup.get(), "Rotation lock contention caused duplicates")
            assert(consumed.size == total, s"data loss: consumed=${consumed.size}, total=$total")
        }
    }
end MpmcUnboundedUnsafeQueueTest
