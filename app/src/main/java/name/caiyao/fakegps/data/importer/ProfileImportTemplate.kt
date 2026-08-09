package name.caiyao.fakegps.data.importer

import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Canonical, header-only CSV template shared with the import parser contract. */
object ProfileImportTemplate {
    const val DEFAULT_FILE_NAME = "FakeGPS-收藏档案导入模板.csv"
    const val MIME_TYPE = "text/csv"

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun headers(): List<String> = ProfileArchiveContract.canonicalHeaders

    fun csvBytes(): ByteArray = utf8Bom +
        (headers().joinToString(",") + "\r\n").toByteArray(StandardCharsets.UTF_8)

    fun writeTo(output: OutputStream) {
        output.write(csvBytes())
        output.flush()
    }
}
