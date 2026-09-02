package name.caiyao.fakegps.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OracleBridgePolicyTest {

    @Test
    fun `registrar accepts only Android system uid`() {
        assertTrue(OracleBridgePolicy.acceptsRegistrarCaller(1_000))
        assertFalse(OracleBridgePolicy.acceptsRegistrarCaller(0))
        assertFalse(OracleBridgePolicy.acceptsRegistrarCaller(10_321))
    }

    @Test
    fun `oracle accepts only the exact resolved QWY uid`() {
        assertTrue(OracleBridgePolicy.acceptsQwyCaller(callingUid = 10_321, resolvedQwyUid = 10_321))
        assertFalse(OracleBridgePolicy.acceptsQwyCaller(callingUid = 10_322, resolvedQwyUid = 10_321))
        assertFalse(OracleBridgePolicy.acceptsQwyCaller(callingUid = 10_321, resolvedQwyUid = null))
    }

    @Test
    fun `system registration links death and death clears authority`() {
        val registry = OracleClientRegistry<String>()
        val death = FakeDeathLink()

        assertTrue(registry.register(1_000, OracleRegistration("oracle-a", death)))
        assertEquals("oracle-a", registry.current())

        death.die()
        assertNull(registry.current())
    }

    @Test
    fun `non-system registration neither links nor replaces current oracle`() {
        val registry = OracleClientRegistry<String>()
        val existingDeath = FakeDeathLink()
        val rejectedDeath = FakeDeathLink()
        assertTrue(registry.register(1_000, OracleRegistration("oracle-a", existingDeath)))

        assertFalse(registry.register(10_321, OracleRegistration("oracle-b", rejectedDeath)))
        assertEquals("oracle-a", registry.current())
        assertEquals(0, rejectedDeath.linkCalls)
    }

    @Test
    fun `stale death from replaced binder cannot clear current oracle`() {
        val registry = OracleClientRegistry<String>()
        val firstDeath = FakeDeathLink()
        val secondDeath = FakeDeathLink()
        assertTrue(registry.register(1_000, OracleRegistration("oracle-a", firstDeath)))
        assertTrue(registry.register(1_000, OracleRegistration("oracle-b", secondDeath)))

        firstDeath.die()

        assertEquals("oracle-b", registry.current())
        assertEquals(1, firstDeath.unlinkCalls)
    }

    @Test
    fun `failed death link is rejected without replacing live authority`() {
        val registry = OracleClientRegistry<String>()
        val existingDeath = FakeDeathLink()
        assertTrue(registry.register(1_000, OracleRegistration("oracle-a", existingDeath)))

        val broken = FakeDeathLink(failLink = true)
        assertFalse(registry.register(1_000, OracleRegistration("oracle-b", broken)))

        assertEquals("oracle-a", registry.current())
    }

    @Test
    fun `binder dying inside link is never published and is unlinked`() {
        val registry = OracleClientRegistry<String>()
        val dying = FakeDeathLink(dieDuringLink = true)

        assertFalse(registry.register(1_000, OracleRegistration("oracle-a", dying)))

        assertNull(registry.current())
        assertEquals(1, dying.unlinkCalls)
    }

    private class FakeDeathLink(
        private val failLink: Boolean = false,
        private val dieDuringLink: Boolean = false,
    ) : OracleDeathLink {
        private var callback: (() -> Unit)? = null
        var linkCalls: Int = 0
            private set
        var unlinkCalls: Int = 0
            private set

        override fun link(onDeath: () -> Unit) {
            linkCalls += 1
            if (failLink) error("binder already dead")
            callback = onDeath
            if (dieDuringLink) onDeath()
        }

        override fun unlink() {
            unlinkCalls += 1
        }

        fun die() {
            callback?.invoke()
        }
    }
}
