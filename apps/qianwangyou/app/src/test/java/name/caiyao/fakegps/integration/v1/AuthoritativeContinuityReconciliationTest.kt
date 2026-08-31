package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeContinuityReconciliationTest {

    @Test
    fun `new stable sequence bumps and ACKs once then retry is idempotent`() {
        val fixture = fixture()
        fixture.establish(snapshot(sequence = 0L))
        val before = fixture.tracker.snapshot().revision

        val advanced = fixture.reconcile(snapshot(sequence = 2L))
        assertEquals(before + 1L, advanced.revision)
        assertEquals(ContinuityCoverageV1.FULL.wire, advanced.coverageWire)

        val responseLostRetry = fixture.reconcile(snapshot(sequence = 2L))
        assertEquals(advanced.revision, responseLostRetry.revision)
        assertEquals(advanced.continuitySinceElapsedRealtimeMs,
            responseLostRetry.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `boot or oracle instance change bumps but first window is NONE`() {
        val fixture = fixture()
        fixture.establish(snapshot(sequence = 4L))
        val before = fixture.tracker.snapshot().revision

        val changed = fixture.reconcile(
            snapshot(bootId = "boot-b", instanceId = "instance-b", sequence = 0L),
        )
        assertEquals(before + 1L, changed.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, changed.coverageWire)
        assertNull(changed.continuitySinceElapsedRealtimeMs)

        fixture.clock.advance(50L)
        val nextStableWindow = fixture.reconcile(
            snapshot(bootId = "boot-b", instanceId = "instance-b", sequence = 0L),
        )
        assertEquals(changed.revision, nextStableWindow.revision)
        assertEquals(ContinuityCoverageV1.FULL.wire, nextStableWindow.coverageWire)
    }

    @Test
    fun `same-instance sequence regression is bumped once and cannot inherit FULL`() {
        val fixture = fixture()
        fixture.establish(snapshot(sequence = 4L))
        val before = fixture.tracker.snapshot().revision

        val regressed = fixture.reconcile(snapshot(sequence = 2L))
        assertEquals(before + 1L, regressed.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, regressed.coverageWire)

        val repeated = fixture.reconcile(snapshot(sequence = 2L))
        assertEquals("same poison must not create an unbounded bump loop",
            regressed.revision, repeated.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, repeated.coverageWire)

        val recoveredBeyondAck = fixture.reconcile(snapshot(sequence = 6L))
        assertEquals(regressed.revision + 1L, recoveredBeyondAck.revision)
        assertEquals(ContinuityCoverageV1.FULL.wire, recoveredBeyondAck.coverageWire)
    }

    @Test
    fun `away then restore during read ACKs post sequence but current window is NONE`() {
        val fixture = fixture()
        fixture.establish(snapshot(sequence = 0L))
        val before = fixture.tracker.snapshot().revision
        val pre = snapshot(sequence = 0L)
        val restoredPost = snapshot(sequence = 4L)

        val rejected = fixture.tracker.reconcileAuthoritativeWindow(
            AuthoritativeObservationWindow(pre, restoredPost, fixture.clock.elapsedRealtimeMs()),
            QWY_PACKAGE,
            QWY_UID,
        )

        assertEquals(before + 1L, rejected.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, rejected.coverageWire)
        fixture.clock.advance(50L)
        assertEquals(
            ContinuityCoverageV1.FULL.wire,
            fixture.reconcile(restoredPost).coverageWire,
        )
    }

    @Test
    fun `revision bump and sequence ACK roll back together when ACK write crashes`() {
        val fixture = fixture()
        fixture.establish(snapshot(sequence = 0L))
        val before = fixture.tracker.snapshot()
        fixture.kv.failOnWrite = { namespace, key ->
            namespace == ContinuityTracker.REVISION_NAMESPACE &&
                key == ContinuityTracker.ORACLE_ACK_SEQUENCE_KEY
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            fixture.reconcile(snapshot(sequence = 2L))
        }
        fixture.kv.failOnWrite = null

        val afterCrash = fixture.tracker.snapshot()
        assertEquals(before.revision, afterCrash.revision)
        assertEquals(before.coverageWire, afterCrash.coverageWire)

        val retried = fixture.reconcile(snapshot(sequence = 2L))
        assertEquals(before.revision + 1L, retried.revision)
        assertEquals(ContinuityCoverageV1.FULL.wire, retried.coverageWire)
    }

    @Test
    fun `same sequence with changed endpoint digest poisons continuity`() {
        val fixture = fixture()
        fixture.establish(snapshot(sequence = 2L))
        val before = fixture.tracker.snapshot().revision
        val endpointChangedWithoutSequence = snapshot(
            sequence = 2L,
            semanticDigest = "semantic-without-sequence",
        )

        val poisoned = fixture.reconcile(endpointChangedWithoutSequence)

        assertEquals(before + 1L, poisoned.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, poisoned.coverageWire)
        assertEquals(poisoned.revision, fixture.reconcile(endpointChangedWithoutSequence).revision)
    }

    @Test
    fun `exact mutation reservation commits one revision with its oracle ACK`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 2L)
        fixture.establish(starting)
        val before = fixture.tracker.snapshot()

        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "advance-mutation-1",
            startingSnapshot = starting,
        )

        assertEquals(before.revision, reservation.baseRevision)
        assertEquals(before.revision + 1L, reservation.reservedRevision)
        assertEquals("reservation must not expose a speculative bump",
            before.revision, fixture.tracker.snapshot().revision)

        val completed = snapshot(sequence = 4L).copy(
            qwySemanticDigest = "semantic-after-advance",
            lastCompletedQwyMutationId = reservation.mutationId,
        )
        val finalized = fixture.tracker.finalizeAuthoritativeReservation(
            reservation = reservation,
            completedSnapshot = completed,
            expectedAfterSemanticDigest = checkNotNull(completed.qwySemanticDigest),
            expectedOwnerPackage = QWY_PACKAGE,
            expectedOwnerUid = QWY_UID,
        )

        assertEquals(reservation.reservedRevision, finalized.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, finalized.coverageWire)
        assertNull(fixture.tracker.activeAuthoritativeReservation())
        val immediateObservation = fixture.reconcile(completed)
        assertEquals(ContinuityCoverageV1.FULL.wire, immediateObservation.coverageWire)
        assertEquals(
            "response-lost reconciliation must not double bump the reserved sequence",
            finalized.revision,
            immediateObservation.revision,
        )
    }

    @Test
    fun `mismatched mutation id cannot consume or expose reservation`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 0L)
        fixture.establish(starting)
        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "advance-mutation-expected",
            startingSnapshot = starting,
        )

        val mismatched = snapshot(sequence = 2L).copy(
            qwySemanticDigest = "semantic-after-unrelated-change",
            lastCompletedQwyMutationId = "unrelated-mutation",
        )

        assertThrows(IllegalStateException::class.java) {
            fixture.tracker.finalizeAuthoritativeReservation(
                reservation = reservation,
                completedSnapshot = mismatched,
                expectedAfterSemanticDigest = checkNotNull(mismatched.qwySemanticDigest),
                expectedOwnerPackage = QWY_PACKAGE,
                expectedOwnerUid = QWY_UID,
            )
        }
        assertEquals(reservation.baseRevision, fixture.tracker.snapshot().revision)
        assertEquals(reservation, fixture.tracker.activeAuthoritativeReservation())
    }

    @Test
    fun `reservation finalization crash leaves both reservation and base revision`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 0L)
        fixture.establish(starting)
        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "advance-mutation-crash",
            startingSnapshot = starting,
        )
        val completed = snapshot(sequence = 2L).copy(
            qwySemanticDigest = "semantic-after-crash-retry",
            lastCompletedQwyMutationId = reservation.mutationId,
        )
        fixture.kv.failOnWrite = { namespace, key ->
            namespace == ContinuityTracker.REVISION_NAMESPACE &&
                key == ContinuityTracker.ORACLE_ACK_SEQUENCE_KEY
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            fixture.tracker.finalizeAuthoritativeReservation(
                reservation = reservation,
                completedSnapshot = completed,
                expectedAfterSemanticDigest = checkNotNull(completed.qwySemanticDigest),
                expectedOwnerPackage = QWY_PACKAGE,
                expectedOwnerUid = QWY_UID,
            )
        }
        fixture.kv.failOnWrite = null

        assertEquals(reservation.baseRevision, fixture.tracker.snapshot().revision)
        assertEquals(reservation, fixture.tracker.activeAuthoritativeReservation())
        assertEquals(
            reservation.reservedRevision,
            fixture.tracker.finalizeAuthoritativeReservation(
                reservation = reservation,
                completedSnapshot = completed,
                expectedAfterSemanticDigest = checkNotNull(completed.qwySemanticDigest),
                expectedOwnerPackage = QWY_PACKAGE,
                expectedOwnerUid = QWY_UID,
            ).revision,
        )
    }

    @Test
    fun `reservation rejects every identity sequence semantic health and owner mismatch`() {
        val variants: List<(AuthoritativeContinuitySnapshot, String) -> AuthoritativeContinuitySnapshot> =
            listOf(
                { value, _ -> value.copy(bootId = "other-boot") },
                { value, _ -> value.copy(oracleInstanceId = "other-instance") },
                { value, _ -> value.copy(sequence = value.sequence + 2L) },
                { value, _ -> value.copy(health = AuthoritativeOracleHealth.SESSION_UNCERTAIN) },
                { value, _ -> value.copy(ownerUid = QWY_UID + 1) },
                { value, _ -> value.copy(ownerPackage = "other.owner") },
                { value, _ -> value.copy(qwySemanticDigest = "unexpected-semantic") },
                { value, id -> value.copy(lastCompletedQwyMutationId = "$id-wrong") },
            )

        variants.forEachIndexed { index, mutate ->
            val fixture = fixture()
            val starting = snapshot(sequence = 0L)
            fixture.establish(starting)
            val reservation = fixture.tracker.reserveAuthoritativeMutation(
                mutationId = "mismatch-$index",
                startingSnapshot = starting,
            )
            val expectedDigest = "semantic-after-$index"
            val validCompleted = snapshot(sequence = 2L).copy(
                qwySemanticDigest = expectedDigest,
                lastCompletedQwyMutationId = reservation.mutationId,
            )
            val mismatched = mutate(validCompleted, reservation.mutationId)

            assertThrows("variant $index", IllegalStateException::class.java) {
                fixture.tracker.finalizeAuthoritativeReservation(
                    reservation = reservation,
                    completedSnapshot = mismatched,
                    expectedAfterSemanticDigest = expectedDigest,
                    expectedOwnerPackage = QWY_PACKAGE,
                    expectedOwnerUid = QWY_UID,
                )
            }
            assertEquals(reservation.baseRevision, fixture.tracker.snapshot().revision)
            assertEquals(reservation, fixture.tracker.activeAuthoritativeReservation())
        }
    }

    @Test
    fun `tracker reconstruction with active reservation coalesces restart bump`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 0L)
        fixture.establish(starting)
        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "restart-reservation",
            startingSnapshot = starting,
        )

        val restarted = ContinuityTracker(fixture.kv, fixture.clock)

        assertEquals(reservation.baseRevision, restarted.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, restarted.snapshot().coverageWire)
        assertEquals(reservation, restarted.activeAuthoritativeReservation())
    }

    @Test
    fun `reservation rejects same cursor whose evidence was never ACKed`() {
        val fixture = fixture()
        val acknowledged = snapshot(sequence = 0L)
        fixture.establish(acknowledged)
        val unsequencedEvidence = acknowledged.copy(
            lastCompletedQwyMutationId = "unsequenced-correlation",
        )

        assertNull(
            fixture.tracker.tryReserveAuthoritativeMutation(
                mutationId = "must-not-reserve",
                startingSnapshot = unsequencedEvidence,
                expectedOwnerPackage = QWY_PACKAGE,
                expectedOwnerUid = QWY_UID,
            ),
        )
        assertNull(fixture.tracker.activeAuthoritativeReservation())
    }

    @Test
    fun `owner restart coalesces pending reservation and fences the first stable window`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 0L)
        fixture.establish(starting)
        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "restart-recovery",
            startingSnapshot = starting,
        )
        val restarted = ContinuityTracker(fixture.kv, fixture.clock)
        val recovered = snapshot(
            sequence = 6L,
            semanticDigest = "semantic-generation-2-after-advance",
        ).copy(lastCompletedQwyMutationId = reservation.mutationId)

        val finalized = restarted.finalizeRecoveredAuthoritativeReservation(
            reservation = reservation,
            recoveredSnapshot = recovered,
            expectedCurrentSemanticDigest = checkNotNull(recovered.qwySemanticDigest),
            expectedOwnerPackage = QWY_PACKAGE,
            expectedOwnerUid = QWY_UID,
        )

        assertEquals(reservation.reservedRevision, finalized.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, finalized.coverageWire)
        assertNull(restarted.activeAuthoritativeReservation())

        val firstWindow = restarted.reconcileAuthoritativeWindow(
            AuthoritativeObservationWindow(
                recovered,
                recovered,
                fixture.clock.elapsedRealtimeMs(),
            ),
            QWY_PACKAGE,
            QWY_UID,
        )
        assertEquals(reservation.reservedRevision, firstWindow.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, firstWindow.coverageWire)

        fixture.clock.advance(50L)
        val secondWindow = restarted.reconcileAuthoritativeWindow(
            AuthoritativeObservationWindow(
                recovered,
                recovered,
                fixture.clock.elapsedRealtimeMs(),
            ),
            QWY_PACKAGE,
            QWY_UID,
        )
        assertEquals(reservation.reservedRevision, secondWindow.revision)
        assertEquals(ContinuityCoverageV1.FULL.wire, secondWindow.coverageWire)
    }

    @Test
    fun `owner recovery refuses a different boot or oracle instance`() {
        listOf(
            snapshot(
                bootId = "other-boot",
                instanceId = "instance-a",
                sequence = 6L,
                semanticDigest = "semantic-generation-2",
            ).copy(lastCompletedQwyMutationId = "restart-identity-guard"),
            snapshot(
                bootId = "boot-a",
                instanceId = "other-instance",
                sequence = 6L,
                semanticDigest = "semantic-generation-2",
            ).copy(lastCompletedQwyMutationId = "restart-identity-guard"),
        ).forEach { recovered ->
            val fixture = fixture()
            val starting = snapshot(sequence = 0L)
            fixture.establish(starting)
            val reservation = fixture.tracker.reserveAuthoritativeMutation(
                mutationId = "restart-identity-guard",
                startingSnapshot = starting,
            )
            val restarted = ContinuityTracker(fixture.kv, fixture.clock)

            assertThrows(IllegalStateException::class.java) {
                restarted.finalizeRecoveredAuthoritativeReservation(
                    reservation = reservation,
                    recoveredSnapshot = recovered,
                    expectedCurrentSemanticDigest = checkNotNull(recovered.qwySemanticDigest),
                    expectedOwnerPackage = QWY_PACKAGE,
                    expectedOwnerUid = QWY_UID,
                )
            }
            assertEquals(reservation.baseRevision, restarted.snapshot().revision)
            assertEquals(reservation, restarted.activeAuthoritativeReservation())
        }
    }

    @Test
    fun `owner recovery refuses unrelated sequence or missing reserved correlation`() {
        val variants = listOf<(AuthoritativeRevisionReservation) -> AuthoritativeContinuitySnapshot>(
            { reservation ->
                snapshot(
                    sequence = reservation.startingSequence + 8L,
                    semanticDigest = "semantic-generation-2",
                ).copy(lastCompletedQwyMutationId = reservation.mutationId)
            },
            { reservation ->
                snapshot(
                    sequence = reservation.startingSequence + 6L,
                    semanticDigest = "semantic-generation-2",
                ).copy(lastCompletedQwyMutationId = null)
            },
            { reservation ->
                snapshot(
                    sequence = reservation.startingSequence + 6L,
                    semanticDigest = "semantic-generation-2",
                ).copy(lastCompletedQwyMutationId = "foreign-${reservation.mutationId}")
            },
        )

        variants.forEachIndexed { index, variant ->
            val fixture = fixture()
            val starting = snapshot(sequence = 0L)
            fixture.establish(starting)
            val reservation = fixture.tracker.reserveAuthoritativeMutation(
                mutationId = "recovery-interleaving-$index",
                startingSnapshot = starting,
            )
            val restarted = ContinuityTracker(fixture.kv, fixture.clock)
            val recovered = variant(reservation)

            assertThrows(IllegalStateException::class.java) {
                restarted.finalizeRecoveredAuthoritativeReservation(
                    reservation = reservation,
                    recoveredSnapshot = recovered,
                    expectedCurrentSemanticDigest = checkNotNull(recovered.qwySemanticDigest),
                    expectedOwnerPackage = QWY_PACKAGE,
                    expectedOwnerUid = QWY_UID,
                )
            }
            assertEquals(reservation.baseRevision, restarted.snapshot().revision)
            assertEquals(reservation, restarted.activeAuthoritativeReservation())
        }
    }

    @Test
    fun `quarantine refuses same or regressed cursor from the starting oracle`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 4L)
        fixture.establish(starting)
        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "quarantine-cursor-monotonicity",
            startingSnapshot = starting,
        )
        var committedSideEffects = 0

        listOf(4L, 2L).forEach { invalidSequence ->
            val invalid = snapshot(
                sequence = invalidSequence,
                semanticDigest = "semantic-invalid-$invalidSequence",
            )
            assertThrows(IllegalStateException::class.java) {
                fixture.tracker.quarantineAuthoritativeReservation(
                    reservation = reservation,
                    currentSnapshot = invalid,
                    expectedCurrentSemanticDigest = checkNotNull(invalid.qwySemanticDigest),
                    expectedOwnerPackage = QWY_PACKAGE,
                    expectedOwnerUid = QWY_UID,
                    commitSideEffects = { committedSideEffects++ },
                )
            }
            assertEquals(reservation.baseRevision, fixture.tracker.snapshot().revision)
            assertEquals(reservation, fixture.tracker.activeAuthoritativeReservation())
            assertFalse(fixture.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
            assertEquals(0, committedSideEffects)
        }
    }

    @Test
    fun `uncorrelated healthy epoch quarantines receipt and recovers after one fenced window`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 0L)
        fixture.establish(starting)
        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "quarantine-after-reboot",
            startingSnapshot = starting,
        )
        val current = snapshot(
            bootId = "boot-after-reboot",
            instanceId = "instance-after-reboot",
            sequence = 2L,
            semanticDigest = "semantic-current-after-reboot",
        ).copy(lastCompletedQwyMutationId = reservation.mutationId)

        val quarantined = fixture.tracker.quarantineAuthoritativeReservation(
            reservation = reservation,
            currentSnapshot = current,
            expectedCurrentSemanticDigest = checkNotNull(current.qwySemanticDigest),
            expectedOwnerPackage = QWY_PACKAGE,
            expectedOwnerUid = QWY_UID,
        )

        assertEquals(reservation.reservedRevision + 1L, quarantined.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, quarantined.coverageWire)
        assertNull(fixture.tracker.activeAuthoritativeReservation())
        assertTrue(fixture.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))

        val firstWindow = fixture.reconcile(current)
        assertEquals(quarantined.revision, firstWindow.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, firstWindow.coverageWire)

        fixture.clock.advance(50L)
        val secondWindow = fixture.reconcile(current)
        assertEquals(quarantined.revision, secondWindow.revision)
        assertEquals(ContinuityCoverageV1.FULL.wire, secondWindow.coverageWire)
    }

    @Test
    fun `newer sequence cannot bypass an outstanding recovery fence`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 0L)
        fixture.establish(starting)
        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "quarantine-fence-newer-sequence",
            startingSnapshot = starting,
        )
        val quarantinedCursor = snapshot(
            sequence = 2L,
            semanticDigest = "semantic-quarantined-cursor",
        ).copy(lastCompletedQwyMutationId = reservation.mutationId)
        val quarantined = fixture.tracker.quarantineAuthoritativeReservation(
            reservation = reservation,
            currentSnapshot = quarantinedCursor,
            expectedCurrentSemanticDigest = checkNotNull(quarantinedCursor.qwySemanticDigest),
            expectedOwnerPackage = QWY_PACKAGE,
            expectedOwnerUid = QWY_UID,
        )
        val newer = snapshot(
            sequence = 4L,
            semanticDigest = "semantic-after-newer-change",
        )

        val firstWindow = fixture.reconcile(newer)

        assertEquals(quarantined.revision + 1L, firstWindow.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, firstWindow.coverageWire)
        fixture.clock.advance(50L)
        val secondWindow = fixture.reconcile(newer)
        assertEquals(firstWindow.revision, secondWindow.revision)
        assertEquals(ContinuityCoverageV1.FULL.wire, secondWindow.coverageWire)
    }

    @Test
    fun `accounted mutation cannot consume a recovery fence before observation`() {
        val fixture = fixture()
        val starting = snapshot(sequence = 0L)
        fixture.establish(starting)
        val reservation = fixture.tracker.reserveAuthoritativeMutation(
            mutationId = "quarantine-fence-accounted-mutation",
            startingSnapshot = starting,
        )
        val quarantinedCursor = snapshot(
            sequence = 2L,
            semanticDigest = "semantic-quarantined-before-accounted",
        ).copy(lastCompletedQwyMutationId = reservation.mutationId)
        fixture.tracker.quarantineAuthoritativeReservation(
            reservation = reservation,
            currentSnapshot = quarantinedCursor,
            expectedCurrentSemanticDigest = checkNotNull(quarantinedCursor.qwySemanticDigest),
            expectedOwnerPackage = QWY_PACKAGE,
            expectedOwnerUid = QWY_UID,
        )
        val accounted = snapshot(
            sequence = 4L,
            semanticDigest = "semantic-accounted-after-quarantine",
        ).copy(lastCompletedQwyMutationId = "accounted-after-quarantine")

        fixture.tracker.acknowledgeAccountedAuthoritativeMutation(
            completedSnapshot = accounted,
            expectedMutationId = "accounted-after-quarantine",
            expectedAfterSemanticDigest = checkNotNull(accounted.qwySemanticDigest),
            expectedOwnerPackage = QWY_PACKAGE,
            expectedOwnerUid = QWY_UID,
        )

        val firstWindow = fixture.reconcile(accounted)
        assertEquals(ContinuityCoverageV1.NONE.wire, firstWindow.coverageWire)
        fixture.clock.advance(50L)
        val secondWindow = fixture.reconcile(accounted)
        assertEquals(firstWindow.revision, secondWindow.revision)
        assertEquals(ContinuityCoverageV1.FULL.wire, secondWindow.coverageWire)
    }

    @Test
    fun `same sequence unhealthy evidence remains poisoned after endpoint restore`() {
        val fixture = fixture()
        val healthy = snapshot(sequence = 2L)
        fixture.establish(healthy)
        val unhealthy = healthy.copy(gpsProviderEnabled = false)

        val rejected = fixture.tracker.reconcileAuthoritativeWindow(
            AuthoritativeObservationWindow(
                pre = unhealthy,
                post = unhealthy,
                windowStartElapsedRealtimeMs = fixture.clock.elapsedRealtimeMs(),
            ),
            QWY_PACKAGE,
            QWY_UID,
        )
        assertEquals(ContinuityCoverageV1.NONE.wire, rejected.coverageWire)

        fixture.clock.advance(50L)
        val restoredWithoutSequence = fixture.reconcile(healthy)
        assertEquals(
            "restoring old endpoint bytes cannot erase proof that the producer missed a mutation",
            ContinuityCoverageV1.NONE.wire,
            restoredWithoutSequence.coverageWire,
        )

        fixture.clock.advance(50L)
        val advanced = fixture.reconcile(snapshot(sequence = 4L))
        assertEquals(ContinuityCoverageV1.FULL.wire, advanced.coverageWire)
    }

    @Test
    fun `owner baseline cannot lower a same instance durable ACK`() {
        val fixture = fixture()
        val acknowledged = snapshot(sequence = 10L)
        fixture.establish(acknowledged)
        val revisionBefore = fixture.tracker.snapshot().revision

        val rejected = fixture.tracker.acknowledgeAuthoritativeOwnerGenerationBaseline(
            snapshot = snapshot(sequence = 6L),
            expectedOwnerPackage = QWY_PACKAGE,
            expectedOwnerUid = QWY_UID,
        )

        assertEquals(revisionBefore + 1L, rejected.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, rejected.coverageWire)
        fixture.clock.advance(50L)
        val oldAck = fixture.reconcile(acknowledged)
        assertEquals(
            "the higher ACK must remain sticky after a regressed registration",
            ContinuityCoverageV1.NONE.wire,
            oldAck.coverageWire,
        )
        fixture.clock.advance(50L)
        val recovered = fixture.reconcile(snapshot(sequence = 12L))
        assertEquals(ContinuityCoverageV1.FULL.wire, recovered.coverageWire)
    }

    @Test
    fun `bare revision bump always invalidates prior FULL coverage`() {
        val fixture = fixture()
        fixture.establish(snapshot(sequence = 2L))
        val before = fixture.tracker.snapshot()
        assertEquals(ContinuityCoverageV1.FULL.wire, before.coverageWire)

        val revision = fixture.tracker.bump(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED)

        val after = fixture.tracker.snapshot()
        assertEquals(before.revision + 1L, revision)
        assertEquals(revision, after.revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, after.coverageWire)
        assertNull(after.continuitySinceElapsedRealtimeMs)
    }

    private data class Fixture(
        val kv: InMemoryDurableKv,
        val clock: FakeMonotonicClock,
        val tracker: ContinuityTracker,
    ) {
        fun reconcile(value: AuthoritativeContinuitySnapshot): RevisionSnapshot =
            tracker.reconcileAuthoritativeWindow(
                AuthoritativeObservationWindow(value, value, clock.elapsedRealtimeMs()),
                QWY_PACKAGE,
                QWY_UID,
            )

        fun establish(value: AuthoritativeContinuitySnapshot) {
            val baseline = reconcile(value)
            if (baseline.coverageWire != ContinuityCoverageV1.FULL.wire) {
                clock.advance(50L)
                assertEquals(ContinuityCoverageV1.FULL.wire, reconcile(value).coverageWire)
            }
        }
    }

    private fun fixture(): Fixture {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        return Fixture(kv, clock, ContinuityTracker(kv, clock))
    }

    private fun snapshot(
        bootId: String = "boot-a",
        instanceId: String = "instance-a",
        sequence: Long,
        semanticDigest: String = "semantic-a",
    ): AuthoritativeContinuitySnapshot = AuthoritativeContinuitySnapshot(
        protocolVersion = 1,
        bootId = bootId,
        oracleInstanceId = instanceId,
        sequence = sequence,
        ownerUid = QWY_UID,
        ownerPackage = QWY_PACKAGE,
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        requiredCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        health = AuthoritativeOracleHealth.HEALTHY,
        qwySemanticDigest = semanticDigest,
        lastCompletedQwyMutationId = null,
    )

    private companion object {
        const val QWY_PACKAGE = "name.caiyao.fakegps"
        const val QWY_UID = 10_321
    }
}
