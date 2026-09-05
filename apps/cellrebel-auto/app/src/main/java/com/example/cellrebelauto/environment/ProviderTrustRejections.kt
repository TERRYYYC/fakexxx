package com.example.cellrebelauto.environment

/**
 * Issue #10: the process-wide record of the §6.5.3 gate's LATEST rejection.
 *
 * WHY: a revoke mis-touch makes [ProviderTrustGate] fail-close every contract call; discover
 * returns null and the engine used to log only "provider discover failed or protocol
 * incompatible (v1 required)". The gate records its typed rejection here (and Log.w's it), so
 * logcat AND the engine's pause message can name the actual principal cause.
 *
 * Process-wide storage is correlated to a discover generation. A rejection may diagnose only
 * the attempt that opened that generation; beginning the next attempt clears stale evidence and
 * consuming a matching rejection is one-shot.
 *
 * # gate 最近一次拒绝记录：撤销后的 discover=null 必须能追到 typed 真因（进程级单例，latest-wins）
 */
object ProviderTrustRejections {

    data class Rejection(
        val applicationId: String,
        val signerDigest: String?,
        val because: String,
        val atEpochMs: Long,
        val generation: Long,
    )

    @Volatile
    private var latest: Rejection? = null
    private var generation: Long = 0L
    private var activeApplicationId: String? = null

    @Synchronized
    fun beginAttempt(applicationId: String): Long {
        generation += 1
        activeApplicationId = applicationId
        latest = null
        return generation
    }

    @Synchronized
    fun record(applicationId: String, signerDigest: String?, because: String) {
        if (activeApplicationId != null && activeApplicationId != applicationId) return
        latest = Rejection(
            applicationId = applicationId,
            signerDigest = signerDigest,
            because = because,
            atEpochMs = System.currentTimeMillis(),
            generation = generation,
        )
    }

    fun latestRejection(): Rejection? = latest

    @Synchronized
    fun clear(applicationId: String) {
        if (latest?.applicationId == applicationId) latest = null
    }

    @Synchronized
    fun consume(attemptGeneration: Long, applicationId: String): Rejection? {
        val rejection = latest?.takeIf {
            it.generation == attemptGeneration &&
                it.applicationId == applicationId &&
                activeApplicationId == applicationId
        }
        if (rejection != null) latest = null
        return rejection
    }

    /** Test seam: isolate the process-wide state between tests. */
    fun reset() {
        latest = null
        activeApplicationId = null
        generation = 0L
    }
}
