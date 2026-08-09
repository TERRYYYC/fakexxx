package name.caiyao.fakegps.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import name.caiyao.fakegps.config.UnavailableSpec
import name.caiyao.fakegps.data.db.ProfileEntityCodec

/** Canonical string normalization and validation shared by editor and file import. */
object ProfileFieldValueValidator {
    const val MAX_VALUE_CHARS = 4_096
    const val MAX_NAME_CHARS = 200

    data class Result(val value: String? = null, val error: String? = null)

    private val specs = FieldSpec.allCategories().values.flatten().associateBy { it.dbColumn }
    private val json = Json { isLenient = false }
    private val decimalRanges = mapOf(
        "latitude" to (-90.0..90.0),
        "longitude" to (-180.0..180.0),
        "altitude" to (0.0..10_000.0),
        "speed" to (0.0..100.0),
        "bearing" to (0.0..360.0),
        "accuracy" to (1.0..100.0),
    )
    private val integerRanges = mapOf(
        "mcc" to (0L..999L), "mnc" to (0L..999L),
        "lac" to (0L..65_535L), "cid" to (0L..65_535L),
        "arfcn" to (0L..1_023L), "bsic" to (0L..63L),
        "psc" to (0L..511L), "uarfcn" to (0L..16_383L),
        "tac" to (0L..65_535L), "ci" to (0L..268_435_455L),
        "pci" to (0L..503L), "earfcn" to (0L..262_143L),
        "lte_bandwidth" to (0L..20_000L), "nci" to (0L..68_719_476_735L),
        "nrarfcn" to (0L..3_279_165L), "nr_pci" to (0L..1_007L),
        "nr_tac" to (0L..16_777_215L),
        "gsm_rssi" to (-113L..-51L), "gsm_ber" to (0L..99L),
        "gsm_ta" to (0L..219L), "wcdma_rssi" to (-120L..-24L),
        "wcdma_rscp" to (-120L..-24L), "wcdma_ecno" to (-24L..1L),
        "lte_rssi" to (-120L..-25L), "lte_rsrp" to (-140L..-44L),
        "lte_rsrq" to (-20L..-3L), "lte_sinr" to (-23L..40L),
        "lte_cqi" to (0L..15L), "lte_ta" to (0L..1_282L),
        "nr_ss_rsrp" to (-140L..-44L), "nr_ss_rsrq" to (-20L..-3L),
        "nr_ss_sinr" to (-23L..40L), "nr_csi_rsrp" to (-140L..-44L),
        "nr_csi_rsrq" to (-20L..-3L), "nr_csi_sinr" to (-23L..40L),
        "signal_fluctuation_range_db" to (1L..10L),
        "wifi_rssi" to (-90L..-40L), "wifi_frequency" to (2_412L..7_125L),
        "wifi_channel" to (1L..233L), "wifi_standard" to (1L..6L),
        "wifi_security_type" to (0L..4L),
    )

    fun normalize(column: String, raw: String): Result {
        if (raw.isBlank()) return Result()
        if (column == "addname") {
            return if (raw.length <= MAX_NAME_CHARS) {
                Result(raw)
            } else {
                Result(error = "档案名超过 $MAX_NAME_CHARS 字符")
            }
        }
        val spec = specs[column] ?: return Result(error = "未知字段")
        if (raw.length > MAX_VALUE_CHARS) {
            return Result(error = "单元格超过 $MAX_VALUE_CHARS 字符")
        }
        val trimmed = raw.trim()
        if (trimmed == ProfileEntityCodec.UNAVAILABLE_TOKEN) {
            return if (UnavailableSpec.supportsUnavailable(column)) {
                Result(ProfileEntityCodec.UNAVAILABLE_TOKEN)
            } else {
                Result(error = "此字段不支持 -- 不上报")
            }
        }

        return when (spec.type) {
            FieldType.TEXT -> validateText(column, raw)
            FieldType.BOOLEAN -> when {
                trimmed == "0" || trimmed.equals("false", ignoreCase = true) -> Result("0")
                trimmed == "1" || trimmed.equals("true", ignoreCase = true) -> Result("1")
                else -> Result(error = "布尔值必须是 0/1/true/false")
            }
            FieldType.INTEGER -> normalizeInteger(column, trimmed)
            FieldType.DOUBLE -> normalizeDecimal(column, trimmed, false)
            FieldType.FLOAT -> normalizeDecimal(column, trimmed, true)
        }
    }

    private fun normalizeInteger(column: String, value: String): Result {
        if (!INTEGER.matches(value)) return Result(error = "必须是整数")
        val parsed = value.toLongOrNull() ?: return Result(error = "整数超出范围")
        if (column != "nci" && parsed !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            return Result(error = "整数超出 32-bit 范围")
        }
        if (column == "gsm_ber" && parsed !in 0L..7L && parsed != 99L) {
            return Result(error = "必须在 0..7 范围内，或使用 99 表示未知")
        }
        val range = integerRanges[column]
        if (range != null && parsed !in range) {
            return Result(error = "必须在 ${range.first}..${range.last} 范围内")
        }
        return Result(parsed.toString())
    }

    private fun normalizeDecimal(column: String, value: String, float: Boolean): Result {
        val parsed = value.toDoubleOrNull()
            ?: return Result(error = if (float) "必须是浮点数" else "必须是数值")
        if (!parsed.isFinite()) return Result(error = "必须是有限数值")
        if (float && (parsed > Float.MAX_VALUE || parsed < -Float.MAX_VALUE)) {
            return Result(error = "浮点数超出范围")
        }
        val range = decimalRanges[column]
        if (range != null && parsed !in range) {
            return Result(error = "必须在 ${range.start}..${range.endInclusive} 范围内")
        }
        return Result(if (float) parsed.toFloat().toString() else parsed.toString())
    }

    private fun validateText(column: String, value: String): Result {
        if (column == "neighbor_cells_json") {
            val valid = runCatching { json.parseToJsonElement(value) is JsonArray }.getOrDefault(false)
            if (!valid) return Result(error = "必须是 JSON array")
        }
        return Result(value)
    }

    private val INTEGER = Regex("[+-]?\\d+")
}
