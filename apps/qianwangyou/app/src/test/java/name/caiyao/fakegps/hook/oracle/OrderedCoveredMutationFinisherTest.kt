package name.caiyao.fakegps.hook.oracle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OrderedCoveredMutationFinisherTest {

    @Test
    fun `QWY finish barrier cannot return before causally earlier covered completion drains`() {
        val finisher = OrderedCoveredMutationFinisher("covered-finisher-test")
        val callers = Executors.newSingleThreadExecutor()
        val coveredStarted = CountDownLatch(1)
        val releaseCovered = CountDownLatch(1)
        val barrierReturned = CountDownLatch(1)
        try {
            finisher.execute {
                coveredStarted.countDown()
                check(releaseCovered.await(5, TimeUnit.SECONDS))
            }
            assertTrue(coveredStarted.await(5, TimeUnit.SECONDS))

            val barrier = callers.submit {
                finisher.awaitDrained()
                barrierReturned.countDown()
            }
            assertFalse(barrierReturned.await(100, TimeUnit.MILLISECONDS))

            releaseCovered.countDown()
            assertTrue(barrierReturned.await(5, TimeUnit.SECONDS))
            barrier.get(5, TimeUnit.SECONDS)
        } finally {
            releaseCovered.countDown()
            callers.shutdownNow()
            finisher.close()
        }
    }

    @Test
    fun `stalled covered completion times out and retires finisher`() {
        val finisher = OrderedCoveredMutationFinisher(
            "covered-finisher-stall-test",
            100,
            TimeUnit.MILLISECONDS,
        )
        val coveredStarted = CountDownLatch(1)
        val releaseCovered = CountDownLatch(1)
        try {
            finisher.execute {
                coveredStarted.countDown()
                runCatching { releaseCovered.await(5, TimeUnit.SECONDS) }
            }
            assertTrue(coveredStarted.await(5, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            val failure = assertThrows(IllegalStateException::class.java) {
                finisher.awaitDrained()
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(failure.message.orEmpty().contains("timed out"))
            assertTrue("barrier must fail within a bounded interval", elapsedMillis < 2_000)
            assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
                finisher.execute {}
            }
        } finally {
            releaseCovered.countDown()
            finisher.close()
        }
    }

    @Test
    fun `timeout retires running and queued accepted actions exactly once`() {
        val finisher = OrderedCoveredMutationFinisher(
            "covered-finisher-accepted-actions-test",
            100,
            TimeUnit.MILLISECONDS,
        )
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val firstRetired = AtomicInteger()
        val secondRan = AtomicInteger()
        val secondRetired = AtomicInteger()
        try {
            finisher.execute(
                {
                    firstStarted.countDown()
                    while (releaseFirst.count > 0L) {
                        try {
                            releaseFirst.await(50, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // Model a framework sampler that ignores interruption and unwinds late.
                        }
                    }
                    firstFinished.countDown()
                },
                { firstRetired.incrementAndGet() },
            )
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            finisher.execute(
                { secondRan.incrementAndGet() },
                { secondRetired.incrementAndGet() },
            )

            assertThrows(IllegalStateException::class.java) { finisher.awaitDrained() }

            assertEquals(1, firstRetired.get())
            assertEquals(1, secondRetired.get())
            assertEquals(0, secondRan.get())

            releaseFirst.countDown()
            assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
            assertEquals("late unwind must not retire the running token twice", 1, firstRetired.get())
            assertEquals(1, secondRetired.get())
        } finally {
            releaseFirst.countDown()
            finisher.close()
        }
    }
}
