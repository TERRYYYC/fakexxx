package name.caiyao.fakegps.integration.v1

/**
 * P10DBG-COLLECTOR-V1 — pure gate/arm logic for the qwy-side fault & revoke
 * collector (G2 §3 P10, §5B/§5C).
 *
 * WHY PURE
 * ---------
 * Everything adb-fireable in [FaultCollectorActivity] reduces to decisions a
 * JVM lane can pin: does this gate token parse, does this snapshot satisfy
 * it, does the arm record survive encode/decode. The Android surface is glue;
 * the exact-window semantics live HERE so "cannot fire at the specified
 * moment" is a test failure, not a runbook hope.
 *
 * EXACT WINDOW, DEFINED
 * ---------------------
 * A window is exact because the gate evaluates against the provider's own
 * DURABLE lease state — committed bytes in the FileDurableKv, loaded fresh
 * from disk at every poll tick. Not wall-clock timing, not logcat racing.
 * When the fire lands, the lease WAS in the gated state on disk; that is the
 * claim §5C "run 中撤销" and §5B "指定 checkpoint 崩溃" need to be testable.
 *
 * FROZEN VOCABULARY — tokens are pinned by P10CollectorSurfaceGuardTest and
 * the per-injection exit/restore matrix freezes against them.
 */

/** What a gate is allowed to see: a read-only projection of durable state. */
data class QwyLeaseSnapshot(
    val currentLeaseId: String?,
    /** Raw persisted LeaseRecord.state.name — as-committed, pre-recovery. */
    val leaseState: String?,
    val callerApplicationId: String?,
)

sealed interface FaultGate {
    /** Frozen token (asserted in P10CollectorSurfaceGuardTest). */
    val token: String

    fun isSatisfiedBy(snapshot: QwyLeaseSnapshot): Boolean

    /** Fire when the current lease is committed ACTIVE — §5C run 中 revoke, §5B.2 unclean-kill window. */
    object LeaseActive : FaultGate {
        override val token = "lease_active"
        override fun isSatisfiedBy(snapshot: QwyLeaseSnapshot): Boolean =
            snapshot.leaseState == "ACTIVE"
    }

    /** Fire during committed RELEASING — §5B M-LS-17 restart-replay window. */
    object LeaseReleasing : FaultGate {
        override val token = "lease_releasing"
        override fun isSatisfiedBy(snapshot: QwyLeaseSnapshot): Boolean =
            snapshot.leaseState == "RELEASING"
    }

    /** Fire during committed ACQUIRING — §5B.2 apply-in-flight unclean window. */
    object LeaseAcquiring : FaultGate {
        override val token = "lease_acquiring"
        override fun isSatisfiedBy(snapshot: QwyLeaseSnapshot): Boolean =
            snapshot.leaseState == "ACQUIRING"
    }

    companion object {
        fun parse(raw: String): FaultGate? = when (raw.trim()) {
            LeaseActive.token -> LeaseActive
            LeaseReleasing.token -> LeaseReleasing
            LeaseAcquiring.token -> LeaseAcquiring
            else -> null
        }

        val allTokens: List<String> = listOf(LeaseActive, LeaseReleasing, LeaseAcquiring).map { it.token }
    }
}

/** What the arm command should do once the gate opens. */
enum class ArmAction(val token: String) {
    /** Unclean self-kill of the provider process at the gated moment (§5B windows). */
    SELF_KILL("self_kill"),

    /** Revoke a caller principal at the gated moment (§5C qwy run 中撤销). */
    REVOKE_CALLER("revoke_caller"),
    ;

    companion object {
        fun parse(raw: String): ArmAction? = entries.firstOrNull { it.token == raw.trim() }
    }
}

/**
 * Caller scoping. A gate may optionally require the current lease to belong
 * to one caller — that is what makes "revoke caller X the moment X's lease is
 * active" a single command instead of a human watching a screen.
 */
data class CallerScope(val applicationId: String?) {
    fun matches(snapshot: QwyLeaseSnapshot): Boolean =
        applicationId == null || snapshot.callerApplicationId == applicationId
}

data class ArmSpec(
    val action: ArmAction,
    val gate: FaultGate,
    val scope: CallerScope,
    /** REVOKE_CALLER: both halves of the principal (§6.5 — never fuzzy). */
    val revokeSignerDigest: String?,
    val pollMs: Long,
    val timeoutMs: Long,
) {
    companion object {
        const val DEFAULT_POLL_MS = 200L
        const val DEFAULT_TIMEOUT_MS = 600_000L

        /**
         * Validate an arm request. Returns null (with the reason swallowed into
         * the caller's error report) when the spec cannot arm — arming a gun
         * that cannot fire is worse than refusing.
         */
        fun validate(
            action: ArmAction?,
            gate: FaultGate?,
            scope: CallerScope,
            revokeSignerDigest: String?,
            pollMs: Long,
            timeoutMs: Long,
        ): String? {
            if (action == null) return "action must be one of: ${ArmAction.entries.joinToString() { it.token }}"
            if (gate == null) return "gate must be one of: ${FaultGate.allTokens.joinToString()}"
            if (pollMs < 50) return "poll_ms must be >= 50 (got $pollMs) — tighter polls only burn CPU"
            if (timeoutMs !in 1_000..3_600_000) return "timeout_ms must be within [1000, 3600000]"
            if (action == ArmAction.REVOKE_CALLER) {
                if (scope.applicationId == null || scope.applicationId.isBlank())
                    return "revoke_caller needs --es caller <applicationId>"
                if (revokeSignerDigest == null || revokeSignerDigest.isBlank())
                    return "revoke_caller needs --es signer <sha256> — half a principal is a different principal"
            }
            return null
        }
    }

    fun isSatisfiedBy(snapshot: QwyLeaseSnapshot): Boolean =
        gate.isSatisfiedBy(snapshot) && scope.matches(snapshot)
}

/**
 * Durable arm/fire/outcome record — the collector's own audit trail.
 *
 * Format: append-only lines in `filesDir/debug-collector/arm.log`, each line
 * one [DurableFieldCodec] record. The product's durable stores remain the
 * STATE truth (§5 log boundary); this file records WHAT THE COLLECTOR DID,
 * so an executor's evidence pack can bind "who fired, when, under which
 * spec" without scraping logcat.
 */
object ArmRecordCodec {

    data class ArmLine(
        val kind: String,        // ARMED | FIRED | TIMEOUT | DISARMED | OUTCOME
        val action: String,
        val gate: String,
        val caller: String?,
        val atMs: Long,
        val detail: String?,     // e.g. snapshot-at-fire, revoke outcome
    )

    fun encode(line: ArmLine): String =
        DurableFieldCodec.encode(
            listOf(
                line.kind,
                line.action,
                line.gate,
                line.caller,
                line.atMs.toString(),
                line.detail,
            ),
        )

    fun decode(raw: String): ArmLine? = runCatching {
        val parts = DurableFieldCodec.decode(raw)
        ArmLine(
            kind = parts[0]!!,
            action = parts[1]!!,
            gate = parts[2]!!,
            caller = parts[3],
            atMs = parts[4]!!.toLong(),
            detail = parts[5],
        )
    }.getOrNull()
}
