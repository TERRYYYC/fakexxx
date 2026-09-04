package com.example.cellrebelauto.environment

/**
 * Issue #10: the process-wide record of the §6.5.3 gate's LATEST rejection.
 *
 * WHY: a revoke mis-touch makes [ProviderTrustGate] fail-close every contract call; discover
 * returns null and the engine used to log only "provider discover failed or protocol
 * incompatible (v1 required)". The gate records its typed rejection here (and Log.w's it), so
 * logcat AND the engine's pause message can name the actual principal cause.
 *
 * Process-wide on purpose: the gate runs inside per-run decorators/compositions, but there is
 * exactly ONE operator-visible truth per process at any moment. Latest-wins; a successful check
 * does NOT clear it (the record answers "what did the gate last reject", not "is it trusted
 * now").
 *
 * # gate 最近一次拒绝记录：撤销后的 discover=null 必须能追到 typed 真因（进程级单例，latest-wins）
 */
object ProviderTrustRejections {

    data class Rejection(
        val applicationId: String,
        val signerDigest: String?,
        val because: String,
        val atEpochMs: Long,
    )

    @Volatile
    private var latest: Rejection? = null

    fun record(applicationId: String, signerDigest: String?, because: String) {
        latest = Rejection(
            applicationId = applicationId,
            signerDigest = signerDigest,
            because = because,
            atEpochMs = System.currentTimeMillis(),
        )
    }

    fun latestRejection(): Rejection? = latest

    /** Test seam: isolate the process-wide state between tests. */
    fun reset() {
        latest = null
    }
}
