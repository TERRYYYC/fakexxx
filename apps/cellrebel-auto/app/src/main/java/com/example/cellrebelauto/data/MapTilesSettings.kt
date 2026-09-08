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
private val Context.mapTilesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "map_tiles"
)

/**
 * Tile map (T-tilemap, 2026-09-08) — the persisted `map_tiles_enabled` switch
 * (DataStore, same habit as [SelfHealSettings] / [DashboardMetricsSettings]).
 *
 * Default ON: the run dashboard prefers the real OSM tile map. Switching OFF
 * degrades the dashboard to the existing abstract canvas map — the offline
 * fallback stays in the codebase permanently. The engine never reads this key;
 * it is a display-only preference.
 *
 * # 瓦片底图开关：DataStore `map_tiles_enabled`，默认开，关=回退抽象地图
 */
class MapTilesSettings(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.mapTilesDataStore)

    private object Keys {
        val MAP_TILES_ENABLED = booleanPreferencesKey("map_tiles_enabled")
    }

    /** Default true — tiles when the switch, network and tile health allow. */
    val enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.MAP_TILES_ENABLED] ?: true
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MAP_TILES_ENABLED] = enabled }
    }
}
