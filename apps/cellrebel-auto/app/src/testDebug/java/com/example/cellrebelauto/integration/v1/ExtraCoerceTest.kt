package com.example.cellrebelauto.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P10DBG-COLLECTOR-V1 — extra type coercion (R2, gpt55 P1-1), Auto side.
 * Mirrors the qwy-side cases: adb `--ei`/`--el`/`--es`/`--ez` spellings must
 * all coerce; garbage refuses instead of guessing.
 */
class ExtraCoerceTest {

    @Test
    fun longOfAcceptsEveryAdbSpelling() {
        assertEquals(30000L, ExtraCoerce.longOf(30000L))
        assertEquals(30000L, ExtraCoerce.longOf(30000))
        assertEquals(30000L, ExtraCoerce.longOf("30000"))
    }

    @Test
    fun longOfRefusesGarbageInsteadOfGuessing() {
        assertNull(ExtraCoerce.longOf(null))
        assertNull(ExtraCoerce.longOf("30s"))
        assertNull(ExtraCoerce.longOf(3.14))
        assertEquals(9_000_000_000L, ExtraCoerce.longOf("9000000000"))
    }

    @Test
    fun boolOfAcceptsAndRefusesCorrectly() {
        assertEquals(true, ExtraCoerce.boolOf(true))
        assertEquals(true, ExtraCoerce.boolOf(1))
        assertEquals(true, ExtraCoerce.boolOf("true"))
        assertEquals(false, ExtraCoerce.boolOf("0"))
        assertNull(ExtraCoerce.boolOf("yes"))
        assertNull(ExtraCoerce.boolOf(2))
    }
}
