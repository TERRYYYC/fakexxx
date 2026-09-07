package com.example.cellrebelauto.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.cellrebelauto.cutover.CutoverArchiveV2
import com.example.cellrebelauto.cutover.CutoverGenerationState
import com.example.cellrebelauto.cutover.CutoverPlanConfigSchema
import com.example.cellrebelauto.cutover.CutoverPreferenceEntry
import com.example.cellrebelauto.cutover.CutoverPreferenceGenerationPort
import com.example.cellrebelauto.cutover.CutoverPreferenceSnapshotPort
import com.example.cellrebelauto.cutover.CutoverPreferenceType
import com.example.cellrebelauto.model.plan.PlanConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
) : CutoverPreferenceGenerationPort, CutoverPreferenceSnapshotPort {
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

    /** Raw five-key projection for cutover. Mapped runtime defaults are deliberately not read here. */
    override suspend fun captureCutoverPreferences(): List<CutoverPreferenceEntry> {
        val preferences = dataStore.data.first()
        return listOf(
            intEntry("global_buffer_seconds", preferences[Keys.GLOBAL_BUFFER_SECONDS]),
            intEntry("test_timeout_seconds", preferences[Keys.TEST_TIMEOUT_SECONDS]),
            intEntry("gps_settle_seconds", preferences[Keys.GPS_SETTLE_SECONDS]),
            booleanEntry("location_stage_enabled", preferences[Keys.LOCATION_STAGE_ENABLED]),
            booleanEntry("test_stage_enabled", preferences[Keys.TEST_STAGE_ENABLED])
        ).sortedBy { it.key }
    }

    override suspend fun classify(archive: CutoverArchiveV2): CutoverGenerationState {
        val expected = validateCutoverPreferences(archive.preferences)
        val current = captureCutoverPreferences()
        return CutoverGenerationState(
            isEmpty = current.none { it.present },
            matchesArchive = current == expected
        )
    }

    override suspend fun restore(archive: CutoverArchiveV2) {
        val entries = validateCutoverPreferences(archive.preferences)
        dataStore.edit { preferences ->
            check(
                !preferences.contains(Keys.GLOBAL_BUFFER_SECONDS) &&
                    !preferences.contains(Keys.TEST_TIMEOUT_SECONDS) &&
                    !preferences.contains(Keys.GPS_SETTLE_SECONDS) &&
                    !preferences.contains(Keys.LOCATION_STAGE_ENABLED) &&
                    !preferences.contains(Keys.TEST_STAGE_ENABLED)
            ) { "target PlanConfig is not empty" }
            replaceCutoverPreferences(preferences, entries)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.GLOBAL_BUFFER_SECONDS)
            preferences.remove(Keys.TEST_TIMEOUT_SECONDS)
            preferences.remove(Keys.GPS_SETTLE_SECONDS)
            preferences.remove(Keys.LOCATION_STAGE_ENABLED)
            preferences.remove(Keys.TEST_STAGE_ENABLED)
        }
    }

    private fun replaceCutoverPreferences(
        preferences: MutablePreferences,
        entries: List<CutoverPreferenceEntry>
    ) {
        val byKey = entries.associateBy { it.key }
        byKey.getValue("global_buffer_seconds").presentInt()?.let {
            preferences[Keys.GLOBAL_BUFFER_SECONDS] = it
        }
        byKey.getValue("test_timeout_seconds").presentInt()?.let {
            preferences[Keys.TEST_TIMEOUT_SECONDS] = it
        }
        byKey.getValue("gps_settle_seconds").presentInt()?.let {
            preferences[Keys.GPS_SETTLE_SECONDS] = it
        }
        byKey.getValue("location_stage_enabled").presentBoolean()?.let {
            preferences[Keys.LOCATION_STAGE_ENABLED] = it
        }
        byKey.getValue("test_stage_enabled").presentBoolean()?.let {
            preferences[Keys.TEST_STAGE_ENABLED] = it
        }
    }

    private fun validateCutoverPreferences(
        entries: List<CutoverPreferenceEntry>
    ): List<CutoverPreferenceEntry> {
        require(entries.size == CutoverPlanConfigSchema.preferenceTypes.size) {
            "cutover PlanConfig must contain exactly five keys"
        }
        val byKey = entries.associateBy { it.key }
        require(byKey.size == entries.size && byKey.keys == CutoverPlanConfigSchema.preferenceTypes.keys) {
            "cutover PlanConfig key census mismatch"
        }
        entries.forEach { entry ->
            require(entry.type == CutoverPlanConfigSchema.preferenceTypes.getValue(entry.key)) {
                "cutover PlanConfig type mismatch for ${entry.key}"
            }
            if (entry.present) {
                when (entry.type) {
                    CutoverPreferenceType.INT -> entry.presentInt()
                    CutoverPreferenceType.BOOLEAN -> entry.presentBoolean()
                }
            } else {
                require(entry.value == null) { "absent cutover PlanConfig value must be null" }
            }
        }
        return entries.sortedBy { it.key }
    }

    private fun intEntry(key: String, value: Int?): CutoverPreferenceEntry = CutoverPreferenceEntry(
        key = key,
        type = CutoverPreferenceType.INT,
        present = value != null,
        value = value?.toString()
    )

    private fun booleanEntry(key: String, value: Boolean?): CutoverPreferenceEntry = CutoverPreferenceEntry(
        key = key,
        type = CutoverPreferenceType.BOOLEAN,
        present = value != null,
        value = value?.toString()
    )

    private fun CutoverPreferenceEntry.presentInt(): Int? {
        if (!present) return null
        val raw = requireNotNull(value) { "present cutover PlanConfig integer is missing" }
        val parsed = raw.toIntOrNull()
        require(parsed != null && parsed.toString() == raw) { "non-canonical cutover PlanConfig integer" }
        return parsed
    }

    private fun CutoverPreferenceEntry.presentBoolean(): Boolean? {
        if (!present) return null
        return when (val raw = requireNotNull(value) { "present cutover PlanConfig boolean is missing" }) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("non-canonical cutover PlanConfig boolean: $raw")
        }
    }

}
