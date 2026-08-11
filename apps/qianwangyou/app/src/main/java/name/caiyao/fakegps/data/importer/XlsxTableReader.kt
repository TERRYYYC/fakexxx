package name.caiyao.fakegps.data.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

internal object XlsxTableReader {
    private const val OFFICE_REL_NS =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WORKBOOK_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    private const val WORKSHEET_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"

    fun read(bytes: ByteArray): TabularDocument {
        val archive = unzip(bytes)
        if ("[Content_Types].xml" !in archive || "xl/workbook.xml" !in archive) {
            fail(ImportIssueCode.MALFORMED_FILE, "XLSX 缺少必要的 OOXML 部件")
        }
        rejectExternalRelationships(archive)

        val contentTypes = xml(archive.getValue("[Content_Types].xml"), "[Content_Types].xml")
            .documentElement
            .descendants("Override")
            .associate { override ->
                override.getAttribute("PartName").trimStart('/') to
                    override.getAttribute("ContentType")
            }
        requireContentType(contentTypes, "xl/workbook.xml", WORKBOOK_CONTENT_TYPE)

        val workbook = xml(archive.getValue("xl/workbook.xml"), "workbook.xml")
        val sheets = workbook.documentElement.descendants("sheet")
        if (sheets.isEmpty()) fail(ImportIssueCode.MALFORMED_FILE, "XLSX 没有工作表")
        if (sheets.size != 1) {
            fail(ImportIssueCode.MULTIPLE_SHEETS, "只支持恰好一个工作表，当前为 ${sheets.size} 个")
        }

        val relationshipId = sheets.single().getAttributeNS(OFFICE_REL_NS, "id")
            .ifBlank { sheets.single().getAttribute("r:id") }
        if (relationshipId.isBlank()) fail(ImportIssueCode.MALFORMED_FILE, "工作表关系缺失")

        val relBytes = archive["xl/_rels/workbook.xml.rels"]
            ?: fail(ImportIssueCode.MALFORMED_FILE, "XLSX 缺少 workbook relationships")
        val relationship = xml(relBytes, "workbook.xml.rels").documentElement
            .descendants("Relationship")
            .firstOrNull { it.getAttribute("Id") == relationshipId }
            ?: fail(ImportIssueCode.MALFORMED_FILE, "找不到工作表关系 $relationshipId")
        if (!relationship.getAttribute("Type").endsWith("/worksheet")) {
            fail(ImportIssueCode.MALFORMED_FILE, "工作表关系类型无效")
        }
        val target = relationship.getAttribute("Target")
        val sheetPath = resolvePart("xl/workbook.xml", target)
        requireContentType(contentTypes, sheetPath, WORKSHEET_CONTENT_TYPE)
        val sheetBytes = archive[sheetPath]
            ?: fail(ImportIssueCode.MALFORMED_FILE, "XLSX 缺少工作表 $sheetPath")

        val sharedStrings = archive["xl/sharedStrings.xml"]?.let(::readSharedStrings).orEmpty()
        return readSheet(sheetBytes, sharedStrings)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var expanded = 0
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > ProfileImportLimits.MAX_ZIP_ENTRIES) {
                        fail(ImportIssueCode.FILE_TOO_LARGE, "XLSX ZIP 条目过多")
                    }
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith('/') || name.split('/').any { it == ".." }) {
                        fail(ImportIssueCode.MALFORMED_FILE, "XLSX 包含非法 ZIP 路径")
                    }
                    if (entry.isDirectory) continue
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        expanded += read
                        if (expanded > ProfileImportLimits.MAX_XLSX_EXPANDED_BYTES) {
                            fail(ImportIssueCode.FILE_TOO_LARGE, "XLSX 展开内容超过安全上限")
                        }
                        output.write(buffer, 0, read)
                    }
                    if (entries.put(name, output.toByteArray()) != null) {
                        fail(ImportIssueCode.MALFORMED_FILE, "XLSX 包含重复 ZIP 条目 $name")
                    }
                }
            }
        } catch (failure: ProfileImportFormatException) {
            throw failure
        } catch (_: Throwable) {
            fail(ImportIssueCode.MALFORMED_FILE, "文件不是有效的 XLSX ZIP")
        }
        if (entries.isEmpty()) fail(ImportIssueCode.MALFORMED_FILE, "XLSX ZIP 为空")
        return entries
    }

    private fun rejectExternalRelationships(archive: Map<String, ByteArray>) {
        for ((name, bytes) in archive) {
            if (!name.endsWith(".rels")) continue
            val relationships = xml(bytes, name).documentElement.descendants("Relationship")
            if (relationships.any {
                    it.getAttribute("TargetMode").equals("External", ignoreCase = true)
                }) {
                fail(ImportIssueCode.EXTERNAL_REFERENCE, "XLSX 不允许外部关系")
            }
        }
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        return xml(bytes, "sharedStrings.xml").documentElement.descendants("si").map { item ->
            item.descendants("t").joinToString(separator = "") { it.textContent ?: "" }
        }
    }

    private fun readSheet(bytes: ByteArray, sharedStrings: List<String>): TabularDocument {
        val document = xml(bytes, "worksheet.xml")
        val rowElements = document.documentElement.descendants("row")
        if (rowElements.size > ProfileImportLimits.MAX_ROWS + 1) {
            fail(ImportIssueCode.TOO_MANY_ROWS, "数据行超过 ${ProfileImportLimits.MAX_ROWS}")
        }

        val rows = mutableListOf<TabularRow>()
        val issues = mutableListOf<ProfileImportIssue>()
        var fallbackRow = 1
        for (row in rowElements) {
            val rowNumber = row.getAttribute("r").toIntOrNull() ?: fallbackRow
            fallbackRow = rowNumber + 1
            val values = mutableMapOf<Int, String>()
            var fallbackColumn = 0
            for (cell in row.directChildren("c")) {
                val reference = cell.getAttribute("r")
                val columnIndex = columnIndex(reference).takeIf { it >= 0 } ?: fallbackColumn
                fallbackColumn = columnIndex + 1
                if (columnIndex >= ProfileImportLimits.MAX_COLUMNS) {
                    issues += ProfileImportIssue(
                        ImportIssueCode.TOO_MANY_COLUMNS,
                        "列数超过 ${ProfileImportLimits.MAX_COLUMNS}",
                        rowNumber,
                        reference.takeWhile(Char::isLetter),
                    )
                    continue
                }
                val columnLabel = reference.takeWhile(Char::isLetter).ifBlank {
                    excelColumn(columnIndex)
                }
                if (cell.directChildren("f").isNotEmpty()) {
                    issues += ProfileImportIssue(
                        ImportIssueCode.FORMULA_NOT_ALLOWED,
                        "XLSX 公式单元格 $columnLabel$rowNumber 不允许导入",
                        rowNumber,
                        columnLabel,
                    )
                    continue
                }
                val type = cell.getAttribute("t")
                val value = when (type) {
                    "s" -> {
                        val index = cell.firstDirectText("v").toIntOrNull()
                        if (index == null || index !in sharedStrings.indices) {
                            issues += ProfileImportIssue(
                                ImportIssueCode.MALFORMED_FILE,
                                "共享字符串索引无效",
                                rowNumber,
                                columnLabel,
                            )
                            ""
                        } else {
                            sharedStrings[index]
                        }
                    }
                    "inlineStr" -> cell.directChildren("is").firstOrNull()
                        ?.descendants("t")
                        ?.joinToString(separator = "") { it.textContent ?: "" }
                        .orEmpty()
                    "b" -> when (cell.firstDirectText("v")) {
                        "0" -> "false"
                        "1" -> "true"
                        else -> {
                            issues += ProfileImportIssue(
                                ImportIssueCode.INVALID_VALUE,
                                "布尔单元格必须是 0 或 1",
                                rowNumber,
                                columnLabel,
                            )
                            ""
                        }
                    }
                    "e" -> {
                        issues += ProfileImportIssue(
                            ImportIssueCode.CELL_ERROR,
                            "XLSX 错误单元格 $columnLabel$rowNumber 不允许导入",
                            rowNumber,
                            columnLabel,
                        )
                        ""
                    }
                    "", "n" -> cell.firstDirectText("v")
                    else -> {
                        issues += ProfileImportIssue(
                            ImportIssueCode.MALFORMED_FILE,
                            "XLSX 单元格类型 $type 不受支持",
                            rowNumber,
                            columnLabel,
                        )
                        ""
                    }
                }
                if (value.length > ProfileImportLimits.MAX_CELL_CHARS) {
                    issues += ProfileImportIssue(
                        ImportIssueCode.CELL_TOO_LONG,
                        "单元格超过 ${ProfileImportLimits.MAX_CELL_CHARS} 字符",
                        rowNumber,
                        columnLabel,
                    )
                } else {
                    values[columnIndex] = value
                }
                if (issues.size >= ProfileImportLimits.MAX_ISSUES) break
            }
            val lastColumn = values.keys.maxOrNull() ?: -1
            rows += TabularRow(
                number = rowNumber,
                cells = if (lastColumn < 0) emptyList() else List(lastColumn + 1) { values[it].orEmpty() },
            )
            if (issues.size >= ProfileImportLimits.MAX_ISSUES) break
        }
        if (rows.isEmpty()) fail(ImportIssueCode.EMPTY_FILE, "工作表为空")
        return TabularDocument(rows, issues)
    }

    private fun xml(bytes: ByteArray, partName: String) = try {
        // OOXML parts may be UTF-8 or UTF-16. Removing NUL bytes makes the declaration scan cover
        // both byte orders before the platform XML implementation sees any entity declaration.
        val ascii = bytes.toString(Charsets.ISO_8859_1)
            .replace("\u0000", "")
            .uppercase(Locale.ROOT)
        if ("<!DOCTYPE" in ascii || "<!ENTITY" in ascii) {
            fail(ImportIssueCode.MALFORMED_FILE, "$partName 包含不允许的 XML 声明")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ ->
                throw SAXException("XLSX external XML entities are disabled")
            }
        }
        val document = builder.parse(ByteArrayInputStream(bytes))
        if (document.doctype != null) {
            fail(ImportIssueCode.MALFORMED_FILE, "$partName 包含不允许的 DOCTYPE")
        }
        document
    } catch (failure: ProfileImportFormatException) {
        throw failure
    } catch (_: Throwable) {
        fail(ImportIssueCode.MALFORMED_FILE, "$partName 不是安全有效的 XML")
    }

    private fun resolvePart(owner: String, target: String): String {
        if (target.contains('\\')) fail(ImportIssueCode.MALFORMED_FILE, "OOXML 关系路径无效")
        val base = if (target.startsWith('/')) emptyList() else owner.substringBeforeLast('/').split('/')
        val parts = base.toMutableList()
        for (part in target.trimStart('/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) {
                    fail(ImportIssueCode.MALFORMED_FILE, "OOXML 关系路径越界")
                } else {
                    parts.removeAt(parts.lastIndex)
                }
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun requireContentType(
        contentTypes: Map<String, String>,
        part: String,
        expected: String,
    ) {
        if (contentTypes[part] != expected) {
            fail(ImportIssueCode.MALFORMED_FILE, "OOXML 部件 $part 的 ContentType 无效")
        }
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile(Char::isLetter)
        if (letters.isEmpty()) return -1
        var value = 0
        for (letter in letters.uppercase(Locale.ROOT)) {
            if (letter !in 'A'..'Z') return -1
            value = value * 26 + (letter - 'A' + 1)
        }
        return value - 1
    }

    private fun excelColumn(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            val digit = (value - 1) % 26
            result.append(('A'.code + digit).toChar())
            value = (value - 1) / 26
        }
        return result.reverse().toString()
    }

    private fun Element.descendants(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return List(nodes.length) { nodes.item(it) as Element }
    }

    private fun Element.directChildren(localName: String): List<Element> = buildList {
        var child: Node? = firstChild
        while (child != null) {
            if (child is Element && (child.localName ?: child.nodeName) == localName) add(child)
            child = child.nextSibling
        }
    }

    private fun Element.firstDirectText(localName: String): String =
        directChildren(localName).firstOrNull()?.textContent.orEmpty()

    private fun fail(code: ImportIssueCode, message: String): Nothing {
        throw ProfileImportFormatException(listOf(ProfileImportIssue(code, message)))
    }
}
