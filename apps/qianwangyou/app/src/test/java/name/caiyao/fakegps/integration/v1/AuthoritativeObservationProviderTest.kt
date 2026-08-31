package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeObservationProviderTest {

    @Test
    fun `continuity start cannot predate readiness work and authoritative PRE`() {
        val h = readyHarness("pre-boundary-after-readiness")
        h.tracker.invalidateCoverage()
        val beforeReadiness = h.clock.elapsedRealtimeMs()
        val readinessDelayMs = 500L
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            watermarks = VerifiedObservationWatermarkStore(h.kv),
            authoritativeSource = h.authoritativeOracle,
            expectedOracleOwnerPackage = ProviderHarness.QWY_PACKAGE,
            expectedOracleOwnerUid = ProviderHarness.QWY_UID,
            semanticWriterReadiness = QwySemanticWriterReadiness {
                h.clock.advance(readinessDelayMs)
                true
            },
        )

        val observed = observer.observe(
            lease = checkNotNull(h.leases.get(checkNotNull(h.currentLeaseId))),
            request = ObserveRequestV1(
                leaseId = checkNotNull(h.currentLeaseId),
                operationId = "pre-boundary-after-readiness-observe",
                expectedIntentHash = checkNotNull(h.currentIntentHash),
            ),
        )

        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)
        assertTrue(
            "continuity cannot include time before writer readiness and PRE completed",
            checkNotNull(observed.continuitySinceElapsedRealtimeMs) >=
                beforeReadiness + readinessDelayMs,
        )
    }

    @Test
    fun `drifted framework coordinate refreshed during raw read cannot retain FULL`() {
        val h = readyHarness("actual-coordinate-drift-refresh")
        val desiredLatitude = checkNotNull(h.env.effectiveLatitude)
        val desiredLongitude = checkNotNull(h.env.effectiveLongitude)
        val sequenceBefore = checkNotNull(h.authoritativeOracle.snapshot()).sequence
        h.env.authoritativeProjectionOverride =
            (desiredLatitude + 0.5) to (desiredLongitude + 0.5)
        h.env.refreshAuthoritativeProjectionOnObserve = true
        h.env.afterAuthoritativeProjectionRefresh = {
            // Production's lock-held coordinate hook records the real B→A
            // publication even though the final semantic digest is A again.
            h.authoritativeOracle.changed()
        }

        val observed = h.observeCurrent("actual-coordinate-drift-refresh-observe")

        assertEquals(
            "B to A inside the raw read is an unproved semantic transition",
            ContinuityCoverageV1.NONE.wire,
            observed.continuityCoverageWire,
        )
        assertNull(observed.continuitySinceElapsedRealtimeMs)
        assertEquals(
            "a coordinate-changing refresh must leave authoritative history",
            sequenceBefore + 2L,
            checkNotNull(h.authoritativeOracle.snapshot()).sequence,
        )
    }

    @Test
    fun `owner away then restore inside raw read is rejected despite equal endpoint`() {
        val h = readyHarness("owner-away-restore")
        val before = h.tracker.snapshot().revision
        h.env.beforeObserveEffective = {
            h.authoritativeOracle.changed(
                ownerUid = 20_002,
                ownerPackage = "other.mock.owner",
            )
            h.authoritativeOracle.changed(
                ownerUid = ProviderHarness.QWY_UID,
                ownerPackage = ProviderHarness.QWY_PACKAGE,
            )
        }

        val observed = h.observeCurrent("owner-away-restore-observe")

        assertEquals(before + 1L, observed.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        assertNull(observed.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `provider disable then enable inside raw read is rejected`() {
        val h = readyHarness("provider-away-restore")
        val before = h.tracker.snapshot().revision
        h.env.beforeObserveEffective = {
            h.authoritativeOracle.changed(gpsEnabled = false)
            h.authoritativeOracle.changed(gpsEnabled = true)
        }

        val observed = h.observeCurrent("provider-away-restore-observe")

        assertEquals(before + 1L, observed.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
    }

    @Test
    fun `mutation still odd at POST is rejected and does not mint FULL`() {
        val h = readyHarness("odd-at-post")
        var token: AuthoritativeMutationToken? = null
        h.env.beforeObserveEffective = {
            token = h.authoritativeOracle.oracle.beginMutation()
        }

        val observed = h.observeCurrent("odd-at-post-observe")

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        assertNull(observed.continuitySinceElapsedRealtimeMs)
        h.authoritativeOracle.oracle.finishMutation(
            checkNotNull(token),
            AuthoritativeMutationOutcome.PROVED_NO_OP,
            h.authoritativeOracle.currentState(),
        )
    }

    @Test
    fun `boot change between PRE and POST is rejected and ACKed fail closed`() {
        val h = readyHarness("boot-change")
        val pre = h.authoritativeOracle.oracle.snapshot()
        h.authoritativeOracle.scriptedSnapshots.addLast(pre)
        h.authoritativeOracle.scriptedSnapshots.addLast(
            pre.copy(bootId = "boot-after-restart", oracleInstanceId = "instance-after-restart"),
        )

        val observed = h.observeCurrent("boot-change-observe")

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        assertNull(observed.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `Binder failure on POST is NONE and a later stable window may recover`() {
        val h = readyHarness("post-read-failure")
        val stable = h.authoritativeOracle.oracle.snapshot()
        h.authoritativeOracle.scriptedSnapshots.addLast(stable)
        h.authoritativeOracle.failNextRead = true

        val failed = h.observeCurrent("post-read-failure-observe")
        assertEquals(ContinuityCoverageV1.NONE.wire, failed.continuityCoverageWire)

        h.env.beforeObserveEffective = null
        h.clock.advance(50L)
        h.observeCurrent("post-read-rebaseline")
        h.clock.advance(50L)
        val recovered = h.observeCurrent("post-read-recovered")
        assertEquals(ContinuityCoverageV1.FULL.wire, recovered.continuityCoverageWire)
    }

    @Test
    fun `ordinary raw refresh reads do not change authoritative sequence`() {
        val h = readyHarness("refresh-sequence")
        val before = h.authoritativeOracle.oracle.snapshot().sequence

        repeat(5) { index ->
            h.clock.advance(50L)
            h.observeCurrent("refresh-sequence-$index")
        }

        assertEquals(before, h.authoritativeOracle.oracle.snapshot().sequence)
    }

    @Test
    fun `delayed relevant callback after oracle restore still revokes cached FULL`() {
        val h = readyHarness("delayed-callback-after-restore")
        val before = h.tracker.snapshot()
        assertEquals(ContinuityCoverageV1.FULL.wire, before.coverageWire)
        h.authoritativeOracle.changed(
            ownerUid = 20_002,
            ownerPackage = "other.mock.owner",
        )
        h.authoritativeOracle.changed(
            ownerUid = ProviderHarness.QWY_UID,
            ownerPackage = ProviderHarness.QWY_PACKAGE,
        )

        h.env.emitRelevantChange(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)

        val after = h.tracker.snapshot()
        assertEquals(before.revision + 1L, after.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, after.coverageWire)
        assertNull(after.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `unrelated delayed callback is not hidden by another thread writer bracket`() {
        val h = readyHarness("cross-thread-delayed-callback")
        val before = h.tracker.snapshot()
        val installation = QwySemanticWriterRuntime.install(
            coordinator = h.semanticCoordinator,
            semanticDigestProvider = QwySemanticDigestProvider {
                h.env.authoritativeSemanticDigest(h.tracker.generation)
            },
            sessionHealth = QwySemanticSessionHealth { expected ->
                h.semanticCoordinator.isReadyFor(expected) &&
                    h.authoritativeOracle.snapshot()
                        ?.takeIf {
                            it.isStableCompleteFor(
                                ProviderHarness.QWY_PACKAGE,
                                ProviderHarness.QWY_UID,
                            )
                        }
                        ?.qwySemanticDigest == expected
            },
            mutationIdFactory = { "unrelated-writer-bracket" },
        )
        val executor = Executors.newSingleThreadExecutor()
        val insideWriter = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        try {
            val writer = executor.submit<String> {
                QwySemanticWriterRuntime.mutate("unrelated-no-op") {
                    insideWriter.countDown()
                    check(releaseWriter.await(5, TimeUnit.SECONDS))
                    "finished"
                }
            }
            assertTrue(insideWriter.await(5, TimeUnit.SECONDS))
            assertTrue(QwySemanticWriterRuntime.isAuthoritativeMutationInFlight())

            h.env.emitRelevantChange(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
            releaseWriter.countDown()
            assertEquals("finished", writer.get(5, TimeUnit.SECONDS))

            val after = h.tracker.snapshot()
            assertEquals(before.revision + 1L, after.revision)
            assertEquals(ContinuityCoverageV1.NONE.wire, after.coverageWire)
            assertNull(after.continuitySinceElapsedRealtimeMs)
        } finally {
            releaseWriter.countDown()
            executor.shutdownNow()
            installation.close()
        }
    }

    @Test
    fun `central writer advance cannot leave cached FULL on discover or preflight`() {
        listOf("discover", "preflight").forEach { surface ->
            val h = readyHarness("central-writer-$surface")
            assertEquals(
                ContinuityCoverageV1.FULL.wire,
                h.tracker.snapshot().coverageWire,
            )
            val trackerBeforeWriter = h.tracker.snapshot()
            val oracleBeforeWriter = checkNotNull(h.authoritativeOracle.snapshot())
            val beforeDigest = checkNotNull(
                h.env.authoritativeSemanticDigest(h.tracker.generation),
            )
            val mutation = h.semanticCoordinator.runMutation(
                mutationId = "central-writer-$surface",
                beforeDigest = beforeDigest,
            ) {
                h.env.currentItemId = "item-2"
                QwySemanticMutationWork.Changed(
                    Unit,
                    checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation)),
                )
            }
            assertTrue(mutation is QwySemanticMutationResult.Changed)
            val oracleAfterWriter = checkNotNull(h.authoritativeOracle.snapshot())
            assertEquals(oracleBeforeWriter.sequence + 2L, oracleAfterWriter.sequence)
            assertEquals(trackerBeforeWriter.revision, h.tracker.snapshot().revision)
            assertTrue(h.tracker.isAuthoritativeCursorAcknowledged(oracleBeforeWriter))
            assertTrue(!h.tracker.isAuthoritativeCursorAcknowledged(oracleAfterWriter))

            val coverage = when (surface) {
                "discover" -> h.handler.discover(AUTO_UID).continuityCoverageWire
                else -> h.handler.preflight(
                    AUTO_UID,
                    PreflightRequestV1(
                        intent = h.intent(scheduleRef = "item-2"),
                        idempotencyKey = "central-writer-preflight",
                        callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
                    ),
                ).continuityCoverageWire
            }

            assertEquals(
                "$surface must not serve the pre-mutation FULL cache",
                ContinuityCoverageV1.NONE.wire,
                coverage,
            )
            val trackerAfterClaim = h.tracker.snapshot()
            assertEquals(
                "$surface claim must not invent a revision without reconciliation",
                trackerBeforeWriter.revision,
                trackerAfterClaim.revision,
            )
            assertEquals(ContinuityCoverageV1.NONE.wire, trackerAfterClaim.coverageWire)
            assertNull(trackerAfterClaim.continuitySinceElapsedRealtimeMs)
        }
    }

    @Test
    fun `handler mutation accounts central writer cursor instead of raw fallback`() {
        val h = readyHarness("central-writer-before-handler")
        val revisionBefore = h.tracker.snapshot().revision
        val beginBefore = h.semanticEndpoint.beginCount
        val installation = QwySemanticWriterRuntime.install(
            coordinator = h.semanticCoordinator,
            semanticDigestProvider = QwySemanticDigestProvider {
                h.env.authoritativeSemanticDigest(h.tracker.generation)
            },
            sessionHealth = QwySemanticSessionHealth { expected ->
                h.semanticCoordinator.isReadyFor(expected) &&
                    h.authoritativeOracle.snapshot()
                        ?.takeIf {
                            it.isStableCompleteFor(
                                ProviderHarness.QWY_PACKAGE,
                                ProviderHarness.QWY_UID,
                            )
                        }
                        ?.qwySemanticDigest == expected
            },
            mutationIdFactory = { kind -> "central-before-handler-$kind" },
        )
        try {
            QwySemanticWriterRuntime.mutate("profile-update") {
                h.env.effectiveLatitude = checkNotNull(h.env.effectiveLatitude) + 0.125
            }
            val centralSnapshot = checkNotNull(h.authoritativeOracle.snapshot())
            assertTrue(!h.tracker.isAuthoritativeCursorAcknowledged(centralSnapshot))

            val release = h.release(
                leaseId = checkNotNull(h.currentLeaseId),
                key = "central-writer-before-handler-release",
            )

            assertTrue(release.releaseComplete)
            assertEquals(
                "central update and handler cleanup each require one exact bracket",
                beginBefore + 2,
                h.semanticEndpoint.beginCount,
            )
            val completed = checkNotNull(h.authoritativeOracle.snapshot())
            assertTrue(h.tracker.isAuthoritativeCursorAcknowledged(completed))
            assertEquals(revisionBefore + 2L, h.tracker.snapshot().revision)
            assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
            assertNull(h.tracker.snapshot().continuitySinceElapsedRealtimeMs)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `local semantic drift without an oracle sequence can never regain FULL`() {
        val h = readyHarness("unsequenced-local-semantic-drift")
        val oracleSequence = h.authoritativeOracle.oracle.snapshot().sequence
        h.env.beforeObserveEffective = {
            h.env.effectiveLatitude = checkNotNull(h.env.effectiveLatitude) + 0.25
        }

        val driftWindow = h.observeCurrent("unsequenced-local-semantic-drift-window")

        assertEquals(oracleSequence, h.authoritativeOracle.oracle.snapshot().sequence)
        assertEquals(ContinuityCoverageV1.NONE.wire, driftWindow.continuityCoverageWire)

        h.env.beforeObserveEffective = null
        h.clock.advance(50L)
        val laterStableWindow = h.observeCurrent("unsequenced-local-semantic-drift-later")
        assertEquals(
            "an unchanged but stale oracle digest must not validate changed local semantics",
            ContinuityCoverageV1.NONE.wire,
            laterStableWindow.continuityCoverageWire,
        )
    }

    private fun readyHarness(key: String): ProviderHarness {
        val h = ProviderHarness.create()
        h.env.authoritativeSemanticMutations = true
        h.pair()
        val receipt = h.apply(key = key)
        h.currentLeaseId = receipt.leaseId
        h.currentIntentHash = receipt.acceptedIntentHash
        // Apply's existing authoritative COMPLETE path establishes coverage;
        // this first stable observe also durably anchors the oracle cursor.
        h.observeCurrent("$key-baseline")
        return h
    }

    private fun ProviderHarness.observeCurrent(operationId: String) = handler.observe(
        AUTO_UID,
        ObserveRequestV1(
            leaseId = checkNotNull(currentLeaseId),
            operationId = operationId,
            expectedIntentHash = checkNotNull(currentIntentHash),
        ),
    )
}
