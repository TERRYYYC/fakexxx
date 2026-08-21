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
        earnedScheduleRef = "item-1",
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

    /**
     * #18 adds earnedScheduleRef as provider-internal attribution evidence, but
     * durable rows written by the immediately preceding provider schema have
     * only the original 13 fields. An app upgrade must still decode that row;
     * absence means "unproven" at completeAndAdvance, not a process crash.
     */
    @Test
    fun leaseStore_preAttributionRow_decodesWithUnprovenItemBinding() {
        val kv = InMemoryDurableKv()
        val legacyFields = listOf<String?>(
            "L-legacy", "C", "S", "H", LeaseState.RELEASED.name, "AK",
            "1", "100", "1", null, "", null, null,
        )
        kv.write(
            "integration.v1.leases",
            "lease:L-legacy",
            DurableFieldCodec.encode(legacyFields),
        )

        val got = leaseStore(kv).get("L-legacy")!!
        assertNull("legacy row carries no provable item attribution", got.earnedScheduleRef)
    }

    @Test
    fun idempotencyStore_sameApplicationDifferentSigners_haveIndependentScopes() {
        val kv = InMemoryDurableKv()
        val store = DurableIdempotencyStore(kv)
        fun record(signer: String, digest: String) = OperationReceiptRecord(
            callerApplicationId = "C",
            callerSignerDigest = signer,
            operation = ContractOperation.APPLY,
            idempotencyKey = "K",
            requestDigest = digest,
            resultDigest = "",
            receiptPayload = "payload-$signer",
            createdAtElapsedRealtimeMs = 1L,
        )

        store.record(record("S1", "D1"))
        store.record(record("S2", "D2"))

        assertEquals("D1", store.find("C", "S1", ContractOperation.APPLY, "K")?.requestDigest)
        assertEquals("D2", store.find("C", "S2", ContractOperation.APPLY, "K")?.requestDigest)
    }

    /**
     * The previous seven-field receipt schema remains decodable, but carries
     * no signer proof. Handler-level migration must validate its lease before
     * replaying or rewriting it under a full-principal key.
     */
    @Test
    fun idempotencyStore_prePrincipalScopeRow_decodesAsUnattributed() {
        val kv = InMemoryDurableKv()
        val legacyKey = DurableFieldCodec.encode(
            listOf("C", ContractOperation.APPLY.name, "K"),
        )
        kv.write(
            DurableIdempotencyStore.RECEIPT_NAMESPACE,
            legacyKey,
            DurableFieldCodec.encode(
                listOf("C", ContractOperation.APPLY.name, "K", "D", "", "1", "payload"),
            ),
        )

        val got = DurableIdempotencyStore(kv).find("C", "S1", ContractOperation.APPLY, "K")!!
        assertEquals("D", got.requestDigest)
        assertNull("legacy receipt has no signer attribution", got.callerSignerDigest)
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
