package com.example.cellrebelauto.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * Tile map (T-tilemap, 2026-09-08) — the persisted `map_tiles_enabled` switch.
 *
 * The dashboard's dual-card fallback is driven by this DataStore key: default
 * ON (tiles when possible), user OFF → the abstract canvas map. The value must
 * survive process restarts (a NEW settings instance over the SAME file reads
 * back what the previous one wrote) — the B-层 acceptance "开关持久化" oracle.
 *
 * Killing mutations: an in-memory-only switch (never hitting DataStore) fails
 * the restart readback; a default of false fails the fresh-file assertion.
 *
 * # 瓦片开关 oracle：默认开、写入落盘、跨实例（进程重启语义）可读回
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MapTilesSettingsTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        file = File(System.getProperty("java.io.tmpdir"), "map-tiles-test-${UUID.randomUUID()}.preferences_pb")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        file.delete()
    }

    // One ACTIVE DataStore per file at a time: each instance owns a scope, and a
    // NEW instance over the same file (process-restart semantics) is only legal
    // after the previous scope is cancelled. DataStore throws lazily on first
    // READ, so the retry wraps the read itself.
    private var scope: CoroutineScope? = null

    private fun settings(): MapTilesSettings {
        val s = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.Job())
        scope = s
        return MapTilesSettings(PreferenceDataStoreFactory.create(scope = s, produceFile = { file }))
    }

    private fun readAfterRestart(): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            try {
                scope?.cancel()
                val reader = settings()
                return kotlinx.coroutines.runBlocking { reader.enabled.first() }
            } catch (e: IllegalStateException) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun `fresh install defaults to tiles enabled`() = runTest {
        assertEquals(true, settings().enabled.first())
    }

    @Test
    fun `user switch persists across instances (process restart semantics)`() = runTest {
        val writer = settings()
        writer.setEnabled(false)
        assertEquals(false, writer.enabled.first())
        // a NEW instance over the SAME file — what the next process launch sees
        assertEquals(false, readAfterRestart())
    }

    @Test
    fun `re-enabling flips the persisted value back`() = runTest {
        val writer = settings()
        writer.setEnabled(false)
        writer.setEnabled(true)
        assertEquals(true, readAfterRestart())
    }
}
