package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Regression lane for raw OS evidence freshness at the provider boundary. */
class ObservationFreshnessProviderTest {

    @Test
    fun `incomplete authoritative source forces the observation to NONE`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.continuityCapability = ContinuityEvidenceCapability.INCOMPLETE
        val receipt = h.apply(key = "incomplete-continuity")

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                receipt.leaseId,
                "incomplete-continuity-observe",
                receipt.acceptedIntentHash,
            ),
        )

        assertEquals(
            "public callbacks may stage PARTIAL, but missing authoritative hooks make the served window NONE",
            ContinuityCoverageV1.NONE.wire,
            observed.continuityCoverageWire,
        )
        assertNull(observed.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `unavailable relevant-change source leaves continuity at NONE`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.continuityCapability = ContinuityEvidenceCapability.UNAVAILABLE
        val receipt = h.apply(key = "unavailable-continuity")

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                receipt.leaseId,
                "unavailable-continuity-observe",
                receipt.acceptedIntentHash,
            ),
        )

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        assertNull(observed.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `complete relevant-change source remains the only route to FULL continuity`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.continuityCapability = ContinuityEvidenceCapability.COMPLETE
        val receipt = h.apply(key = "complete-continuity")

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                receipt.leaseId,
                "complete-continuity-observe",
                receipt.acceptedIntentHash,
            ),
        )

        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)
    }

    @Test
    fun `a relevant callback delivered during effective read is reflected in that observation`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "delivered-change-fence")
        val before = h.tracker.snapshot().revision
        var fired = false
        h.env.beforeObserveEffective = {
            if (!fired) {
                fired = true
                h.env.hijackAndRestoreMockOwner()
            }
        }

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                receipt.leaseId,
                "delivered-change-fence-observe",
                receipt.acceptedIntentHash,
            ),
        )

        assertEquals(before + 2L, observed.environmentRevision)
    }

    @Test
    fun `observer gap callback clears the formerly full continuity window`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.continuityCapability = ContinuityEvidenceCapability.COMPLETE
        h.apply(key = "observer-gap-degrades")
        assertEquals(ContinuityCoverageV1.FULL.wire, h.tracker.snapshot().coverageWire)

        h.env.emitRelevantChange(RevisionBumpReason.OBSERVER_GAP)

        val degraded = h.tracker.snapshot()
        assertEquals(ContinuityCoverageV1.PARTIAL.wire, degraded.coverageWire)
        assertNull(degraded.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `handler cannot wash an old provider sample with its current clock`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.verifiedSourceElapsedRealtimeMs = linkedMapOf(
            "gps" to 1_000_200L,
            "network" to 1_000_100L,
        )
        val receipt = h.apply(key = "freshness-time")
        h.clock.advance(90_000L)

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                receipt.leaseId,
                "freshness-time-observe",
                receipt.acceptedIntentHash,
            ),
        )

        assertEquals(
            "wire time must be the older required-source evidence time, not handler now",
            1_000_100L,
            observed.observedAtElapsedRealtimeMs,
        )
        assertEquals(
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            observed.verificationLevelWire,
        )
    }

    @Test
    fun `same lease must not reuse one gps network sample as verified pre and post`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.fingerprint = "same-environment"
        h.env.verifiedSourceElapsedRealtimeMs = linkedMapOf(
            "gps" to 1_000_200L,
            "network" to 1_000_100L,
        )
        val receipt = h.apply(key = "freshness-watermark")

        val pre = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(receipt.leaseId, "freshness-pre", receipt.acceptedIntentHash),
        )
        h.clock.advance(20_000L)
        val replayed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(receipt.leaseId, "freshness-post-replay", receipt.acceptedIntentHash),
        )

        assertEquals(
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            pre.verificationLevelWire,
        )
        assertEquals(
            "a repeated raw sample is not a post-execution observation",
            VerificationLevelV1.NONE.wire,
            replayed.verificationLevelWire,
        )
        assertEquals(pre.observedAtElapsedRealtimeMs, replayed.observedAtElapsedRealtimeMs)

        h.env.verifiedSourceElapsedRealtimeMs = linkedMapOf(
            "gps" to 1_020_200L,
            "network" to 1_020_100L,
        )
        val freshPost = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(receipt.leaseId, "freshness-post-new", receipt.acceptedIntentHash),
        )
        assertEquals(
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            freshPost.verificationLevelWire,
        )
        assertEquals(pre.environmentFingerprint, freshPost.environmentFingerprint)
    }

    @Test
    fun `owner fence admits exactly one concurrent observer for one raw sample`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.verifiedSourceElapsedRealtimeMs = linkedMapOf(
            "gps" to 1_000_200L,
            "network" to 1_000_100L,
        )
        val receipt = h.apply(key = "freshness-concurrent")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val observations = (1..2).map { ordinal ->
                pool.submit<Int> {
                    start.await()
                    h.handler.observe(
                        AUTO_UID,
                        ObserveRequestV1(
                            receipt.leaseId,
                            "freshness-concurrent-$ordinal",
                            receipt.acceptedIntentHash,
                        ),
                    ).verificationLevelWire
                }
            }
            start.countDown()
            val levels = observations.map { it.get() }

            assertEquals(
                1,
                levels.count {
                    it == VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
                },
            )
            assertEquals(1, levels.count { it == VerificationLevelV1.NONE.wire })
        } finally {
            pool.shutdownNow()
        }
    }
}
