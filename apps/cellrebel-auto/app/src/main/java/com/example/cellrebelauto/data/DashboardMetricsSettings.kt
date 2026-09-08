package com.example.cellrebelauto.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.cellrebelauto.ui.dashboard.v2.MetricKey
import com.example.cellrebelauto.ui.dashboard.v2.MetricPillFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// # 应用级 DataStore 委托（测试用 PreferenceDataStoreFactory 注入独立文件）
private val Context.dashboardMetricsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dashboard_metrics"
)

/**
 * T7v2 §A1-v2 #4 — the metric-pill row's persisted selection (DataStore, same
 * habit as [SelfHealSettings] / [PlanConfigStore]). Storage format is the key
 * names joined by ","; reading ALWAYS routes through
 * [MetricPillFormatter.sanitize], so a corrupt or legacy value degrades to the
 * default [MetricKey.PROGRESS] instead of crashing or rendering nonsense.
 *
 * # 指标选择持久化：1..3 个 pill 的键名 CSV；读取端同样过 sanitize 兜底
 */
class DashboardMetricsSettings(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.dashboardMetricsDataStore)

    private object Keys {
        val SELECTED_METRICS = stringPreferencesKey("selected_metrics_csv")
    }

    val selection: Flow<List<MetricKey>> = dataStore.data.map { prefs ->
        fromCsv(prefs[Keys.SELECTED_METRICS])
    }

    /** Persists the sanitized selection; sanitize caps at three and never empties. */
    suspend fun setSelection(keys: List<MetricKey>) {
        dataStore.edit { it[Keys.SELECTED_METRICS] = toCsv(MetricPillFormatter.sanitize(keys)) }
    }

    internal fun toCsv(keys: List<MetricKey>): String =
        keys.joinToString(",") { it.name }

    internal fun fromCsv(csv: String?): List<MetricKey> = MetricPillFormatter.sanitize(
        csv?.split(',')
            ?.mapNotNull {
                runCatching { MetricKey.valueOf(it.trim()) }.getOrNull()
            }
            .orEmpty()
    )
}
