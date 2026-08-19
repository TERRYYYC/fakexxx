package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import name.caiyao.fakegps.integration.v1.ContractException
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_UID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

/**
 * §10 concurrency rows owned by the qwy provider lane (INV-13/14/16).
 * Real threads, real races: the store's serialized read-modify-write is the
 * property under test, not a mocked lock.
 */
class ConcurrencyMatrixTest {

    private class Racer(private val h: ProviderHarness) {
        val receipts: MutableList<ApplyReceiptV1> = Collections.synchronizedList(mutableListOf())
        val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

        fun race(vararg calls: () -> ApplyReceiptV1) {
            val start = CountDownLatch(1)
            val done = CountDownLatch(calls.size)
            calls.forEach { call ->
                Thread {
                    start.await()
                    try {
                        receipts.add(call())
                    } catch (t: Throwable) {
                        failures.add(t)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue("race finished", done.await(30, TimeUnit.SECONDS))
        }
    }

    /** M-CC-03: two callers race for a conflicting lease → exactly one receipt, one typed LEASE_CONFLICT. */
    @Test
    fun M_CC_03() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.pair(OTHER_PKG, OTHER_SIGNER)

        val racer = Racer(h)
        racer.race(
            { h.apply(uid = AUTO_UID, key = "cc03-auto") },
            { h.apply(uid = OTHER_UID, key = "cc03-other") },
        )

        assertEquals("exactly one winner", 1, racer.receipts.size)
        assertEquals("exactly one loser", 1, racer.failures.size)
        val failure = racer.failures.single()
        assertTrue("loser failed typed, got $failure", failure is ContractException)
        assertEquals(ContractErrorCodeV1.LEASE_CONFLICT, (failure as ContractException).code)
        assertEquals("environment applied exactly once", 1, h.env.applyCount)
    }

    /** M-CC-04: same key + different payload racing → one execution, one typed conflict, never a second apply. */
    @Test
    fun M_CC_04() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)

        val intentA = h.intent(attemptId = "att-a")
        val intentB = h.intent(attemptId = "att-b")

        val racer = Racer(h)
        racer.race(
            { h.handler.apply(AUTO_UID, ApplyRequestV1(intentA, "cc04-shared-key", 1)) },
            { h.handler.apply(AUTO_UID, ApplyRequestV1(intentB, "cc04-shared-key", 1)) },
        )

        assertEquals("exactly one execution", 1, racer.receipts.size)
        assertEquals("exactly one rejection", 1, racer.failures.size)
        val failure = racer.failures.single()
        assertTrue("typed failure, got $failure", failure is ContractException)
        assertEquals(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT, (failure as ContractException).code)
        assertEquals("second payload never executed", 1, h.env.applyCount)
    }
}
