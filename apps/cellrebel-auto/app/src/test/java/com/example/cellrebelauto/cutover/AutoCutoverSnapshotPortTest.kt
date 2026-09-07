package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoCutoverSnapshotPortTest {
    private val policy = CutoverArchivePolicy(
        schemaVersion = 9,
        requiredTableSchemaDigests = mapOf(
            "location_plans" to "sha256:${"1".repeat(64)}",
            "provider_pairing_records" to "sha256:${"2".repeat(64)}"
        ),
        historicalOnlyTables = setOf("provider_pairing_records")
    )
    private val tables = policy.requiredTableSchemaDigests.map { (name, digest) ->
        CutoverTableSection(
            name,
            digest,
            if (name == "provider_pairing_records") {
                CutoverRestorationMode.HISTORICAL_ONLY
            } else {
                CutoverRestorationMode.EXACT
            },
            emptyList()
        )
    }
    private val preferences = CutoverPlanConfigSchema.preferenceTypes.map { (key, type) ->
        CutoverPreferenceEntry(key, type, present = false, value = null)
    }

    @Test
    fun captureCombinesSchemaNineRoomAndRawPreferencesIntoCodecVerifiedArchive() = runTest {
        val room = FakeRoomSnapshotPort(policy, tables)
        val raw = FakePreferenceSnapshotPort(preferences)
        val port = AutoCutoverSnapshotPort(
            CutoverAccessGate.open(),
            CutoverRunQuiescencePort { true },
            room,
            raw
        )

        val result = port.capture("capture-source")

        val completed = requireType<AutoCutoverSnapshotResult.Completed>(result)
        val decoded = CutoverArchiveV2Codec(policy).decode(completed.encoded.serialized)
        assertEquals("capture-source", decoded.archive.captureId)
        assertEquals(tables.sortedBy { it.name }, decoded.archive.tables)
        assertEquals(preferences.sortedBy { it.key }, decoded.archive.preferences)
        assertEquals(completed.encoded.archiveDigest, decoded.archiveDigest)
        assertEquals(1, room.captureCalls)
        assertEquals(1, raw.captureCalls)
    }

    @Test
    fun quiescenceFailureReturnsTypedResultWithoutReadingRoomOrPreferences() = runTest {
        val room = FakeRoomSnapshotPort(policy, tables)
        val raw = FakePreferenceSnapshotPort(preferences)
        val gate = CutoverAccessGate.open()
        val port = AutoCutoverSnapshotPort(
            gate,
            CutoverRunQuiescencePort { false },
            room,
            raw
        )

        assertEquals(
            AutoCutoverSnapshotResult.Rejected(CutoverSnapshotRejection.QUIESCENCE_FAILED),
            port.capture("capture-source")
        )
        assertEquals(0, room.captureCalls)
        assertEquals(0, raw.captureCalls)
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    @Test
    fun captureWaitsForAlreadyAdmittedNormalAccessAfterQuiescence() = runTest {
        val gate = CutoverAccessGate.open()
        val normalStarted = CompletableDeferred<Unit>()
        val finishNormal = CompletableDeferred<Unit>()
        val normal = async {
            gate.withNormalAccess {
                normalStarted.complete(Unit)
                finishNormal.await()
            }
        }
        normalStarted.await()
        val room = FakeRoomSnapshotPort(policy, tables)
        val capture = async {
            AutoCutoverSnapshotPort(
                gate,
                CutoverRunQuiescencePort { true },
                room,
                FakePreferenceSnapshotPort(preferences)
            ).capture("capture-source")
        }
        runCurrent()

        assertEquals(CutoverGatePhase.DRAINING, gate.snapshot().phase)
        assertEquals(0, room.captureCalls)
        finishNormal.complete(Unit)
        normal.await()

        requireType<AutoCutoverSnapshotResult.Completed>(capture.await())
        assertEquals(1, room.captureCalls)
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    private class FakeRoomSnapshotPort(
        private val policy: CutoverArchivePolicy,
        private val tables: List<CutoverTableSection>
    ) : CutoverRoomSnapshotPort {
        var captureCalls = 0
        override suspend fun schemaPolicy(): CutoverArchivePolicy = policy
        override suspend fun captureTables(): List<CutoverTableSection> = tables.also { captureCalls += 1 }
    }

    private class FakePreferenceSnapshotPort(
        private val entries: List<CutoverPreferenceEntry>
    ) : CutoverPreferenceSnapshotPort {
        var captureCalls = 0
        override suspend fun captureCutoverPreferences(): List<CutoverPreferenceEntry> =
            entries.also { captureCalls += 1 }
    }

    private inline fun <reified T> requireType(value: Any?): T {
        assertTrue("expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
        return value as T
    }
}
