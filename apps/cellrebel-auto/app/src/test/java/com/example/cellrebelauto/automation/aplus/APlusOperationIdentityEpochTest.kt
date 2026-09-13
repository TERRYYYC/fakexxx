package com.example.cellrebelauto.automation.aplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #179 design-plan test 1 + RELEASE symmetry: the A+ idempotency key is derived from
 * (planEpoch, attemptId); a NULL epoch reproduces the legacy literal BYTE FOR BYTE (the
 * mid-upgrade in-flight-attempt compatibility rule), and every non-null epoch yields a key that
 * is structurally different from — and never equal to — any legacy key.
 *
 * # 幂等键计划纪元：同纪元同 attempt 必同键；异纪元必异键；null 纪元 = 旧字面量逐字节一致
 */
class APlusOperationIdentityEpochTest {

    @Test
    fun `same epoch and attempt always derive the same key`() {
        for (epoch in listOf(null, 1726200000000L, 4102444800000L)) {
            assertEquals(
                APlusOperationIdentity.applyIdempotencyKey(5L, epoch),
                APlusOperationIdentity.applyIdempotencyKey(5L, epoch)
            )
            assertEquals(
                APlusOperationIdentity.releaseIdempotencyKey(5L, epoch),
                APlusOperationIdentity.releaseIdempotencyKey(5L, epoch)
            )
        }
    }

    @Test
    fun `same attempt under different epochs derives different keys`() {
        val epochA = 1726200000000L
        val epochB = 1765100000000L
        assertNotEquals(
            APlusOperationIdentity.applyIdempotencyKey(5L, epochA),
            APlusOperationIdentity.applyIdempotencyKey(5L, epochB)
        )
        assertNotEquals(
            APlusOperationIdentity.releaseIdempotencyKey(5L, epochA),
            APlusOperationIdentity.releaseIdempotencyKey(5L, epochB)
        )
    }

    @Test
    fun `null epoch reproduces the exact legacy key literals`() {
        // The pre-#179 literals, spelled out here so a key-format regression cannot hide.
        assertEquals("auto-aplus-apply-5", APlusOperationIdentity.applyIdempotencyKey(5L, null))
        assertEquals("auto-aplus-release-5", APlusOperationIdentity.releaseIdempotencyKey(5L, null))
        assertEquals("auto-aplus-apply-61", APlusOperationIdentity.applyIdempotencyKey(61L, null))
        assertEquals("auto-aplus-release-61", APlusOperationIdentity.releaseIdempotencyKey(61L, null))
    }

    @Test
    fun `epoch keys are structurally distinct from every legacy key`() {
        val legacyApply = "auto-aplus-apply-5"
        val legacyRelease = "auto-aplus-release-5"
        for (epoch in listOf(1L, 1000L, 1726200000000L, Long.MAX_VALUE)) {
            assertNotEquals(legacyApply, APlusOperationIdentity.applyIdempotencyKey(5L, epoch))
            assertNotEquals(legacyRelease, APlusOperationIdentity.releaseIdempotencyKey(5L, epoch))
            // The epoch sits BETWEEN prefix and id: an epoch key can never re-address a receipt
            // minted under the legacy single-id key (old receipts are harmless sediment).
        }
        assertNotEquals(
            APlusOperationIdentity.applyIdempotencyKey(5L, 5L), // even when the epoch spells "-5-5"
            legacyApply
        )
    }

    @Test
    fun `RELEASE carries the epoch symmetrically so ADVANCE cannot reuse a legacy apply key`() {
        // Owner ruling on design open question 2: the release key is epoch-symmetric. An
        // epoch-stamped attempt's ADVANCE reuses its (epoch) apply key — which can never equal
        // the pre-reset legacy receipt key, closing the g54-style wrap-around conflict.
        val epoch = 1726200000000L
        assertEquals(
            APlusOperationIdentity.applyIdempotencyKey(5L, epoch),
            APlusOperationIdentity.applyIdempotencyKey(5L, epoch) // ADVANCE reuses THIS key
        )
        assertNotEquals(
            "auto-aplus-apply-5",
            APlusOperationIdentity.applyIdempotencyKey(5L, epoch)
        )
        assertNotEquals(
            "auto-aplus-release-5",
            APlusOperationIdentity.releaseIdempotencyKey(5L, epoch)
        )
        // Apply and release stay distinct domains under the same epoch.
        assertNotEquals(
            APlusOperationIdentity.applyIdempotencyKey(5L, epoch),
            APlusOperationIdentity.releaseIdempotencyKey(5L, epoch)
        )
    }
}
