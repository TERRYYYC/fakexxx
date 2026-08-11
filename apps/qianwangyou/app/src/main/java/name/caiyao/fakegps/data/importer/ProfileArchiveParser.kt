package name.caiyao.fakegps.data.importer

import java.io.InputStream
import java.util.Locale
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec
import name.caiyao.fakegps.data.model.ProfileFieldValueValidator

class ProfileArchiveParser {
    fun parse(displayName: String?, input: InputStream): ProfileImportAnalysis {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > ProfileImportLimits.MAX_INPUT_BYTES) {
                return invalid(ImportIssueCode.FILE_TOO_LARGE, "文件超过 2 MiB 上限")
            }
            output.write(buffer, 0, read)
        }
        return parse(displayName, output.toByteArray())
    }

    fun parse(displayName: String?, bytes: ByteArray): ProfileImportAnalysis {
        if (bytes.size > ProfileImportLimits.MAX_INPUT_BYTES) {
            return invalid(ImportIssueCode.FILE_TOO_LARGE, "文件超过 2 MiB 上限")
        }

        val isZip = bytes.isZipSignature()
        val isOle = bytes.isOleSignature()
        val extension = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val kind = when {
            extension == "csv" && isZip -> return invalid(
                ImportIssueCode.FILE_TYPE_MISMATCH,
                "文件扩展名是 CSV，但内容是 ZIP/XLSX",
            )
            extension == "xlsx" && !isZip -> return invalid(
                ImportIssueCode.FILE_TYPE_MISMATCH,
                "文件扩展名是 XLSX，但内容不是 OOXML ZIP",
            )
            extension == "csv" -> FileKind.CSV
            extension == "xlsx" -> FileKind.XLSX
            extension.isNotEmpty() -> return invalid(
                ImportIssueCode.UNSUPPORTED_FILE,
                "仅支持 .csv 与 .xlsx，不支持 .$extension",
            )
            isOle -> return invalid(ImportIssueCode.UNSUPPORTED_FILE, "不支持旧版 .xls 文件")
            isZip -> FileKind.XLSX
            else -> FileKind.CSV
        }

        val table = try {
            when (kind) {
                FileKind.CSV -> CsvTableReader.read(bytes)
                FileKind.XLSX -> XlsxTableReader.read(bytes)
            }
        } catch (failure: ProfileImportFormatException) {
            return ProfileImportAnalysis.Invalid(failure.issues.take(ProfileImportLimits.MAX_ISSUES))
        } catch (_: Exception) {
            return invalid(ImportIssueCode.MALFORMED_FILE, "文件解析失败")
        }
        if (table.issues.isNotEmpty()) {
            return ProfileImportAnalysis.Invalid(table.issues.take(ProfileImportLimits.MAX_ISSUES))
        }
        return ProfileArchiveSchema.analyze(table)
    }

    private enum class FileKind { CSV, XLSX }

    private fun invalid(code: ImportIssueCode, message: String) =
        ProfileImportAnalysis.Invalid(listOf(ProfileImportIssue(code, message)))

    private fun ByteArray.isZipSignature(): Boolean = size >= 4 && this[0] == 0x50.toByte() &&
        this[1] == 0x4B.toByte() && this[2] in setOf(0x03, 0x05, 0x07).map(Int::toByte) &&
        this[3] in setOf(0x04, 0x06, 0x08).map(Int::toByte)

    private fun ByteArray.isOleSignature(): Boolean {
        val signature = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        return size >= 4 && indices.take(minOf(size, signature.size)).all { this[it] == signature[it] }
    }
}

internal object ProfileArchiveSchema {
    private const val NAME_COLUMN = ProfileArchiveContract.NAME_COLUMN
    private val specs = ProfileArchiveContract.specsByColumn
    private val allowedColumns = ProfileArchiveContract.allowedColumns

    fun analyze(table: TabularDocument): ProfileImportAnalysis {
        val headerRow = table.rows.firstOrNull()
            ?: return invalid(ImportIssueCode.EMPTY_FILE, "文件为空")
        val headers = headerRow.cells.map(String::trim)
        val headerIssues = validateHeaders(headers, headerRow.number)
        if (headerIssues.isNotEmpty()) return ProfileImportAnalysis.Invalid(headerIssues)

        val issues = mutableListOf<ProfileImportIssue>()
        val records = mutableListOf<ProfileEntity>()
        val seen = linkedSetOf<ProfileEntity>()
        var dataRows = 0
        var duplicateRows = 0

        for (row in table.rows.drop(1)) {
            if (row.cells.all { it.isBlank() }) continue
            dataRows++
            if (dataRows > ProfileImportLimits.MAX_ROWS) {
                issues += ProfileImportIssue(
                    ImportIssueCode.TOO_MANY_ROWS,
                    "数据行超过 ${ProfileImportLimits.MAX_ROWS}",
                    row.number,
                )
                break
            }
            if (row.cells.drop(headers.size).any { it.isNotBlank() }) {
                issues += ProfileImportIssue(
                    ImportIssueCode.INVALID_VALUE,
                    "数据列数多于 header",
                    row.number,
                )
                if (issues.size >= ProfileImportLimits.MAX_ISSUES) break
                continue
            }

            val draft = linkedMapOf<String, String>()
            var addname: String? = null
            for ((index, header) in headers.withIndex()) {
                val raw = row.cells.getOrNull(index).orEmpty()
                val normalized = ProfileFieldValueValidator.normalize(header, raw)
                if (normalized.error != null) {
                    issues += ProfileImportIssue(
                        ImportIssueCode.INVALID_VALUE,
                        normalized.error,
                        row.number,
                        header,
                    )
                    if (issues.size >= ProfileImportLimits.MAX_ISSUES) break
                } else if (normalized.value != null) {
                    if (header == NAME_COLUMN) addname = normalized.value else draft[header] = normalized.value
                }
            }
            if (issues.size >= ProfileImportLimits.MAX_ISSUES) break
            if (draft.isEmpty()) {
                issues += ProfileImportIssue(
                    ImportIssueCode.EMPTY_PROFILE,
                    "档案没有任何可配置字段",
                    row.number,
                )
                continue
            }
            if (!normalizePlmn(draft, row.number, issues)) continue
            val entity = try {
                ProfileEntityCodec.canonical(ProfileEntityCodec.fromDraft(draft, addname = addname))
            } catch (failure: IllegalArgumentException) {
                issues += ProfileImportIssue(
                    ImportIssueCode.INVALID_VALUE,
                    failure.message ?: "字段组合无效",
                    row.number,
                )
                continue
            }
            if (seen.add(entity)) records += entity else duplicateRows++
        }

        if (issues.isNotEmpty()) {
            return ProfileImportAnalysis.Invalid(issues.take(ProfileImportLimits.MAX_ISSUES))
        }
        if (dataRows == 0) return invalid(ImportIssueCode.EMPTY_PROFILE, "文件没有数据行")
        if (records.isEmpty()) return invalid(ImportIssueCode.EMPTY_PROFILE, "文件没有可导入档案")
        return ProfileImportAnalysis.Ready(records, dataRows, duplicateRows)
    }

    private fun validateHeaders(headers: List<String>, row: Int): List<ProfileImportIssue> {
        val issues = mutableListOf<ProfileImportIssue>()
        if (headers.isEmpty() || headers.all(String::isBlank)) {
            return listOf(ProfileImportIssue(ImportIssueCode.INVALID_HEADER, "header 为空", row))
        }
        val counts = headers.groupingBy { it }.eachCount()
        for ((index, header) in headers.withIndex()) {
            val column = if (header.isBlank()) "#${index + 1}" else header
            when {
                header.isBlank() -> issues += ProfileImportIssue(
                    ImportIssueCode.INVALID_HEADER,
                    "header 不能留空",
                    row,
                    column,
                )
                header == "id" -> issues += ProfileImportIssue(
                    ImportIssueCode.FORBIDDEN_COLUMN,
                    "id 由数据库生成，禁止导入",
                    row,
                    header,
                )
                counts.getValue(header) > 1 -> issues += ProfileImportIssue(
                    ImportIssueCode.DUPLICATE_COLUMN,
                    "header 重复：$header",
                    row,
                    header,
                )
                header !in allowedColumns -> issues += ProfileImportIssue(
                    ImportIssueCode.UNKNOWN_COLUMN,
                    "未知字段：$header",
                    row,
                    header,
                )
            }
            if (issues.size >= ProfileImportLimits.MAX_ISSUES) break
        }
        if (headers.none { it in specs }) {
            issues += ProfileImportIssue(
                ImportIssueCode.MISSING_CONFIG_COLUMN,
                "header 至少需要一个可配置字段",
                row,
            )
        }
        return issues.distinctBy { Triple(it.code, it.row, it.column) }
            .take(ProfileImportLimits.MAX_ISSUES)
    }

    private fun normalizePlmn(
        draft: MutableMap<String, String>,
        row: Int,
        issues: MutableList<ProfileImportIssue>,
    ): Boolean {
        val mccUnavailable = draft["mcc"] == ProfileEntityCodec.UNAVAILABLE_TOKEN
        val mncUnavailable = draft["mnc"] == ProfileEntityCodec.UNAVAILABLE_TOKEN
        if (!mccUnavailable && !mncUnavailable) return true
        val otherConcrete = (mccUnavailable && draft["mnc"] != null && !mncUnavailable) ||
            (mncUnavailable && draft["mcc"] != null && !mccUnavailable)
        if (otherConcrete) {
            issues += ProfileImportIssue(
                ImportIssueCode.INVALID_VALUE,
                "MCC/MNC 不上报必须成对，不能与具体值混用",
                row,
                if (mccUnavailable) "mnc" else "mcc",
            )
            return false
        }
        draft["mcc"] = ProfileEntityCodec.UNAVAILABLE_TOKEN
        draft["mnc"] = ProfileEntityCodec.UNAVAILABLE_TOKEN
        return true
    }

    private fun invalid(code: ImportIssueCode, message: String) =
        ProfileImportAnalysis.Invalid(listOf(ProfileImportIssue(code, message)))
}
