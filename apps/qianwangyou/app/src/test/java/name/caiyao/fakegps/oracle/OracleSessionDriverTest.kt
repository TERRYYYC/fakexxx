package name.caiyao.fakegps.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * #155: exercises the app-side session driver over the REAL client registry —
 * arrival → registerQwySession with the observer's digest, clear/death → token
 * dropped, begin/finish bracket serialization against a concurrent arrival.
 */
class OracleSessionDriverTest {

    private class Registration(val oracle: RecordingOracle) {
        val deathLink = FakeOracleDeathLink()
        fun into(registry: OracleClientRegistry<IAuthoritativeContinuityOracle>, uid: Int = 1000) {
            assertTrue(registry.register(uid, OracleRegistration(oracle, deathLink)))
        }
    }

    private fun newDriver(
        registry: OracleClientRegistry<IAuthoritativeContinuityOracle>,
        digest: String = "digest-registered",
    ): OracleSessionDriver =
        OracleSessionDriver(registry, semanticDigest = { digest }, deathTokenFactory = { FakeBinder() })

    @Test
    fun `arrival registers the session with the digest source and a live token`() {
        val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()
        val driver = newDriver(registry)
        driver.attach()
        val registration = Registration(RecordingOracle())
        registration.into(registry)

        assertEquals(1, registration.oracle.registrations.size)
        val (digest, token) = registration.oracle.registrations.single()
        assertEquals("digest-registered", digest)
        assertNotNull(token)

        // The registered token is the one every mutation presents.
        assertNotNull(driver.begin("apply-1"))
        val begin = registration.oracle.begins.single()
        assertEquals("apply-1", begin.first)
        assertEquals("digest-registered", begin.second)
        assertSame(token, begin.third)
    }

    @Test
    fun `late attach replays an oracle that arrived before the driver existed`() {
        val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()
        val registration = Registration(RecordingOracle())
        registration.into(registry)
        val driver = newDriver(registry)
        driver.attach()

        assertEquals(1, registration.oracle.registrations.size)
        assertEquals("digest-registered", registration.oracle.registrations.single().first)
    }

    @Test
    fun `registry clear drops the token so begin reports no session`() {
        val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()
        val driver = newDriver(registry)
        driver.attach()
        val registration = Registration(RecordingOracle())
        registration.into(registry)
        assertNotNull(driver.begin("apply-1"))
        assertEquals(1, registration.oracle.begins.size)

        registry.clear()

        assertNull(driver.begin("apply-2"))
        assertEquals("no mutation may cross a dead session", 1, registration.oracle.begins.size)
    }

    @Test
    fun `oracle binder death drops the token`() {
        val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()
        val driver = newDriver(registry)
        driver.attach()
        val registration = Registration(RecordingOracle())
        registration.into(registry)

        registration.deathLink.die()

        assertNull(registry.current())
        assertNull(driver.begin("apply-1"))
        assertTrue(registration.oracle.begins.isEmpty())
    }

    @Test
    fun `rejected begin drops the session and the next attempt re-registers with a fresh digest`() {
        val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()
        var digest = "digest-stale"
        val driver = OracleSessionDriver(
            registry,
            semanticDigest = { digest },
            deathTokenFactory = { FakeBinder() },
        )
        driver.attach()
        val registration = Registration(RecordingOracle())
        registration.into(registry)
        registration.oracle.failNextBegin = true
        assertNull("rejected begin proceeds unbracketed", driver.begin("apply-1"))
        assertEquals(1, registration.oracle.registrations.size)

        digest = "digest-fresh"
        assertNotNull(driver.begin("apply-2"))
        assertEquals("self-heal re-registers once", 2, registration.oracle.registrations.size)
        assertEquals("digest-fresh", registration.oracle.registrations.last().first)
    }

    @Test
    fun `a registration arriving during an open bracket waits instead of retiring the token`() {
        val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()
        val driver = newDriver(registry)
        driver.attach()
        val first = Registration(RecordingOracle())
        first.into(registry)
        val mutation = driver.begin("apply-1")!!
        assertEquals(1, first.oracle.begins.size)

        // The superseding oracle arrives on the registrar's Binder thread while
        // the bracket is open: it must NOT register (retiring the open token
        // would turn the later finish into an invariant poison) until finish.
        // register() publishes before it notifies listeners, so poll until the
        // superseding oracle is current, then assert it has no registration yet.
        val second = Registration(RecordingOracle())
        val arrival = Thread { second.into(registry) }
        arrival.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (registry.current() !== second.oracle && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertSame(second.oracle, registry.current())
        assertEquals(
            "registration must wait for the open bracket",
            0,
            second.oracle.registrations.size,
        )

        mutation.finish(changed = true, uncertain = false, afterDigest = "digest-after")
        arrival.join(TimeUnit.SECONDS.toMillis(5))
        assertEquals(1, second.oracle.registrations.size)
        assertEquals("the old token's finish still landed", 1, first.oracle.finishes.size)
    }
}
