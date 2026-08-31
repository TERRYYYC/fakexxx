package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.FakeQwySemanticMutationEndpoint
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeAdvanceProviderTest {

    @Test
    fun `installed lane with central writer ACK lag accounts cursor then reserves exact advance`() {
        val h = readyAuthoritativeHarness("authoritative-central-before-advance")
        val lease = h.apply(key = "authoritative-central-before-advance-apply")
        h.release(lease.leaseId, key = "authoritative-central-before-advance-release")
        val revisionBeforeCentralWriter = h.tracker.snapshot().revision
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
            mutationIdFactory = { kind -> "central-before-advance-$kind" },
        )
        try {
            QwySemanticWriterRuntime.mutate("profile-update") {
                h.env.effectiveLatitude = 12.345
            }
            val centralSnapshot = checkNotNull(h.authoritativeOracle.snapshot())
            assertFalse(h.tracker.isAuthoritativeCursorAcknowledged(centralSnapshot))

            val receipt = h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, lease.leaseId, "authoritative-central-before-advance"),
            )

            assertEquals(
                "the central cursor and advance each own one revision",
                revisionBeforeCentralWriter + 2L,
                receipt.effectiveEnvironmentRevision,
            )
            assertEquals(receipt.effectiveEnvironmentRevision, h.tracker.snapshot().revision)
            assertEquals(beginBefore + 2, h.semanticEndpoint.beginCount)
            assertTrue(
                h.tracker.isAuthoritativeCursorAcknowledged(
                    checkNotNull(h.authoritativeOracle.snapshot()),
                ),
            )
            assertNull(h.tracker.activeAuthoritativeReservation())
            assertEquals("", pendingMarker(h))
        } finally {
            installation.close()
        }
    }

    @Test
    fun `installed lane serializes odd central writer before advance selection`() {
        val h = readyAuthoritativeHarness("authoritative-odd-before-advance")
        val lease = h.apply(key = "authoritative-odd-before-advance-apply")
        h.release(lease.leaseId, key = "authoritative-odd-before-advance-release")
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
            mutationIdFactory = { kind -> "odd-before-advance-$kind" },
        )
        val executor = Executors.newFixedThreadPool(2)
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        try {
            val writer = executor.submit<Unit> {
                QwySemanticWriterRuntime.mutate("profile-update") {
                    writerEntered.countDown()
                    check(releaseWriter.await(5, TimeUnit.SECONDS))
                    h.env.effectiveLatitude = 54.321
                }
            }
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS))
            assertTrue(checkNotNull(h.authoritativeOracle.snapshot()).sequence % 2L == 1L)

            val advance = executor.submit(java.util.concurrent.Callable {
                h.handler.completeAndAdvance(
                    AUTO_UID,
                    request(h, lease.leaseId, "authoritative-odd-before-advance"),
                )
            })
            Thread.sleep(100L)
            assertFalse(
                "advance must wait for authoritative lane selection to become stable",
                advance.isDone,
            )

            releaseWriter.countDown()
            writer.get(5, TimeUnit.SECONDS)
            val receipt = advance.get(5, TimeUnit.SECONDS)
            assertEquals(receipt.effectiveEnvironmentRevision, h.tracker.snapshot().revision)
            assertTrue(
                h.tracker.isAuthoritativeCursorAcknowledged(
                    checkNotNull(h.authoritativeOracle.snapshot()),
                ),
            )
        } finally {
            releaseWriter.countDown()
            executor.shutdownNow()
            installation.close()
        }
    }

    @Test
    fun `reserved advance and immediate PRE POST observation share exactly R plus one`() {
        val h = readyAuthoritativeHarness("authoritative-normal")
        val lease = h.apply(key = "authoritative-normal-apply")
        h.release(lease.leaseId, key = "authoritative-normal-release")
        val before = h.tracker.snapshot().revision

        val receipt = h.handler.completeAndAdvance(
            AUTO_UID,
            request(h, lease.leaseId, "authoritative-normal-advance"),
        )

        assertEquals(before + 1L, receipt.effectiveEnvironmentRevision)
        assertEquals(receipt.effectiveEnvironmentRevision, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-normal-observe",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(receipt.effectiveEnvironmentRevision, observed.environmentRevision)
        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)
        assertEquals("item-2", observed.scheduleItemId)
    }

    @Test
    fun `remote completion then local pending-clear crash rolls back ACK and replays once`() {
        val h = readyAuthoritativeHarness("authoritative-finalize-crash")
        val lease = h.apply(key = "authoritative-finalize-crash-apply")
        h.release(lease.leaseId, key = "authoritative-finalize-crash-release")
        val request = request(h, lease.leaseId, "authoritative-finalize-crash-advance")
        val before = h.tracker.snapshot().revision
        val beginBefore = h.semanticEndpoint.beginCount
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null

        assertEquals(before, h.tracker.snapshot().revision)
        assertFalse(pendingMarker(h).isNullOrEmpty())
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals(before + 1L, reservation.reservedRevision)
        assertEquals("item-2", h.env.currentItemId)
        assertEquals(beginBefore + 1, h.semanticEndpoint.beginCount)

        val replay = h.handler.completeAndAdvance(AUTO_UID, request)

        assertEquals(reservation.reservedRevision, replay.effectiveEnvironmentRevision)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertEquals("completed correlation must avoid a second remote begin",
            beginBefore + 1, h.semanticEndpoint.beginCount)
        assertEquals(1, h.env.advanceCount)
        assertEquals("", pendingMarker(h))
        assertNull(h.tracker.activeAuthoritativeReservation())
    }

    @Test
    fun `same process scope retries exact S plus 6 finalize without abort or new generation`() {
        val h = readyAuthoritativeHarness("authoritative-process-scope-finalize-retry")
        val lease = h.apply(key = "authoritative-process-scope-apply")
        h.release(lease.leaseId, key = "authoritative-process-scope-release")
        val request = request(h, lease.leaseId, "authoritative-process-scope-advance")
        val baseRevision = h.tracker.snapshot().revision
        var livePendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY &&
                ++livePendingWrites == 2
        }
        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        val exactS2 = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(reservation.startingSequence + 2L, exactS2.sequence)
        assertEquals(reservation.mutationId, exactS2.lastCompletedQwyMutationId)

        // This is the one genuine owner-process transition. The scope below is then retried
        // twice inside that same process and must reuse its tracker/coordinator/death token.
        h.authoritativeOracle.ownerProcessDied()
        val recoveryEndpoint = FakeQwySemanticMutationEndpoint(h.authoritativeOracle)
        var deathTokensCreated = 0
        val recoveryCoordinator = QwySemanticMutationCoordinator(
            endpointProvider = QwySemanticMutationEndpointProvider { recoveryEndpoint },
            clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
                deathTokensCreated += 1
                QwySemanticClientDeathToken { true }
            },
        )
        h.env.abortOwnerStartClearsProjection = true
        val expectedLatitude = checkNotNull(h.env.effectiveLatitude)
        val expectedLongitude = checkNotNull(h.env.effectiveLongitude)
        ProviderRuntime.CleanShutdownMarker.record(h.kv)
        val scope = ProviderRuntime.createProcessScope(
            kv = h.kv,
            clock = h.clock,
            resolver = h.resolver,
            environment = h.env,
            authoritativeSource = h.authoritativeOracle,
            expectedOracleOwnerPackage = ProviderHarness.QWY_PACKAGE,
            expectedOracleOwnerUid = ProviderHarness.QWY_UID,
            semanticCoordinator = recoveryCoordinator,
        )
        val retainedHandler = scope.handler
        val retainedGeneration = scope.tracker.generation
        h.kv.failOnWrite = { namespace, key ->
            namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
        }

        assertThrows(SimulatedWriteCrash::class.java) { scope.start() }

        val exactS6 = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(reservation.startingSequence + 6L, exactS6.sequence)
        assertEquals(reservation.mutationId, exactS6.lastCompletedQwyMutationId)
        assertEquals(1, recoveryEndpoint.registrationCount)
        assertEquals(1, deathTokensCreated)
        assertEquals(0, h.env.abortOwnerStartCount)
        assertEquals(expectedLatitude, h.env.effectiveLatitude)
        assertEquals(expectedLongitude, h.env.effectiveLongitude)
        assertEquals(baseRevision, scope.tracker.snapshot().revision)
        assertEquals(reservation, scope.tracker.activeAuthoritativeReservation())

        h.kv.failOnWrite = null
        val resumed = scope.start()

        assertSame(retainedHandler, resumed)
        assertEquals(retainedGeneration, scope.tracker.generation)
        assertEquals(1, recoveryEndpoint.registrationCount)
        assertEquals(1, deathTokensCreated)
        assertEquals(reservation.reservedRevision, scope.tracker.snapshot().revision)
        assertNull(scope.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertEquals(1, h.env.advanceCount)
        assertEquals(0, h.env.abortOwnerStartCount)
        assertEquals(reservation.startingSequence + 6L, h.authoritativeOracle.snapshot()?.sequence)
    }

    @Test
    fun `public callback cannot consume the base revision while reservation is pending`() {
        val h = readyAuthoritativeHarness("authoritative-pending-callback")
        val lease = h.apply(key = "authoritative-pending-callback-apply")
        h.release(lease.leaseId, key = "authoritative-pending-callback-release")
        val request = request(h, lease.leaseId, "authoritative-pending-callback-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        h.env.continuityCapability = ContinuityEvidenceCapability.UNAVAILABLE

        val callback = EnvironmentControlHandler::class.java.getDeclaredMethod(
            "recordRelevantChange",
            RevisionBumpReason::class.java,
        ).apply { isAccessible = true }
        callback.invoke(h.handler, RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)

        assertEquals(
            "a callback must not make every reservation terminal path fail its base CAS",
            reservation.baseRevision,
            h.tracker.snapshot().revision,
        )
        assertEquals(reservation, h.tracker.activeAuthoritativeReservation())

        h.env.continuityCapability = ContinuityEvidenceCapability.COMPLETE
        val replay = h.handler.completeAndAdvance(AUTO_UID, request)
        assertEquals(reservation.reservedRevision, replay.effectiveEnvironmentRevision)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
    }

    @Test
    fun `failure before pointer mutation proves no-op and retries the same reservation`() {
        val h = readyAuthoritativeHarness("authoritative-pre-pointer-crash")
        val lease = h.apply(key = "authoritative-pre-pointer-crash-apply")
        h.release(lease.leaseId, key = "authoritative-pre-pointer-crash-release")
        val request = request(h, lease.leaseId, "authoritative-pre-pointer-crash-advance")
        val revisionBefore = h.tracker.snapshot().revision
        val beginBefore = h.semanticEndpoint.beginCount
        h.env.failNextAdvancePointer = true

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }

        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals(revisionBefore + 1L, reservation.reservedRevision)
        assertEquals("item-1", h.env.currentItemId)
        assertEquals(revisionBefore, h.tracker.snapshot().revision)
        assertFalse(pendingMarker(h).isNullOrEmpty())
        assertEquals(beginBefore + 1, h.semanticEndpoint.beginCount)

        val replay = h.handler.completeAndAdvance(AUTO_UID, request)

        assertEquals(reservation.reservedRevision, replay.effectiveEnvironmentRevision)
        assertEquals("item-2", h.env.currentItemId)
        assertEquals(1, h.env.advanceCount)
        assertEquals(beginBefore + 2, h.semanticEndpoint.beginCount)
        assertEquals(
            reservation.mutationId,
            h.authoritativeOracle.snapshot()?.lastCompletedQwyMutationId,
        )
        assertTrue(h.semanticCoordinator.isReadyFor(
            checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation)),
        ))
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
    }

    @Test
    fun `owner restart coalesces a remotely completed pending advance without inheriting FULL`() {
        val h = readyAuthoritativeHarness("authoritative-owner-restart")
        val lease = h.apply(key = "authoritative-owner-restart-apply")
        h.release(lease.leaseId, key = "authoritative-owner-restart-release")
        val request = request(h, lease.leaseId, "authoritative-owner-restart-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("item-2", h.env.currentItemId)

        h.restart(cleanlinessProvable = false)

        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertEquals(
            reservation.reservedRevision,
            h.handler.completeAndAdvance(AUTO_UID, request).effectiveEnvironmentRevision,
        )

        val first = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-owner-restart-observe-1",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(reservation.reservedRevision, first.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, first.continuityCoverageWire)

        val second = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-owner-restart-observe-2",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(reservation.reservedRevision, second.environmentRevision)
        assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
    }

    @Test
    fun `completed active restart rejects down-time physical projection drift before S plus 6`() {
        val h = readyAuthoritativeHarness("authoritative-owner-restart-drift")
        val lease = h.apply(key = "authoritative-owner-restart-drift-apply")
        h.release(lease.leaseId, key = "authoritative-owner-restart-drift-release")
        val request = request(h, lease.leaseId, "authoritative-owner-restart-drift-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }
        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        val registrationsBeforeRestart = h.semanticEndpoint.registrationCount

        assertThrows(IllegalStateException::class.java) {
            h.restart(cleanlinessProvable = false) {
                // Model an independently discovered/pre-existing physical cache mismatch while
                // the owner is down, without granting a newer usable oracle correlation.
                h.env.authoritativeProjectionOverride = 12.345 to 67.89
            }
        }

        val deferred = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(reservation.startingSequence + 4L, deferred.sequence)
        assertEquals(reservation.mutationId, deferred.lastCompletedQwyMutationId)
        assertEquals(registrationsBeforeRestart, h.semanticEndpoint.registrationCount)
        assertEquals(reservation, h.tracker.activeAuthoritativeReservation())
        assertEquals(h.tracker.snapshot().revision + 1L, reservation.reservedRevision)
        assertTrue(checkNotNull(pendingMarker(h)).isNotEmpty())
    }

    @Test
    fun `owner restart nests inherited provider cleanup inside reserved replay`() {
        val h = readyAuthoritativeHarness("authoritative-owner-cleanup-replay")
        val starting = checkNotNull(h.authoritativeOracle.snapshot())
        val reservation = h.tracker.reserveAuthoritativeMutation(
            mutationId = "authoritative-owner-cleanup-replay-reservation",
            startingSnapshot = starting,
        )
        h.kv.write(
            EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
            EnvironmentControlHandler.ADVANCE_PENDING_KEY,
            PendingAdvanceTicket.encode(
                PendingAdvanceTicket.Authoritative(
                    fromItemId = "item-1",
                    toItemId = "item-2",
                    versionAfter = h.env.scheduleVersion + 1L,
                    reservation = reservation,
                ),
            ),
        )
        h.env.semanticDigestUnavailableUntilProjectionReconciled = true
        h.env.projectionStartupReconciliationRequired = true
        h.env.onProjectionStartupReconciliation = {
            h.authoritativeOracle.changedByQwyCoveredOperation()
        }

        h.restart(cleanlinessProvable = false)

        val recovered = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(starting.sequence + 6L, recovered.sequence)
        assertEquals(reservation.mutationId, recovered.lastCompletedQwyMutationId)
        assertEquals(1, h.env.projectionStartupReconciliationCount)
        assertEquals("item-2", h.env.currentItemId)
        assertEquals(1, h.env.advanceCount)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertFalse(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
    }

    @Test
    fun `terminal completed restart adopts exact inactive proof without cleanup or replay`() {
        val h = readyAuthoritativeHarness("authoritative-terminal-completed-restart")
        h.env.itemIds = mutableListOf("item-1")
        val lease = h.apply(key = "authoritative-terminal-completed-apply")
        h.release(lease.leaseId, key = "authoritative-terminal-completed-release")
        val request = request(h, lease.leaseId, "authoritative-terminal-completed-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertTrue(h.env.exhausted)
        assertEquals(1, h.env.advanceCount)
        h.env.semanticDigestUnavailableUntilProjectionReconciled = true
        h.env.projectionStartupReconciliationRequired = true

        h.restart(cleanlinessProvable = false)

        val recovered = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(reservation.startingSequence + 6L, recovered.sequence)
        assertEquals(reservation.mutationId, recovered.lastCompletedQwyMutationId)
        assertEquals(0, h.env.projectionStartupReconciliationCount)
        assertEquals(1, h.env.advanceCount)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertFalse(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
    }

    @Test
    fun `terminal completed restart refuses inactive adoption for retained active projection`() {
        val h = readyAuthoritativeHarness("authoritative-terminal-active-restart")
        h.env.itemIds = mutableListOf("item-1")
        val lease = h.apply(key = "authoritative-terminal-active-apply")
        h.release(lease.leaseId, key = "authoritative-terminal-active-release")
        // A service-side projection can legitimately reappear after lease
        // release; account that semantic transition before reserving the terminal advance.
        val inactiveDigest = checkNotNull(
            h.env.authoritativeSemanticDigest(h.tracker.generation),
        )
        val reactivated = h.semanticCoordinator.runMutation(
            "authoritative-terminal-active-service-reactivation",
            inactiveDigest,
        ) {
            h.env.effectiveLatitude = 48.45
            h.env.effectiveLongitude = 34.98
            QwySemanticMutationWork.Changed(
                Unit,
                checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation)),
            )
        }
        assertTrue(reactivated is QwySemanticMutationResult.Changed)
        val activeDigest = checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation))
        assertTrue(h.semanticCoordinator.isReadyFor(activeDigest))
        val serviceCompletion = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(activeDigest, serviceCompletion.qwySemanticDigest)
        h.tracker.bump(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED)
        h.tracker.acknowledgeAccountedAuthoritativeMutation(
            completedSnapshot = serviceCompletion,
            expectedMutationId = "authoritative-terminal-active-service-reactivation",
            expectedAfterSemanticDigest = activeDigest,
            expectedOwnerPackage = ProviderHarness.QWY_PACKAGE,
            expectedOwnerUid = ProviderHarness.QWY_UID,
        )
        val request = request(h, lease.leaseId, "authoritative-terminal-active-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        h.env.semanticDigestUnavailableUntilProjectionReconciled = true
        h.env.projectionStartupReconciliationRequired = true
        val registrationsBeforeRestart = h.semanticEndpoint.registrationCount

        assertThrows(IllegalStateException::class.java) {
            h.restart(cleanlinessProvable = false)
        }

        val deferred = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(reservation.startingSequence + 4L, deferred.sequence)
        assertEquals(reservation.mutationId, deferred.lastCompletedQwyMutationId)
        assertEquals(registrationsBeforeRestart, h.semanticEndpoint.registrationCount)
        assertEquals(0, h.env.projectionStartupReconciliationCount)
        assertEquals(reservation, h.tracker.activeAuthoritativeReservation())
        assertTrue(checkNotNull(pendingMarker(h)).isNotEmpty())
    }

    @Test
    fun `terminal replay cleans inherited projection inside reserved S plus 6 mutation`() {
        val h = readyAuthoritativeHarness("authoritative-terminal-replay-restart")
        h.env.itemIds = mutableListOf("item-1")
        val starting = checkNotNull(h.authoritativeOracle.snapshot())
        val reservation = h.tracker.reserveAuthoritativeMutation(
            mutationId = "authoritative-terminal-replay-reservation",
            startingSnapshot = starting,
        )
        h.kv.write(
            EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
            EnvironmentControlHandler.ADVANCE_PENDING_KEY,
            PendingAdvanceTicket.encode(
                PendingAdvanceTicket.Authoritative(
                    fromItemId = "item-1",
                    toItemId = null,
                    versionAfter = h.env.scheduleVersion + 1L,
                    reservation = reservation,
                ),
            ),
        )
        h.env.semanticDigestUnavailableUntilProjectionReconciled = true
        h.env.projectionStartupReconciliationRequired = true
        h.env.onProjectionStartupReconciliation = {
            h.authoritativeOracle.changedByQwyCoveredOperation()
        }

        h.restart(cleanlinessProvable = false)

        val recovered = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(starting.sequence + 6L, recovered.sequence)
        assertEquals(reservation.mutationId, recovered.lastCompletedQwyMutationId)
        assertEquals(1, h.env.projectionStartupReconciliationCount)
        assertTrue(h.env.exhausted)
        assertEquals(1, h.env.advanceCount)
        assertEquals(0, h.env.projectionConvergenceCount)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertFalse(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
    }

    @Test
    fun `completed active restart defers unreadable digest without consuming S plus 4`() {
        val h = readyAuthoritativeHarness("authoritative-active-unreadable-restart")
        val lease = h.apply(key = "authoritative-active-unreadable-apply")
        h.release(lease.leaseId, key = "authoritative-active-unreadable-release")
        val request = request(h, lease.leaseId, "authoritative-active-unreadable-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("item-2", h.env.currentItemId)
        h.env.semanticDigestUnavailableUntilProjectionReconciled = true
        val registrationsBeforeRestart = h.semanticEndpoint.registrationCount

        assertThrows(IllegalStateException::class.java) {
            h.restart(cleanlinessProvable = false)
        }

        val deferred = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(reservation.startingSequence + 4L, deferred.sequence)
        assertEquals(reservation.mutationId, deferred.lastCompletedQwyMutationId)
        assertEquals(registrationsBeforeRestart, h.semanticEndpoint.registrationCount)
        assertEquals(0, h.env.projectionStartupReconciliationCount)
        assertEquals(reservation, h.tracker.activeAuthoritativeReservation())
        assertTrue(checkNotNull(pendingMarker(h)).isNotEmpty())

        h.env.semanticDigestUnavailableUntilProjectionReconciled = false
        h.handler.onOwnerProcessStart(cleanlinessProvable = false)

        val recovered = checkNotNull(h.authoritativeOracle.snapshot())
        assertEquals(reservation.startingSequence + 6L, recovered.sequence)
        assertEquals(reservation.mutationId, recovered.lastCompletedQwyMutationId)
        assertEquals(registrationsBeforeRestart + 1, h.semanticEndpoint.registrationCount)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertFalse(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
    }

    @Test
    fun `reboot quarantines an uncorrelatable advance receipt without stranding service`() {
        val h = readyAuthoritativeHarness("authoritative-reboot-quarantine")
        val lease = h.apply(key = "authoritative-reboot-quarantine-apply")
        h.release(lease.leaseId, key = "authoritative-reboot-quarantine-release")
        val request = request(h, lease.leaseId, "authoritative-reboot-quarantine-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())

        h.restart(cleanlinessProvable = false, reboot = true)

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertEquals("the committed pointer still moves only once", 1, h.env.advanceCount)
        assertThrows(IllegalStateException::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }

        val first = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-reboot-quarantine-observe-1",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(reservation.reservedRevision + 1L, first.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, first.continuityCoverageWire)

        val second = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-reboot-quarantine-observe-2",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(reservation.reservedRevision + 1L, second.environmentRevision)
        assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
    }

    @Test
    fun `unrelated oracle mutation quarantines stale advance correlation on restart`() {
        val h = readyAuthoritativeHarness("authoritative-interleaving-quarantine")
        val lease = h.apply(key = "authoritative-interleaving-quarantine-apply")
        h.release(lease.leaseId, key = "authoritative-interleaving-quarantine-release")
        val request = request(h, lease.leaseId, "authoritative-interleaving-quarantine-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        h.authoritativeOracle.changed(mutationId = "foreign-interleaving")

        h.restart(cleanlinessProvable = false)

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertThrows(IllegalStateException::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
    }

    @Test
    fun `same endpoint interleaving quarantines a pending advance without restart`() {
        val h = readyAuthoritativeHarness("authoritative-live-pending-interleaving")
        val lease = h.apply(key = "authoritative-live-pending-interleaving-apply")
        h.release(lease.leaseId, key = "authoritative-live-pending-interleaving-release")
        val request = request(
            h,
            lease.leaseId,
            "authoritative-live-pending-interleaving-advance",
        )
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        val beginsBeforeForeign = h.semanticEndpoint.beginCount
        h.authoritativeOracle.changed(mutationId = "foreign-live-pending-interleaving")

        assertThrows(IllegalStateException::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertEquals("the pointer must still move exactly once", 1, h.env.advanceCount)
        assertEquals(
            "an unrelated live cursor must be quarantined without replaying the stale mutation",
            beginsBeforeForeign,
            h.semanticEndpoint.beginCount,
        )
    }

    @Test
    fun `public callback cannot counterfeit recovery across two foreign mutations`() {
        val h = readyAuthoritativeHarness("authoritative-callback-counterfeit")
        val starting = checkNotNull(h.authoritativeOracle.snapshot())
        val reservation = h.tracker.reserveAuthoritativeMutation(
            mutationId = "callback-counterfeit-reservation",
            startingSnapshot = starting,
        )
        h.kv.write(
            EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
            EnvironmentControlHandler.ADVANCE_PENDING_KEY,
            PendingAdvanceTicket.encode(
                PendingAdvanceTicket.Authoritative(
                    fromItemId = "item-1",
                    toItemId = "item-2",
                    versionAfter = h.env.scheduleVersion + 1L,
                    reservation = reservation,
                ),
            ),
        )
        h.authoritativeOracle.changed(mutationId = "foreign-before-callback-1")
        h.authoritativeOracle.changed(mutationId = "foreign-before-callback-2")
        EnvironmentControlHandler::class.java.getDeclaredMethod(
            "recordRelevantChange",
            RevisionBumpReason::class.java,
        ).apply { isAccessible = true }
            .invoke(h.handler, RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
        val beginsBeforeSettlement = h.semanticEndpoint.beginCount

        h.handler.discover(AUTO_UID)

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertEquals(
            "a public callback cannot authorize replay over an unrelated cursor",
            beginsBeforeSettlement,
            h.semanticEndpoint.beginCount,
        )
        assertEquals("the stale pending mutation must not move the pointer", 0, h.env.advanceCount)
    }

    @Test
    fun `lost semantic session cannot adopt one foreign mutation as recovery`() {
        val h = readyAuthoritativeHarness("authoritative-session-loss-counterfeit")
        val starting = checkNotNull(h.authoritativeOracle.snapshot())
        val reservation = h.tracker.reserveAuthoritativeMutation(
            mutationId = "session-loss-counterfeit-reservation",
            startingSnapshot = starting,
        )
        h.kv.write(
            EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
            EnvironmentControlHandler.ADVANCE_PENDING_KEY,
            PendingAdvanceTicket.encode(
                PendingAdvanceTicket.Authoritative(
                    fromItemId = "item-1",
                    toItemId = "item-2",
                    versionAfter = h.env.scheduleVersion + 1L,
                    reservation = reservation,
                ),
            ),
        )
        h.authoritativeOracle.changed(mutationId = "foreign-before-session-loss")
        h.replaceSemanticEndpointProxy()
        val beginsBeforeSettlement = h.semanticEndpoint.beginCount

        h.handler.discover(AUTO_UID)

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertEquals(
            "session loss cannot authorize replay over an unrelated cursor",
            beginsBeforeSettlement,
            h.semanticEndpoint.beginCount,
        )
        assertEquals(0, h.env.advanceCount)
    }

    @Test
    fun `original call cannot return a receipt quarantined during settlement`() {
        val h = readyAuthoritativeHarness("authoritative-live-interleaving-quarantine")
        val lease = h.apply(key = "authoritative-live-interleaving-quarantine-apply")
        h.release(lease.leaseId, key = "authoritative-live-interleaving-quarantine-release")
        val request = request(h, lease.leaseId, "authoritative-live-interleaving-quarantine-advance")
        var reservation: AuthoritativeRevisionReservation? = null
        h.env.beforeAdvancePointer = {
            reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
            h.authoritativeOracle.changed(mutationId = "foreign-live-interleaving")
        }

        assertThrows(IllegalStateException::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }

        val quarantined = checkNotNull(reservation)
        assertEquals(quarantined.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(quarantined.mutationId))
    }

    @Test
    fun `live oracle replacement rebaselines and quarantines lost correlation`() {
        val h = readyAuthoritativeHarness("authoritative-live-oracle-replacement")
        val lease = h.apply(key = "authoritative-live-oracle-replacement-apply")
        h.release(lease.leaseId, key = "authoritative-live-oracle-replacement-release")
        val request = request(h, lease.leaseId, "authoritative-live-oracle-replacement-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        h.authoritativeOracle.changed(mutationId = null)
        h.authoritativeOracle.replaceOracle(
            bootId = "live-replacement-boot",
            oracleInstanceId = "live-replacement-instance",
        )

        assertThrows(IllegalStateException::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
    }

    @Test
    fun `same cursor Binder proxy replacement cannot strand a pending reservation`() {
        val h = readyAuthoritativeHarness("authoritative-same-cursor-proxy-replacement")
        val starting = checkNotNull(h.authoritativeOracle.snapshot())
        val reservation = h.tracker.reserveAuthoritativeMutation(
            mutationId = "same-cursor-proxy-replacement",
            startingSnapshot = starting,
        )
        h.kv.write(
            EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
            EnvironmentControlHandler.ADVANCE_PENDING_KEY,
            PendingAdvanceTicket.encode(
                PendingAdvanceTicket.Authoritative(
                    fromItemId = "item-1",
                    toItemId = "item-2",
                    versionAfter = h.env.scheduleVersion + 1L,
                    reservation = reservation,
                ),
            ),
        )
        h.replaceSemanticEndpointProxy()

        val capability = h.handler.discover(AUTO_UID)

        assertEquals("item-2", h.env.currentItemId)
        assertEquals(reservation.reservedRevision + 1L, capability.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, capability.continuityCoverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertEquals("the pending pointer must converge exactly once", 1, h.env.advanceCount)
    }

    @Test
    fun `partial pointer success rebaselines and settles in the same owner process`() {
        val h = readyAuthoritativeHarness("authoritative-partial-projection")
        val lease = h.apply(key = "authoritative-partial-projection-apply")
        h.release(lease.leaseId, key = "authoritative-partial-projection-release")
        val request = request(h, lease.leaseId, "authoritative-partial-projection-advance")
        val beginBefore = h.semanticEndpoint.beginCount
        h.env.failNextProjectionAfterPointer = true

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("item-2", h.env.currentItemId)
        assertFalse(pendingMarker(h).isNullOrEmpty())

        val replay = h.handler.completeAndAdvance(AUTO_UID, request)

        assertEquals(reservation.reservedRevision, replay.effectiveEnvironmentRevision)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertEquals(beginBefore + 2, h.semanticEndpoint.beginCount)
        assertEquals(1, h.env.advanceCount)
        assertEquals(1, h.env.projectionConvergenceCount)
        assertEquals(
            reservation.mutationId,
            h.authoritativeOracle.snapshot()?.lastCompletedQwyMutationId,
        )
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
    }

    @Test
    fun `partial changed uncertainty plus foreign cursor quarantines without replay`() {
        val h = readyAuthoritativeHarness("authoritative-partial-foreign-quarantine")
        val lease = h.apply(key = "authoritative-partial-foreign-quarantine-apply")
        h.release(lease.leaseId, key = "authoritative-partial-foreign-quarantine-release")
        val request = request(
            h,
            lease.leaseId,
            "authoritative-partial-foreign-quarantine-advance",
        )
        h.env.failNextProjectionAfterPointer = true

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        h.authoritativeOracle.changed(mutationId = "foreign-after-partial-advance")
        val beginsBeforeSettlement = h.semanticEndpoint.beginCount

        h.handler.discover(AUTO_UID)

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertEquals(
            "the uncorrelated S+4 cursor must retire without registration or replay",
            beginsBeforeSettlement,
            h.semanticEndpoint.beginCount,
        )
        assertEquals(1, h.env.advanceCount)
        assertEquals(0, h.env.projectionConvergenceCount)
    }

    @Test
    fun `exact recovered cursor finalizes before unavailable proxy can trigger registration`() {
        val h = readyAuthoritativeHarness("authoritative-recovered-terminal-order")
        val starting = checkNotNull(h.authoritativeOracle.snapshot())
        val reservation = h.tracker.reserveAuthoritativeMutation(
            mutationId = "recovered-terminal-order-reservation",
            startingSnapshot = starting,
        )
        h.kv.write(
            EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
            EnvironmentControlHandler.ADVANCE_PENDING_KEY,
            PendingAdvanceTicket.encode(
                PendingAdvanceTicket.Authoritative(
                    fromItemId = "item-1",
                    toItemId = "item-2",
                    versionAfter = h.env.scheduleVersion + 1L,
                    reservation = reservation,
                ),
            ),
        )
        val converged = h.env.convergeAdvance(
            fromItemId = "item-1",
            expectedToItemId = "item-2",
            expectedVersionAfter = h.env.scheduleVersion + 1L,
        )
        assertTrue(converged is AdvancePointerOutcome.Advanced)
        val recoveredDigest = h.env.authoritativeSemanticDigest(h.tracker.generation)
        h.authoritativeOracle.changed(semanticDigest = recoveredDigest, mutationId = null)
        h.authoritativeOracle.changed(semanticDigest = recoveredDigest, mutationId = null)
        h.authoritativeOracle.changed(
            semanticDigest = recoveredDigest,
            mutationId = reservation.mutationId,
        )
        assertEquals(starting.sequence + 6L, h.authoritativeOracle.snapshot()?.sequence)
        h.replaceSemanticEndpointProxy()
        val beginsBeforeSettlement = h.semanticEndpoint.beginCount

        val capability = h.handler.discover(AUTO_UID)

        assertEquals(reservation.reservedRevision, capability.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, capability.continuityCoverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertEquals(beginsBeforeSettlement, h.semanticEndpoint.beginCount)
        assertEquals(1, h.env.advanceCount)
    }

    private fun readyAuthoritativeHarness(key: String): ProviderHarness =
        ProviderHarness.create().also { h ->
            h.env.authoritativeSemanticMutations = true
            h.pair()
            // The harness registered its initial semantic baseline at boot;
            // assert the controlled source is genuinely ready before mutating.
            val digest = checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation))
            check(h.semanticCoordinator.isReadyFor(digest)) { "$key semantic session not ready" }
        }

    private fun request(
        h: ProviderHarness,
        leaseId: String,
        key: String,
    ): CompleteAndAdvanceRequestV1 {
        val proof = CompletionProofV1(
            scheduleItemId = "item-1",
            trustedSuccessCount = 3,
            quotaRequired = 3,
            ledgerRef = "auto:ledger:$key:item-1",
            verifiedAtElapsedRealtimeMs = h.clock.elapsedRealtimeMs(),
        )
        val bare = CompleteAndAdvanceRequestV1(
            leaseId = leaseId,
            idempotencyKey = key,
            requestDigest = "",
            expectedScheduleId = h.env.scheduleId,
            expectedScheduleVersion = h.env.scheduleVersion,
            expectedCurrentItemId = "item-1",
            completionProof = proof,
            callerProtocolVersion = 1,
        )
        return bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))
    }

    private fun pendingMarker(h: ProviderHarness): String? = h.kv.read(
        EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
        EnvironmentControlHandler.ADVANCE_PENDING_KEY,
    )
}
