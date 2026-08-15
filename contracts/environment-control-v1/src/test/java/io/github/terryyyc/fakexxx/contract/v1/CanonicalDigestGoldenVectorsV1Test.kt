package io.github.terryyyc.fakexxx.contract.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Golden vectors for the three canonical digest domains (§6.3.1, §6.7.3).
 *
 * **Nothing in this file may read a production constant.** That is the entire
 * point, and it is what the previous framing test got wrong: it derived both the
 * expected length and the expected text from
 * `CanonicalDigestV1.DOMAIN_ADVANCE_REQUEST` itself, so editing that constant to
 * any other ASCII string left the assertion green. A test that computes its
 * expectation from the thing under test asserts self-consistency, never the
 * frozen value — it cannot fail in the one direction it exists to catch.
 *
 * So every expectation below is a literal: the domain text spelled out, its
 * `uint32be` length prefix written as a number, and a SHA-256 pinned as hex.
 * The digests were produced by an **independent** implementation of §6.3.1's
 * framing (`uint32be(len) || bytes`, no separators, domain first) written from
 * the spec rather than from this codebase. If production and that independent
 * implementation agree on all four vectors, two separately-derived readings of
 * the frozen algorithm agree; if they ever diverge, exactly one of them changed
 * and this file says which vector moved.
 *
 * Renaming a domain, reordering a field, changing the framing width, or dropping
 * the domain from the first position all change these bytes, and all of them are
 * silent everywhere else in the suite.
 */
class CanonicalDigestGoldenVectorsV1Test {

    // ------------------------------------------------------------------ literals
    // Spelled out on purpose. Do NOT replace these with CanonicalDigestV1.DOMAIN_*.

    private val intentDomain = "fakexxx:contract:v1:intent"
    private val requestDomain = "fakexxx:contract:v1:advance-request"
    private val receiptDomain = "fakexxx:contract:v1:advance-receipt"

    // ------------------------------------------------------------------ fixtures
    // KB-8: coordinates left the intent and its preimage (the provider is the
    // sole coordinate authority), so the intent vector below changed at that
    // removal — that is the one deliberate vector move this file is allowed to
    // have, and it was re-derived from the spec by the independent
    // implementation, not copied out of the production code.

    private val intent = EnvironmentIntentV1(
        runId = "run-1",
        attemptId = "attempt-1",
        profileRef = "profile-1",
        scheduleRef = "schedule-1",
        requiredVerificationWire = 2,
        notBeforeEpochMs = 1_000L,
        deadlineEpochMs = 2_000L,
    )

    private val request = CompleteAndAdvanceRequestV1(
        leaseId = "lease-1",
        idempotencyKey = "idem-1",
        requestDigest = "",
        expectedScheduleId = "schedule-1",
        expectedScheduleVersion = 4L,
        expectedCurrentItemId = "item-7",
        completionProof = CompletionProofV1(
            scheduleItemId = "item-7",
            trustedSuccessCount = 12,
            quotaRequired = 12,
            ledgerRef = "ledger-abc",
            verifiedAtElapsedRealtimeMs = 640_000L,
        ),
        callerProtocolVersion = 1,
    )

    private fun receipt(outcome: Int, to: String?) = AdvanceReceiptV1(
        outcomeWire = outcome,
        advancedFromItemId = "item-7",
        advancedToItemId = to,
        scheduleVersionAfter = 5L,
        effectiveIntentHash = "e".repeat(64),
        effectiveEnvironmentRevision = 9L,
        receiptDigest = "",
    )

    private val originatingDigest = "a".repeat(64)
    private val idempotencyKey = "idem-1"

    // ------------------------------------------------------------------ framing

    /**
     * The domain tag is the FIRST framed field of every preimage, and its length
     * prefix is four big-endian bytes. Asserted per domain against a literal.
     */
    private fun assertFirstFieldIs(expected: String, bytes: ByteArray) {
        val len = expected.length
        assertEquals("length prefix byte 0", 0, bytes[0].toInt())
        assertEquals("length prefix byte 1", 0, bytes[1].toInt())
        assertEquals("length prefix byte 2", 0, bytes[2].toInt())
        assertEquals("length prefix byte 3", len, bytes[3].toInt())
        assertEquals(
            "domain must be the first framed field, spelled exactly",
            expected,
            String(bytes, 4, len, Charsets.US_ASCII),
        )
    }

    @Test
    fun `intent preimage starts with its literal domain`() {
        assertEquals("intent domain length", 26, intentDomain.length)
        assertFirstFieldIs(intentDomain, CanonicalIntentDigestV1.canonicalBytes(intent))
    }

    @Test
    fun `advance-request preimage starts with its literal domain`() {
        assertEquals("advance-request domain length", 35, requestDomain.length)
        assertFirstFieldIs(requestDomain, CanonicalAdvanceDigestV1.canonicalBytes(request))
    }

    @Test
    fun `advance-receipt preimage starts with its literal domain`() {
        assertEquals("advance-receipt domain length", 35, receiptDomain.length)
        assertFirstFieldIs(
            receiptDomain,
            CanonicalAdvanceReceiptDigestV1.canonicalBytes(
                receipt(AdvanceOutcomeV1.ADVANCED.wire, "item-8"),
                originatingDigest,
                idempotencyKey,
            ),
        )
    }

    /**
     * The two advance domains are the same LENGTH (35). Distinctness therefore
     * cannot come from the length prefix — only from the bytes — which is exactly
     * the case a length-only check would wave through.
     */
    @Test
    fun `the two advance domains are distinguished by bytes, not by length`() {
        assertEquals(requestDomain.length, receiptDomain.length)
        assertNotEquals(requestDomain, receiptDomain)
    }

    // ------------------------------------------------------------------ goldens

    @Test
    fun `intent digest matches its golden vector`() {
        assertEquals(
            "6b0e33f0b21856715172b2a03c887095a63159d0fa8bc177722785f6f2bea8de",
            CanonicalIntentDigestV1.compute(intent),
        )
    }

    @Test
    fun `advance-request digest matches its golden vector`() {
        assertEquals(
            "7e90dbd9a0fc51c8565799451250b74cecf94b7f79b1f0fe0ecb8a5226df170d",
            CanonicalAdvanceDigestV1.compute(request),
        )
    }

    @Test
    fun `advance-receipt digest matches its golden vector when the target is present`() {
        assertEquals(
            "0c01a054576e95b676e6b53a4a1206fe1f55c2d11b837d0e1a87f2137d644bf7",
            CanonicalAdvanceReceiptDigestV1.compute(
                receipt(AdvanceOutcomeV1.ADVANCED.wire, "item-8"),
                originatingDigest,
                idempotencyKey,
            ),
        )
    }

    /**
     * The exhausted receipt: presence discriminator `"0"` followed by nothing.
     * Pinned separately so the absent branch has its own frozen bytes rather than
     * being covered only by "it differs from the present one".
     */
    @Test
    fun `advance-receipt digest matches its golden vector when the target is absent`() {
        assertEquals(
            "8bb286b3953cda2a7d7dadb7b76ff08311a3550316364797d7ecfa6a413d6701",
            CanonicalAdvanceReceiptDigestV1.compute(
                receipt(AdvanceOutcomeV1.EXHAUSTED.wire, null),
                originatingDigest,
                idempotencyKey,
            ),
        )
    }
}
