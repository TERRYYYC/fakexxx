package com.example.cellrebelauto.cutover

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CutoverControlStoreTest {
    private val files = mutableListOf<File>()
    private val identity = CutoverRestoreIdentity(
        "sha256:${"c".repeat(64)}",
        "capture-control"
    )

    @After
    fun deleteFiles() {
        files.forEach(File::delete)
    }

    @Test
    fun absentFileHasNoJournalAndEveryWriteRoundTripsExactly() = runTest {
        val (_, store) = newStore(backgroundScope)
        assertNull(store.read())

        val staged = CutoverRestoreJournal(identity, CutoverRestorePhase.STAGED)
        store.write(staged)
        assertEquals(staged, store.read())

        val rollback = CutoverRestoreJournal(
            identity,
            CutoverRestorePhase.ROLLBACK_REQUIRED,
            CutoverRestoreFailureReason.ROOM_WRITE_FAILED
        )
        store.write(rollback)
        assertEquals(rollback, store.read())
    }

    @Test
    fun journalSurvivesAStoreAndScopeRestart() = runTest {
        val file = file()
        val scopeJob = Job()
        val scope1 = CoroutineScope(backgroundScope.coroutineContext + scopeJob)
        val dataStore1 = PreferenceDataStoreFactory.create(scope = scope1, produceFile = { file })
        CutoverControlStore(dataStore1).write(
            CutoverRestoreJournal(identity, CutoverRestorePhase.DATASTORE_WRITTEN)
        )
        scopeJob.cancel()
        scopeJob.join()

        val dataStore2 = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        assertEquals(
            CutoverRestorePhase.DATASTORE_WRITTEN,
            CutoverControlStore(dataStore2).read()?.phase
        )
    }

    @Test
    fun partialOrUnknownDurableJournalFailsClosed() = runTest {
        val (dataStore, store) = newStore(backgroundScope)
        dataStore.edit { it[stringPreferencesKey("phase")] = "STAGED" }

        val partial = runCatching { store.read() }.exceptionOrNull()
        assertTrue(partial is IllegalStateException)
        assertTrue(partial?.message.orEmpty().contains("incomplete cutover journal"))

        dataStore.edit {
            it[stringPreferencesKey("archive_digest")] = identity.archiveDigest
            it[stringPreferencesKey("capture_id")] = identity.captureId
            it[stringPreferencesKey("phase")] = "UNKNOWN"
        }
        val unknown = runCatching { store.read() }.exceptionOrNull()
        assertTrue(unknown is IllegalStateException)
        assertTrue(unknown?.message.orEmpty().contains("invalid cutover phase"))
    }

    private fun newStore(scope: CoroutineScope): Pair<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, CutoverControlStore> {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file() })
        return dataStore to CutoverControlStore(dataStore)
    }

    private fun file(): File = File(
        System.getProperty("java.io.tmpdir"),
        "cutover-control-${UUID.randomUUID()}.preferences_pb"
    ).also(files::add)
}
