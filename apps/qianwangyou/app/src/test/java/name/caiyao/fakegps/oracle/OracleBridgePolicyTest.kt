package name.caiyao.fakegps.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OracleBridgePolicyTest {

    @Test
    fun `only Android system UID may register a producer`() {
        assertTrue(OracleBridgePolicy.acceptsRegistrarCaller(1_000))
        assertFalse(OracleBridgePolicy.acceptsRegistrarCaller(0))
        assertFalse(OracleBridgePolicy.acceptsRegistrarCaller(10_321))
    }

    @Test
    fun `wrong registrant cannot replace a live producer`() {
        val registry = OracleClientRegistry<String>()
        val liveDeath = FakeDeathLink()
        val rejectedDeath = FakeDeathLink()
        assertTrue(registry.register(1_000, OracleRegistration("oracle-a", liveDeath)))

        assertFalse(registry.register(10_321, OracleRegistration("oracle-b", rejectedDeath)))
        assertEquals("oracle-a", registry.current())
        assertEquals(0, rejectedDeath.linkCalls)
    }

    @Test
    fun `death clears authority and stale death cannot clear a replacement`() {
        val registry = OracleClientRegistry<String>()
        val firstDeath = FakeDeathLink()
        val secondDeath = FakeDeathLink()
        assertTrue(registry.register(1_000, OracleRegistration("oracle-a", firstDeath)))
        firstDeath.die()
        assertNull(registry.current())

        assertTrue(registry.register(1_000, OracleRegistration("oracle-b", secondDeath)))
        firstDeath.die()
        assertEquals("oracle-b", registry.current())
        secondDeath.die()
        assertNull(registry.current())
    }

    private class FakeDeathLink : OracleDeathLink {
        private var callback: (() -> Unit)? = null
        var linkCalls: Int = 0
            private set

        override fun link(onDeath: () -> Unit) {
            linkCalls += 1
            callback = onDeath
        }

        fun die() {
            callback?.invoke()
        }
    }
}
