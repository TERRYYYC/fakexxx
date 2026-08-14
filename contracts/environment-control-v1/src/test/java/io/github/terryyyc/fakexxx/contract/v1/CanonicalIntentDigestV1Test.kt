package io.github.terryyyc.fakexxx.contract.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical intent digest, spec §6.3.1.
 *
 * The digest is the load-bearing half of INV-23: it is what proves a trusted
 * completion belongs to *this* attempt's schedule identity. Both apps compute it
 * independently, so its encoding has to be injective and stable.
 *
 * ## KB-8: coordinates removed
 *
 * `latitude` / `longitude` were removed from the preimage. The provider is the
 * sole coordinate authority; the intent binds only schedule identity, not a
 * coordinate the consumer has no authority to assert. All coordinate-specific
 * tests (fixedPoint7, negative zero, rounding) are removed because the digest
 * no longer covers coordinates.
 */
class CanonicalIntentDigestV1Test {

    private fun intent(
        runId: String = "run-1",
        attemptId: String = "attempt-1",
        profileRef: String = "profile-1",
        scheduleRef: String = "schedule-1",
        requiredVerificationWire: Int = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        notBeforeEpochMs: Long = 1_786_000_000_000L,
        deadlineEpochMs: Long = 1_786_000_600_000L,
    ) = EnvironmentIntentV1(
        runId = runId,
        attemptId = attemptId,
        profileRef = profileRef,
        scheduleRef = scheduleRef,
        requiredVerificationWire = requiredVerificationWire,
        notBeforeEpochMs = notBeforeEpochMs,
        deadlineEpochMs = deadlineEpochMs,
    )

    /**
     * The reason the encoding is length-prefixed rather than delimiter-joined.
     *
     * With any fixed delimiter, a free-form string field can absorb the
     * delimiter and shift a boundary. Joined with '\n',
     * `runId="a\nb", attemptId="c"` and `runId="a", attemptId="b\nc"` produce
     * byte-identical canonical forms, so two different intents share one
     * `acceptedIntentHash` and the INV-23 binding is bypassed.
     *
     * Length prefixes make the encoding injective, so this cannot depend on
     * whether a field happens to contain the delimiter at runtime.
     */
    @Test
    fun `delimiter collision pair produces different digests`() {
        val a = intent(runId = "a\nb", attemptId = "c")
        val b = intent(runId = "a", attemptId = "b\nc")

        assertNotEquals(
            "distinct intents must not share a digest",
            CanonicalIntentDigestV1.compute(a),
            CanonicalIntentDigestV1.compute(b),
        )
    }

    /** Same collision shape, moved to the other pair of adjacent free-form fields. */
    @Test
    fun `delimiter collision pair on profile and schedule refs produces different digests`() {
        val a = intent(profileRef = "p\nq", scheduleRef = "s")
        val b = intent(profileRef = "p", scheduleRef = "q\ns")

        assertNotEquals(
            CanonicalIntentDigestV1.compute(a),
            CanonicalIntentDigestV1.compute(b),
        )
    }

    @Test
    fun `digest is deterministic across repeated computation`() {
        val i = intent()
        assertEquals(CanonicalIntentDigestV1.compute(i), CanonicalIntentDigestV1.compute(i))
    }

    @Test
    fun `digest is lowercase hex sha256`() {
        val digest = CanonicalIntentDigestV1.compute(intent())
        assertEquals(64, digest.length)
        assertTrue("expected lowercase hex, got $digest", digest.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `every field participates in the digest`() {
        val base = CanonicalIntentDigestV1.compute(intent())
        val variants = mapOf(
            "runId" to intent(runId = "run-2"),
            "attemptId" to intent(attemptId = "attempt-2"),
            "profileRef" to intent(profileRef = "profile-2"),
            "scheduleRef" to intent(scheduleRef = "schedule-2"),
            "requiredVerificationWire" to intent(
                requiredVerificationWire = VerificationLevelV1.HOOK_UNVERIFIED.wire,
            ),
            "notBeforeEpochMs" to intent(notBeforeEpochMs = 1_786_000_000_001L),
            "deadlineEpochMs" to intent(deadlineEpochMs = 1_786_000_600_001L),
        )
        variants.forEach { (field, variant) ->
            assertNotEquals(
                "changing $field must change the digest",
                base,
                CanonicalIntentDigestV1.compute(variant),
            )
        }
    }

    /** Multi-byte and control characters must survive UTF-8 encoding identically. */
    @Test
    fun `multibyte and control characters are stable`() {
        val exotic = intent(
            runId = "运行-\ttab",
            attemptId = "尝试-🐾",
            profileRef = "profile\nnewline",
            scheduleRef = "schedule\u0000nul",
        )
        assertEquals(
            CanonicalIntentDigestV1.compute(exotic),
            CanonicalIntentDigestV1.compute(exotic.copy()),
        )
        assertNotEquals(
            CanonicalIntentDigestV1.compute(exotic),
            CanonicalIntentDigestV1.compute(exotic.copy(attemptId = "尝试-🐈")),
        )
    }
}
