package com.example.cellrebelauto.recovery

import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R44 (Sol GREEN-review-3 F2): the apply-response validator must (a) bind the provider-accepted
 * intent hash to the digest of the intent we actually SENT, and (b) fail closed on unknown
 * verification wire codes (M-VS-02). Sol's probes survived: a validator that never compared
 * acceptedIntentHash, and `verificationLevelWire = 999` returning Success.
 *
 * # 响应校验器 oracle：acceptedIntentHash 必须绑所发 intent 的 digest；未知 verification wire fail-closed
 */
@RunWith(RobolectricTestRunner::class)
class ContractResponseValidatorTest {

    private fun receipt(
        idempotencyKey: String = "k-1",
        acceptedIntentHash: String = "digest-1",
        verificationLevelWire: Int = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
    ) = ApplyReceiptV1(
        operationId = "op-1",
        idempotencyKey = idempotencyKey,
        leaseId = "lease-1",
        acceptedIntentHash = acceptedIntentHash,
        appliedAtEpochMs = 123456789L,
        environmentRevision = 7L,
        verificationLevelWire = verificationLevelWire
    )

    @Test
    fun `a fully bound apply receipt validates`() {
        val v = ContractResponseValidator.validateApply(
            EnvironmentControlResultV1.apply(receipt()), "k-1", expectedIntentHash = "digest-1"
        )
        assertTrue(v is ContractResponseValidator.ValidatedContractResponse.Success)
    }

    // ---- R46 (Sol R46 P1-2): the advance receipt's canonical digest binding at the VALIDATOR
    // layer (the Binder response boundary; the engine re-verifies as the replay backstop). ----

    private fun advanceReceipt(
        receiptDigest: String,
        effectiveIntentHash: String = "eff-1"
    ) = io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1(
        outcomeWire = io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1.ADVANCED.wire,
        advancedFromItemId = "item-1", advancedToItemId = "item-2",
        scheduleVersionAfter = 2L, effectiveIntentHash = effectiveIntentHash,
        effectiveEnvironmentRevision = 7L, receiptDigest = receiptDigest
    )

    private fun canonicalAdvanceDigest(
        effectiveIntentHash: String = "eff-1",
        requestDigest: String = "req-digest-1",
        idempotencyKey: String = "adv-key-1"
    ): String = io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1.compute(
        advanceReceipt(receiptDigest = "", effectiveIntentHash = effectiveIntentHash),
        requestDigest, idempotencyKey
    )

    @Test
    fun `a canonically bound advance receipt validates`() {
        val v = ContractResponseValidator.validateCompleteAndAdvance(
            EnvironmentControlResultV1.completeAndAdvance(advanceReceipt(receiptDigest = canonicalAdvanceDigest())),
            expectedIntentHash = "eff-1", requestDigest = "req-digest-1", idempotencyKey = "adv-key-1"
        )
        assertTrue(v is ContractResponseValidator.ValidatedContractResponse.Success)
    }

    @Test
    fun `a forged advance receipt digest fail-closes (M-AD-08 - a receipt that does not bind its request is not a receipt)`() {
        val v = ContractResponseValidator.validateCompleteAndAdvance(
            EnvironmentControlResultV1.completeAndAdvance(advanceReceipt(receiptDigest = "forged-not-canonical")),
            expectedIntentHash = "eff-1", requestDigest = "req-digest-1", idempotencyKey = "adv-key-1"
        )
        assertEquals(
            ContractResponseValidator.ValidatedContractResponse.Failure("PROVIDER_ADVANCE_RECEIPT_DIGEST_MISMATCH"),
            v
        )
    }

    @Test
    fun `a canonically valid receipt bound to a DIFFERENT request fail-closes (key and digest identity)`() {
        // The digest is canonical for (req-digest-2, adv-key-2) — not for the request we sent.
        val othersDigest = canonicalAdvanceDigest(requestDigest = "req-digest-2", idempotencyKey = "adv-key-2")
        val v = ContractResponseValidator.validateCompleteAndAdvance(
            EnvironmentControlResultV1.completeAndAdvance(advanceReceipt(receiptDigest = othersDigest)),
            expectedIntentHash = "eff-1", requestDigest = "req-digest-1", idempotencyKey = "adv-key-1"
        )
        assertEquals(
            ContractResponseValidator.ValidatedContractResponse.Failure("PROVIDER_ADVANCE_RECEIPT_DIGEST_MISMATCH"),
            v
        )
    }

    @Test
    fun `an accepted intent hash different from the sent intents digest fail-closes`() {
        val v = ContractResponseValidator.validateApply(
            EnvironmentControlResultV1.apply(receipt(acceptedIntentHash = "digest-OTHER")),
            "k-1", expectedIntentHash = "digest-1"
        )
        assertEquals(
            ContractResponseValidator.ValidatedContractResponse.Failure("PROVIDER_RECEIPT_INTENT_MISMATCH"),
            v
        )
    }

    @Test
    fun `an unknown verification wire code fail-closes (M-VS-02)`() {
        val v = ContractResponseValidator.validateApply(
            EnvironmentControlResultV1.apply(receipt(verificationLevelWire = 999)),
            "k-1", expectedIntentHash = "digest-1"
        )
        assertEquals(
            ContractResponseValidator.ValidatedContractResponse.Failure("PROVIDER_UNKNOWN_VERIFICATION_WIRE"),
            v
        )
    }

    @Test
    fun `a mismatched idempotency key still fail-closes (regression anchor)`() {
        val v = ContractResponseValidator.validateApply(
            EnvironmentControlResultV1.apply(receipt(idempotencyKey = "k-OTHER")),
            "k-1", expectedIntentHash = "digest-1"
        )
        assertEquals(
            ContractResponseValidator.ValidatedContractResponse.Failure("PROVIDER_RECEIPT_KEY_MISMATCH"),
            v
        )
    }

    // ---- Sol R3 P1: outcome↔target implication (§6.7.1) ----
    // ADVANCED ⇒ advancedToItemId != null; EXHAUSTED ⇒ advancedToItemId == null.
    // A contradictory tuple is a malformed carrier regardless of digest correctness.

    private fun advanceReceiptWithOutcomeAndTarget(
        outcomeWire: Int,
        advancedToItemId: String?,
        effectiveIntentHash: String = "eff-1"
    ): io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1 {
        val base = io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1(
            outcomeWire = outcomeWire,
            advancedFromItemId = "item-1",
            advancedToItemId = advancedToItemId,
            scheduleVersionAfter = 2L,
            effectiveIntentHash = effectiveIntentHash,
            effectiveEnvironmentRevision = 7L,
            receiptDigest = ""
        )
        // Compute the canonical digest for this exact tuple so it passes the digest check —
        // the point of this test is that the implication check fires BEFORE or AFTER digest,
        // not that the digest itself is wrong.
        val digest = io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1.compute(
            base, "req-digest-1", "adv-key-1"
        )
        return base.copy(receiptDigest = digest)
    }

    @Test
    fun `EXHAUSTED with non-null advancedToItemId fail-closes (contradictory tuple)`() {
        val contradictory = advanceReceiptWithOutcomeAndTarget(
            outcomeWire = io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1.EXHAUSTED.wire,
            advancedToItemId = "item-2"  // ← contradicts EXHAUSTED
        )
        val v = ContractResponseValidator.validateCompleteAndAdvance(
            EnvironmentControlResultV1.completeAndAdvance(contradictory),
            expectedIntentHash = "eff-1", requestDigest = "req-digest-1", idempotencyKey = "adv-key-1"
        )
        assertEquals(
            ContractResponseValidator.ValidatedContractResponse.Failure("PROVIDER_EXHAUSTED_WITH_TARGET"),
            v
        )
    }

    @Test
    fun `ADVANCED with null advancedToItemId fail-closes (contradictory tuple)`() {
        val contradictory = advanceReceiptWithOutcomeAndTarget(
            outcomeWire = io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1.ADVANCED.wire,
            advancedToItemId = null  // ← contradicts ADVANCED
        )
        val v = ContractResponseValidator.validateCompleteAndAdvance(
            EnvironmentControlResultV1.completeAndAdvance(contradictory),
            expectedIntentHash = "eff-1", requestDigest = "req-digest-1", idempotencyKey = "adv-key-1"
        )
        assertEquals(
            ContractResponseValidator.ValidatedContractResponse.Failure("PROVIDER_ADVANCED_WITHOUT_TARGET"),
            v
        )
    }
}
