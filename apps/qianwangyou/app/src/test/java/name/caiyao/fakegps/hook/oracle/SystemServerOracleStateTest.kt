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
        var endpointReads = 0
        var readEndpoint: () -> SystemServerOracleState.EndpointSample = {
            SystemServerOracleState.EndpointSample(qwyUid, qwyPackage, true, true, true, null)
        }
        val producer = SystemServerOracleState(
            bootId, true, attested,
            object : SystemServerOracleState.CallerIdentity {
                override fun uid() = uid
                override fun pid() = 4321
            },
            Runnable { drain() },
            SystemServerOracleState.EndpointReader {
                endpointReads++
                readEndpoint()
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

    /**
     * #155: the reserved semantic-session bit is granted by a COMPLETED clean
     * (non-uncertain) QWY mutation, never by registration alone — registration
     * still proves only service generation (the assertion above stays), but a
     * bracketed writer finishing cleanly is exactly the proof #66 was waiting
     * for. With all other bits installed, one clean finish must make the
     * producer HEALTHY and close a VALID consumer window over the new digest.
     */
    @Test
    fun `completed clean semantic mutation grants semantic session coverage and closes a valid window`() {
        val f = Fixture()
        f.installPlatformHooks()
        f.connect()
        f.producer.registerQwySession("digest-a", f.session)
        val token = f.producer.beginQwySemanticMutation("apply-lease-1", "digest-a", f.session)
        f.producer.finishQwySemanticMutation(token, true, false, "digest-b")
        val wire = f.producer.snapshot()
        assertNotEquals(
            0L,
            wire.installedCoverageMask and Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION,
        )
        assertEquals(
            Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
            wire.installedCoverageMask,
        )
        assertEquals(OracleWireHealth.HEALTHY, wire.health)
        val registry = OracleClientRegistry<SystemServerOracleState>()
        val death = object : OracleDeathLink { override fun link(onDeath: () -> Unit) = Unit }
        assertTrue(registry.register(1000, OracleRegistration(f.producer, death)))
        val consumer = BinderAuthoritativeContinuitySource { registry.current()?.snapshot() }
        val pre = consumer.snapshot()
        val post = consumer.snapshot()
        assertEquals(
            AuthoritativeWindowVerdict.VALID,
            classifyAuthoritativeWindow(pre, post, qwyPackage, qwyUid),
        )
        assertEquals("digest-b", pre?.qwySemanticDigest)
        registry.clear()
    }

    /** #155: the uncertain-finish clear path must keep removing the granted bit. */
    @Test
    fun `uncertain semantic finish revokes semantic session coverage`() {
        val f = Fixture()
        f.installPlatformHooks()
        f.connect()
        f.producer.registerQwySession("digest-a", f.session)
        val token = f.producer.beginQwySemanticMutation("apply-lease-1", "digest-a", f.session)
        f.producer.finishQwySemanticMutation(token, true, true, "digest-b")
        val wire = f.producer.snapshot()
        assertEquals(
            0L,
            wire.installedCoverageMask and Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION,
        )
        // Generation proof (bit 5) survives; the session is inactive, so no FULL.
        assertNotEquals(OracleWireHealth.HEALTHY, wire.health)
    }

    /** #155: a begin with no live session keeps the existing fail-closed answer. */
    @Test
    fun `begin without a registered session fails closed and grants nothing`() {
        val f = Fixture()
        f.installPlatformHooks()
        f.connect()
        assertThrows(IllegalStateException::class.java) {
            f.producer.beginQwySemanticMutation("apply-lease-1", "digest-a", f.session)
        }
        assertEquals(
            0L,
            f.producer.snapshot().installedCoverageMask and
                Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION,
        )
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

    @Test
    fun `pre bridge covered completion preserves history without reading unavailable context`() {
        val f = Fixture()
        var contextReady = false
        f.readEndpoint = {
            if (contextReady) {
                SystemServerOracleState.EndpointSample(qwyUid, qwyPackage, true, true, true, null)
            } else {
                // Same error returned by the real Android reader if called before phase 600.
                SystemServerOracleState.EndpointSample(null, null, false, false, false,
                    NullPointerException("system Context has not been published"))
            }
        }
        val token = f.producer.beginCoveredMutation(1000, 1, null, null)
        assertEquals(1L, f.producer.snapshot().sequence)
        f.producer.finishCoveredMutation(token, false)
        f.producer.refreshEndpoint()
        assertEquals("pre-bridge state must not invoke the platform reader", 0, f.endpointReads)
        val early = f.producer.snapshot()
        assertEquals(2L, early.sequence)
        assertFalse(early.gpsProviderEnabled)
        assertFalse(early.networkProviderEnabled)
        assertNull(early.ownerUid)
        assertEquals(OracleWireHealth.HOOKS_INCOMPLETE, early.health)

        contextReady = true
        f.connect()
        assertEquals("bridge establishes the first actual endpoint sample", 1, f.endpointReads)
        f.producer.registerQwySession("digest-a", f.session)
        f.producer.refreshEndpoint()
        assertEquals(OracleWireHealth.HOOKS_INCOMPLETE, f.producer.snapshot().health)
        assertEquals(4L, f.producer.snapshot().sequence)
    }

    @Test
    fun `real endpoint failure after bridge readiness remains permanently fail closed`() {
        val f = Fixture()
        f.connect()
        f.producer.registerQwySession("digest-a", f.session)
        f.producer.onBridgeDisconnected(1)
        f.readEndpoint = {
            SystemServerOracleState.EndpointSample(null, null, false, false, false,
                IllegalStateException("injected platform I O failure after readiness"))
        }
        val token = f.producer.beginCoveredMutation(1000, 1, null, null)
        f.producer.finishCoveredMutation(token, false)
        assertEquals("disconnect must not turn a real failure into normal startup", 2, f.endpointReads)
        assertEquals(OracleWireHealth.CALLBACK_POISONED, f.producer.snapshot().health)
        f.readEndpoint = {
            SystemServerOracleState.EndpointSample(qwyUid, qwyPackage, true, true, true, null)
        }
        f.producer.onBridgeConnected(2)
        f.producer.registerQwySession("digest-a", Session())
        f.producer.refreshEndpoint()
        assertEquals(OracleWireHealth.CALLBACK_POISONED, f.producer.snapshot().health)
    }

    @Test
    fun `retired bridge callback cannot prematurely enable endpoint sampling`() {
        val f = Fixture()
        f.producer.onBridgeBindingDied(1)
        f.producer.onBridgeConnected(1)
        val token = f.producer.beginCoveredMutation(1000, 1, null, null)
        f.producer.finishCoveredMutation(token, false)
        assertEquals(0, f.endpointReads)
        f.producer.onBridgeConnected(2)
        assertEquals(1, f.endpointReads)
        assertEquals(OracleWireHealth.HOOKS_INCOMPLETE, f.producer.snapshot().health)
    }
}
