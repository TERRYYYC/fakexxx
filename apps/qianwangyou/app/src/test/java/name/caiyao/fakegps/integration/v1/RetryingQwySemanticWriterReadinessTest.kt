package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryingQwySemanticWriterReadinessTest {

    @Test
    fun `claim retains FULL when installation only adopts its acknowledged live session`() {
        val h = ProviderHarness.create()
        h.env.authoritativeSemanticMutations = true
        val baseline = checkNotNull(h.authoritativeOracle.snapshot())
        h.tracker.reconcileAuthoritativeWindow(
            window = AuthoritativeObservationWindow(
                pre = baseline,
                post = baseline,
                windowStartElapsedRealtimeMs = h.clock.elapsedRealtimeMs(),
            ),
            expectedOwnerPackage = ProviderHarness.QWY_PACKAGE,
            expectedOwnerUid = ProviderHarness.QWY_UID,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, h.tracker.snapshot().coverageWire)
        val revisionBefore = h.tracker.snapshot().revision
        val readiness = readiness(h)
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            watermarks = VerifiedObservationWatermarkStore(h.kv),
            authoritativeSource = h.authoritativeOracle,
            expectedOracleOwnerPackage = ProviderHarness.QWY_PACKAGE,
            expectedOracleOwnerUid = ProviderHarness.QWY_UID,
            semanticWriterReadiness = readiness,
        )
        try {
            val claim = observer.continuitySnapshotForClaim()

            assertEquals(revisionBefore, claim.revision)
            assertEquals(ContinuityCoverageV1.FULL.wire, claim.coverageWire)
            assertEquals(
                h.tracker.snapshot().continuitySinceElapsedRealtimeMs,
                claim.continuitySinceElapsedRealtimeMs,
            )
        } finally {
            readiness.close()
        }
    }

    @Test
    fun `existing acknowledged live session is adopted without replacement registration`() {
        val h = ProviderHarness.create()
        h.env.authoritativeSemanticMutations = true
        val digest = checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation))
        val revisionBefore = h.tracker.snapshot().revision
        val oracleBefore = checkNotNull(h.authoritativeOracle.snapshot())
        val registrationsBefore = h.semanticEndpoint.registrationCount
        val readiness = readiness(h)
        try {
            assertTrue(readiness.ensureReadyFor(digest))

            val installedSnapshot = checkNotNull(h.authoritativeOracle.snapshot())
            assertTrue(h.tracker.isAuthoritativeCursorAcknowledged(installedSnapshot))
            assertEquals(oracleBefore.sequence, installedSnapshot.sequence)
            assertEquals(registrationsBefore, h.semanticEndpoint.registrationCount)
            assertEquals(revisionBefore, h.tracker.snapshot().revision)
        } finally {
            readiness.close()
        }
    }

    @Test
    fun `lost live session can register and recover without owner restart`() {
        val h = ProviderHarness.create()
        h.env.authoritativeSemanticMutations = true
        val digest = checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation))
        val readiness = readiness(h)
        try {
            assertTrue(readiness.installForOwnerStart(digest))
            val healthyBeforeLoss = checkNotNull(h.authoritativeOracle.snapshot())
            assertTrue(h.tracker.isAuthoritativeCursorAcknowledged(healthyBeforeLoss))

            val failedToken = h.authoritativeOracle.beginSemanticMutation()
            h.authoritativeOracle.finishSemanticMutation(
                token = failedToken,
                mutationId = "failed-live-session",
                changed = false,
                uncertain = true,
                afterDigest = null,
            )
            assertEquals(
                AuthoritativeOracleHealth.SESSION_UNCERTAIN,
                h.authoritativeOracle.snapshot()?.health,
            )
            val revisionBeforeRecovery = h.tracker.snapshot().revision

            assertTrue(readiness.ensureReadyFor(digest))

            val recovered = checkNotNull(h.authoritativeOracle.snapshot())
            assertTrue(recovered.isStableCompleteFor(
                ProviderHarness.QWY_PACKAGE,
                ProviderHarness.QWY_UID,
            ))
            assertTrue(h.tracker.isAuthoritativeCursorAcknowledged(recovered))
            assertEquals(revisionBeforeRecovery + 1L, h.tracker.snapshot().revision)
            assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        } finally {
            readiness.close()
        }
    }

    @Test
    fun `canonical drift retains registered lane for exact repair without laundering B as baseline`() {
        val h = ProviderHarness.create()
        h.env.authoritativeSemanticMutations = true
        val desiredDigest = checkNotNull(
            h.env.authoritativeSemanticDigest(h.tracker.generation),
        )
        val readiness = readiness(h)
        try {
            assertTrue(readiness.installForOwnerStart(desiredDigest))
            val desiredBaseline = checkNotNull(h.authoritativeOracle.snapshot())

            h.env.authoritativeProjectionOverride = 51.5074 to -0.1278
            val driftedDigest = checkNotNull(
                h.env.authoritativeSemanticDigest(h.tracker.generation),
            )
            assertNotEquals(desiredDigest, driftedDigest)

            assertFalse(readiness.ensureReadyFor(driftedDigest))

            val driftDetected = checkNotNull(h.authoritativeOracle.snapshot())
            assertEquals(desiredBaseline.sequence, driftDetected.sequence)
            assertEquals(desiredDigest, driftDetected.qwySemanticDigest)
            assertTrue("the A session must survive long enough to fence B to A", QwySemanticWriterRuntime.hasInstalledLane())

            assertTrue(QwySemanticWriterRuntime.repairExternalProjection(
                "test-coordinate-repair",
            ) {
                h.env.authoritativeProjectionOverride = null
            })

            val repaired = checkNotNull(h.authoritativeOracle.snapshot())
            assertEquals(driftDetected.sequence + 2L, repaired.sequence)
            assertEquals(desiredDigest, repaired.qwySemanticDigest)
            assertTrue(repaired.isStableCompleteFor(
                ProviderHarness.QWY_PACKAGE,
                ProviderHarness.QWY_UID,
            ))
        } finally {
            readiness.close()
        }
    }

    private fun readiness(h: ProviderHarness) = RetryingQwySemanticWriterReadiness(
        tracker = h.tracker,
        environment = h.env,
        authoritativeSource = h.authoritativeOracle,
        expectedOracleOwnerPackage = ProviderHarness.QWY_PACKAGE,
        expectedOracleOwnerUid = ProviderHarness.QWY_UID,
        semanticCoordinator = h.semanticCoordinator,
    )
}
