package com.example.cellrebelauto.automation.aplus

import io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43 (Sol GREEN-review-2 F2): CONSUMPTION-TYPE oracles for Auto's intent digest.
 *
 * Sol's surviving mutation: replacing [APlusOperationIdentity.requestDigest] with a CONSTANT kept
 * the whole suite green — the previous digest test only exercised the contract library itself,
 * never Auto's consumption seam. These oracles drive AUTO'S function and pin:
 *  - it is a real 64-hex SHA-256 (never a placeholder string);
 *  - it EQUALS the frozen CanonicalIntentDigestV1 over the same intent (the delegation itself);
 *  - it is deterministic in the durable owner identity and sensitive to attempt/session identity;
 *  - it does NOT vary with coordinates (KB-8: coordinates are not in the preimage).
 *
 * # 消费型 digest oracle：直接驱动 Auto 的 requestDigest，钉死委托冻结算法 + 身份敏感 + KB-8 无坐标
 */
class APlusOperationIdentityDigestTest {

    @Test
    fun `Auto's requestDigest IS the frozen CanonicalIntentDigestV1 over the same owner identity`() {
        val viaAuto = APlusOperationIdentity.requestDigest(39.9, 116.4, attemptId = 77L, runSessionId = 5L)
        val viaContract = CanonicalIntentDigestV1.compute(APlusOperationIdentity.intent(5L, 77L))
        assertEquals(
            "Auto's digest must be EXACTLY the frozen contract digest over the same intent (the delegation seam, Sol GREEN-review-2 F2)",
            viaContract,
            viaAuto
        )
    }

    @Test
    fun `the digest is a real 64-hex SHA-256, never a placeholder string`() {
        for (attemptId in listOf(1L, 77L, 999L)) {
            val d = APlusOperationIdentity.requestDigest(39.9, 116.4, attemptId, 5L)
            assertEquals("64 hex chars (SHA-256)", 64, d.length)
            assertTrue("lowercase hex only: $d", d.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `the digest is deterministic in the durable owner identity`() {
        val d1 = APlusOperationIdentity.requestDigest(39.9, 116.4, attemptId = 77L, runSessionId = 5L)
        val d2 = APlusOperationIdentity.requestDigest(1.0, 2.0, attemptId = 77L, runSessionId = 5L)
        assertEquals(
            "the same owner identity (attempt+session) yields the same digest regardless of coords (KB-8)",
            d1, d2
        )
    }

    @Test
    fun `the digest is sensitive to the attempt identity`() {
        val d77 = APlusOperationIdentity.requestDigest(39.9, 116.4, attemptId = 77L, runSessionId = 5L)
        val d78 = APlusOperationIdentity.requestDigest(39.9, 116.4, attemptId = 78L, runSessionId = 5L)
        assertNotEquals("a different attempt MUST digest differently (INV-13 / attribution)", d77, d78)
    }

    @Test
    fun `the digest is sensitive to the run-session identity`() {
        val dS5 = APlusOperationIdentity.requestDigest(39.9, 116.4, attemptId = 77L, runSessionId = 5L)
        val dS6 = APlusOperationIdentity.requestDigest(39.9, 116.4, attemptId = 77L, runSessionId = 6L)
        assertNotEquals("a different run session MUST digest differently (attribution binding)", dS5, dS6)
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
            APlusOperationIdentity.requestDigest(39.9, 116.4, 77L, 5L)
        )
    }
}
