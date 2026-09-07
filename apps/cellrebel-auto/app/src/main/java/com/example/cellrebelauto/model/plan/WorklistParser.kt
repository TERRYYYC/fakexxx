package com.example.cellrebelauto.model.plan

/**
 * One parsed worklist row.
 * # 一行解析后的清单数据；csvRow 为源文件中从 1 开始的数据行号（不含表头与空行）
 */
data class WorklistRow(
    val longitude: Double,
    val latitude: Double,
    val priority: Int,
    val requiredSuccesses: Int,
    val csvRow: Int,
    val scheduleId: String? = null,
    val scheduleItemId: String? = null
)

/**
 * Row-level validation error.
 * # 行级校验错误
 */
data class RowError(val csvRow: Int, val message: String)

/**
 * Parse result: atomic — either the whole file is valid, or ALL row errors are reported.
 * # 解析结果：原子语义——整份有效才成功，否则报告全部行级错误，不允许部分导入
 */
sealed interface ParseResult {
    data class Success(val rows: List<WorklistRow>) : ParseResult
    data class Failure(val errors: List<RowError>) : ParseResult
}

/**
 * Atomic parser for the operator's CSV worklist.
 * # operator CSV 清单的原子解析器
 *
 * Canonical legacy contract (design gate, no third-party dependency):
 *   longitude,latitude,priority,required_successes
 * Bound v2 contract:
 *   longitude,latitude,priority,required_successes,schedule_id,schedule_item_id
 *
 * Rules:
 * - First non-blank line must be the exact header.
 * - Blank lines are skipped; csvRow numbers data rows only, 1-based.
 * - longitude ∈ [-180, 180], latitude ∈ [-90, 90],
 *   priority ≥ 0 integer, required_successes ≥ 1 integer.
 * - Any invalid row rejects the whole file; every row error is reported.
 */
object WorklistParser {

    const val HEADER = "longitude,latitude,priority,required_successes"
    const val BOUND_HEADER =
        "longitude,latitude,priority,required_successes,schedule_id,schedule_item_id"

    fun parse(text: String): ParseResult {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) {
            return ParseResult.Failure(listOf(RowError(0, "empty file: expected header '$HEADER'")))
        }
        val bound = lines.first() == BOUND_HEADER
        if (lines.first() != HEADER && !bound) {
            return ParseResult.Failure(
                listOf(
                    RowError(
                        0,
                        "invalid header: expected '$HEADER' or '$BOUND_HEADER', got '${lines.first()}'"
                    )
                )
            )
        }

        val rows = mutableListOf<WorklistRow>()
        val errors = mutableListOf<RowError>()
        val expectedColumns = if (bound) 6 else 4
        var boundScheduleId: String? = null
        val seenItemIds = mutableSetOf<String>()

        lines.drop(1).forEachIndexed { index, line ->
            val csvRow = index + 1
            val fields = line.split(",").map { it.trim() }
            if (fields.size != expectedColumns) {
                errors.add(
                    RowError(
                        csvRow,
                        "expected $expectedColumns columns (${if (bound) BOUND_HEADER else HEADER}), got ${fields.size}"
                    )
                )
                return@forEachIndexed
            }

            val longitude = fields[0].toDoubleOrNull()
            val latitude = fields[1].toDoubleOrNull()
            val priority = fields[2].toIntOrNull()
            val requiredSuccesses = fields[3].toIntOrNull()

            when {
                longitude == null || longitude !in -180.0..180.0 ->
                    errors.add(RowError(csvRow, "longitude '${fields[0]}' must be a number in [-180, 180]"))
                latitude == null || latitude !in -90.0..90.0 ->
                    errors.add(RowError(csvRow, "latitude '${fields[1]}' must be a number in [-90, 90]"))
                priority == null || priority < 0 ->
                    errors.add(RowError(csvRow, "priority '${fields[2]}' must be an integer ≥ 0"))
                requiredSuccesses == null || requiredSuccesses < 1 ->
                    errors.add(RowError(csvRow, "required_successes '${fields[3]}' must be an integer ≥ 1"))
                else -> {
                    val scheduleId = fields.getOrNull(4)
                    val scheduleItemId = fields.getOrNull(5)
                    var bindingValid = true
                    if (bound) {
                        if (scheduleId.isNullOrBlank()) {
                            errors.add(RowError(csvRow, "schedule_id must be non-blank"))
                            bindingValid = false
                        } else if (boundScheduleId == null) {
                            boundScheduleId = scheduleId
                        } else if (scheduleId != boundScheduleId) {
                            errors.add(
                                RowError(
                                    csvRow,
                                    "schedule_id '$scheduleId' differs from plan schedule_id '$boundScheduleId'"
                                )
                            )
                            bindingValid = false
                        }
                        if (scheduleItemId.isNullOrBlank()) {
                            errors.add(RowError(csvRow, "schedule_item_id must be non-blank"))
                            bindingValid = false
                        } else if (!seenItemIds.add(scheduleItemId)) {
                            errors.add(RowError(csvRow, "duplicate schedule_item_id '$scheduleItemId'"))
                            bindingValid = false
                        }
                    }
                    if (bindingValid) {
                        rows.add(
                            WorklistRow(
                                longitude,
                                latitude,
                                priority,
                                requiredSuccesses,
                                csvRow,
                                scheduleId,
                                scheduleItemId
                            )
                        )
                    }
                }
            }
        }

        return if (errors.isNotEmpty()) {
            ParseResult.Failure(errors)
        } else if (rows.isEmpty()) {
            // # F9：零数据行 = 僵尸 plan（既不可完成也不可恢复），导入即拒
            ParseResult.Failure(listOf(RowError(0, "no data rows: the worklist must contain at least one row")))
        } else {
            ParseResult.Success(rows)
        }
    }
}
