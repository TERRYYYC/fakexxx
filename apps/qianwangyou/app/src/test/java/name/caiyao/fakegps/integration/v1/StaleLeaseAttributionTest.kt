package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-15: the four §6.7.4b step-3b rejection branches of completeAndAdvance are
 * SPECIES of wire STALE_LEASE(8) (taxonomy frozen v1.75/v1.76/v1.77, contract
 * exact 00e2396). Before F-15 the species verdict lived only inside
 * diagnosticMessage crossing Binder — the C5 probe surfaced PROVIDER_ERROR_8
 * alone (740 lines of logcat, zero hits for unproven/foreign/wrong-item), so
 * F-12 could not tell WHICH branch rejected. These rows pin the two F-15
 * invariants separately:
 *
 *  1. DISTINGUISHABILITY — the four branches map to four MUTUALLY EXCLUSIVE
 *     species tokens, pairwise distinct (not "some log was emitted");
 *  2. WIRE PRESERVATION — each [StaleLeaseAttribution.Rejected.message] is
 *     the historical handler throw string VERBATIM, so the diagnosticMessage
 *     bytes crossing Binder do not change and the wire stays 8.
 *
 * The handler-level wiring (store → attributeLease → DiagnosticLog seam →
 * throw) is pinned in AdvanceProviderRedTest's four 3b rows, which assert the
 * recorded line through the real handler.
 */
class StaleLeaseAttributionTest {

    private val caller = CallerIdentity(
        uid = 10101,
        applicationId = "come.xx.fakeaauto",
        signerDigest = "signer-auto-1",
    )

    private fun row(
        leaseId: String = "lease-1",
        applicationId: String = caller.applicationId,
        signerDigest: String = caller.signerDigest,
        earnedScheduleRef: String? = "item-1",
    ) = LeaseRecord(
        leaseId = leaseId,
        callerApplicationId = applicationId,
        callerSignerDigest = signerDigest,
        acceptedIntentHash = "hash-1",
        state = LeaseState.RELEASED,
        applyIdempotencyKey = "apply-key-1",
        startingEnvironmentRevision = 1L,
        deadlineElapsedRealtimeMs = 100L,
        applyOwnerGeneration = 1L,
        earnedScheduleRef = earnedScheduleRef,
    )

    private fun reject(row: LeaseRecord?): StaleLeaseAttribution.Rejected =
        attributeLease(row, "lease-1", caller, expectedCurrentItemId = "item-1")
            as StaleLeaseAttribution.Rejected

    // --- 1. species per branch -------------------------------------------------

    @Test
    fun missingProviderRecord_isUnprovenNoProviderRecord() {
        val verdict = reject(row = null)
        assertEquals(StaleLeaseSpecies.UNPROVEN_NO_PROVIDER_RECORD, verdict.species)
    }

    @Test
    fun otherCallerPrincipal_isForeignCaller() {
        val verdict = reject(row(signerDigest = "signer-other-1"))
        assertEquals(StaleLeaseSpecies.FOREIGN_CALLER, verdict.species)
    }

    @Test
    fun legacyRowWithoutItemAttribution_isUnprovenNoOriginatingItem() {
        val verdict = reject(row(earnedScheduleRef = null))
        assertEquals(StaleLeaseSpecies.UNPROVEN_NO_ORIGINATING_ITEM, verdict.species)
    }

    @Test
    fun quotaEarnedForAnotherItem_isWrongItem() {
        val verdict = reject(row(earnedScheduleRef = "item-2"))
        assertEquals(StaleLeaseSpecies.WRONG_ITEM, verdict.species)
    }

    // --- 2. the four tokens are mutually exclusive ------------------------------

    /**
     * THE F-15 closure row: not "logging exists" but the four branches yield
     * four DISTINCT tokens — anything collapses (shared token, dropped
     * branch) and F-12 forensics is blind again, this test goes red.
     */
    @Test
    fun fourBranches_yieldFourPairwiseDistinctTokens() {
        val tokens = listOf(
            reject(null).species,
            reject(row(signerDigest = "signer-other-1")).species,
            reject(row(earnedScheduleRef = null)).species,
            reject(row(earnedScheduleRef = "item-2")).species,
        ).map { it.logToken }
        assertEquals("four branches, four distinct species tokens", 4, tokens.toSet().size)
    }

    // --- 3. wire preservation: messages are the historical strings, verbatim ----

    @Test
    fun messages_areTheHistoricalHandlerStrings_verbatim() {
        assertEquals(
            "leaseId lease-1 unproven: no provider record of it (forged or never earned)",
            reject(null).message,
        )
        assertEquals(
            "leaseId lease-1 is foreign: earned by another caller",
            reject(row(signerDigest = "signer-other-1")).message,
        )
        assertEquals(
            "leaseId lease-1 unproven: durable row has no originating-item attribution",
            reject(row(earnedScheduleRef = null)).message,
        )
        assertEquals(
            "leaseId lease-1 earned quota for item item-2, not item-1 (wrong-item)",
            reject(row(earnedScheduleRef = "item-2")).message,
        )
    }

    // --- 4. the logcat line is greppable and names the branch -------------------

    @Test
    fun speciesLogLine_carriesStableKeyOperationLeaseAndItem() {
        val line = staleLeaseSpeciesLogLine(
            StaleLeaseSpecies.WRONG_ITEM,
            leaseId = "lease-9",
            expectedCurrentItemId = "item-3",
        )
        assertTrue("stable grep key", line.contains("STALE_LEASE_SPECIES=WRONG_ITEM"))
        assertTrue("operation context", line.contains("op=completeAndAdvance"))
        assertTrue("lease correlation", line.contains("leaseId=lease-9"))
        assertTrue("item context", line.contains("expectedItem=item-3"))
    }

    // --- 5. the pass-through branch ----------------------------------------------

    @Test
    fun callerOwnedRowEarnedForExpectedItem_isAttributed() {
        val verdict = attributeLease(
            row(earnedScheduleRef = "item-1"),
            "lease-1",
            caller,
            expectedCurrentItemId = "item-1",
        )
        assertEquals(
            StaleLeaseAttribution.Attributed(
                row = row(earnedScheduleRef = "item-1"),
                earnedScheduleRef = "item-1",
            ),
            verdict,
        )
    }
}
