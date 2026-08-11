package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SUPPLEMENTARY RED LANE — INV-18 (issue #4 Done list; not a §10 matrix row).
 * Append-only, correlation ids, no pairing secrets, durable order.
 */
class AuditContractTest {

    @Test
    fun audit_appendOnly_monotonicSeq_correlated_durable() {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val store = DurableIntegrationAuditStore(kv, clock)

        val e1 = store.append("pairing_candidate_recorded", callerApplicationId = "come.xx.fakeaauto")
        clock.advance(10)
        val e2 = store.append("apply_admitted", callerApplicationId = "come.xx.fakeaauto", leaseId = "lease-1", operationId = "op-1")
        clock.advance(10)
        val e3 = store.append("release_receipt", leaseId = "lease-1", operationId = "op-2", payloadDigest = "d".repeat(64))

        assertTrue("seq strictly monotonic", e1.seq < e2.seq && e2.seq < e3.seq)

        val readBack = store.all()
        assertEquals("append order preserved", listOf(e1, e2, e3), readBack)
        assertEquals("correlation ids carried", "lease-1", readBack[1].leaseId)

        // Durability: a rebuilt store over the same KV sees the same stream and continues the seq.
        val reopened = DurableIntegrationAuditStore(kv, clock)
        assertEquals(readBack, reopened.all())
        val e4 = reopened.append("post_restart_event")
        assertTrue("seq continues after restart, never reused", e4.seq > e3.seq)
    }

    @Test
    fun audit_carriesNoPairingSecrets() {
        val kv = InMemoryDurableKv()
        val store = DurableIntegrationAuditStore(kv, FakeMonotonicClock())
        val secretSigner = "signer-auto-1"

        // The append surface intentionally has no signer parameter; events about
        // pairing reference the caller by applicationId only.
        store.append("pairing_approved", callerApplicationId = "come.xx.fakeaauto")
        store.append("pairing_revoked", callerApplicationId = "come.xx.fakeaauto")

        store.all().forEach { event ->
            val flat = listOf(
                event.event,
                event.callerApplicationId.orEmpty(),
                event.leaseId.orEmpty(),
                event.operationId.orEmpty(),
                event.payloadDigest.orEmpty(),
            ).joinToString("|")
            assertTrue("no raw signer material in audit: $flat", !flat.contains(secretSigner))
        }
    }
}
