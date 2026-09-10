package name.caiyao.fakegps.integration.v1

/**
 * Predicate-level decomposition of the authoritative window validity chain (#153).
 *
 * WHY THIS EXISTS: the oracle is fail-closed silent by design (TRUST-RECOVERY-285 §3) —
 * when coverage degrades to NONE, nothing on the device says WHICH predicate failed,
 * and the fail-closed NONE spectrum cannot be attributed (hook group missing? digest
 * drift? stale replay? epoch change?). This decomposition names every intermediate
 * predicate result so the debug probe can render it and the audit row can carry a
 * short greppable reason.
 *
 * PURE and diagnostics-only: the inputs are values the observe chain has already
 * computed (or the probe's read-only reconstruction); the outputs are NEVER a trust
 * input and NEVER reach the wire. The observer keeps its own inline judgement — this
 * type only narrates it.
 *
 * [staleReplayRejected]/[epochStable] come from the durable replay watermark store and
 * are therefore supplied by the caller (the decomposition itself does no I/O).
 */
data class OracleWindowPredicateTrace(
    val prePresent: Boolean,
    val postPresent: Boolean,
    val ownerConfigured: Boolean,
    val ownerMatch: Boolean,
    val verdict: AuthoritativeWindowVerdict?,
    val digestMatch: Boolean,
    val epochStable: Boolean,
    val staleReplayRejected: Boolean,
) {
    /**
     * Reproduces the observer's final verdict: every predicate of the
     * authoritativeWindowIsValid chain (minus the authoritativeSource == null
     * early-out, which callers handle before decomposing).
     */
    val windowValid: Boolean
        get() = prePresent && postPresent && ownerConfigured && ownerMatch &&
            verdict == AuthoritativeWindowVerdict.VALID && digestMatch &&
            epochStable && !staleReplayRejected

    /**
     * Short machine-greppable reason for an INVALID window, or null when valid.
     * The order mirrors the observer's evaluation order, so the FIRST failing
     * predicate is the reason (the same precedence the judgement used).
     */
    fun invalidReason(): String? = when {
        windowValid -> null
        !ownerConfigured -> REASON_OWNER_UNCONFIGURED
        !prePresent -> REASON_PRE_NULL
        !postPresent -> REASON_POST_NULL
        verdict != AuthoritativeWindowVerdict.VALID -> verdictReason(verdict)
        !digestMatch -> REASON_DIGEST_MISMATCH
        staleReplayRejected -> REASON_STALE_REPLAY
        !epochStable -> REASON_EPOCH_CHANGED
        else -> REASON_UNKNOWN
    }

    private fun verdictReason(verdict: AuthoritativeWindowVerdict?): String = when (verdict) {
        AuthoritativeWindowVerdict.BOOT_OR_INSTANCE_CHANGED -> "boot_instance_changed"
        AuthoritativeWindowVerdict.SEQUENCE_REGRESSION -> "sequence_regression"
        AuthoritativeWindowVerdict.MUTATING_OR_CHANGED -> "mutating_or_changed"
        // UNHEALTHY conflates several endpoint facts; owner mismatch is the one
        // operators can act on from the outside (wrong module scoped / wrong lane
        // installed), so it gets its own reason. Everything else stays "unhealthy"
        // — the probe renders the full endpoint fields next to it.
        AuthoritativeWindowVerdict.UNHEALTHY -> if (!ownerMatch) "owner_mismatch" else "unhealthy"
        else -> REASON_UNKNOWN
    }

    companion object {
        const val REASON_OWNER_UNCONFIGURED = "owner_unconfigured"
        const val REASON_PRE_NULL = "pre_null"
        const val REASON_POST_NULL = "post_null"
        const val REASON_DIGEST_MISMATCH = "digest_mismatch"
        const val REASON_STALE_REPLAY = "stale_replay"
        const val REASON_EPOCH_CHANGED = "epoch_changed"
        const val REASON_UNKNOWN = "unknown"
    }
}

/** Pure decomposition of one PRE/POST window; see [OracleWindowPredicateTrace]. */
object OracleWindowDiagnostics {

    /**
     * @param pre/post endpoint snapshots as read around the local projection
     *   (null = the source was absent or failed)
     * @param expectedPackage/expectedUid the expected oracle owner identity
     *   (null = owner wiring absent, which is itself a reason)
     * @param expectedDigest the locally computed QwyObservedSemanticDigest the
     *   producer side must echo
     * @param staleReplayRejected true when the replay watermark rejects this window
     * @param epochStable false when a different source epoch was already acknowledged
     *   inside this local owner generation
     */
    fun decompose(
        pre: AuthoritativeContinuitySnapshot?,
        post: AuthoritativeContinuitySnapshot?,
        expectedPackage: String?,
        expectedUid: Int?,
        expectedDigest: String?,
        staleReplayRejected: Boolean = false,
        epochStable: Boolean = true,
    ): OracleWindowPredicateTrace {
        val ownerConfigured = expectedPackage != null && expectedUid != null
        val ownerMatch = ownerConfigured &&
            pre?.ownerPackage == expectedPackage && pre?.ownerUid == expectedUid &&
            post?.ownerPackage == expectedPackage && post?.ownerUid == expectedUid
        val verdict = classifyAuthoritativeWindow(
            pre = pre,
            post = post,
            expectedPackage = expectedPackage ?: "",
            expectedUid = expectedUid ?: Int.MIN_VALUE,
        )
        return OracleWindowPredicateTrace(
            prePresent = pre != null,
            postPresent = post != null,
            ownerConfigured = ownerConfigured,
            ownerMatch = ownerMatch,
            verdict = verdict,
            digestMatch = expectedDigest != null &&
                pre?.qwySemanticDigest == expectedDigest &&
                post?.qwySemanticDigest == expectedDigest,
            epochStable = epochStable,
            staleReplayRejected = staleReplayRejected,
        )
    }
}

/**
 * Read-only live reconstruction of one oracle window plus the observer's
 * predicate decomposition over it. Produced only by the debug probe path
 * (ProviderRuntime.oracleWindowDiagnostics) — never by observe(), never a
 * trust input.
 */
data class OracleWindowLiveDiagnostics(
    val localGeneration: Long,
    val expectedDigest: String?,
    val pre: AuthoritativeContinuitySnapshot?,
    val post: AuthoritativeContinuitySnapshot?,
    val trace: OracleWindowPredicateTrace,
    val acknowledgedCursor: AuthoritativeObservationCursor?,
)
