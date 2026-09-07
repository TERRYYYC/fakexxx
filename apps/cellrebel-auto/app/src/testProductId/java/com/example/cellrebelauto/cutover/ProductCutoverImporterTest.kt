package com.example.cellrebelauto.cutover

import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductCutoverImporterTest {

    @Test
    fun wrongMediaAndCsvAreRejectedBeforeRestore() = runTest {
        val selected = Uri.parse("content://operator/archive")
        var opens = 0
        var restores = 0
        val importer = ProductCutoverImporter(
            contentType = { "text/csv" },
            openInputStream = { opens += 1; ByteArrayInputStream("a,b".toByteArray()) },
            policy = { POLICY },
            restore = { restores += 1; completedRestore(it) }
        )

        val result = importer.import(selected)

        assertEquals(0, opens)
        assertEquals(0, restores)
        assertEquals(
            ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.WRONG_MEDIA),
            result
        )
    }

    @Test
    fun validSelectedDocumentIsDecodedAndRestored() = runTest {
        val selected = Uri.parse("content://operator/archive")
        val encoded = CutoverArchiveV2Codec(POLICY).encode(archive())
        val opened = mutableListOf<Uri>()
        var restored: DecodedCutoverArchiveV2? = null
        val importer = ProductCutoverImporter(
            contentType = { CutoverSafContract.MEDIA_TYPE },
            openInputStream = { uri ->
                opened += uri
                ByteArrayInputStream(encoded.serialized.toByteArray(Charsets.UTF_8))
            },
            policy = { POLICY },
            restore = { decoded -> restored = decoded; completedRestore(decoded) }
        )

        val result = importer.import(selected)

        assertEquals(listOf(selected), opened)
        assertEquals(encoded.archiveDigest, restored?.archiveDigest)
        assertEquals(ProductCutoverImportResult.Completed(encoded.archiveDigest), result)
    }

    @Test
    fun correctlyLabelledCsvStillFailsCodecWithoutRestore() = runTest {
        var restores = 0
        val importer = ProductCutoverImporter(
            contentType = { CutoverSafContract.MEDIA_TYPE },
            openInputStream = { ByteArrayInputStream("longitude,latitude".toByteArray()) },
            policy = { POLICY },
            restore = { restores += 1; completedRestore(it) }
        )

        val result = importer.import(Uri.parse("content://operator/archive"))

        assertEquals(0, restores)
        assertEquals(
            ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.INVALID_ARCHIVE),
            result
        )
        assertTrue(result.toString().contains("longitude").not())
    }

    @Test
    fun malformedUtf8AndOversizedDocumentsFailBeforeRestore() = runTest {
        suspend fun resultFor(bytes: ByteArray, policy: CutoverArchivePolicy = POLICY): ProductCutoverImportResult {
            var restores = 0
            val result = ProductCutoverImporter(
                contentType = { CutoverSafContract.MEDIA_TYPE },
                openInputStream = { ByteArrayInputStream(bytes) },
                policy = { policy },
                restore = { restores += 1; completedRestore(it) }
            ).import(Uri.parse("content://operator/archive"))
            assertEquals(0, restores)
            return result
        }

        assertEquals(
            ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.INVALID_ARCHIVE),
            resultFor(byteArrayOf(0xC3.toByte(), 0x28))
        )
        assertEquals(
            ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.INVALID_ARCHIVE),
            resultFor(
                "12345".toByteArray(),
                POLICY.copy(limits = CutoverArchiveLimits(maxArchiveBytes = 4))
            )
        )
    }

    @Test
    fun unavailableSelectedDocumentIsTypedBeforeRestore() = runTest {
        var restores = 0
        val importer = ProductCutoverImporter(
            contentType = { CutoverSafContract.MEDIA_TYPE },
            openInputStream = { null },
            policy = { POLICY },
            restore = { restores += 1; completedRestore(it) }
        )

        assertEquals(
            ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.DOCUMENT_READ_FAILED),
            importer.import(Uri.parse("content://operator/archive"))
        )
        assertEquals(0, restores)
    }

    @Test
    fun unreadableMediaTypeIsTypedBeforeOpeningOrRestoring() = runTest {
        var opens = 0
        var restores = 0
        val importer = ProductCutoverImporter(
            contentType = { error("synthetic provider failure") },
            openInputStream = { opens += 1; ByteArrayInputStream(byteArrayOf()) },
            policy = { POLICY },
            restore = { restores += 1; completedRestore(it) }
        )

        assertEquals(
            ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.DOCUMENT_READ_FAILED),
            importer.import(Uri.parse("content://operator/archive"))
        )
        assertEquals(0, opens)
        assertEquals(0, restores)
    }

    private fun completedRestore(decoded: DecodedCutoverArchiveV2) = AutoCutoverRestoreResult.Completed(
        CutoverRestoreJournal(
            CutoverRestoreIdentity(decoded.archiveDigest, decoded.archive.captureId),
            CutoverRestorePhase.READY
        )
    )

    private fun archive() = CutoverArchiveV2(
        sourcePackage = "com.example.cellrebelauto",
        captureId = "capture-1",
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

    private companion object {
        val POLICY = CutoverArchivePolicy(
            schemaVersion = 9,
            requiredTableSchemaDigests = mapOf(
                "provider_pairing_records" to "sha256:${"1".repeat(64)}"
            ),
            historicalOnlyTables = setOf("provider_pairing_records")
        )
    }
}
