package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P10DBG-COLLECTOR-V1 — extra type coercion (R2, gpt55 P1-1).
 *
 * adb's `am start` writes `--ei` as Integer, `--el` as Long, `--es` as
 * String, `--ez` as Boolean. The typed Intent getters silently return their
 * defaults on type mismatch — the documented commands must therefore coerce.
 * These cases pin the coercion rules (the AOSP am/getter behavior itself is
 * cited in ExtraCoerce's header, not re-tested here).
 */
class ExtraCoerceTest {

    @Test
    fun longOfAcceptsEveryAdbSpelling() {
        // --el (Long), --ei (Int), --es (String) — all must yield the value.
        assertEquals(30000L, ExtraCoerce.longOf(30000L))
        assertEquals(30000L, ExtraCoerce.longOf(30000))
        assertEquals(30000L, ExtraCoerce.longOf("30000"))
        assertEquals(0L, ExtraCoerce.longOf(0))
    }

    @Test
    fun longOfRefusesGarbageInsteadOfGuessing() {
        assertNull(ExtraCoerce.longOf(null))
        assertNull(ExtraCoerce.longOf("30s"))
        assertNull(ExtraCoerce.longOf(""))
        assertNull(ExtraCoerce.longOf(" "))
        assertNull(ExtraCoerce.longOf(3.14))
        assertNull(ExtraCoerce.longOf(true))
        // out-of-Int-range strings still parse as Long
        assertEquals(9_000_000_000L, ExtraCoerce.longOf("9000000000"))
    }

    @Test
    fun boolOfAcceptsEveryAdbSpelling() {
        assertEquals(true, ExtraCoerce.boolOf(true))
        assertEquals(true, ExtraCoerce.boolOf(1))
        assertEquals(true, ExtraCoerce.boolOf("1"))
        assertEquals(true, ExtraCoerce.boolOf("true"))
        assertEquals(true, ExtraCoerce.boolOf("TRUE"))
        assertEquals(false, ExtraCoerce.boolOf(false))
        assertEquals(false, ExtraCoerce.boolOf(0))
        assertEquals(false, ExtraCoerce.boolOf("0"))
        assertEquals(false, ExtraCoerce.boolOf("false"))
    }

    @Test
    fun boolOfRefusesAmbiguousInput() {
        assertNull(ExtraCoerce.boolOf(null))
        assertNull(ExtraCoerce.boolOf("yes"))
        assertNull(ExtraCoerce.boolOf(2))
        assertNull(ExtraCoerce.boolOf(1L)) // long is not an adb bool spelling
    }
}
