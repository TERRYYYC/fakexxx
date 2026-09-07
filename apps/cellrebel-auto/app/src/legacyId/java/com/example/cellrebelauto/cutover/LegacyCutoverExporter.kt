package com.example.cellrebelauto.cutover

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

enum class LegacyCutoverExportRejection {
    BUSY,
    QUIESCENCE_FAILED,
    CAPTURE_FAILED,
    DOCUMENT_WRITE_FAILED
}

sealed interface LegacyCutoverExportResult {
    data class Completed(val archiveDigest: String) : LegacyCutoverExportResult
    data class Rejected(val reason: LegacyCutoverExportRejection) : LegacyCutoverExportResult
}

/** Writes one immutable capture only to the URI returned by the operator's CreateDocument action. */
class LegacyCutoverExporter(
    private val capture: suspend (captureId: String) -> AutoCutoverSnapshotResult,
    private val openOutputStream: (Uri) -> OutputStream?
) {
    suspend fun export(selectedUri: Uri, captureId: String): LegacyCutoverExportResult {
        require(captureId.isNotBlank()) { "capture id cannot be blank" }
        return when (val snapshot = capture(captureId)) {
            is AutoCutoverSnapshotResult.Rejected -> LegacyCutoverExportResult.Rejected(
                when (snapshot.reason) {
                    CutoverSnapshotRejection.BUSY -> LegacyCutoverExportRejection.BUSY
                    CutoverSnapshotRejection.QUIESCENCE_FAILED ->
                        LegacyCutoverExportRejection.QUIESCENCE_FAILED
                    CutoverSnapshotRejection.CAPTURE_FAILED ->
                        LegacyCutoverExportRejection.CAPTURE_FAILED
                }
            )
            is AutoCutoverSnapshotResult.Completed -> try {
                val written = withContext(Dispatchers.IO) {
                    val stream = openOutputStream(selectedUri) ?: return@withContext false
                    stream.use {
                        it.write(snapshot.encoded.serialized.toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                    true
                }
                if (written) {
                    LegacyCutoverExportResult.Completed(snapshot.encoded.archiveDigest)
                } else {
                    LegacyCutoverExportResult.Rejected(
                        LegacyCutoverExportRejection.DOCUMENT_WRITE_FAILED
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LegacyCutoverExportResult.Rejected(
                    LegacyCutoverExportRejection.DOCUMENT_WRITE_FAILED
                )
            }
        }
    }
}
