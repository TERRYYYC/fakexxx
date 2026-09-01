package com.example.cellrebelauto.ui

import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ProviderPrincipalFailureReason

/**
 * R43 (Sol GREEN-review-2 F5 / spec Task 6): the SEVEN on-site operator states the run surface
 * must render — pairing/incompatibility/recovery/release errors with a CONCRETE recovery action
 * each (spec §Task 6 RED: 未配对 / provider 待批准 / 不兼容 / 可信 / 未验证 / recovery-required /
 * release-incomplete).
 *
 * Pure projection: every field derives from durable owner state (provider pairing records,
 * attempt rows, ledger truth) — the UI renders this, it never decides it.
 *
 * # 七态现场状态投影：由持久 owner 状态派生；UI 只渲染不判定
 */
sealed class PairingUiState {

    /** Not paired: no provider record for the frozen contract — action: install/open the provider + pair. */
    data object NotPaired : PairingUiState() {
        const val ACTION = "安装并配对千网游 provider（设置 → Provider 管理）"
    }

    /** Pending operator approval: a pairing candidate exists but no active record — approval gate (§6.5.3). */
    data object PendingOperatorApproval : PairingUiState() {
        const val ACTION = "在 Provider 批准页审批配对候选（批准前不进入可信判定）"
    }

    /** Incompatible provider: paired but the contract compatibility matrix rejected it. */
    data class Incompatible(val detail: String) : PairingUiState() {
        companion object { const val ACTION = "升级千网游到与 contract v1 兼容的版本后重新配对" }
    }

    /** Trusted: paired + compatible — normal operation. */
    data object Trusted : PairingUiState() {
        const val ACTION = ""
    }

    /** Unverified completion: the attempt completed but the §6.4 trust predicate FAILED. */
    data object UnverifiedCompletion : PairingUiState() {
        const val ACTION = "该地址未计可信配额；检查环境观察证据后重试"
    }

    /** Recovery required: an in-flight attempt needs reconcile — the plan is paused (§8.2). */
    data object RecoveryRequired : PairingUiState() {
        const val ACTION = "恢复流程已暂停计划；重新启动运行以继续恢复协调"
    }

    /** Release incomplete: the lease cleanup could not be proven (INV-21) — manual recovery. */
    data object ReleaseIncomplete : PairingUiState() {
        const val ACTION = "环境租约未证实清理；手动确认千网游已停止后重试（INV-21）"
    }

    val recoveryAction: String
        get() = when (this) {
            is NotPaired -> NotPaired.ACTION
            is PendingOperatorApproval -> PendingOperatorApproval.ACTION
            is Incompatible -> Incompatible.ACTION
            is Trusted -> Trusted.ACTION
            is UnverifiedCompletion -> UnverifiedCompletion.ACTION
            is RecoveryRequired -> RecoveryRequired.ACTION
            is ReleaseIncomplete -> ReleaseIncomplete.ACTION
        }

    companion object {
        /**
         * The durable-state projection: pairing records → attempt phase → ledger truth.
         * A crashed attempt's phase decides recovery vs release-incomplete; an UNVERIFIED_RECORDED
         * attempt decides unverified; otherwise the pairing lifecycle decides the top state.
         */
        internal fun project(
            hasProviderRecord: Boolean,
            providerActive: Boolean,
            incompatible: Boolean = false,
            incompatibleDetail: String = "",
            crashedAplusState: String? = null,
            crashedProviderFailure: ProviderPrincipalFailureReason? = null,
            hasOutstandingLease: Boolean = crashedAplusState == "RELEASE_PENDING",
            hasUnverifiedRecord: Boolean = false
        ): PairingUiState = when {
            // A replacement signer cannot prove cleanup of the older signer's outstanding lease.
            // Keep the durable incident/manual action visible even when pairing discovery now
            // reports B as pending (or B was later approved).
            crashedAplusState == "RECOVERY_REQUIRED" &&
                crashedProviderFailure == ProviderPrincipalFailureReason.SIGNER_UNTRUSTED ->
                ReleaseIncomplete
            crashedProviderFailure in setOf(
                ProviderPrincipalFailureReason.SIGNER_OWNER_UNKNOWN,
                ProviderPrincipalFailureReason.SIGNER_OWNER_CONFLICT,
            ) && hasOutstandingLease -> ReleaseIncomplete
            crashedProviderFailure in setOf(
                ProviderPrincipalFailureReason.SIGNER_OWNER_UNKNOWN,
                ProviderPrincipalFailureReason.SIGNER_OWNER_CONFLICT,
            ) -> RecoveryRequired
            crashedAplusState != null && !providerActive ->
                if (hasOutstandingLease) ReleaseIncomplete else RecoveryRequired
            // An outstanding durable lease is an active incident even if the current provider is
            // absent/pending/incompatible. Pairing UI must never hide manual cleanup.
            crashedAplusState == "RELEASE_PENDING" -> ReleaseIncomplete
            !hasProviderRecord -> NotPaired
            !providerActive -> PendingOperatorApproval
            incompatible -> Incompatible(incompatibleDetail)
            crashedAplusState == "RECOVERY_REQUIRED" -> RecoveryRequired
            hasUnverifiedRecord -> UnverifiedCompletion
            else -> Trusted
        }

        /** Derive the crash-phase input from an attempt row (§8.1 owner state). */
        fun crashedPhaseOf(attempt: TestAttempt?): String? =
            attempt?.takeIf { it.status in setOf("starting", "running") && it.aplusState != null }?.aplusState
    }
}
