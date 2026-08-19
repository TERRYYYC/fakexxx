package name.caiyao.fakegps.integration.v1

/**
 * The ONE total field codec for provider-side durable carriers (pending-advance
 * marker, receipt payloads, idempotency scope keys, lease records, audit events).
 *
 * §6.3.1/§6.7.3: item IDs and idempotency keys are FREE strings, and the spec
 * rejects fixed-separator encodings because they are not injective — a value
 * containing the separator forges extra fields (Terra PR#22 round-4). Framing is
 * length-prefix: "<len>:<raw>" per field, defined for every string.
 *
 * Nullability is part of totality (Terra PR#22 round-5): a nullable free string
 * has THREE distinct states — null, "", and non-empty — and all three must
 * round-trip. Null is encoded in the LENGTH position as "-1:" (a presence
 * discriminator, not a sentinel value), so "" (encoded "0:") is never confused
 * with null. No carrier may use `?: ""` / `.ifEmpty { null }`: that collapses
 * null and "" and loses a §7.2 authoritative field.
 *
 * One helper by design: v1.38 froze digest framing into a single helper because
 * "a second hand-written copy is a second drift point that does not fail loudly";
 * the same argument applies verbatim to durable storage framing.
 */
internal object DurableFieldCodec {

    private const val NULL_LEN = -1

    fun encode(fields: List<String?>): String = buildString {
        for (f in fields) {
            if (f == null) {
                append(NULL_LEN).append(':')
            } else {
                append(f.length).append(':').append(f)
            }
        }
    }

    fun decode(encoded: String): List<String?> {
        val fields = mutableListOf<String?>()
        var i = 0
        while (i < encoded.length) {
            val colon = encoded.indexOf(':', i)
            check(colon > i) { "corrupt durable framing at offset $i" }
            val len = encoded.substring(i, colon).toInt()
            if (len == NULL_LEN) {
                fields.add(null)
                i = colon + 1
            } else {
                check(len >= 0) { "corrupt durable framing: negative length $len" }
                val start = colon + 1
                check(start + len <= encoded.length) { "corrupt durable framing: field overruns payload" }
                fields.add(encoded.substring(start, start + len))
                i = start + len
            }
        }
        return fields
    }

    /**
     * Convenience for records whose fields are ALL non-null: a decoded null is a
     * serialization-contract violation, so fail loud rather than propagate it.
     */
    fun decodeNonNull(encoded: String): List<String> =
        decode(encoded).map { it ?: error("null field in a non-nullable durable record") }
}
