package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/** Regression coverage for #78: VERIFIED observations must cite durable QWY evidence. */
class ObservationEvidenceRefTest {

    @Test
    fun observe_returnsDurableReferenceToThisObservation() {
        val harness = ProviderHarness.create()
        harness.pair()
        val receipt = harness.apply(key = "observe-evidence-apply")
        val request = ObserveRequestV1(
            leaseId = receipt.leaseId,
            operationId = "observe-evidence-op",
            expectedIntentHash = receipt.acceptedIntentHash,
        )

        val observation = harness.handler.observe(AUTO_UID, request)

        assertEquals("one audit row must back this observation", 1, observation.evidenceRefs.size)
        val match = Regex("^qwy:audit:([1-9]\\d*)$").matchEntire(observation.evidenceRefs.single())
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
        assertEquals(
            "audit digest must bind this exact observation payload",
            QwyObservationEvidenceDigest.compute(observation),
            event.payloadDigest,
        )
        assertNotEquals(
            "changing an observed field must change the backing digest",
            event.payloadDigest,
            QwyObservationEvidenceDigest.compute(
                observation.copy(environmentFingerprint = "different-fingerprint"),
            ),
        )
    }

    @Test
    fun observe_auditWriteFails_returnsNoUnbackedObservation() {
        val harness = ProviderHarness.create()
        harness.pair()
        val receipt = harness.apply(key = "observe-evidence-failing-apply")
        val auditBefore = harness.audit.all()
        harness.kv.failOnWrite = { namespace, key ->
            namespace == "integration.v1.audit" && key.startsWith("evt:")
        }

        try {
            harness.handler.observe(
                AUTO_UID,
                ObserveRequestV1(
                    leaseId = receipt.leaseId,
                    operationId = "observe-evidence-failing-op",
                    expectedIntentHash = receipt.acceptedIntentHash,
                ),
            )
            fail("observe must fail closed when its evidence row is not durable")
        } catch (expected: SimulatedWriteCrash) {
            // A returned observation with a non-resolvable ref would be a lie.
        } finally {
            harness.kv.failOnWrite = null
        }

        assertEquals("failed audit transaction must append nothing", auditBefore, harness.audit.all())
    }
}
