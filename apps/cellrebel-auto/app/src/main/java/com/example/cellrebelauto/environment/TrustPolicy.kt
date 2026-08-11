package com.example.cellrebelauto.environment

import com.example.cellrebelauto.model.execution.CellRebelExecution

/**
 * Decides whether an observed CellRebel completion may mint a [com.example.cellrebelauto.model.ledger.TrustedQuotaEntry]
 * (§8.1 DECIDING → QUOTA_COMMITTED). Per §8.6 / §9 only VERIFIED_NEW_COMPLETION, with pre + post
 * observation bound to the SAME lease (INV-07/11/23/27), may pass.
 *
 * PRE-FREEZE SKELETON (RED): always returns [TrustDecision.FAIL]. The real predicate is GREEN
 * work, gated on contract v1 freeze. Tests asserting PASS for a verified execution therefore FAIL
 * until GREEN — the intended RED signal. Only the decision TYPE is frozen here.
 *
 * # 信任策略骨架（RED）：恒 FAIL；真实谓词是 GREEN，待 contract 冻结
 */
class TrustPolicy {
    fun evaluate(execution: CellRebelExecution): TrustDecision = TrustDecision.FAIL
}

enum class TrustDecision { PASS, FAIL }
