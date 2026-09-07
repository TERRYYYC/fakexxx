package com.example.cellrebelauto.cutover

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.cellrebelauto.data.PlanConfigStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlanConfigCutoverStoreTest {
    private val files = mutableListOf<File>()

    @After
    fun deleteFiles() {
        files.forEach(File::delete)
    }

    @Test
    fun mappedDefaultsRemainRawAbsence() = runTest {
        val store = newStore(backgroundScope)

        val raw = store.captureCutoverPreferences()

        assertEquals(CutoverPlanConfigSchema.preferenceTypes.keys.sorted(), raw.map { it.key })
        assertTrue(raw.none { it.present })
        assertTrue(raw.all { it.value == null })
        val mapped = store.config.first()
        assertEquals(90, mapped.testTimeoutSeconds)
        assertEquals(60, mapped.gpsSettleSeconds)
        assertEquals(true, mapped.locationStageEnabled)
        assertEquals(true, mapped.testStageEnabled)
    }

    @Test
    fun rawSnapshotPreservesPresenceInsteadOfMaterializingUnwrittenKeys() = runTest {
        val store = newStore(backgroundScope)
        store.setGlobalBufferSeconds(17)
        store.setLocationStageEnabled(false)

        val raw = store.captureCutoverPreferences().associateBy { it.key }

        assertEquals(CutoverPreferenceEntry("global_buffer_seconds", CutoverPreferenceType.INT, true, "17"), raw.getValue("global_buffer_seconds"))
        assertEquals(CutoverPreferenceEntry("location_stage_enabled", CutoverPreferenceType.BOOLEAN, true, "false"), raw.getValue("location_stage_enabled"))
        assertEquals(false, raw.getValue("test_timeout_seconds").present)
        assertEquals(false, raw.getValue("gps_settle_seconds").present)
        assertEquals(false, raw.getValue("test_stage_enabled").present)
    }

    @Test
    fun oneAtomicReplacementRestoresExactlyFiveRawStatesAndClearReturnsToEmpty() = runTest {
        val store = newStore(backgroundScope)
        val archive = archive(
            listOf(
                entry("global_buffer_seconds", CutoverPreferenceType.INT, true, "21"),
                entry("test_timeout_seconds", CutoverPreferenceType.INT, false, null),
                entry("gps_settle_seconds", CutoverPreferenceType.INT, true, "33"),
                entry("location_stage_enabled", CutoverPreferenceType.BOOLEAN, false, null),
                entry("test_stage_enabled", CutoverPreferenceType.BOOLEAN, true, "false")
            )
        )

        assertEquals(CutoverGenerationState.EMPTY, store.classify(archive))
        store.restore(archive)

        assertEquals(CutoverGenerationState.EXACT, store.classify(archive))
        assertEquals(archive.preferences.sortedBy { it.key }, store.captureCutoverPreferences())
        val mapped = store.config.first()
        assertEquals(21, mapped.globalBufferSeconds)
        assertEquals(90, mapped.testTimeoutSeconds)
        assertEquals(33, mapped.gpsSettleSeconds)
        assertEquals(true, mapped.locationStageEnabled)
        assertEquals(false, mapped.testStageEnabled)

        store.clear()
        assertEquals(CutoverGenerationState.EMPTY, store.classify(archive))
        assertTrue(store.captureCutoverPreferences().none { it.present })
    }

    @Test
    fun restoreRefusesToOverwriteAnyExistingRawPreference() = runTest {
        val store = newStore(backgroundScope)
        store.setTestTimeoutSeconds(44)
        val archive = archive(allAbsent())

        assertEquals(CutoverGenerationState.MISMATCH, store.classify(archive))
        val failure = runCatching { store.restore(archive) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("target PlanConfig is not empty"))
        assertEquals(44, store.config.first().testTimeoutSeconds)
    }

    @Test
    fun allAbsentArchiveIsBothAnEmptyTargetAndAnExactGeneration() = runTest {
        val store = newStore(backgroundScope)
        val archive = archive(allAbsent())

        val before = store.classify(archive)
        assertTrue(before.isEmpty)
        assertTrue(before.matchesArchive)
        store.restore(archive)
        val after = store.classify(archive)
        assertTrue(after.isEmpty)
        assertTrue(after.matchesArchive)
        assertTrue(store.captureCutoverPreferences().none { it.present })
    }

    private fun newStore(scope: CoroutineScope): PlanConfigStore {
        val file = File(
            System.getProperty("java.io.tmpdir"),
            "cutover-plan-config-${UUID.randomUUID()}.preferences_pb"
        ).also(files::add)
        return PlanConfigStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        )
    }

    private fun archive(preferences: List<CutoverPreferenceEntry>) = CutoverArchiveV2(
        sourcePackage = "com.example.cellrebelauto",
        captureId = "capture-preferences",
        schemaVersion = 9,
        tables = emptyList(),
        preferences = preferences
    )

    private fun allAbsent() = CutoverPlanConfigSchema.preferenceTypes.map { (key, type) ->
        entry(key, type, false, null)
    }

    private fun entry(
        key: String,
        type: CutoverPreferenceType,
        present: Boolean,
        value: String?
    ) = CutoverPreferenceEntry(key, type, present, value)
}
