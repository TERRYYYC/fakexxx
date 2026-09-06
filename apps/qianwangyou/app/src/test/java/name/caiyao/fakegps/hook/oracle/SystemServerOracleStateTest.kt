package name.caiyao.fakegps.hook.oracle

import name.caiyao.fakegps.integration.v1.AuthoritativeWindowVerdict
import name.caiyao.fakegps.integration.v1.BinderAuthoritativeContinuitySource
import name.caiyao.fakegps.integration.v1.classifyAuthoritativeWindow
import name.caiyao.fakegps.oracle.OracleBundleCodec
import name.caiyao.fakegps.oracle.OracleClientRegistry
import name.caiyao.fakegps.oracle.OracleDeathLink
import name.caiyao.fakegps.oracle.OracleRegistration
import name.caiyao.fakegps.oracle.OracleWireHealth
import org.junit.Assert.*
import org.junit.Test

/** Executes the exact state owner delegated to by the production Binder, without Android/adb. */
class SystemServerOracleStateTest {
    private val qwyPackage = "name.caiyao.fakegps.bench"
    private val qwyUid = 10_321
    private val bootId = "6c6742ae-f815-4589-93cb-02b375d629be"

    private class Session : SystemServerOracleState.SessionToken {
        private val deaths = mutableListOf<Runnable>()
        var dieOnLink = false
        override fun link(onDeath: Runnable) {
            deaths += onDeath
            if (dieOnLink) onDeath.run()
        }
        override fun unlink(onDeath: Runnable) { deaths -= onDeath }
        fun die() { deaths.toList().forEach(Runnable::run) }
    }

    private inner class Fixture(attested: Boolean = true) {
        var uid = qwyUid
        val session = Session()
        var drain: () -> Unit = {}
        val producer = SystemServerOracleState(
            bootId, true, attested,
            object : SystemServerOracleState.CallerIdentity {
                override fun uid() = uid
                override fun pid() = 4321
            },
            Runnable { drain() },
            SystemServerOracleState.EndpointReader {
                SystemServerOracleState.EndpointSample(qwyUid, qwyPackage, true, true, true, null)
            },
        ).apply { configureExpectedQwyIdentity(qwyUid, qwyPackage) }

        fun connect() { producer.onBridgeConnected(1) }
        fun installPlatformHooks() {
            producer.markInstalled(
                Android15OracleHookPlan.COVERAGE_APP_OPS_WRAPPER or
                    Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_DELEGATE or
                    Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_LIFECYCLE or
                    Android15OracleHookPlan.COVERAGE_LOCATION_PROVIDER_STATE or
                    Android15OracleHookPlan.COVERAGE_LOCATION_EFFECTIVE_ENABLED or
                    Android15OracleHookPlan.COVERAGE_LOCATION_SEMANTIC_COORDINATE,
            )
        }
    }

    @Test
    fun `registration alone never invents missing semantic writer coverage`() {
        val f = Fixture()
        f.installPlatformHooks()
        f.connect()
        f.producer.registerQwySession("digest-a", f.session)
        val wire = f.producer.snapshot()
        assertEquals(OracleWireHealth.HOOKS_INCOMPLETE, wire.health)
        assertEquals(0L, wire.installedCoverageMask and Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION)
        val registry = OracleClientRegistry<SystemServerOracleState>()
        val death = object : OracleDeathLink { override fun link(onDeath: () -> Unit) = Unit }
        assertFalse(registry.register(qwyUid, OracleRegistration(f.producer, death)))
        assertTrue(registry.register(1000, OracleRegistration(f.producer, death)))
        val consumer = BinderAuthoritativeContinuitySource {
            registry.current()?.snapshot()?.let { OracleBundleCodec.decodeFields(OracleBundleCodec.encodeFields(it)) }
        }
        val pre = consumer.snapshot()
        assertNotNull(pre)
        assertEquals(AuthoritativeWindowVerdict.UNHEALTHY,
            classifyAuthoritativeWindow(pre, consumer.snapshot(), qwyPackage, qwyUid))
        registry.clear()
        assertNull(consumer.snapshot())
    }

    @Test
    fun `only resolved QWY caller can register or read and failed identity makes no source`() {
        val f = Fixture()
        f.uid = 1000
        assertThrows(SecurityException::class.java) { f.producer.registerQwySession("digest-a", f.session) }
        assertThrows(SecurityException::class.java) { f.producer.snapshot() }
        f.uid = qwyUid
        assertEquals(0L, f.producer.snapshot().sequence)
    }

    @Test
    fun `live registration and death advance journal and revoke session coverage`() {
        val f = Fixture()
        f.connect()
        val before = f.producer.snapshot().sequence
        f.producer.registerQwySession("digest-a", f.session)
        val registered = f.producer.snapshot()
        assertEquals(before + 2, registered.sequence)
        f.session.die()
        val died = f.producer.snapshot()
        assertEquals(registered.sequence + 2, died.sequence)
        assertEquals(0L, died.installedCoverageMask and Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION)
        assertNotEquals(OracleWireHealth.HEALTHY, died.health)
        f.producer.onBridgeDisconnected(1)
        assertEquals("same process loss must not double count death", died.sequence, f.producer.snapshot().sequence)
    }

    @Test
    fun `death during registration cannot publish a live semantic session`() {
        val f = Fixture()
        f.session.dieOnLink = true
        assertThrows(IllegalStateException::class.java) { f.producer.registerQwySession("digest-a", f.session) }
        assertEquals(0L, f.producer.snapshot().installedCoverageMask and Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION)
    }

    @Test
    fun `covered away restore changes cursor while same semantic no op preserves it`() {
        val f = Fixture()
        f.connect()
        f.producer.registerQwySession("digest-a", f.session)
        val original = f.producer.snapshot()
        repeat(2) {
            val token = f.producer.beginCoveredMutation(2000, 7000, "foreign", null)
            assertEquals(1L, f.producer.snapshot().sequence and 1)
            f.producer.finishCoveredMutation(token, false)
        }
        assertEquals(original.sequence + 4, f.producer.snapshot().sequence)
        val beforeNoOp = f.producer.snapshot()
        val token = f.producer.beginQwySemanticMutation("no-op", "digest-a", f.session)
        f.producer.finishQwySemanticMutation(token, false, false, "digest-a")
        assertEquals(beforeNoOp, f.producer.snapshot())
    }

    @Test
    fun `barrier failure poisons producer instead of publishing trusted stable state`() {
        val f = Fixture()
        f.connect()
        f.producer.registerQwySession("digest-a", f.session)
        val token = f.producer.beginQwySemanticMutation("change", "digest-a", f.session)
        f.drain = { throw IllegalStateException("injected finisher failure") }
        assertThrows(IllegalStateException::class.java) {
            f.producer.finishQwySemanticMutation(token, true, false, "digest-b")
        }
        assertEquals(OracleWireHealth.CALLBACK_POISONED, f.producer.snapshot().health)
    }

    @Test
    fun `production fingerprint allowlist remains empty and runtime flags cannot attest it`() {
        assertTrue(Android15OracleHookPlan.ATTESTED_FINGERPRINTS.isEmpty())
        val f = Fixture(attested = false)
        f.producer.markInstalled(Android15OracleHookPlan.REQUIRED_COVERAGE_MASK)
        f.connect()
        f.producer.registerQwySession("digest-a", f.session)
        assertEquals(OracleWireHealth.BUILD_UNATTESTED, f.producer.snapshot().health)
    }
}
