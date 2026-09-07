package com.example.cellrebelauto.automation.selfheal

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the service-reconnect self-heal bookkeeping (SharedPreferences — the recycle
 * write happens on the #15a destroy path where the engine deliberately avoids coroutine-based IO;
 * `apply()` is an in-memory write with a background flush, so the synchronous terminal publish
 * stays first and unblocked).
 *
 * Two records live here:
 *  - the PENDING RECYCLE MARKER: (planId, recycledAtMs) written when a run was active when the
 *    accessibility service instance was recycled; consumed by the NEXT instance's connect callback;
 *  - the AUTO-RESUME LEDGER: timestamps of every auto-resume action — the rolling 30-min ≤3 budget.
 *
 * # 服务回收标记 + 自动恢复预算台账：onDestroy 路径用 apply()（内存写 + 后台落盘），绝不阻塞 #15a 的同步终态发布
 */
class ServiceRecycleMarkerStore(
    context: Context,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Written on the recycle path when a plan run was active at recycle time. */
    fun saveRecycle(planId: Long, recycledAtMs: Long = nowMs()) {
        prefs.edit()
            .putLong(KEY_RECYCLE_PLAN_ID, planId)
            .putLong(KEY_RECYCLE_AT_MS, recycledAtMs)
            .apply()
    }

    fun pendingRecycle(): ServiceReconnectAutoResumePolicy.RecycleMarker? {
        if (!prefs.contains(KEY_RECYCLE_PLAN_ID)) return null
        return ServiceReconnectAutoResumePolicy.RecycleMarker(
            planId = prefs.getLong(KEY_RECYCLE_PLAN_ID, -1L),
            recycledAtMs = prefs.getLong(KEY_RECYCLE_AT_MS, 0L)
        )
    }

    /** Consumed when the reconnect callback acts on it (auto-resume executed or the run restarted). */
    fun clearPendingRecycle() {
        prefs.edit().remove(KEY_RECYCLE_PLAN_ID).remove(KEY_RECYCLE_AT_MS).apply()
    }

    /**
     * One entry per executed auto-resume — the budget window reads this. Serialized as ONE
     * comma-joined string (NOT a StringSet: sets collapse same-ms entries and mis-order, which
     * would under-count the budget and could permit a restart storm). Timestamps stay strictly
     * increasing.
     */
    fun recordAutoResume(atMs: Long = nowMs()) {
        val next = maxOf(atMs, (autoResumeTimestamps().maxOrNull() ?: Long.MIN_VALUE) + 1)
        prefs.edit().putString(KEY_AUTO_RESUME_TIMESTAMPS, (autoResumeTimestamps() + next).joinToString(",")).apply()
    }

    fun autoResumeTimestamps(): List<Long> =
        prefs.getString(KEY_AUTO_RESUME_TIMESTAMPS, null)
            ?.split(',')?.takeIf { it.firstOrNull()?.isNotBlank() == true }
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()

    companion object {
        private const val PREFS_NAME = "self_heal_service"
        private const val KEY_RECYCLE_PLAN_ID = "recycle_plan_id"
        private const val KEY_RECYCLE_AT_MS = "recycle_at_ms"
        private const val KEY_AUTO_RESUME_TIMESTAMPS = "auto_resume_timestamps_csv"
    }
}
