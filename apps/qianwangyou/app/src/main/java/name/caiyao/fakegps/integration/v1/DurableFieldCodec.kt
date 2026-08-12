package name.caiyao.fakegps.integration.v1

/**
 * The ONE total field codec for provider-side durable carriers (pending-advance
 * marker, receipt payloads, idempotency scope keys).
 *
 * §6.3.1/§6.7.3: item IDs and idempotency keys are FREE strings, and the spec
 * rejects fixed-separator encodings because they are not injective — a value
 * containing the separator forges extra fields (Terra PR#22 round-4: a tab in
 * an item ID made a committed advance unrecoverable). Framing therefore uses
 * the same length-prefix discipline as the contract's canonical digests:
 * "<len>:<raw>" per field, defined for every string, no unreachable-value
 * assumption. Null is a caller-side presence discriminator field, never a
 * sentinel value.
 *
 * This is deliberately a single shared helper: v1.38 froze the digest framing
 * into one helper because "第二份手写副本是第二个漂移点，且漂移的 framing 不会
 * 响亮失败" — the same argument applies verbatim to durable storage framing.
 */
internal object DurableFieldCodec {

    fun encode(fields: List<String>): String = buildString {
        for (f in fields) append(f.length).append(':').append(f)
    }

    fun decode(encoded: String): List<String> {
        val fields = mutableListOf<String>()
        var i = 0
        while (i < encoded.length) {
            val colon = encoded.indexOf(':', i)
            check(colon > i) { "corrupt durable framing at offset $i" }
            val len = encoded.substring(i, colon).toInt()
            val start = colon + 1
            check(start + len <= encoded.length) { "corrupt durable framing: field overruns payload" }
            fields.add(encoded.substring(start, start + len))
            i = start + len
        }
        return fields
    }
}
