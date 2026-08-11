package io.github.terryyyc.fakexxx.contract.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advance digest is what makes §6.7.4's preconditions enforceable.
 *
 * Idempotent replay decides "this is the same request" from key + digest. Every
 * negative below is therefore a real guard, not a shape check: if the digest
 * failed to separate two requests, replay would answer one with the other's
 * receipt, and wire 14/15 would still exist while no longer distinguishing the
 * cases they are named after.
 */
class CanonicalAdvanceDigestV1Test {

    private fun proof(
        itemId: String = "item-7",
        trusted: Int = 12,
        quota: Int = 12,
        ledger: String = "ledger-abc",
    ) = CompletionProofV1(
        scheduleItemId = itemId,
        trustedSuccessCount = trusted,
        quotaRequired = quota,
        ledgerRef = ledger,
        verifiedAtElapsedRealtimeMs = 640_000L,
    )

    private fun request(
        itemId: String = "item-7",
        version: Long = 3L,
        lease: String = "lease-1",
        key: String = "idem-1",
        proof: CompletionProofV1 = proof(),
        callerProtocol: Int = ContractV1.PROTOCOL_VERSION,
    ) = CompleteAndAdvanceRequestV1(
        leaseId = lease,
        idempotencyKey = key,
        requestDigest = "",
        expectedScheduleVersion = version,
        expectedCurrentItemId = itemId,
        completionProof = proof,
        callerProtocolVersion = callerProtocol,
    )

    // ------------------------------------------------------------ the three named negatives

    /**
     * WRONG-ITEM. Two requests that differ only in which item the caller believes
     * is current must not share a digest. If they did, a caller holding a stale
     * current item would replay onto the key of a different item's request and be
     * handed that request's receipt — a wrong-item advance that replay itself
     * caused.
     */
    @Test
    fun `wrong-item - differing expectedCurrentItemId must change the digest`() {
        assertNotEquals(
            CanonicalAdvanceDigestV1.compute(request(itemId = "item-7")),
            CanonicalAdvanceDigestV1.compute(request(itemId = "item-8")),
        )
    }

    /**
     * SKIP / STALE VERSION. The schedule moving under Auto while it proved quota
     * must change the digest, otherwise a completion proved against one ordering
     * is replayed as though it belonged to another.
     */
    @Test
    fun `stale-version - differing expectedScheduleVersion must change the digest`() {
        assertNotEquals(
            CanonicalAdvanceDigestV1.compute(request(version = 3L)),
            CanonicalAdvanceDigestV1.compute(request(version = 4L)),
        )
    }

    /**
     * DOUBLE ADVANCE. The other half: an identical request must produce an
     * identical digest, because that is what lets the provider recognise a retry
     * and return the stored receipt instead of advancing a second time. A digest
     * that varied per call would turn every retry into a new advance.
     */
    @Test
    fun `double - an identical request must produce an identical digest`() {
        assertEquals(
            CanonicalAdvanceDigestV1.compute(request()),
            CanonicalAdvanceDigestV1.compute(request()),
        )
    }

    // ------------------------------------------------------------ framing and domain

    /**
     * The §6.3.1 collision, in the advance fields. With any fixed separator,
     * leaseId="a|b", item="c" and leaseId="a", item="b|c" would encode
     * identically. Length prefixes must keep them apart.
     */
    @Test
    fun `field boundaries cannot be moved by a value containing a delimiter`() {
        assertNotEquals(
            CanonicalAdvanceDigestV1.compute(request(lease = "a|b", itemId = "c")),
            CanonicalAdvanceDigestV1.compute(request(lease = "a", itemId = "b|c")),
        )
        assertNotEquals(
            CanonicalAdvanceDigestV1.compute(request(lease = "a\nb", itemId = "c")),
            CanonicalAdvanceDigestV1.compute(request(lease = "a", itemId = "b\nc")),
        )
    }

    /** Every preimage must start with its own domain tag (§6.3.1 v1.38). */
    @Test
    fun `each digest carries its own domain`() {
        val bytes = CanonicalAdvanceDigestV1.canonicalBytes(request())
        val domain = CanonicalDigestV1.DOMAIN_ADVANCE_REQUEST.toByteArray(Charsets.US_ASCII)
        assertEquals("length prefix of the domain", domain.size, bytes[3].toInt())
        assertEquals(
            CanonicalDigestV1.DOMAIN_ADVANCE_REQUEST,
            String(bytes, 4, domain.size, Charsets.US_ASCII),
        )
        assertNotEquals(
            "the three domains must be distinct",
            CanonicalDigestV1.DOMAIN_ADVANCE_REQUEST,
            CanonicalDigestV1.DOMAIN_ADVANCE_RECEIPT,
        )
    }

    /**
     * Same field values under different domains must not collide. Without the
     * domain tag the two preimages would live in one space and correctness would
     * rest on no crafted input ever crossing over.
     */
    @Test
    fun `identical fields under different domains do not collide`() {
        val fields = listOf(CanonicalDigestV1.utf8("x"), CanonicalDigestV1.utf8("y"))
        assertNotEquals(
            CanonicalDigestV1.digest(CanonicalDigestV1.DOMAIN_INTENT, fields),
            CanonicalDigestV1.digest(CanonicalDigestV1.DOMAIN_ADVANCE_REQUEST, fields),
        )
    }

    /** callerProtocolVersion is deliberately outside the preimage (§6.3.4). */
    @Test
    fun `a caller upgrading mid-retry is not a false conflict`() {
        assertEquals(
            CanonicalAdvanceDigestV1.compute(request(callerProtocol = 1)),
            CanonicalAdvanceDigestV1.compute(request(callerProtocol = 2)),
        )
    }

    // ------------------------------------------------------------ receipt binding

    private fun receipt(
        outcome: Int = AdvanceOutcomeV1.ADVANCED.wire,
        to: String? = "item-8",
        digest: String = "",
    ) = AdvanceReceiptV1(
        outcomeWire = outcome,
        advancedFromItemId = "item-7",
        advancedToItemId = to,
        scheduleVersionAfter = 4L,
        effectiveIntentHash = "e".repeat(64),
        effectiveEnvironmentRevision = 9L,
        receiptDigest = digest,
    )

    /**
     * A receipt must bind the request it answers. Otherwise a retry cannot tell
     * "the stored answer to MY request" from "some other answer the provider
     * happened to have".
     */
    @Test
    fun `receipt digest changes with the request it answers`() {
        val r = receipt()
        assertNotEquals(
            CanonicalAdvanceReceiptDigestV1.compute(r, requestDigest = "req-a", idempotencyKey = "k1"),
            CanonicalAdvanceReceiptDigestV1.compute(r, requestDigest = "req-b", idempotencyKey = "k1"),
        )
        assertNotEquals(
            CanonicalAdvanceReceiptDigestV1.compute(r, requestDigest = "req-a", idempotencyKey = "k1"),
            CanonicalAdvanceReceiptDigestV1.compute(r, requestDigest = "req-a", idempotencyKey = "k2"),
        )
    }

    /** A receipt whose digest does not recompute is not a weaker receipt. */
    @Test
    fun `verify rejects a receipt whose contents were changed after signing`() {
        val signed = receipt().let {
            it.copy(receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(it, "req-a", "k1"))
        }
        assertTrue(CanonicalAdvanceReceiptDigestV1.verify(signed, "req-a", "k1"))

        // Provider claims it advanced somewhere else, keeping the old digest.
        val tampered = signed.copy(advancedToItemId = "item-99")
        assertFalse(CanonicalAdvanceReceiptDigestV1.verify(tampered, "req-a", "k1"))

        // Same receipt replayed against a different request must not verify.
        assertFalse(CanonicalAdvanceReceiptDigestV1.verify(signed, "req-b", "k1"))
    }

    /**
     * No id can be read as absence.
     *
     * The first encoding used a magic sentinel and justified it with "no real id
     * looks like this" -- an assumption nothing enforces, so an id equal to the
     * sentinel collided with null. The presence discriminator removes that
     * obligation entirely, so this walks ids chosen to be hostile to it,
     * including the exact old sentinel.
     */
    @Test
    fun `no schedule item id can collide with absence`() {
        val absent = CanonicalAdvanceReceiptDigestV1.compute(
            receipt(outcome = AdvanceOutcomeV1.EXHAUSTED.wire, to = null), "req-a", "k1",
        )
        val hostile = listOf("", "0", "1", "null", "\u0000null", "0\u0000", "1item-8")
        for (id in hostile) {
            assertNotEquals(
                "id must not digest as absence: " + id,
                absent,
                CanonicalAdvanceReceiptDigestV1.compute(
                    receipt(outcome = AdvanceOutcomeV1.EXHAUSTED.wire, to = id), "req-a", "k1",
                ),
            )
        }
    }

    /**
     * Exhausted is null, not empty. If null encoded as "", a receipt saying
     * "advanced to nothing" and one saying "advanced to an item whose id is
     * empty" would be the same bytes.
     */
    @Test
    fun `a null target does not collide with an empty target`() {
        assertNotEquals(
            CanonicalAdvanceReceiptDigestV1.compute(
                receipt(outcome = AdvanceOutcomeV1.EXHAUSTED.wire, to = null), "req-a", "k1",
            ),
            CanonicalAdvanceReceiptDigestV1.compute(
                receipt(outcome = AdvanceOutcomeV1.EXHAUSTED.wire, to = ""), "req-a", "k1",
            ),
        )
    }
}
