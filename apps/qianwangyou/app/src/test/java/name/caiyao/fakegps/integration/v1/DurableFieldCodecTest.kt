package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Totality guard for the shared durable codec (Terra PR#22 round-4). The codec
 * must round-trip ANY list of strings — the spec (§6.3.1/§6.7.3) rejects fixed
 * separators precisely because a value containing the separator forges fields.
 * The length-prefix scheme has no separator in the value position, so a colon,
 * tab, or the framing colon itself inside a field is harmless.
 */
class DurableFieldCodecTest {

    private fun roundTrips(fields: List<String>) {
        assertEquals(fields, DurableFieldCodec.decode(DurableFieldCodec.encode(fields)))
    }

    @Test fun emptyFieldList() { roundTrips(emptyList()) }

    @Test fun emptyStringFields() { roundTrips(listOf("", "", "")) }

    @Test fun tabBearingItemIds() { roundTrips(listOf("item\tfrom", "item\t2", "plain")) }

    /** The framing delimiter is a colon; a colon INSIDE a value must not confuse decode. */
    @Test fun colonBearingValues() { roundTrips(listOf("a:b:c", "1:hostile", "http://x:8080")) }

    @Test fun everySeparatorClassMixed() { roundTrips(listOf("k\t1:hostile", "|,\t:", "a\nb")) }

    @Test fun unicodeAndLongFields() { roundTrips(listOf("千网游\t换环境", "z".repeat(257), "")) }

    /**
     * Receipt payloads are themselves encoded and then embedded as ONE field of
     * an outer record — nested framing must stay total.
     */
    @Test
    fun nestedEncodingRoundTrips() {
        val inner = DurableFieldCodec.encode(listOf("op\t1", "key:2", ""))
        roundTrips(listOf("caller.app", inner, "tail"))
    }

    /** null, "", and non-empty are THREE distinct states that must all survive (round-5). */
    @Test
    fun nullDistinctFromEmptyString() {
        val fields = listOf<String?>(null, "", "x", null, "")
        assertEquals(fields, DurableFieldCodec.decode(DurableFieldCodec.encode(fields)))
        org.junit.Assert.assertNotEquals(
            "null and empty string must encode differently",
            DurableFieldCodec.encode(listOf<String?>(null)),
            DurableFieldCodec.encode(listOf<String?>("")),
        )
    }

    /** decodeNonNull fails loud if a durable record claims a field is non-null but decoded null. */
    @Test(expected = IllegalStateException::class)
    fun decodeNonNullRejectsNullField() {
        DurableFieldCodec.decodeNonNull(DurableFieldCodec.encode(listOf<String?>("ok", null)))
    }
}
