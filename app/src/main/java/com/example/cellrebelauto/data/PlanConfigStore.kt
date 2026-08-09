package com.example.cellrebelauto.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.cellrebelauto.model.plan.PlanConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// # 应用级 DataStore 委托（测试用 PreferenceDataStoreFactory 注入独立文件）
private val Context.planConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "plan_config"
)

/**
 * DataStore-backed persistence for PlanConfig (O6). Each field is writable
 * independently; the buffer key stays absent until first set (null default).
 * # PlanConfig 的 DataStore 持久化。各字段可独立写入；
 * # 缓冲键在首次设置前保持缺省（默认 null）
 */
class PlanConfigStore(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.planConfigDataStore)

    private object Keys {
        val GLOBAL_BUFFER_SECONDS = intPreferencesKey("global_buffer_seconds")
        val TEST_TIMEOUT_SECONDS = intPreferencesKey("test_timeout_seconds")
        val GPS_SETTLE_SECONDS = intPreferencesKey("gps_settle_seconds")
        // # F003：阶段开关（缺省 = 默认开）
        val LOCATION_STAGE_ENABLED = booleanPreferencesKey("location_stage_enabled")
        val TEST_STAGE_ENABLED = booleanPreferencesKey("test_stage_enabled")
    }

    val config: Flow<PlanConfig> = dataStore.data.map { prefs ->
        PlanConfig(
            globalBufferSeconds = prefs[Keys.GLOBAL_BUFFER_SECONDS],
            testTimeoutSeconds = prefs[Keys.TEST_TIMEOUT_SECONDS] ?: 90,
            gpsSettleSeconds = prefs[Keys.GPS_SETTLE_SECONDS] ?: 60,
            locationStageEnabled = prefs[Keys.LOCATION_STAGE_ENABLED] ?: true,
            testStageEnabled = prefs[Keys.TEST_STAGE_ENABLED] ?: true
        )
    }

    suspend fun setGlobalBufferSeconds(seconds: Int) {
        dataStore.edit { it[Keys.GLOBAL_BUFFER_SECONDS] = seconds }
    }

    suspend fun setTestTimeoutSeconds(seconds: Int) {
        dataStore.edit { it[Keys.TEST_TIMEOUT_SECONDS] = seconds }
    }

    suspend fun setGpsSettleSeconds(seconds: Int) {
        dataStore.edit { it[Keys.GPS_SETTLE_SECONDS] = seconds }
    }

    // # F003：位置阶段开关（运行时偏好，下个 attempt 生效）
    suspend fun setLocationStageEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.LOCATION_STAGE_ENABLED] = enabled }
    }

    // # F003：CellRebel 测试阶段开关（运行时偏好，下个 attempt 生效）
    suspend fun setTestStageEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TEST_STAGE_ENABLED] = enabled }
    }
}
