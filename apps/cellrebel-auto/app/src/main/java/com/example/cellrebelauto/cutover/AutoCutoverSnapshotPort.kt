package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CancellationException

interface CutoverRoomSnapshotPort {
    suspend fun schemaPolicy(): CutoverArchivePolicy
    suspend fun captureTables(): List<CutoverTableSection>
}

interface CutoverPreferenceSnapshotPort {
    suspend fun captureCutoverPreferences(): List<CutoverPreferenceEntry>
}

enum class CutoverSnapshotRejection {
    BUSY,
    QUIESCENCE_FAILED,
    CAPTURE_FAILED
}

sealed interface AutoCutoverSnapshotResult {
    data class Completed(val encoded: EncodedCutoverArchiveV2) : AutoCutoverSnapshotResult
    data class Rejected(val reason: CutoverSnapshotRejection) : AutoCutoverSnapshotResult
}

/** Source-side capture owner. The exclusive gate keeps Room and raw preferences one generation. */
class AutoCutoverSnapshotPort(
    private val accessGate: CutoverAccessGate,
    private val quiescencePort: CutoverRunQuiescencePort,
    private val roomPort: CutoverRoomSnapshotPort,
    private val preferencePort: CutoverPreferenceSnapshotPort
) {
    suspend fun capture(captureId: String): AutoCutoverSnapshotResult {
        val admission = accessGate.acquireCaptureExclusive(captureId) { quiescencePort.quiesce() }
        val lease = when (admission) {
            is CutoverExclusiveAdmission.Granted -> admission.lease
            is CutoverExclusiveAdmission.Busy ->
                return AutoCutoverSnapshotResult.Rejected(CutoverSnapshotRejection.BUSY)
            CutoverExclusiveAdmission.QuiescenceFailed ->
                return AutoCutoverSnapshotResult.Rejected(CutoverSnapshotRejection.QUIESCENCE_FAILED)
        }
        return try {
            val policy = roomPort.schemaPolicy()
            val archive = CutoverArchiveV2(
                sourcePackage = SOURCE_PACKAGE,
                captureId = captureId,
                schemaVersion = policy.schemaVersion,
                tables = roomPort.captureTables(),
                preferences = preferencePort.captureCutoverPreferences()
            )
            AutoCutoverSnapshotResult.Completed(CutoverArchiveV2Codec(policy).encode(archive))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AutoCutoverSnapshotResult.Rejected(CutoverSnapshotRejection.CAPTURE_FAILED)
        } finally {
            lease.release(CutoverExclusiveRelease.OPEN)
        }
    }

    private companion object {
        const val SOURCE_PACKAGE = "com.example.cellrebelauto"
    }
}
