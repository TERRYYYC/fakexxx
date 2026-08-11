package com.example.cellrebelauto.model.execution

/**
 * CellRebel completion evidence classification (§8.6.2, frozen wire codes 1-5).
 *
 * Only [VERIFIED_NEW_COMPLETION] enters the trusted quota (INV-11, DP-3 = A: accepted product
 * semantics with an honest upper bound). Every other value records an unverified attempt, never
 * a trusted one.
 *
 * NOTE (pre-freeze): the wire codes are stable per §6.7 compatibility.yaml. The enum TYPE is
 * Auto's classification (§8.6.2), declared locally here. Post contract-v1 freeze (PR #11 / #3)
 * the type may be sourced from the contract module; the §8.6.2 semantics and wire codes do not
 * change. Tests assert semantics, not the type's package.
 *
 * # CellRebel 完成证据判定（§8.6.2 冻结 wire 1-5）：仅 VERIFIED_NEW_COMPLETION 进可信配额
 */
enum class CellRebelCompletionEvidenceV1(val wire: Int) {
    VERIFIED_NEW_COMPLETION(1),
    PRE_EXISTING_RUN(2),
    WEAK_RUNNING_EVIDENCE(3),
    RUNNING_TOO_SHORT(4),
    NO_COMPLETION_EVIDENCE(5);

    companion object {
        /** Resolve from wire code; null if unknown (§6.7: unknown wire ⇒ INCOMPATIBLE, not trusted). */
        fun fromWire(wire: Int): CellRebelCompletionEvidenceV1? =
            entries.firstOrNull { it.wire == wire }

        /** The single value that may produce a TrustedQuotaEntry (INV-11). */
        val TRUSTED: CellRebelCompletionEvidenceV1 = VERIFIED_NEW_COMPLETION
    }
}
