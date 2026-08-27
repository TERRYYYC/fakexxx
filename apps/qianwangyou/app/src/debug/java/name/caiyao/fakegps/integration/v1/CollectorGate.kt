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

/**
 * What a gate is allowed to see: a read-only projection of durable state.
 *
 * R2 (gpt55 P1-3): `callerSignerDigest` is exposed because lease ownership is
 * the FULL principal (applicationId, signerDigest) — §6.5: a signer rotation
 * is a different provider/caller. A gate scoped by appId only could open on
 * ANOTHER principal's in-flight lease and fire against the wrong transaction.
 */
data class QwyLeaseSnapshot(
    val currentLeaseId: String?,
    /** Raw persisted LeaseRecord.state.name — as-committed, pre-recovery. */
    val leaseState: String?,
    val callerApplicationId: String?,
    val callerSignerDigest: String?,
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
 * Principal scoping. A gate may require the current lease to belong to one
 * caller — and for a revoke, "one caller" means the FULL principal
 * (applicationId AND signerDigest): a same-package rotated-signer lease is a
 * DIFFERENT principal's transaction (§6.5), and firing a revoke against it at
 * "the gated moment" would be exact-window for the wrong in-flight lease.
 */
data class CallerScope(val applicationId: String?, val signerDigest: String? = null) {
    fun matches(snapshot: QwyLeaseSnapshot): Boolean {
        if (applicationId != null && snapshot.callerApplicationId != applicationId) return false
        if (signerDigest != null && snapshot.callerSignerDigest != signerDigest) return false
        return true
    }
}

data class ArmSpec(
    val action: ArmAction,
    val gate: FaultGate,
    /** REVOKE_CALLER: scope carries BOTH halves of the principal (§6.5 — never fuzzy). */
    val scope: CallerScope,
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
            pollMs: Long,
            timeoutMs: Long,
        ): String? {
            if (action == null) return "action must be one of: ${ArmAction.entries.joinToString() { it.token }}"
            if (gate == null) return "gate must be one of: ${FaultGate.allTokens.joinToString()}"
            if (pollMs < 50) return "poll_ms must be >= 50 (got $pollMs) — tighter polls only burn CPU"
            if (timeoutMs !in 1_000..3_600_000) return "timeout_ms must be within [1000, 3600000]"
            if (action == ArmAction.REVOKE_CALLER) {
                if (scope.applicationId.isNullOrBlank())
                    return "revoke_caller needs --es caller <applicationId>"
                if (scope.signerDigest.isNullOrBlank())
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

/**
 * R2 (gpt55 P1-2 companion, qwy side): what "REVOKE PROVEN" is allowed to mean.
 *
 * The disease this object exists to kill: proving a revoke from broad
 * post-conditions ("principal currently inactive + a caller_revoked audit row
 * exists") false-proves for a typo'd or never-paired principal — the audit row
 * is appended by the transition itself, and absence was always absent. The
 * ONLY honest proof is the before→after transition of the EXACT principal:
 *
 *   before: findActive(appId, signer) != null   — the principal WAS paired
 *   after:  findActive(appId, signer) == null   — durably inactive now
 *   audit:  a caller_revoked row for the appId exists
 *
 * Anything else reports NOT_PROVEN_* with the reason — an executor must see
 * "nothing was revoked", never a green that lies.
 */
object QwyRevokeProof {

    enum class Verdict { PROVEN, NOT_PROVEN_NOTHING_ACTIVE, NOT_PROVEN_STILL_ACTIVE, NOT_PROVEN_NO_AUDIT, UNKNOWN }

    fun verdict(
        beforeActive: Boolean?,
        afterActive: Boolean?,
        revokeAudited: Boolean?,
    ): Verdict = when {
        beforeActive != true -> Verdict.NOT_PROVEN_NOTHING_ACTIVE
        afterActive == null -> Verdict.UNKNOWN
        afterActive == true -> Verdict.NOT_PROVEN_STILL_ACTIVE
        revokeAudited != true -> Verdict.NOT_PROVEN_NO_AUDIT
        else -> Verdict.PROVEN
    }

    fun render(v: Verdict): String = when (v) {
        Verdict.PROVEN -> "REVOKE PROVEN: exact principal was active, now durably inactive, audit row present."
        Verdict.NOT_PROVEN_NOTHING_ACTIVE ->
            "NOT PROVEN — principal was NOT durably active before the fire. Nothing to revoke " +
                "(typo, never paired, or already revoked). The revoke transition still ran; treat as no-op."
        Verdict.NOT_PROVEN_STILL_ACTIVE ->
            "REVOKE NOT PROVEN — principal STILL reads active after the fire. INVESTIGATE."
        Verdict.NOT_PROVEN_NO_AUDIT ->
            "REVOKE NOT PROVEN — principal inactive but no caller_revoked audit row. INVESTIGATE."
        Verdict.UNKNOWN -> "REVOKE NOT PROVEN — after-state unreadable."
    }
}
