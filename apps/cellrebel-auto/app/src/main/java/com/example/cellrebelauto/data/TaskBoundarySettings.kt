package com.example.cellrebelauto.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// # 应用级 DataStore 委托（测试用 PreferenceDataStoreFactory 注入独立文件）
private val Context.taskBoundaryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "task_boundary"
)

/**
 * [task-boundary monitor 2026-09-13] — the persisted `return_to_monitor_on_task_boundary`
 * switch (DataStore, same habit as [SelfHealSettings] / [MapTilesSettings]).
 *
 * Default ON: when a task's quota is reached (boundary), the engine brings this app's
 * run dashboard to the FOREGROUND so the operator sees the last round's verified
 * position (durable projection: task states + trusted counts — the same source the
 * map card renders). Switching OFF keeps the phone wherever the test stage left it.
 *
 * The engine never reads this key directly — the Service wiring reads it inside the
 * injected `taskBoundaryAction` hook, keeping the engine JVM-testable (hook seam).
 *
 * # 任务边界回监控页开关：DataStore，默认开；关=边界时不切前台
 */
class TaskBoundarySettings(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.taskBoundaryDataStore)

    private object Keys {
        val RETURN_TO_MONITOR = booleanPreferencesKey("return_to_monitor_on_task_boundary")
    }

    /** Default true — the boundary surfaces the dashboard automatically. */
    val returnToMonitor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.RETURN_TO_MONITOR] ?: true
    }

    suspend fun setReturnToMonitor(enabled: Boolean) {
        dataStore.edit { it[Keys.RETURN_TO_MONITOR] = enabled }
    }
}
