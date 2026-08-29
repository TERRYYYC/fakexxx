package com.example.cellrebelauto.automation.aplus

import io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43 (Sol GREEN-review-2 F2) / R44 (Sol GREEN-review-3 F2): CONSUMPTION-TYPE oracles for Auto's
 * intent digest.
 *
 * Sol's surviving mutation: replacing [APlusOperationIdentity.requestDigest] with a CONSTANT kept
 * the whole suite green — the previous digest test only exercised the contract library itself,
 * never Auto's consumption seam. These oracles drive AUTO'S function and pin:
 *  - it is a real 64-hex SHA-256 (never a placeholder string);
 *  - it EQUALS the frozen CanonicalIntentDigestV1 over the same intent object (the delegation);
 *  - it is deterministic in the durable owner identity and sensitive to attempt/session identity;
 *  - R44: the plan/task refs and the validity window are REAL preimage inputs (never invented
 *    constants like "auto-profile"/"auto-schedule" or the infinite window) and each moves the digest.
 *
 * # 消费型 digest oracle：直接驱动 Auto 的 requestDigest，钉死委托冻结算法 + 身份敏感 + 真实 refs/窗口
 */
class APlusOperationIdentityDigestTest {

    private fun intent(
        sessionId: Long = 5L,
        attemptId: Long = 77L,
        planId: Long = 1L,
        scheduleRef: String = "qwy-default-schedule",
        notBefore: Long = 600L,
        deadline: Long = 600L + 90_000L
    ) = APlusOperationIdentity.intent(sessionId, attemptId, planId, scheduleRef, notBefore, deadline)

    @Test
    fun `Auto's requestDigest IS the frozen CanonicalIntentDigestV1 over the same intent object`() {
        val intent = intent()
        assertEquals(
            "Auto's digest must be EXACTLY the frozen contract digest over the same intent (the delegation seam, Sol GREEN-review-2 F2)",
            CanonicalIntentDigestV1.compute(intent),
            APlusOperationIdentity.requestDigest(intent)
        )
    }

    @Test
    fun `the digest is a real 64-hex SHA-256, never a placeholder string`() {
        for (attemptId in listOf(1L, 77L, 999L)) {
            val d = APlusOperationIdentity.requestDigest(intent(attemptId = attemptId))
            assertEquals("64 hex chars (SHA-256)", 64, d.length)
            assertTrue("lowercase hex only: $d", d.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `the digest is deterministic in the durable owner identity (crash-recompute stability)`() {
        assertEquals(
            "the same durable owner inputs yield the same digest (normal path == post-crash recompute)",
            APlusOperationIdentity.requestDigest(intent()),
            APlusOperationIdentity.requestDigest(intent())
        )
    }

    @Test
    fun `the digest is sensitive to the attempt identity`() {
        assertNotEquals(
            "a different attempt MUST digest differently (INV-13 / attribution)",
            APlusOperationIdentity.requestDigest(intent(attemptId = 77L)),
            APlusOperationIdentity.requestDigest(intent(attemptId = 78L))
        )
    }

    @Test
    fun `the digest is sensitive to the run-session identity`() {
        assertNotEquals(
            "a different run session MUST digest differently (attribution binding)",
            APlusOperationIdentity.requestDigest(intent(sessionId = 5L)),
            APlusOperationIdentity.requestDigest(intent(sessionId = 6L))
        )
    }

    // R44 (Sol GREEN-review-3 F2): the plan/schedule refs and the validity window are REAL preimage
    // inputs — never invented constants; each must move the digest.
    // F12: scheduleRef is now the provider's durable anchor, not "task-$taskId".
    @Test
    fun `the digest is sensitive to the plan and schedule refs`() {
        assertNotEquals(
            "a different plan ref MUST digest differently",
            APlusOperationIdentity.requestDigest(intent(planId = 1L)),
            APlusOperationIdentity.requestDigest(intent(planId = 2L))
        )
        assertNotEquals(
            "a different schedule ref MUST digest differently",
            APlusOperationIdentity.requestDigest(intent(scheduleRef = "qwy-default-schedule")),
            APlusOperationIdentity.requestDigest(intent(scheduleRef = "qwy-custom-schedule"))
        )
    }

    @Test
    fun `the digest is sensitive to the validity window`() {
        assertNotEquals(
            "a different window start MUST digest differently",
            APlusOperationIdentity.requestDigest(intent(notBefore = 600L)),
            APlusOperationIdentity.requestDigest(intent(notBefore = 700L))
        )
        assertNotEquals(
            "a different deadline MUST digest differently",
            APlusOperationIdentity.requestDigest(intent(deadline = 600L + 90_000L)),
            APlusOperationIdentity.requestDigest(intent(deadline = 600L + 91_000L))
        )
    }

    @Test
    fun `the intent refs and window carry the real identity, never invented constants`() {
        val i = intent(planId = 9L, scheduleRef = "qwy-test-schedule", notBefore = 1234L, deadline = 1234L + 5000L)
        assertEquals("plan-9", i.profileRef)
        assertEquals("qwy-test-schedule", i.scheduleRef)  // F12: verbatim provider anchor
        assertEquals(1234L, i.notBeforeEpochMs)
        assertEquals(1234L + 5000L, i.deadlineEpochMs)
        assertEquals("auto-run-5", i.runId)
        assertEquals("77", i.attemptId)
    }

    @Test
    fun `the release digest is a real frozen-domain digest over the lease`() {
        val d1 = APlusOperationIdentity.releaseDigest("lease-77")
        assertEquals("64 hex chars", 64, d1.length)
        assertNotEquals("lease-bound (different lease ⇒ different digest)", d1, APlusOperationIdentity.releaseDigest("lease-78"))
        // Domain separation from the intent digest.
        assertNotEquals(
            "release digest lives in a DIFFERENT frozen domain than the intent digest",
            d1,
            APlusOperationIdentity.requestDigest(intent())
        )
    }
}
