package com.example.cellrebelauto.automation.aplus

import io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * F12 RED tests: kill the `taskId` pseudo-binding on `scheduleRef` and prove
 * crash-recovery byte-identical replay once the durable provider anchor is wired.
 *
 * Root cause: `APlusOperationIdentity.intent()` line 55 builds
 * `scheduleRef = "task-$taskId"` — an Auto-local Room PK, not the provider's
 * schedule identity. The provider's `scheduleDecisionWire(scheduleRef)` compares
 * it against `scheduleId = "qwy-default-schedule"` and returns DENIED on mismatch.
 * The canonical intent digest (§6.3.1) bakes this wrong value into `acceptedIntentHash`,
 * so crash-recovery replaying from the persisted attempt state would produce a
 * digest that differs from what the provider stored on the lease (`earnedScheduleRef`
 * was PR #41's fix target on the provider side; this test covers the Auto side).
 *
 * # F12 RED：杀死 taskId 伪绑定 + 证明基于 provider anchor 的 replay 逐字节一致
 */
class APlusScheduleRefAnchorTest {

    /**
     * RED 1: The `scheduleRef` field must carry the provider's durable schedule
     * anchor (e.g., "qwy-default-schedule"), not an Auto-local `"task-$taskId"`.
     * The provider's `scheduleDecisionWire` compares this value against its own
     * `scheduleId`; a `"task-N"` string would be DENIED on real hardware.
     */
    @Test
    fun `scheduleRef carries the provider schedule anchor not an Auto task PK`() {
        val anchor = "qwy-default-schedule"
        val i = APlusOperationIdentity.intent(
            runSessionId = 5L,
            attemptId = 77L,
            planId = 1L,
            scheduleRef = anchor,
            notBeforeEpochMs = 600L,
            deadlineEpochMs = 90_600L
        )
        assertEquals(
            "scheduleRef must be the verbatim provider anchor, not derived from a task PK",
            anchor,
            i.scheduleRef
        )
        assertFalse(
            "scheduleRef must NOT start with 'task-' (that's the Auto-local pseudo-binding)",
            i.scheduleRef.startsWith("task-")
        )
    }

    /**
     * RED 2: Crash-recovery recompute from the SAME persisted anchor produces
     * byte-identical canonical form (and therefore the same `acceptedIntentHash`).
     * The three legs — wire intent, canonical digest preimage, crash-recovery
     * replay — all source from the same durable `aplusAnchorScheduleId`.
     */
    @Test
    fun `crash recovery recompute produces byte-identical digest from anchored scheduleRef`() {
        val anchor = "qwy-default-schedule"

        // Normal path: build intent with discovered anchor → compute digest
        val normalIntent = APlusOperationIdentity.intent(5L, 77L, 1L, anchor, 600L, 90_600L)
        val normalDigest = APlusOperationIdentity.requestDigest(normalIntent)

        // Recovery path: rebuild from persisted attempt state (same anchor)
        val recoveryIntent = APlusOperationIdentity.intent(5L, 77L, 1L, anchor, 600L, 90_600L)
        val recoveryDigest = APlusOperationIdentity.requestDigest(recoveryIntent)

        // Canonical bytes must be byte-identical (not just same digest)
        val normalBytes = CanonicalIntentDigestV1.canonicalBytes(normalIntent)
        val recoveryBytes = CanonicalIntentDigestV1.canonicalBytes(recoveryIntent)

        assertEquals(
            "normal and recovery paths must produce identical digests (three-leg closure)",
            normalDigest, recoveryDigest
        )
        assertEquals(
            "canonical byte arrays must be identical length",
            normalBytes.size, recoveryBytes.size
        )
        assert(normalBytes.contentEquals(recoveryBytes)) {
            "canonical bytes must be byte-identical across normal and recovery paths"
        }
    }

    /**
     * RED 3: Different anchors produce different digests — the schedule identity
     * feeds into the frozen preimage (§6.3.1) and moves the `acceptedIntentHash`.
     * This is the complement of the existing sensitivity test, but using real
     * provider-format anchor strings instead of Auto-local task PKs.
     */
    @Test
    fun `different provider anchors produce different digests`() {
        val d1 = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(5L, 77L, 1L, "qwy-default-schedule", 600L, 90_600L)
        )
        val d2 = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(5L, 77L, 1L, "qwy-custom-schedule", 600L, 90_600L)
        )
        assert(d1 != d2) {
            "different provider schedule anchors must produce different digests"
        }
    }
}
