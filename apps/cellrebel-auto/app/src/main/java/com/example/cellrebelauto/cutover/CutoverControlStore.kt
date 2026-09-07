package com.example.cellrebelauto.cutover

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.cutoverControlDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cutover_control"
)

/** Durable restore journal kept outside both the Room payload and the five PlanConfig keys. */
class CutoverControlStore(
    private val dataStore: DataStore<Preferences>
) : CutoverRestoreJournalPort {
    constructor(context: Context) : this(context.cutoverControlDataStore)

    override suspend fun read(): CutoverRestoreJournal? {
        val preferences = dataStore.data.first()
        val archiveDigest = preferences[Keys.ARCHIVE_DIGEST]
        val captureId = preferences[Keys.CAPTURE_ID]
        val phaseName = preferences[Keys.PHASE]
        val failureName = preferences[Keys.FAILURE_REASON]
        if (archiveDigest == null && captureId == null && phaseName == null && failureName == null) {
            return null
        }
        check(archiveDigest != null && captureId != null && phaseName != null) {
            "incomplete cutover journal"
        }
        val phase = enumValueOrNull<CutoverRestorePhase>(phaseName)
            ?: error("invalid cutover phase: $phaseName")
        val failure = failureName?.let {
            enumValueOrNull<CutoverRestoreFailureReason>(it)
                ?: error("invalid cutover failure reason: $it")
        }
        return try {
            CutoverRestoreJournal(
                identity = CutoverRestoreIdentity(archiveDigest, captureId),
                phase = phase,
                failureReason = failure
            )
        } catch (invalid: IllegalArgumentException) {
            throw IllegalStateException("invalid cutover journal", invalid)
        }
    }

    override suspend fun write(journal: CutoverRestoreJournal) {
        dataStore.edit { preferences ->
            preferences[Keys.ARCHIVE_DIGEST] = journal.identity.archiveDigest
            preferences[Keys.CAPTURE_ID] = journal.identity.captureId
            preferences[Keys.PHASE] = journal.phase.name
            val failure = journal.failureReason
            if (failure == null) {
                preferences.remove(Keys.FAILURE_REASON)
            } else {
                preferences[Keys.FAILURE_REASON] = failure.name
            }
        }
    }

    private object Keys {
        val ARCHIVE_DIGEST = stringPreferencesKey("archive_digest")
        val CAPTURE_ID = stringPreferencesKey("capture_id")
        val PHASE = stringPreferencesKey("phase")
        val FAILURE_REASON = stringPreferencesKey("failure_reason")
    }
}

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }
