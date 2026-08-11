package name.caiyao.fakegps.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Serializes [SpoofConfig] to/from a canonical JSON snapshot and computes a stable
 * content fingerprint used as the "basis of truth" reference for verification
 * (the same short hash is meant to be shown in three places: UI / hook log / probe).
 *
 * Determinism: kotlinx.serialization emits fields in declaration order, and we pin
 * `encodeDefaults = true`, so an equal SpoofConfig always yields byte-identical JSON
 * and therefore an identical fingerprint.
 */
object ConfigCodec {

    private val json = Json {
        encodeDefaults = true   // stable structure => stable hash
        prettyPrint = false
    }

    fun toJson(config: SpoofConfig): String = json.encodeToString(config)

    /**
     * Parse a JSON snapshot. Throws [kotlinx.serialization.SerializationException]
     * (or IllegalArgumentException) on malformed input — the CALLER decides the
     * fallback (see [ConfigHolder], which keeps last-known-good rather than
     * reverting to real device data).
     */
    fun fromJson(text: String): SpoofConfig = json.decodeFromString(text)

    /** SHA-256 of the canonical JSON, hex. Equal config => equal fingerprint. */
    fun fingerprint(config: SpoofConfig): String {
        val bytes = toJson(config).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
