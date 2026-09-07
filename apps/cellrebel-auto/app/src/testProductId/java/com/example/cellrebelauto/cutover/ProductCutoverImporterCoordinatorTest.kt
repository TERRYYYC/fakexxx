package com.example.cellrebelauto.cutover

import android.net.Uri
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductCutoverImporterCoordinatorTest {

    @Test
    fun eligibilityLossRollbackIsNotReportedAsImportSuccess() = runTest {
        val journal = JournalPort()
        val room = GenerationPort()
        val preferences = GenerationPort()
        val coordinator = coordinator(
            gate = CutoverAccessGate.open(),
            journal = journal,
            room = room,
            preferences = preferences,
            eligibility = EligibilityPort(
                CutoverEligibility.ELIGIBLE,
                CutoverEligibility.ELIGIBLE,
                CutoverEligibility.INELIGIBLE
            )
        )

        val result = importer(coordinator).import(SELECTED_URI)

        assertEquals(CutoverRestorePhase.ROLLED_BACK, journal.current?.phase)
        assertTrue(room.state.isEmpty)
        assertTrue(preferences.state.isEmpty)
        assertEquals(
            ProductCutoverImportResult.RolledBack(archiveDigest()),
            result
        )
    }

    @Test
    fun interruptedRestoreReselectionReportsRollbackBeforeAThirdAttemptImports() = runTest {
        val journal = JournalPort()
        val room = GenerationPort()
        val preferences = CancelOnceAfterRoomWrittenPreferencePort(journal)
        val gate = CutoverAccessGate.open()
        val coordinator = coordinator(
            gate,
            journal,
            room,
            preferences,
            EligibilityPort(*Array(6) { CutoverEligibility.ELIGIBLE })
        )

        val firstFailure = runCatching { importer(coordinator).import(SELECTED_URI) }.exceptionOrNull()

        assertTrue(firstFailure is CancellationException)
        assertEquals(CutoverRestorePhase.ROLLBACK_REQUIRED, journal.current?.phase)

        val second = importer(coordinator).import(SELECTED_URI)
        assertEquals(CutoverRestorePhase.ROLLED_BACK, journal.current?.phase)
        assertEquals(
            ProductCutoverImportResult.RolledBack(archiveDigest()),
            second
        )

        val third = importer(coordinator).import(SELECTED_URI)
        assertEquals(CutoverRestorePhase.READY, journal.current?.phase)
        assertTrue(third is ProductCutoverImportResult.Completed)
    }

    private fun importer(coordinator: AutoCutoverRestoreCoordinator): ProductCutoverImporter {
        val encoded = CutoverArchiveV2Codec(POLICY).encode(ARCHIVE)
        return ProductCutoverImporter(
            contentType = { CutoverSafContract.MEDIA_TYPE },
            openInputStream = {
                ByteArrayInputStream(encoded.serialized.toByteArray(Charsets.UTF_8))
            },
            policy = { POLICY },
            restore = coordinator::restore
        )
    }

    private fun archiveDigest(): String = CutoverArchiveV2Codec(POLICY).encode(ARCHIVE).archiveDigest

    private fun coordinator(
        gate: CutoverAccessGate,
        journal: JournalPort,
        room: CutoverRoomGenerationPort,
        preferences: CutoverPreferenceGenerationPort,
        eligibility: CutoverEligibilityPort
    ) = AutoCutoverRestoreCoordinator(
        accessGate = gate,
        journalPort = journal,
        roomPort = room,
        preferencePort = preferences,
        eligibilityPort = eligibility
    )

    private class JournalPort : CutoverRestoreJournalPort {
        var current: CutoverRestoreJournal? = null

        override suspend fun read(): CutoverRestoreJournal? = current

        override suspend fun write(journal: CutoverRestoreJournal) {
            current = journal
        }
    }

    private open class GenerationPort : CutoverRoomGenerationPort, CutoverPreferenceGenerationPort {
        var state: CutoverGenerationState = CutoverGenerationState.EMPTY

        override suspend fun classify(archive: CutoverArchiveV2): CutoverGenerationState = state

        override suspend fun restore(archive: CutoverArchiveV2) {
            state = CutoverGenerationState.EXACT
        }

        override suspend fun clear() {
            state = CutoverGenerationState.EMPTY
        }
    }

    private class CancelOnceAfterRoomWrittenPreferencePort(
        private val journal: JournalPort
    ) : GenerationPort() {
        private var cancelled = false

        override suspend fun classify(archive: CutoverArchiveV2): CutoverGenerationState {
            if (!cancelled && journal.current?.phase == CutoverRestorePhase.ROOM_WRITTEN) {
                cancelled = true
                throw CancellationException("synthetic interruption after Room commit")
            }
            return super.classify(archive)
        }
    }

    private class EligibilityPort(
        vararg observations: CutoverEligibility
    ) : CutoverEligibilityPort {
        private val values = ArrayDeque(observations.asList())

        override suspend fun observe(): CutoverEligibility =
            values.removeFirstOrNull() ?: error("unexpected eligibility observation")
    }

    private companion object {
        val SELECTED_URI: Uri = Uri.parse("content://operator/archive")
        val ARCHIVE = CutoverArchiveV2(
            sourcePackage = AndroidCutoverEligibilityPort.LEGACY_PACKAGE,
            captureId = "capture-coordinator",
            schemaVersion = 9,
            tables = listOf(
                CutoverTableSection(
                    name = "provider_pairing_records",
                    schemaDigest = "sha256:${"1".repeat(64)}",
                    restorationMode = CutoverRestorationMode.HISTORICAL_ONLY,
                    rows = emptyList()
                )
            ),
            preferences = CutoverPlanConfigSchema.preferenceTypes.map { (key, type) ->
                CutoverPreferenceEntry(key, type, present = false, value = null)
            }
        )
        val POLICY = CutoverArchivePolicy(
            schemaVersion = 9,
            requiredTableSchemaDigests = mapOf(
                "provider_pairing_records" to "sha256:${"1".repeat(64)}"
            ),
            historicalOnlyTables = setOf("provider_pairing_records")
        )
    }
}
