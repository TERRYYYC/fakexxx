package name.caiyao.fakegps.hook.oracle

import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySnapshot
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySource
import name.caiyao.fakegps.integration.v1.AuthoritativeOracleHealth
import name.caiyao.fakegps.integration.v1.AuthoritativeWindowVerdict
import name.caiyao.fakegps.integration.v1.DurablePairingStore
import name.caiyao.fakegps.integration.v1.PendingPairingCandidate
import name.caiyao.fakegps.integration.v1.ProviderRuntime
import name.caiyao.fakegps.integration.v1.classifyAuthoritativeWindow
import name.caiyao.fakegps.integration.v1.support.FakeIdentityResolver
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.FakeQwyEnvironment
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.oracle.FakeBinder
import name.caiyao.fakegps.oracle.FakeOracleDeathLink
import name.caiyao.fakegps.oracle.IAuthoritativeContinuityOracle
import name.caiyao.fakegps.oracle.OracleBundleCodec
import name.caiyao.fakegps.oracle.OracleClientRegistry
import name.caiyao.fakegps.oracle.OracleRegistration
import name.caiyao.fakegps.oracle.OracleWireHealth
import name.caiyao.fakegps.oracle.OracleWireSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #155 acceptance core, JVM level: driver + REAL state machine + observer over
 * the production composition root. One oracle arrival, one bracketed apply,
 * and the observation that follows must classify a FULL window — the exact
 * chain that was structurally impossible before the producer side existed
 * (bit 5 had no app caller, bit 6 had no grant).
 */
class OracleSessionDriverIntegrationTest {

    private val qwyPackage = "name.caiyao.fakegps.bench"
    private val qwyUid = 10_321
    private val bootId = "6c6742ae-f815-4589-93cb-02b375d629be"

    /** AIDL-shaped delegate into the exact state owner the production Binder uses. */
    private class StateDelegatingOracle(
        private val state: SystemServerOracleState,
        private val binder: FakeBinder,
    ) : IAuthoritativeContinuityOracle {
        val registrations = mutableListOf<String?>()

        override fun snapshot() = null // Bundle transport stays device-side; the map seam is read here

        fun snapshotFields(): Map<String, Any?> = OracleBundleCodec.encodeFields(state.snapshot())

        override fun registerQwySession(semanticDigest: String?, clientDeathToken: android.os.IBinder?) {
            registrations += semanticDigest
            state.registerQwySession(semanticDigest, FakeSessionToken(requireNotNull(clientDeathToken)))
        }

        override fun beginQwySemanticMutation(
            mutationId: String?,
            beforeDigest: String?,
            clientDeathToken: android.os.IBinder?,
        ): Long = state.beginQwySemanticMutation(
            requireNotNull(mutationId),
            requireNotNull(beforeDigest),
            FakeSessionToken(requireNotNull(clientDeathToken)),
        )

        override fun finishQwySemanticMutation(
            token: Long,
            changed: Boolean,
            uncertain: Boolean,
            afterDigest: String?,
        ) = state.finishQwySemanticMutation(token, changed, uncertain, requireNotNull(afterDigest))

        override fun asBinder(): android.os.IBinder = binder
    }

    /** SessionToken over binder identity, mirroring SystemServerOracleBinder's adapter. */
    private class FakeSessionToken(private val binder: android.os.IBinder) :
        SystemServerOracleState.SessionToken {
        private val deaths = mutableListOf<Runnable>()

        override fun link(onDeath: Runnable) {
            deaths += onDeath
        }

        override fun unlink(onDeath: Runnable) {
            deaths.remove(onDeath)
        }

        override fun equals(other: Any?): Boolean =
            other is FakeSessionToken && other.binder == binder

        override fun hashCode(): Int = binder.hashCode()
    }

    private fun OracleWireSnapshot.toDomain(): AuthoritativeContinuitySnapshot =
        AuthoritativeContinuitySnapshot(
            protocolVersion, bootId, oracleInstanceId, sequence, ownerUid, ownerPackage,
            gpsProviderEnabled, networkProviderEnabled, requiredCoverageMask, installedCoverageMask,
            when (health) {
                OracleWireHealth.HEALTHY -> AuthoritativeOracleHealth.HEALTHY
                OracleWireHealth.BUILD_UNATTESTED -> AuthoritativeOracleHealth.BUILD_UNATTESTED
                OracleWireHealth.UNSUPPORTED_PLATFORM -> AuthoritativeOracleHealth.HOOKS_INCOMPLETE
                OracleWireHealth.BOOT_ID_UNAVAILABLE -> AuthoritativeOracleHealth.UNINITIALIZED
                OracleWireHealth.HOOKS_INCOMPLETE -> AuthoritativeOracleHealth.HOOKS_INCOMPLETE
                OracleWireHealth.BRIDGE_UNAVAILABLE -> AuthoritativeOracleHealth.SESSION_UNAVAILABLE
                OracleWireHealth.SESSION_UNAVAILABLE -> AuthoritativeOracleHealth.SESSION_UNAVAILABLE
                OracleWireHealth.ENDPOINT_UNAVAILABLE -> AuthoritativeOracleHealth.ENDPOINT_UNAVAILABLE
                OracleWireHealth.CALLBACK_POISONED -> AuthoritativeOracleHealth.SESSION_UNCERTAIN
                OracleWireHealth.INVARIANT_FAILURE -> AuthoritativeOracleHealth.INVARIANT_FAILED
            },
            qwySemanticDigest,
            lastCompletedQwyMutationId,
        )

    @Test
    fun `driver arrival plus bracketed apply lets the observer classify a FULL window`() {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val resolver = FakeIdentityResolver().apply {
            register(ProviderHarness.AUTO_UID, ProviderHarness.AUTO_PKG, ProviderHarness.AUTO_SIGNER)
        }
        val env = FakeQwyEnvironment(kv)

        // The REAL producer state machine, wired exactly like the device Binder.
        val state = SystemServerOracleState(
            bootId, true, true,
            object : SystemServerOracleState.CallerIdentity {
                override fun uid() = qwyUid
                override fun pid() = 4321
            },
            Runnable { },
            SystemServerOracleState.EndpointReader {
                SystemServerOracleState.EndpointSample(qwyUid, qwyPackage, true, true, true, null)
            },
        ).apply {
            configureExpectedQwyIdentity(qwyUid, qwyPackage)
            markInstalled(
                Android15OracleHookPlan.COVERAGE_APP_OPS_WRAPPER or
                    Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_DELEGATE or
                    Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_LIFECYCLE or
                    Android15OracleHookPlan.COVERAGE_LOCATION_PROVIDER_STATE or
                    Android15OracleHookPlan.COVERAGE_LOCATION_EFFECTIVE_ENABLED or
                    Android15OracleHookPlan.COVERAGE_LOCATION_SEMANTIC_COORDINATE,
            )
            onBridgeConnected(1)
        }
        val oracle = StateDelegatingOracle(state, FakeBinder())

        val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()
        val source = AuthoritativeContinuitySource {
            (registry.current() as? StateDelegatingOracle)
                ?.let { o -> OracleBundleCodec.decodeFields(o.snapshotFields())?.toDomain() }
        }

        // Production composition root: tracker/env/observer/handler/commit store
        // plus the #155 session driver as the semantic-mutation seam.
        val handler = ProviderRuntime.compose(
            kv = kv,
            clock = clock,
            resolver = resolver,
            environment = env,
            authoritativeSource = source,
            expectedOracleOwnerPackage = qwyPackage,
            expectedOracleOwnerUid = qwyUid,
            oracleRegistry = registry,
        )
        DurablePairingStore(kv).approve(
            PendingPairingCandidate(
                callerApplicationId = ProviderHarness.AUTO_PKG,
                currentSignerDigest = ProviderHarness.AUTO_SIGNER,
                observedVersionCode = 1L,
                firstSeenAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            ),
            atElapsedRealtimeMs = clock.elapsedRealtimeMs(),
        )

        // The oracle arrives AFTER composition, like a real registrar bind.
        registry.register(
            1000,
            OracleRegistration(oracle, FakeOracleDeathLink()),
        )
        assertEquals(
            "the driver registered the QWY session at arrival",
            1,
            oracle.registrations.size,
        )
        assertNotNull(oracle.registrations.single())

        // RED-era this next line observed coverage=NONE: without bracketing the
        // digest never publishes and bit 6 is never granted.
        val intent = EnvironmentIntentV1(
            runId = "run-1",
            attemptId = "att-1",
            profileRef = "profile-1",
            scheduleRef = "item-1",
            requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            notBeforeEpochMs = clock.epochMs() - 1_000L,
            deadlineEpochMs = clock.epochMs() + 600_000L,
        )
        val receipt = handler.apply(
            ProviderHarness.AUTO_UID,
            ApplyRequestV1(intent, "apply-k1", 1),
        )

        val pre = source.snapshot()
        val post = source.snapshot()
        assertNotNull(pre)
        assertEquals(
            AuthoritativeWindowVerdict.VALID,
            classifyAuthoritativeWindow(pre, post, qwyPackage, qwyUid),
        )
        assertEquals(
            Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
            state.installedCoverageMask(),
        )
        assertEquals(
            "the bracketed apply is the completed mutation correlation",
            "apply-${receipt.leaseId}",
            pre?.lastCompletedQwyMutationId,
        )

        val observation = handler.observe(
            ProviderHarness.AUTO_UID,
            ObserveRequestV1(
                leaseId = receipt.leaseId,
                operationId = "op-obs-1",
                expectedIntentHash = receipt.acceptedIntentHash,
            ),
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, observation.continuityCoverageWire)
        assertEquals(
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            observation.verificationLevelWire,
        )
        assertNotNull(observation.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `without the producer side the same flow stays honestly NONE`() {
        // Guard for the negative direction: an oracle whose bits 5/6 never
        // arrive must not produce FULL — the pre-#155 permanent state.
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val resolver = FakeIdentityResolver().apply {
            register(ProviderHarness.AUTO_UID, ProviderHarness.AUTO_PKG, ProviderHarness.AUTO_SIGNER)
        }
        val env = FakeQwyEnvironment(kv)

        val state = SystemServerOracleState(
            bootId, true, true,
            object : SystemServerOracleState.CallerIdentity {
                override fun uid() = qwyUid
                override fun pid() = 4321
            },
            Runnable { },
            SystemServerOracleState.EndpointReader {
                SystemServerOracleState.EndpointSample(qwyUid, qwyPackage, true, true, true, null)
            },
        ).apply {
            configureExpectedQwyIdentity(qwyUid, qwyPackage)
            markInstalled(
                Android15OracleHookPlan.COVERAGE_APP_OPS_WRAPPER or
                    Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_DELEGATE or
                    Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_LIFECYCLE or
                    Android15OracleHookPlan.COVERAGE_LOCATION_PROVIDER_STATE or
                    Android15OracleHookPlan.COVERAGE_LOCATION_EFFECTIVE_ENABLED or
                    Android15OracleHookPlan.COVERAGE_LOCATION_SEMANTIC_COORDINATE,
            )
            onBridgeConnected(1)
        }
        val oracle = StateDelegatingOracle(state, FakeBinder())
        val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()
        val source = AuthoritativeContinuitySource {
            (registry.current() as? StateDelegatingOracle)
                ?.let { o -> OracleBundleCodec.decodeFields(o.snapshotFields())?.toDomain() }
        }
        val handler = ProviderRuntime.compose(
            kv = kv,
            clock = clock,
            resolver = resolver,
            environment = env,
            authoritativeSource = source,
            expectedOracleOwnerPackage = qwyPackage,
            expectedOracleOwnerUid = qwyUid,
            // No composed driver: the pre-#155 world where the oracle binder is
            // readable but nobody ever registers the QWY session.
            oracleRegistry = null,
        )
        DurablePairingStore(kv).approve(
            PendingPairingCandidate(
                callerApplicationId = ProviderHarness.AUTO_PKG,
                currentSignerDigest = ProviderHarness.AUTO_SIGNER,
                observedVersionCode = 1L,
                firstSeenAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            ),
            atElapsedRealtimeMs = clock.elapsedRealtimeMs(),
        )
        registry.register(1000, OracleRegistration(oracle, FakeOracleDeathLink()))

        val intent = EnvironmentIntentV1(
            runId = "run-1",
            attemptId = "att-1",
            profileRef = "profile-1",
            scheduleRef = "item-1",
            requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            notBeforeEpochMs = clock.epochMs() - 1_000L,
            deadlineEpochMs = clock.epochMs() + 600_000L,
        )
        val receipt = handler.apply(ProviderHarness.AUTO_UID, ApplyRequestV1(intent, "apply-k1", 1))
        val observation = handler.observe(
            ProviderHarness.AUTO_UID,
            ObserveRequestV1(
                leaseId = receipt.leaseId,
                operationId = "op-obs-1",
                expectedIntentHash = receipt.acceptedIntentHash,
            ),
        )
        assertEquals(ContinuityCoverageV1.NONE.wire, observation.continuityCoverageWire)
        assertNotEquals(
            Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
            state.installedCoverageMask(),
        )
    }
}
