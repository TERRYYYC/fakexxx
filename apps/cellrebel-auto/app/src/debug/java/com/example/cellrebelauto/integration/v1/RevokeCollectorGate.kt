package com.example.cellrebelauto.integration.v1

import com.example.cellrebelauto.automation.aplus.AttemptState

/**
 * P10DBG-COLLECTOR-V1 — pure gate/arm logic for the Auto-side revoke
 * collector (G2 §3 P10, §5C "Auto 撤销 provider" run 前 / run 中).
 *
 * WHY PURE — same reason as the qwy side: the exact-window semantics must be
 * JVM-pinnable, not runbook hope. A gate here answers "is a REAL attempt
 * durably in the state §5C names", evaluated against Auto's own Room rows —
 * never against logs or timing.
 *
 * WHAT THE WINDOW IS — the revoke must land while a genuine attempt is
 * in-flight, because §5C's Auto-side assertion is that the in-flight attempt
 * enters NORMAL release/recovery and must NOT be misrouted into qwy's
 * revoked-caller self-cleanup. That assertion is only testable if the revoke
 * provably landed mid-flight.
 *
 * FROZEN VOCABULARY (pinned by P10CollectorSurfaceGuardTest; the per-injection
 * exit/restore matrix freezes against these tokens):
 *
 *   run_active         — ≥1 test_attempts row has status 'starting'/'running'
 *   attempt_state:<S>  — ≥1 running row has durable aplusState == <S>
 *                        (real [AttemptState] names, e.g. ENV_APPLIED,
 *                        RELEASE_PENDING, QUOTA_COMMITTED)
 *   trusted_count:<N>  — trusted_quota_entries total has reached N
 *                        (fires at ≥N — the §5B checkpoint-crash companion:
 *                        "kill right after the Nth trusted commit")
 */

/** Read-only projection of Auto's durable run state (Room rows). */
data class AutoRunSnapshot(
    val runningAttemptCount: Int,
    /** aplusState values of the running rows (null state = legacy row, excluded). */
    val runningAplusStates: List<String>,
    val trustedCountTotal: Int,
)

sealed interface AutoGate {
    fun isSatisfiedBy(snapshot: AutoRunSnapshot): Boolean

    data object RunActive : AutoGate {
        override fun isSatisfiedBy(snapshot: AutoRunSnapshot): Boolean =
            snapshot.runningAttemptCount > 0
    }

    data class AttemptStateIs(private val state: String) : AutoGate {
        override fun isSatisfiedBy(snapshot: AutoRunSnapshot): Boolean =
            snapshot.runningAplusStates.any { it == state }
    }

    data class TrustedCountReached(private val n: Int) : AutoGate {
        override fun isSatisfiedBy(snapshot: AutoRunSnapshot): Boolean =
            snapshot.trustedCountTotal >= n
    }

    companion object {
        const val RUN_ACTIVE = "run_active"
        const val ATTEMPT_STATE_PREFIX = "attempt_state:"
        const val TRUSTED_COUNT_PREFIX = "trusted_count:"

        /** Valid tokens render back for diagnostics; invalid ones return null. */
        fun parse(raw: String): AutoGate? {
            val token = raw.trim()
            return when {
                token == RUN_ACTIVE -> RunActive
                token.startsWith(ATTEMPT_STATE_PREFIX) -> {
                    val state = token.removePrefix(ATTEMPT_STATE_PREFIX).trim().uppercase()
                    // Bind to the REAL §8.1 enum: a token no state machine can
                    // ever hold must refuse to arm, not arm-and-never-fire.
                    if (AttemptState.entries.any { it.name == state }) AttemptStateIs(state) else null
                }
                token.startsWith(TRUSTED_COUNT_PREFIX) -> {
                    val n = token.removePrefix(TRUSTED_COUNT_PREFIX).trim().toIntOrNull()
                    if (n != null && n >= 0) TrustedCountReached(n) else null
                }
                else -> null
            }
        }
    }
}

enum class AutoArmAction(val token: String) {
    /** Revoke a provider principal at the gated moment (§5C run 中撤销). */
    REVOKE_PROVIDER("revoke_provider"),

    /** Unclean self-kill of the Auto process at the gated moment (§5B Auto checkpoint crash). */
    SELF_KILL("self_kill"),
    ;

    companion object {
        fun parse(raw: String): AutoArmAction? = entries.firstOrNull { it.token == raw.trim() }
    }
}

data class AutoArmSpec(
    val action: AutoArmAction,
    val gate: AutoGate,
    val gateToken: String,
    val providerApplicationId: String?,
    val providerSignerDigest: String?,
    val pollMs: Long,
    val timeoutMs: Long,
) {
    companion object {
        const val DEFAULT_POLL_MS = 200L
        const val DEFAULT_TIMEOUT_MS = 600_000L

        fun validate(
            action: AutoArmAction?,
            gate: AutoGate?,
            gateToken: String,
            appId: String?,
            signer: String?,
            pollMs: Long,
            timeoutMs: Long,
        ): String? {
            if (action == null)
                return "action must be one of: ${AutoArmAction.entries.joinToString { it.token }}"
            if (gate == null)
                return "gate must be one of: ${AutoGate.RUN_ACTIVE}, " +
                    "${AutoGate.ATTEMPT_STATE_PREFIX}<STATE>, ${AutoGate.TRUSTED_COUNT_PREFIX}<N> " +
                    "(got '$gateToken')"
            if (pollMs < 50) return "poll_ms must be >= 50 (got $pollMs)"
            if (timeoutMs !in 1_000..3_600_000) return "timeout_ms must be within [1000, 3600000]"
            if (action == AutoArmAction.REVOKE_PROVIDER) {
                if (appId.isNullOrBlank())
                    return "revoke_provider needs --es app_id <provider applicationId>"
                if (signer.isNullOrBlank())
                    return "revoke_provider needs --es signer <sha256> — half a principal is a different principal"
            }
            return null
        }
    }

    fun isSatisfiedBy(snapshot: AutoRunSnapshot): Boolean = gate.isSatisfiedBy(snapshot)
}

/**
 * R2 (gpt55 P1-2): what "REVOKE PROVEN" is allowed to mean on the Auto side.
 *
 * The disease: proving from broad rows ("no active row for this appId")
 * false-proves an empty row set (typo'd appId / never approved) or blames a
 * same-app OTHER signer's still-active row. The honest proof binds the store's
 * own boolean return (ProviderTrustStore.revoke returns true iff the EXACT
 * principal's active row was flipped) plus the exact-principal activeFor
 * query going inactive.
 */
object RevokeReadback {

    enum class Verdict { PROVEN, NOT_PROVEN_NO_ROW_FLIPPED, NOT_PROVEN_STILL_ACTIVE, UNKNOWN }

    fun verdict(
        revokeReturned: Boolean?,
        activeForPrincipalAfter: Boolean?,
    ): Verdict = when {
        revokeReturned == false -> Verdict.NOT_PROVEN_NO_ROW_FLIPPED
        activeForPrincipalAfter == null -> Verdict.UNKNOWN
        activeForPrincipalAfter == true -> Verdict.NOT_PROVEN_STILL_ACTIVE
        else -> Verdict.PROVEN
    }

    fun render(v: Verdict): String = when (v) {
        Verdict.PROVEN -> "REVOKE PROVEN: exact principal's row flipped (store returned true) and now reads inactive."
        Verdict.NOT_PROVEN_NO_ROW_FLIPPED ->
            "NOT PROVEN — ProviderTrustStore.revoke returned false: no ACTIVE row for that exact " +
                "(appId, signer) was flipped. Typo, never approved, or already revoked. Treat as no-op."
        Verdict.NOT_PROVEN_STILL_ACTIVE ->
            "REVOKE NOT PROVEN — exact principal still reads ACTIVE after the fire. INVESTIGATE."
        Verdict.UNKNOWN -> "REVOKE NOT PROVEN — after-state unreadable."
    }
}

/**
 * Durable arm/fire/outcome record — `filesDir/debug-collector/arm.log`,
 * append-only. Same contract as the qwy side: Room stays the state truth;
 * this log binds WHAT THE COLLECTOR DID into the executor's evidence pack.
 */
object AutoArmRecordCodec {

    data class ArmLine(
        val kind: String, // ARMED | FIRED | TIMEOUT | DISARMED | OUTCOME
        val action: String,
        val gate: String,
        val target: String?,
        val atMs: Long,
        val detail: String?,
    )

    /**
     * Unit-separator record — deliberately same shape as qwy's (no shared module: INV-19).
     *
     * SANITIZE, DON'T ESCAPE: the field is operator-facing audit text, not
     * state truth, so characters that would break the line format (separator,
     * newline, carriage return) are replaced with a space on encode instead
     * of being escaped. A lossy-but-honest codec beats a hand-rolled escaping
     * scheme with subtle non-injective corners. Null fields encode as "".
     */
    fun encode(line: ArmLine): String = listOf(
        line.kind, line.action, line.gate, line.target, line.atMs.toString(), line.detail,
    ).joinToString("\u001F") { field ->
        (field ?: "").replace('\u001F', ' ').replace('\n', ' ').replace('\r', ' ')
    }

    fun decode(raw: String): ArmLine? = runCatching {
        val parts = raw.split('\u001F')
        if (parts.size != 6) return null
        ArmLine(
            kind = parts[0],
            action = parts[1],
            gate = parts[2],
            target = parts[3].takeIf { it.isNotEmpty() },
            atMs = parts[4].toLong(),
            detail = parts[5].takeIf { it.isNotEmpty() },
        )
    }.getOrNull()
}
