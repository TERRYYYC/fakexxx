package name.caiyao.fakegps.data.importer

import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.model.ProfileFieldValueValidator

enum class ImportIssueCode {
    UNSUPPORTED_FILE,
    FILE_TYPE_MISMATCH,
    FILE_TOO_LARGE,
    INVALID_ENCODING,
    MALFORMED_FILE,
    MULTIPLE_SHEETS,
    EXTERNAL_REFERENCE,
    FORMULA_NOT_ALLOWED,
    CELL_ERROR,
    TOO_MANY_ROWS,
    TOO_MANY_COLUMNS,
    CELL_TOO_LONG,
    EMPTY_FILE,
    INVALID_HEADER,
    FORBIDDEN_COLUMN,
    UNKNOWN_COLUMN,
    DUPLICATE_COLUMN,
    MISSING_CONFIG_COLUMN,
    EMPTY_PROFILE,
    INVALID_VALUE,
}

data class ProfileImportIssue(
    val code: ImportIssueCode,
    val message: String,
    val row: Int? = null,
    val column: String? = null,
)

sealed interface ProfileImportAnalysis {
    data class Ready(
        val records: List<ProfileEntity>,
        val dataRows: Int,
        val duplicateRows: Int,
    ) : ProfileImportAnalysis

    data class Invalid(val issues: List<ProfileImportIssue>) : ProfileImportAnalysis
}

internal data class TabularRow(
    val number: Int,
    val cells: List<String>,
)

internal data class TabularDocument(
    val rows: List<TabularRow>,
    val issues: List<ProfileImportIssue> = emptyList(),
)

internal class ProfileImportFormatException(
    val issues: List<ProfileImportIssue>,
) : IllegalArgumentException(issues.firstOrNull()?.message)

internal object ProfileImportLimits {
    const val MAX_INPUT_BYTES = 2 * 1024 * 1024
    const val MAX_XLSX_EXPANDED_BYTES = 8 * 1024 * 1024
    const val MAX_ROWS = 1_000
    const val MAX_COLUMNS = 128
    const val MAX_CELL_CHARS = ProfileFieldValueValidator.MAX_VALUE_CHARS
    const val MAX_ISSUES = 50
    const val MAX_ZIP_ENTRIES = 1_000
}
