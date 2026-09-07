package com.example.cellrebelauto.cutover

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class ProductCutoverImportRejection {
    WRONG_MEDIA,
    DOCUMENT_READ_FAILED,
    INVALID_ARCHIVE,
    TARGET_UNAVAILABLE,
    BUSY,
    QUIESCENCE_FAILED,
    TARGET_NOT_EMPTY,
    NOT_ELIGIBLE,
    ARCHIVE_CONFLICT,
    INVALID_PHASE
}

sealed interface ProductCutoverImportResult {
    data class Completed(val archiveDigest: String) : ProductCutoverImportResult
    data class Rejected(val reason: ProductCutoverImportRejection) : ProductCutoverImportResult
    data class RecoveryRequired(val archiveDigest: String) : ProductCutoverImportResult
}

/** Reads and restores only the URI returned by the operator's OpenDocument action. */
class ProductCutoverImporter(
    private val contentType: (Uri) -> String?,
    private val openInputStream: (Uri) -> InputStream?,
    private val policy: suspend () -> CutoverArchivePolicy,
    private val restore: suspend (DecodedCutoverArchiveV2) -> AutoCutoverRestoreResult
) {
    suspend fun import(selectedUri: Uri): ProductCutoverImportResult {
        val selectedType = try {
            withContext(Dispatchers.IO) { contentType(selectedUri) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProductCutoverImportResult.Rejected(
                ProductCutoverImportRejection.DOCUMENT_READ_FAILED
            )
        }
        if (selectedType != CutoverSafContract.MEDIA_TYPE) {
            return ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.WRONG_MEDIA)
        }

        val archivePolicy = try {
            policy()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProductCutoverImportResult.Rejected(
                ProductCutoverImportRejection.TARGET_UNAVAILABLE
            )
        }
        val serialized = try {
            withContext(Dispatchers.IO) {
                val stream = openInputStream(selectedUri)
                    ?: return@withContext null
                stream.use { readStrictUtf8(it, archivePolicy.limits.maxArchiveBytes) }
            } ?: return ProductCutoverImportResult.Rejected(
                ProductCutoverImportRejection.DOCUMENT_READ_FAILED
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CharacterCodingException) {
            return ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.INVALID_ARCHIVE)
        } catch (_: IllegalArgumentException) {
            return ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.INVALID_ARCHIVE)
        } catch (_: Exception) {
            return ProductCutoverImportResult.Rejected(
                ProductCutoverImportRejection.DOCUMENT_READ_FAILED
            )
        }
        val decoded = try {
            CutoverArchiveV2Codec(archivePolicy).decode(serialized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.INVALID_ARCHIVE)
        }

        return try {
            when (val result = restore(decoded)) {
                is AutoCutoverRestoreResult.Completed ->
                    ProductCutoverImportResult.Completed(decoded.archiveDigest)
                is AutoCutoverRestoreResult.RecoveryRequired ->
                    ProductCutoverImportResult.RecoveryRequired(decoded.archiveDigest)
                is AutoCutoverRestoreResult.Rejected -> ProductCutoverImportResult.Rejected(
                    when (result.reason) {
                        CutoverCoordinatorRejection.BUSY -> ProductCutoverImportRejection.BUSY
                        CutoverCoordinatorRejection.QUIESCENCE_FAILED ->
                            ProductCutoverImportRejection.QUIESCENCE_FAILED
                        CutoverCoordinatorRejection.TARGET_NOT_EMPTY ->
                            ProductCutoverImportRejection.TARGET_NOT_EMPTY
                        CutoverCoordinatorRejection.NOT_ELIGIBLE ->
                            ProductCutoverImportRejection.NOT_ELIGIBLE
                        CutoverCoordinatorRejection.ARCHIVE_CONFLICT ->
                            ProductCutoverImportRejection.ARCHIVE_CONFLICT
                        CutoverCoordinatorRejection.INVALID_PHASE ->
                            ProductCutoverImportRejection.INVALID_PHASE
                    }
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProductCutoverImportResult.Rejected(ProductCutoverImportRejection.TARGET_UNAVAILABLE)
        }
    }

    private fun readStrictUtf8(stream: InputStream, maximumBytes: Int): String {
        val bytes = ByteArrayOutputStream(minOf(maximumBytes, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maximumBytes.toLong()) { "archive exceeds byte limit" }
            bytes.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
    }
}
