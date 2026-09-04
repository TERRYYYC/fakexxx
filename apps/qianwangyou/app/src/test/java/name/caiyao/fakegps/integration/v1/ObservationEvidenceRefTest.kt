package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for #78: VERIFIED observations must cite durable QWY evidence. */
class ObservationEvidenceRefTest {

    @Test
    fun observe_returnsDurableReferenceToThisObservation() {
        val harness = ProviderHarness.create()
        harness.pair()
        // Mirror the production adapter, which currently supplies no refs.
        harness.env.evidenceRefs = emptyList()
        val receipt = harness.apply(key = "observe-evidence-apply")
        val request = ObserveRequestV1(
            leaseId = receipt.leaseId,
            operationId = "observe-evidence-op",
            expectedIntentHash = receipt.acceptedIntentHash,
        )

        val observation = harness.handler.observe(AUTO_UID, request)

        assertEquals("one audit row must back this observation", 1, observation.evidenceRefs.size)
        val match = Regex("^qwy:audit:(\\d+)$").matchEntire(observation.evidenceRefs.single())
        assertNotNull("evidence ref must use qwy:audit:<seq>", match)
        val referencedSeq = match!!.groupValues[1].toLong()

        // Reopen over the same durable KV: the returned ref must survive the
        // process that produced it and resolve to this exact operation.
        val event = DurableIntegrationAuditStore(harness.kv, harness.clock)
            .all()
            .single { it.seq == referencedSeq }
        assertEquals("observe", event.event)
        assertEquals(AUTO_PKG, event.callerApplicationId)
        assertEquals(receipt.leaseId, event.leaseId)
        assertEquals(request.operationId, event.operationId)
        assertTrue(
            "audit row must bind the observation payload with a SHA-256 digest",
            event.payloadDigest?.matches(Regex("^[0-9a-f]{64}$")) == true,
        )
    }
}
