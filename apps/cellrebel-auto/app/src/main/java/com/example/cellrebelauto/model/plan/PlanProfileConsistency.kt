package com.example.cellrebelauto.model.plan

/**
 * P0.1-5 "one import, both sides effective" — the plan↔profile consistency verdict.
 *
 * A 51-row plan driven against 52 provider profiles grabs a SHIFTED profile from row 2
 * onward: every attempt still "works", nothing fails loudly, and quota burns without a
 * single verifiable success (proven root cause on the monitoring threads). The check is
 * therefore a prominent WARN on the Plan surface, computed from the imported row count
 * and the provider profile count read over the existing discover channel.
 *
 * Null on either side = the count could not be obtained (provider unreachable, channel
 * exception) — the check is silently SKIPPED, never reported as a mismatch and never a
 * crash. # 两侧任一数字取不到 → 静默跳过，绝不误报也不崩
 */
object PlanProfileConsistency {

    fun evaluate(planRows: Int?, providerProfiles: Int?): PlanProfileMismatch? {
        if (planRows == null || providerProfiles == null) return null
        if (planRows <= 0) return null
        if (planRows == providerProfiles) return null
        return PlanProfileMismatch(planRows, providerProfiles)
    }
}

data class PlanProfileMismatch(val planRows: Int, val providerProfiles: Int) {

    /** The prominent, actionable warning line the Plan surface renders. */
    val message: String
        get() =
            "Plan/profile count mismatch: plan has $planRows row(s) but the QWY provider " +
                "holds $providerProfiles profile(s). Every row would apply a SHIFTED profile " +
                "and burn quota without progress — re-align both sides (matching profile CSV " +
                "and plan CSV) before starting."
}
