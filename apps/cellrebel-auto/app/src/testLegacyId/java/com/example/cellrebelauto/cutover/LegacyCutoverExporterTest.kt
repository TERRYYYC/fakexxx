package com.example.cellrebelauto.cutover

import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyCutoverExporterTest {

    @Test
    fun completedSnapshotIsWrittenOnlyToTheSelectedUri() = runTest {
        val selected = Uri.parse("content://operator/archive")
        val bytes = ByteArrayOutputStream()
        val opened = mutableListOf<Uri>()
        val exporter = LegacyCutoverExporter(
            capture = { AutoCutoverSnapshotResult.Completed(ENCODED) },
            openOutputStream = { uri -> opened += uri; bytes }
        )

        val result = exporter.export(selected, "capture-1")

        assertEquals(listOf(selected), opened)
        assertEquals(ENCODED.serialized, bytes.toString(Charsets.UTF_8.name()))
        assertEquals(LegacyCutoverExportResult.Completed(ENCODED.archiveDigest), result)
    }

    @Test
    fun rejectedCaptureNeverOpensTheSelectedUri() = runTest {
        var opens = 0
        val exporter = LegacyCutoverExporter(
            capture = {
                AutoCutoverSnapshotResult.Rejected(CutoverSnapshotRejection.QUIESCENCE_FAILED)
            },
            openOutputStream = { opens += 1; ByteArrayOutputStream() }
        )

        val result = exporter.export(Uri.parse("content://operator/archive"), "capture-1")

        assertEquals(0, opens)
        assertEquals(
            LegacyCutoverExportResult.Rejected(LegacyCutoverExportRejection.QUIESCENCE_FAILED),
            result
        )
    }

    @Test
    fun streamFailureIsTypedAndNeverIncludesArchiveContent() = runTest {
        val exporter = LegacyCutoverExporter(
            capture = { AutoCutoverSnapshotResult.Completed(ENCODED) },
            openOutputStream = { throw IOException("synthetic write failure") }
        )

        val result = exporter.export(Uri.parse("content://operator/archive"), "capture-1")

        assertEquals(
            LegacyCutoverExportResult.Rejected(LegacyCutoverExportRejection.DOCUMENT_WRITE_FAILED),
            result
        )
        assertTrue(result.toString().contains(ENCODED.serialized).not())
    }

    @Test
    fun unavailableSelectedDocumentIsTyped() = runTest {
        val exporter = LegacyCutoverExporter(
            capture = { AutoCutoverSnapshotResult.Completed(ENCODED) },
            openOutputStream = { null }
        )

        assertEquals(
            LegacyCutoverExportResult.Rejected(LegacyCutoverExportRejection.DOCUMENT_WRITE_FAILED),
            exporter.export(Uri.parse("content://operator/archive"), "capture-1")
        )
    }

    private companion object {
        val ENCODED = EncodedCutoverArchiveV2(
            serialized = "synthetic-cutover-archive",
            archiveDigest = "sha256:${"a".repeat(64)}"
        )
    }
}
