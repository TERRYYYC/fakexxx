package name.caiyao.fakegps.data.importer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object CsvTableReader {
    fun read(bytes: ByteArray): TabularDocument {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: Throwable) {
            fail(ImportIssueCode.INVALID_ENCODING, "CSV 必须使用 UTF-8 编码")
        }

        if (text.isEmpty()) fail(ImportIssueCode.EMPTY_FILE, "文件为空")

        val rows = mutableListOf<TabularRow>()
        val cells = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var index = 0
        var line = 1
        var rowStartLine = 1

        fun addField() {
            if (field.length > ProfileImportLimits.MAX_CELL_CHARS) {
                fail(
                    ImportIssueCode.CELL_TOO_LONG,
                    "单元格超过 ${ProfileImportLimits.MAX_CELL_CHARS} 字符",
                    rowStartLine,
                )
            }
            cells += field.toString()
            field.setLength(0)
            afterQuote = false
            if (cells.size > ProfileImportLimits.MAX_COLUMNS) {
                fail(
                    ImportIssueCode.TOO_MANY_COLUMNS,
                    "列数超过 ${ProfileImportLimits.MAX_COLUMNS}",
                    rowStartLine,
                )
            }
        }

        fun addRow() {
            addField()
            rows += TabularRow(rowStartLine, cells.toList())
            cells.clear()
            if (rows.size > ProfileImportLimits.MAX_ROWS + 1) {
                fail(
                    ImportIssueCode.TOO_MANY_ROWS,
                    "数据行超过 ${ProfileImportLimits.MAX_ROWS}",
                    rowStartLine,
                )
            }
        }

        while (index < text.length) {
            val ch = text[index]
            if (quoted) {
                when {
                    ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                        field.append('"')
                        index += 2
                    }
                    ch == '"' -> {
                        quoted = false
                        afterQuote = true
                        index++
                    }
                    else -> {
                        field.append(ch)
                        if (ch == '\n') line++
                        if (field.length > ProfileImportLimits.MAX_CELL_CHARS) {
                            fail(
                                ImportIssueCode.CELL_TOO_LONG,
                                "单元格超过 ${ProfileImportLimits.MAX_CELL_CHARS} 字符",
                                rowStartLine,
                            )
                        }
                        index++
                    }
                }
                continue
            }

            if (afterQuote) {
                when (ch) {
                    ',' -> {
                        addField()
                        index++
                    }
                    '\r', '\n' -> {
                        addRow()
                        if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                        index++
                        line++
                        rowStartLine = line
                    }
                    else -> fail(
                        ImportIssueCode.MALFORMED_FILE,
                        "第 $rowStartLine 行引号闭合后存在非法字符",
                        rowStartLine,
                    )
                }
                continue
            }

            when (ch) {
                '"' -> {
                    if (field.isNotEmpty()) {
                        fail(
                            ImportIssueCode.MALFORMED_FILE,
                            "第 $rowStartLine 行字段中存在未转义引号",
                            rowStartLine,
                        )
                    }
                    quoted = true
                    index++
                }
                ',' -> {
                    addField()
                    index++
                }
                '\r', '\n' -> {
                    addRow()
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    index++
                    line++
                    rowStartLine = line
                }
                else -> {
                    field.append(ch)
                    if (field.length > ProfileImportLimits.MAX_CELL_CHARS) {
                        fail(
                            ImportIssueCode.CELL_TOO_LONG,
                            "单元格超过 ${ProfileImportLimits.MAX_CELL_CHARS} 字符",
                            rowStartLine,
                        )
                    }
                    index++
                }
            }
        }

        if (quoted) {
            fail(
                ImportIssueCode.MALFORMED_FILE,
                "第 $rowStartLine 行存在未闭合引号",
                rowStartLine,
            )
        }
        if (field.isNotEmpty() || cells.isNotEmpty() || !text.endsWith('\n') && !text.endsWith('\r')) {
            addRow()
        }
        if (rows.isEmpty()) fail(ImportIssueCode.EMPTY_FILE, "文件为空")
        return TabularDocument(rows)
    }

    private fun fail(code: ImportIssueCode, message: String, row: Int? = null): Nothing {
        throw ProfileImportFormatException(listOf(ProfileImportIssue(code, message, row)))
    }
}
