package name.caiyao.fakegps.verify

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Correlated public-API observations returned by the private hook probe process. */
@Serializable
data class ProbeObservationEnvelope(
    val requestId: String,
    val fingerprint: String,
    val values: Map<String, String>,
    val notes: List<String>,
    val cellCount: Int,
)

object ProbeObservationCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(envelope: ProbeObservationEnvelope): String = json.encodeToString(envelope)

    fun decode(raw: String?): ProbeObservationEnvelope? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ProbeObservationEnvelope>(raw) }.getOrNull()
    }
}
