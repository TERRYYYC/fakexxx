package com.example.cellrebelauto.recovery

import io.github.terryyyc.fakexxx.contract.v1.ContractResultKindV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1

/**
 * R43 (Sol GREEN-review-2 F2): the ONE frozen-contract response validator every Binder call goes
 * through. Enforces the EnvironmentControlResultV1 carrier invariants the contract documents:
 *
 *  - `resultSchemaVersion` must equal the frozen [EnvironmentControlResultV1.SCHEMA_VERSION];
 *  - the result kind must be the EXPECTED kind for the call (APPLY returns applyReceipt etc.);
 *  - payload EXCLUSIVITY: exactly the matching payload field is non-null (ERROR ⇒ all payloads
 *    null + a non-null errorCode; a malformed tuple is a response anomaly, fail-closed);
 *  - unknown wire codes decode to null (M-VS-02) ⇒ fail-closed.
 *
 * Every violation yields a typed [ValidatedContractResponse.Failure] — never an exception into the
 * engine, never a silent partial accept.
 *
 * # 冻结契约响应统一校验器：schema/kind/payload 排他性/未知 wire 全部 fail-closed
 */
object ContractResponseValidator {

    sealed class ValidatedContractResponse<out T> {
        data class Success<T>(val payload: T) : ValidatedContractResponse<T>()
        data class Failure(val typedOutcome: String) : ValidatedContractResponse<Nothing>()
    }

    fun validateSchemaAndKind(
        result: EnvironmentControlResultV1,
        expectedKind: ContractResultKindV1
    ): ValidatedContractResponse<EnvironmentControlResultV1> {
        if (result.resultSchemaVersion != EnvironmentControlResultV1.SCHEMA_VERSION) {
            return ValidatedContractResponse.Failure("PROVIDER_SCHEMA_MISMATCH")
        }
        val kind = result.resultKindOrNull()
            ?: return ValidatedContractResponse.Failure("PROVIDER_UNKNOWN_RESULT_KIND")
        if (kind == ContractResultKindV1.ERROR) {
            // Typed business failure: errorCode non-null, ALL payloads null (carrier invariant).
            val code = result.errorCodeWire
                ?: return ValidatedContractResponse.Failure("PROVIDER_ERROR_WITHOUT_CODE")
            if (result.capabilitySnapshot != null || result.preflightReport != null ||
                result.applyReceipt != null || result.environmentObservation != null ||
                result.releaseReceipt != null || result.advanceReceipt != null
            ) {
                return ValidatedContractResponse.Failure("PROVIDER_ERROR_WITH_PAYLOAD")
            }
            return ValidatedContractResponse.Failure("PROVIDER_ERROR_$code")
        }
        if (kind != expectedKind) {
            return ValidatedContractResponse.Failure("PROVIDER_UNEXPECTED_KIND_${kind.name}")
        }
        // Success kind: errorCode MUST be null (carrier invariant).
        if (result.errorCodeWire != null) {
            return ValidatedContractResponse.Failure("PROVIDER_SUCCESS_WITH_ERROR_CODE")
        }
        return ValidatedContractResponse.Success(result)
    }

    /** APPLY: exactly the applyReceipt payload; the receipt's tuple is bound to the caller's key AND
     *  the provider-accepted intent hash must equal the digest of the intent we actually sent (R44,
     *  Sol GREEN-review-3 F2); an unknown verification wire code fail-closes (M-VS-02). */
    fun validateApply(
        result: EnvironmentControlResultV1,
        idempotencyKey: String,
        expectedIntentHash: String
    ): ValidatedContractResponse<io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1> {
        val base = validateSchemaAndKind(result, ContractResultKindV1.APPLY)
        if (base is ValidatedContractResponse.Failure) return base
        val payload = (base as ValidatedContractResponse.Success).payload
        val receipt = payload.applyReceipt
            ?: return ValidatedContractResponse.Failure("PROVIDER_APPLY_WITHOUT_RECEIPT")
        if (hasForeignPayloads(result, ContractResultKindV1.APPLY)) {
            return ValidatedContractResponse.Failure("PROVIDER_APPLY_FOREIGN_PAYLOAD")
        }
        if (receipt.idempotencyKey != idempotencyKey) {
            return ValidatedContractResponse.Failure("PROVIDER_RECEIPT_KEY_MISMATCH")
        }
        // The provider-accepted intent hash + lease are the attribution proof; both must be
        // present (the hash is compared against the owner recompute in the trust predicate).
        if (receipt.acceptedIntentHash.isBlank() || receipt.leaseId.isBlank()) {
            return ValidatedContractResponse.Failure("PROVIDER_RECEIPT_INCOMPLETE")
        }
        // R44 (Sol GREEN-review-3 F2): the accepted hash must bind the digest of the intent we SENT —
        // a receipt attesting a DIFFERENT intent is not a proof for this operation.
        if (receipt.acceptedIntentHash != expectedIntentHash) {
            return ValidatedContractResponse.Failure("PROVIDER_RECEIPT_INTENT_MISMATCH")
        }
        // R44 (Sol GREEN-review-3 F2): unknown verification wire codes fail closed (M-VS-02) — an
        // undecodable verification claim can never be trusted.
        if (io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.fromWire(receipt.verificationLevelWire) == null) {
            return ValidatedContractResponse.Failure("PROVIDER_UNKNOWN_VERIFICATION_WIRE")
        }
        return ValidatedContractResponse.Success(receipt)
    }

    /** RELEASE: exactly the releaseReceipt payload; releaseComplete=true is mandatory for RELEASED. */
    fun validateRelease(
        result: EnvironmentControlResultV1,
        leaseId: String
    ): ValidatedContractResponse<io.github.terryyyc.fakexxx.contract.v1.ReleaseReceiptV1> {
        val base = validateSchemaAndKind(result, ContractResultKindV1.RELEASE)
        if (base is ValidatedContractResponse.Failure) return base
        val payload = (base as ValidatedContractResponse.Success).payload
        val receipt = payload.releaseReceipt
            ?: return ValidatedContractResponse.Failure("PROVIDER_RELEASE_WITHOUT_RECEIPT")
        if (hasForeignPayloads(result, ContractResultKindV1.RELEASE)) {
            return ValidatedContractResponse.Failure("PROVIDER_RELEASE_FOREIGN_PAYLOAD")
        }
        if (receipt.leaseId != leaseId) {
            return ValidatedContractResponse.Failure("PROVIDER_RELEASE_LEASE_MISMATCH")
        }
        if (!receipt.releaseComplete) {
            // An unproven cleanup MUST NOT be treated as RELEASED (Sol GREEN-review-2 F2).
            return ValidatedContractResponse.Failure("PROVIDER_RELEASE_INCOMPLETE")
        }
        return ValidatedContractResponse.Success(receipt)
    }

    /** Payload exclusivity: exactly ONE matching payload field non-null on a success kind. */
    private fun hasForeignPayloads(
        result: EnvironmentControlResultV1,
        expectedKind: ContractResultKindV1
    ): Boolean {
        val slots = listOfNotNull(
            if (expectedKind == ContractResultKindV1.DISCOVER) null else result.capabilitySnapshot,
            if (expectedKind == ContractResultKindV1.PREFLIGHT) null else result.preflightReport,
            if (expectedKind == ContractResultKindV1.APPLY) null else result.applyReceipt,
            if (expectedKind == ContractResultKindV1.OBSERVE) null else result.environmentObservation,
            if (expectedKind == ContractResultKindV1.RELEASE) null else result.releaseReceipt,
            if (expectedKind == ContractResultKindV1.COMPLETE_AND_ADVANCE) null else result.advanceReceipt
        )
        return slots.isNotEmpty()
    }

    /** DISCOVER: exactly the capabilitySnapshot payload. */
    fun validateDiscover(
        result: EnvironmentControlResultV1
    ): ValidatedContractResponse<io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1> {
        val base = validateSchemaAndKind(result, ContractResultKindV1.DISCOVER)
        if (base is ValidatedContractResponse.Failure) return base
        val payload = (base as ValidatedContractResponse.Success).payload
        val snapshot = payload.capabilitySnapshot
            ?: return ValidatedContractResponse.Failure("PROVIDER_DISCOVER_WITHOUT_SNAPSHOT")
        if (hasForeignPayloads(result, ContractResultKindV1.DISCOVER)) {
            return ValidatedContractResponse.Failure("PROVIDER_DISCOVER_FOREIGN_PAYLOAD")
        }
        return ValidatedContractResponse.Success(snapshot)
    }

    /** PREFLIGHT: exactly the preflightReport payload; the report must bind the intent we sent. */
    fun validatePreflight(
        result: EnvironmentControlResultV1,
        expectedIntentHash: String
    ): ValidatedContractResponse<io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1> {
        val base = validateSchemaAndKind(result, ContractResultKindV1.PREFLIGHT)
        if (base is ValidatedContractResponse.Failure) return base
        val payload = (base as ValidatedContractResponse.Success).payload
        val report = payload.preflightReport
            ?: return ValidatedContractResponse.Failure("PROVIDER_PREFLIGHT_WITHOUT_REPORT")
        if (hasForeignPayloads(result, ContractResultKindV1.PREFLIGHT)) {
            return ValidatedContractResponse.Failure("PROVIDER_PREFLIGHT_FOREIGN_PAYLOAD")
        }
        if (report.acceptedIntentHash != expectedIntentHash) {
            return ValidatedContractResponse.Failure("PROVIDER_PREFLIGHT_INTENT_MISMATCH")
        }
        return ValidatedContractResponse.Success(report)
    }

    /** OBSERVE: exactly the observation payload; bound to the attempt's lease AND intent hash. */
    fun validateObserve(
        result: EnvironmentControlResultV1,
        expectedLeaseId: String,
        expectedIntentHash: String
    ): ValidatedContractResponse<io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1> {
        val base = validateSchemaAndKind(result, ContractResultKindV1.OBSERVE)
        if (base is ValidatedContractResponse.Failure) return base
        val payload = (base as ValidatedContractResponse.Success).payload
        val observation = payload.environmentObservation
            ?: return ValidatedContractResponse.Failure("PROVIDER_OBSERVE_WITHOUT_OBSERVATION")
        if (hasForeignPayloads(result, ContractResultKindV1.OBSERVE)) {
            return ValidatedContractResponse.Failure("PROVIDER_OBSERVE_FOREIGN_PAYLOAD")
        }
        if (observation.leaseId != expectedLeaseId) {
            return ValidatedContractResponse.Failure("PROVIDER_OBSERVE_LEASE_MISMATCH")
        }
        if (observation.acceptedIntentHash != expectedIntentHash) {
            return ValidatedContractResponse.Failure("PROVIDER_OBSERVE_INTENT_MISMATCH")
        }
        return ValidatedContractResponse.Success(observation)
    }

    /** COMPLETE_AND_ADVANCE: exactly the advanceReceipt payload; outcome wire decodes; the receipt's
     *  effective intent hash binds the attempt's apply intent. */
    fun validateCompleteAndAdvance(
        result: EnvironmentControlResultV1,
        expectedIntentHash: String
    ): ValidatedContractResponse<io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1> {
        val base = validateSchemaAndKind(result, ContractResultKindV1.COMPLETE_AND_ADVANCE)
        if (base is ValidatedContractResponse.Failure) return base
        val payload = (base as ValidatedContractResponse.Success).payload
        val receipt = payload.advanceReceipt
            ?: return ValidatedContractResponse.Failure("PROVIDER_ADVANCE_WITHOUT_RECEIPT")
        if (hasForeignPayloads(result, ContractResultKindV1.COMPLETE_AND_ADVANCE)) {
            return ValidatedContractResponse.Failure("PROVIDER_ADVANCE_FOREIGN_PAYLOAD")
        }
        // M-VS-02: an unknown advance outcome wire must NEVER be read optimistically.
        if (io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1.fromWire(receipt.outcomeWire) == null) {
            return ValidatedContractResponse.Failure("PROVIDER_UNKNOWN_ADVANCE_OUTCOME")
        }
        if (receipt.effectiveIntentHash != expectedIntentHash) {
            return ValidatedContractResponse.Failure("PROVIDER_ADVANCE_INTENT_MISMATCH")
        }
        return ValidatedContractResponse.Success(receipt)
    }
}
