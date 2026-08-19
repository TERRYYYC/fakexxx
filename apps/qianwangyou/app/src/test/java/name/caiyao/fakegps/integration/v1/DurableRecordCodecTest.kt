package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §7.2 authoritative durable fields must round-trip an empty free string
 * DISTINCTLY from null across an owner restart (Terra PR#22 round-5). "" and
 * null are different facts; a sentinel codec (`?: ""` on write, `.ifEmpty { null }`
 * on read) collapses them, losing a frozen field. The shared codec encodes null
 * with a presence discriminator, so both survive.
 */
class DurableRecordCodecTest {

    private fun leaseStore(kv: InMemoryDurableKv) = EnvironmentLeaseStore(kv, FakeMonotonicClock())

    private fun baseLease() = LeaseRecord(
        leaseId = "L1",
        callerApplicationId = "C",
        callerSignerDigest = "S",
        acceptedIntentHash = "H",
        state = LeaseState.RELEASED,
        applyIdempotencyKey = "AK",
        startingEnvironmentRevision = 1L,
        deadlineElapsedRealtimeMs = 100L,
        applyOwnerGeneration = 1L,
    )

    @Test
    fun leaseStore_emptyNullableFields_surviveAsEmptyNotNull() {
        val kv = InMemoryDurableKv()
        leaseStore(kv).put(
            baseLease().copy(
                leaseId = "L-empty",
                releaseIdempotencyKey = "", // a valid empty free string, NOT null
                recoveryEvidenceRef = "",
            ),
        )
        // Read through a FRESH store over the same kv — the owner restart path.
        val got = leaseStore(kv).get("L-empty")!!
        assertEquals("empty release key survives, not collapsed to null", "", got.releaseIdempotencyKey)
        assertEquals("empty evidence ref survives, not collapsed to null", "", got.recoveryEvidenceRef)
    }

    @Test
    fun leaseStore_nullNullableFields_surviveAsNull() {
        val kv = InMemoryDurableKv()
        leaseStore(kv).put(
            baseLease().copy(
                leaseId = "L-null",
                releaseIdempotencyKey = null,
                recoveryEvidenceRef = null,
            ),
        )
        val got = leaseStore(kv).get("L-null")!!
        assertNull("null release key stays null", got.releaseIdempotencyKey)
        assertNull("null evidence ref stays null", got.recoveryEvidenceRef)
    }

    @Test
    fun auditStore_emptyOperationId_surviveAsEmptyNotNull() {
        val kv = InMemoryDurableKv()
        DurableIntegrationAuditStore(kv, FakeMonotonicClock())
            .append("advance", callerApplicationId = "C", leaseId = "L", operationId = "")
        val got = DurableIntegrationAuditStore(kv, FakeMonotonicClock()).all().single()
        assertEquals("empty operationId (a caller free-string key) survives, not null", "", got.operationId)
    }

    @Test
    fun auditStore_nullCorrelationIds_surviveAsNull() {
        val kv = InMemoryDurableKv()
        DurableIntegrationAuditStore(kv, FakeMonotonicClock()).append("boot")
        val got = DurableIntegrationAuditStore(kv, FakeMonotonicClock()).all().single()
        assertNull(got.callerApplicationId)
        assertNull(got.leaseId)
        assertNull(got.operationId)
        assertNull(got.payloadDigest)
    }
}
