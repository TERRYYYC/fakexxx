package name.caiyao.fakegps.ui.screen.editor

import name.caiyao.fakegps.config.UnavailablePayloadContract
import name.caiyao.fakegps.config.UnavailableSpec
import name.caiyao.fakegps.data.model.ProfileFieldValueValidator

/** Pure three-state editor reducer: blank = passthrough, "--" = unavailable, value = spoof. */
object ProfileFieldDraft {
    const val UNAVAILABLE_TOKEN = "--"

    data class Split(
        val values: Map<String, String>,
        val unavailable: Set<String>,
    )

    fun update(current: Map<String, String>, column: String, input: String): Map<String, String> {
        val next = current.toMutableMap()
        val normalized = if (input.trim() == UNAVAILABLE_TOKEN) UNAVAILABLE_TOKEN else input
        when {
            normalized.isBlank() -> {
                next.remove(column)
                if (column in PLMN_FIELDS && current[column] == UNAVAILABLE_TOKEN) {
                    next.remove(otherPlmn(column))
                }
            }
            normalized == UNAVAILABLE_TOKEN -> {
                next[column] = UNAVAILABLE_TOKEN
                if (column in PLMN_FIELDS && UnavailableSpec.supportsUnavailable(column)) {
                    next[otherPlmn(column)] = UNAVAILABLE_TOKEN
                }
            }
            else -> {
                next[column] = normalized
                if (column in PLMN_FIELDS && current[column] == UNAVAILABLE_TOKEN) {
                    next.remove(otherPlmn(column))
                }
            }
        }
        return next
    }

    fun validationErrors(draft: Map<String, String>): Map<String, String> {
        return buildMap {
            for ((column, raw) in draft) {
                val error = ProfileFieldValueValidator.normalize(column, raw).error
                if (error != null) put(column, error)
            }
        }
    }

    fun requireValid(draft: Map<String, String>) {
        val errors = validationErrors(draft)
        require(errors.isEmpty()) { errors.entries.joinToString { "${it.key}: ${it.value}" } }
    }

    fun split(draft: Map<String, String>): Split {
        requireValid(draft)
        val normalized = buildMap {
            for ((column, raw) in draft) {
                ProfileFieldValueValidator.normalize(column, raw).value?.let { put(column, it) }
            }
        }
        val unavailable = normalized.filterValues { it == UNAVAILABLE_TOKEN }.keys.toList()
        val values = normalized.filterValues { it != UNAVAILABLE_TOKEN }
        val validated = UnavailablePayloadContract.validate(values.keys, unavailable)
        return Split(values, validated.asSet())
    }

    fun forDisplay(values: Map<String, String>, unavailable: Set<String>): Map<String, String> {
        val validated = UnavailablePayloadContract.validate(values.keys, unavailable.toList())
        return values + validated.asSet().associateWith { UNAVAILABLE_TOKEN }
    }

    private val PLMN_FIELDS = setOf("mcc", "mnc")

    private fun otherPlmn(column: String): String = if (column == "mcc") "mnc" else "mcc"
}
