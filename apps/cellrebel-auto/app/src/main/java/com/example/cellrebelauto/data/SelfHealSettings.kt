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
private val Context.selfHealConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "self_heal_config"
)

/**
 * P1.3 自愈三件套的持久化开关（DataStore，与 [PlanConfigStore] 同一习惯）。
 *
 * Defaults follow the P1.3 acceptance brief:
 *  - attempt watchdog        ON  (the 8+ minute zombie attempt incident);
 *  - coordinate guard        ON  (the 52/51 profile-misalignment quota burn incident);
 *  - service auto-resume     OFF (conservative — highest-blast-radius intervention).
 *
 * # 自愈开关持久化：看门狗默认开、坐标校验默认开、服务重连自动恢复默认关（保守）
 */
data class SelfHealConfig(
    val attemptWatchdogEnabled: Boolean = true,
    val coordinateGuardEnabled: Boolean = true,
    val serviceReconnectAutoResumeEnabled: Boolean = false
)

class SelfHealSettings(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.selfHealConfigDataStore)

    private object Keys {
        val ATTEMPT_WATCHDOG_ENABLED = booleanPreferencesKey("attempt_watchdog_enabled")
        val COORDINATE_GUARD_ENABLED = booleanPreferencesKey("coordinate_guard_enabled")
        val SERVICE_RECONNECT_AUTO_RESUME_ENABLED =
            booleanPreferencesKey("service_reconnect_auto_resume_enabled")
    }

    val config: Flow<SelfHealConfig> = dataStore.data.map { prefs ->
        SelfHealConfig(
            attemptWatchdogEnabled = prefs[Keys.ATTEMPT_WATCHDOG_ENABLED] ?: true,
            coordinateGuardEnabled = prefs[Keys.COORDINATE_GUARD_ENABLED] ?: true,
            serviceReconnectAutoResumeEnabled =
                prefs[Keys.SERVICE_RECONNECT_AUTO_RESUME_ENABLED] ?: false
        )
    }

    // # attempt 看门狗开关（默认 on）
    suspend fun setAttemptWatchdogEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ATTEMPT_WATCHDOG_ENABLED] = enabled }
    }

    // # 配额 commit 前坐标校验开关（默认 on）
    suspend fun setCoordinateGuardEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.COORDINATE_GUARD_ENABLED] = enabled }
    }

    // # 服务重连自动恢复开关（默认 off，保守）
    suspend fun setServiceReconnectAutoResumeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SERVICE_RECONNECT_AUTO_RESUME_ENABLED] = enabled }
    }
}
