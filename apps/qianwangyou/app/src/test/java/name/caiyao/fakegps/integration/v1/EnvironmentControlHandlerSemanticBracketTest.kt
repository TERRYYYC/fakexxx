package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.support.FakeIdentityResolver
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.FakeQwyEnvironment
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.RecordingDiagnosticLog
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #155: the semantic write funnels of [EnvironmentControlHandler] must bracket
 * against the oracle session when one is live — begin/finish exactly once per
 * real transition, the after-digest equal to what an observer recomputes from
 * the same environment, measured `changed`, and uncertain=true on any failure
 * path. A seam with no live session keeps the legacy unbracketed behavior.
 */
class EnvironmentControlHandlerSemanticBracketTest {

    private class RecordingMutations : QwySemanticMutationSource {
        class FinishCall(val changed: Boolean, val uncertain: Boolean, val afterDigest: String)

        val began = mutableListOf<String>()
        val finished = mutableListOf<FinishCall>()

        /** Next begin's baseline digest; null = no live session (begin → null). */
        var nextBeforeDigest: String? = null

        override fun begin(mutationId: String): QwySemanticMutation? {
            val before = nextBeforeDigest ?: return null
            nextBeforeDigest = null
            began += mutationId
            return object : QwySemanticMutation {
                override val beforeDigest: String = before
                override fun finish(changed: Boolean, uncertain: Boolean, afterDigest: String) {
                    finished += FinishCall(changed, uncertain, afterDigest)
                }
            }
        }
    }

    private class Fixture(envSetup: FakeQwyEnvironment.() -> Unit = {}) {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val resolver = FakeIdentityResolver()
        val env = FakeQwyEnvironment(kv).apply(envSetup)
        val tracker = ContinuityTracker(kv, clock)
        val audit = DurableIntegrationAuditStore(kv, clock)
        val observer = EnvironmentObserver(tracker, env, clock, audit)
        val mutations = RecordingMutations()
        val handler = EnvironmentControlHandler(
            authorizer = CallerAuthorizer(resolver, DurablePairingStore(kv), clock),
            pairingStore = DurablePairingStore(kv),
            leaseStore = EnvironmentLeaseStore(kv, clock),
            idempotency = DurableIdempotencyStore(kv),
            tracker = tracker,
            observer = observer,
            audit = audit,
            environment = env,
            clock = clock,
            storage = kv,
            semanticMutations = mutations,
            diagnostics = RecordingDiagnosticLog(),
        )

        init {
            resolver.register(ProviderHarness.AUTO_UID, ProviderHarness.AUTO_PKG, ProviderHarness.AUTO_SIGNER)
            DurablePairingStore(kv).approve(
                PendingPairingCandidate(
                    callerApplicationId = ProviderHarness.AUTO_PKG,
                    currentSignerDigest = ProviderHarness.AUTO_SIGNER,
                    observedVersionCode = 1L,
                    firstSeenAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                ),
                atElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            )
            handler.onOwnerProcessStart(cleanlinessProvable = true)
        }

        /** The digest an observer recomputes from the CURRENT environment. */
        fun observerDigest(): String = observedSemanticDigestNow(tracker, env)

        fun apply(key: String = "apply-k1"): ApplyReceiptV1 {
            val intent = EnvironmentIntentV1(
                runId = "run-1",
                attemptId = "att-1",
                profileRef = "profile-1",
                scheduleRef = "item-1",
                requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                notBeforeEpochMs = clock.epochMs() - 1_000L,
                deadlineEpochMs = clock.epochMs() + 600_000L,
            )
            return handler.apply(ProviderHarness.AUTO_UID, ApplyRequestV1(intent, key, 1))
        }
    }

    @Test
    fun `apply is bracketed once and finishes with the observer-equal after digest`() {
        val f = Fixture()
        val before = f.observerDigest()
        f.mutations.nextBeforeDigest = before

        val receipt = f.apply()

        assertEquals(listOf("apply-${receipt.leaseId}"), f.mutations.began)
        val finish = f.mutations.finished.single()
        assertEquals("the apply moved the effective environment", true, finish.changed)
        assertEquals(false, finish.uncertain)
        assertEquals(
            "afterDigest must be exactly the next observer recomputation",
            f.observerDigest(),
            finish.afterDigest,
        )
        assertNotEquals(before, finish.afterDigest)
        assertEquals(
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            receipt.verificationLevelWire,
        )
    }

    @Test
    fun `release is bracketed and a semantic no-op finishes proved no-op`() {
        val f = Fixture()
        val receipt = f.apply()
        val before = f.observerDigest()
        f.mutations.nextBeforeDigest = before

        val release = f.handler.release(
            ProviderHarness.AUTO_UID,
            ReleaseRequestV1(receipt.leaseId, "op-rel-1", "release-k1"),
        )

        assertEquals(listOf("release-${receipt.leaseId}"), f.mutations.began)
        val finish = f.mutations.finished.single()
        // The fake cleanup does not move the effective environment: the honest
        // answer is a PROVED no-op, not a claimed change.
        assertEquals(false, finish.changed)
        assertEquals(false, finish.uncertain)
        assertEquals(before, finish.afterDigest)
        assertTrue(release.releaseComplete)
    }

    @Test
    fun `idempotent apply replay performs no second bracket`() {
        val f = Fixture()
        f.mutations.nextBeforeDigest = f.observerDigest()
        f.apply("same-key")
        assertEquals(1, f.mutations.began.size)

        f.mutations.nextBeforeDigest = f.observerDigest()
        f.apply("same-key")

        assertEquals("replay returns the receipt without a semantic interval", 1, f.mutations.began.size)
        assertEquals(1, f.mutations.finished.size)
    }

    @Test
    fun `no live session keeps the unbracketed behavior`() {
        val f = Fixture()
        f.mutations.nextBeforeDigest = null

        val receipt = f.apply()

        assertTrue(f.mutations.began.isEmpty())
        assertTrue(f.mutations.finished.isEmpty())
        assertNotNull(receipt.leaseId)
    }

    @Test
    fun `failure inside the bracket finishes uncertain before propagating`() {
        val f = Fixture {
            // Crash the environment at the exact semantic write.
            failNextApplyEnvironment = true
        }
        val before = f.observerDigest()
        f.mutations.nextBeforeDigest = before

        try {
            f.apply()
            fail("the environment failure must propagate")
        } catch (expected: SimulatedWriteCrash) {
            // propagates unchanged
        }
        val finish = f.mutations.finished.single()
        assertEquals(true, finish.uncertain)
    }
}
