package name.caiyao.fakegps.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Canonical Room representation for the profile's explicit-unavailable field set. */
object UnavailableFieldSet {
    private val json = Json { isLenient = false }

    @JvmStatic
    fun encode(fields: Collection<String>): String? {
        val validated = UnavailablePayloadContract.validate(emptySet(), fields.toList())
        if (validated.asList().isEmpty()) return null
        return JsonArray(validated.asList().map(::JsonPrimitive)).toString()
    }

    @JvmStatic
    fun decode(stored: String?): Set<String> {
        if (stored.isNullOrBlank()) return emptySet()
        val names = try {
            json.parseToJsonElement(stored).jsonArray.map { element ->
                val primitive = element.jsonPrimitive
                require(primitive.isString) { "unavailable field must be a string" }
                primitive.content
            }
        } catch (t: Throwable) {
            throw IllegalArgumentException("invalid unavailable_fields JSON", t)
        }
        return UnavailablePayloadContract.validate(emptySet(), names).asSet()
    }
}
