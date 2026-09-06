package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.*
import com.example.cellrebelauto.repository.PlanRepository
import io.github.terryyyc.fakexxx.contract.v1.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** All recovery admission exits cross healthy/missing/malformed local history and provider truth. */
@RunWith(RobolectricTestRunner::class)
class EngineLegacyAdmissionMatrixTest {
    private enum class Entrance(val admitted: Boolean = false) {
        ACTIVE(true), NO_ACTIVE, FOREIGN_ACTIVE, MULTIPLE_CURRENT, MULTIPLE_FOREIGN,
        CLOSED_FAILURE, CLOSED_FAILURE_NO_ACTIVE,
        PSEUDO_SUCCEEDED(true), PSEUDO_FAILED(true), PSEUDO_INTERRUPTED(true)
    }

    private enum class Shape(val failure: String? = null) {
        HEALTHY, HEALTHY_RECEIPT, UNDER_QUOTA, NEGATIVE,
        MISSING_BOTH("RELEASE_RECEIPT_MISSING"), MISSING_RELEASE("RELEASE_RECEIPT_MISSING"),
        KEY_ONLY("RELEASE_INDEX_CONFLICT"), LEASE_ONLY("RELEASE_INDEX_CONFLICT"),
        DIVERGENT("RELEASE_INDEX_CONFLICT"), DUPLICATE("RELEASE_INDEX_CONFLICT"),
        BAD_RELEASE("RELEASE_RECEIPT_MISMATCH"), MISSING_REQUEST("ADVANCE_CARRIER_MISSING"),
        BAD_REQUEST("ADVANCE_CARRIER_INVALID"), FOREIGN_REQUEST("ADVANCE_CARRIER_OWNER_MISMATCH"),
        BAD_RECEIPT("ADVANCE_RECEIPT_INVALID")
    }

    private class Fixture(val entrance: Entrance, val shape: Shape, val exhausted: Boolean) : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val repo = PlanRepository(db)
        var planId = 0L
        var taskId = 0L
        var sessionId = 0L
        val ownerIds = mutableListOf(31L)
        val accesses = mutableListOf<String>()
        val advances = mutableListOf<CompleteAndAdvanceRequestV1>()
        var beforeDiscover: (() -> Unit)? = null
        var discoveryUnavailable = false
        var discoverySkewed = false
        var discoveryUnrelated = false
        var discoveryThrows = false
        var failAdvanceOnce = false
        var failObserveOnce = false
        var failReadbackOnce = false

        val backend = object : ExternalApplyExecutor {
            override fun apply(attemptId: Long, intent: EnvironmentIntentV1, idempotencyKey: String,
                requestDigest: String, now: Long): ApplyOutcome {
                accesses += "apply"
                error("legacy admission must never create an apply request")
            }
            override fun release(attemptId: Long, idempotencyKey: String, leaseId: String,
                releaseDigest: String, now: Long): ApplyOutcome {
                accesses += "release"
                error("legacy recovery must never create a release request")
            }
            override fun discover(): CapabilitySnapshotV1? {
                accesses += "discover"
                if (failReadbackOnce && advances.isNotEmpty()) {
                    failReadbackOnce = false
                    error("simulated crash before terminal readback")
                }
                beforeDiscover?.also { beforeDiscover = null }?.invoke()
                if (discoveryThrows) error("injected discovery failure")
                if (discoveryUnavailable) return null
                return CapabilitySnapshotV1(
                    serviceVersion = "matrix", supportedModeWires = listOf(1),
                    supportedVerificationLevelWires = listOf(1), continuityCoverageWire = 1,
                    environmentRevision = 7, profileRefs = emptyList(), scheduleRefs = listOf("schedule"),
                    currentScheduleId = if (discoveryUnrelated) "unrelated" else "schedule",
                    currentItemId = "item", scheduleVersion = if (exhausted) 13 else 12,
                    exhausted = exhausted, protocolVersion = if (discoverySkewed) 999 else ContractV1.PROTOCOL_VERSION
                )
            }
            override fun preflight(intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String): PreflightReportV1? {
                accesses += "preflight"
                error("no new preflight is authorized by legacy validation")
            }
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1 {
                if (failAdvanceOnce) {
                    failAdvanceOnce = false
                    error("simulated crash after local commit before advance dispatch")
                }
                accesses += "advance"
                advances += request
                return receipt(request)
            }
            override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1 {
                accesses += "observe"
                if (failObserveOnce) {
                    failObserveOnce = false
                    error("simulated crash before independent observation")
                }
                return EnvironmentObservationV1(
                    leaseId = leaseId, acceptedIntentHash = "effective", observedAtEpochMs = 1,
                    observedAtElapsedRealtimeMs = 1, environmentRevision = 7, environmentFingerprint = "fp",
                    continuityCoverageWire = 1, continuitySinceEpochMs = null, continuitySinceElapsedRealtimeMs = null,
                    deliveryModeWire = 1, verificationLevelWire = 1, effectiveLatitude = null, effectiveLongitude = null,
                    isMock = true, scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire, evidenceRefs = emptyList(),
                    scheduleItemId = "next", scheduleVersion = 13
                )
            }
        }

        fun request(id: Long): CompleteAndAdvanceRequestV1 {
            val base = CompleteAndAdvanceRequestV1("lease-$id", APlusOperationIdentity.applyIdempotencyKey(id), "",
                "schedule", 12, "item", CompletionProofV1("item", 1, 1, "ledger-$id", 123_456_789),
                ContractV1.PROTOCOL_VERSION)
            return base.copy(requestDigest = CanonicalAdvanceDigestV1.compute(base))
        }

        fun receipt(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 {
            val base = AdvanceReceiptV1(
                outcomeWire = if (exhausted) AdvanceOutcomeV1.EXHAUSTED.wire else AdvanceOutcomeV1.ADVANCED.wire,
                advancedFromItemId = "item", advancedToItemId = if (exhausted) null else "next",
                scheduleVersionAfter = 13, effectiveIntentHash = "effective", effectiveEnvironmentRevision = 7,
                receiptDigest = ""
            )
            return base.copy(receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(base, request.requestDigest, request.idempotencyKey))
        }

        suspend fun seed() {
            val quota = if (shape == Shape.UNDER_QUOTA) 3 else 1
            planId = db.planDao().insertPlanWithTasks(
                LocationPlan(sourceFileName = "matrix.csv", importedAt = 1, globalBufferSeconds = 0,
                    totalRows = 1, totalRequiredSuccesses = quota),
                listOf(LocationTask(planId = 0, csvRow = 1, longitude = 1.0, latitude = 1.0,
                    priority = 1, requiredSuccesses = quota)))
            taskId = repo.getTasks(planId).single().id
            sessionId = repo.createSession(planId, 500)
            seedOwner(31, sessionId)
            when (entrance) {
                Entrance.NO_ACTIVE, Entrance.CLOSED_FAILURE_NO_ACTIVE -> repo.finishSession(sessionId, "interrupted", 900, 4)
                Entrance.FOREIGN_ACTIVE -> repo.createSession(planId, 1000)
                Entrance.MULTIPLE_CURRENT -> { ownerIds += 32; seedOwner(32, sessionId) }
                Entrance.MULTIPLE_FOREIGN -> { ownerIds += 32; seedOwner(32, repo.createSession(planId, 1000)) }
                Entrance.PSEUDO_SUCCEEDED -> sql("UPDATE test_attempts SET status = 'succeeded', endedAt = 900 WHERE id = 31")
                Entrance.PSEUDO_FAILED -> sql("UPDATE test_attempts SET status = 'failed', endedAt = 900 WHERE id = 31")
                Entrance.PSEUDO_INTERRUPTED -> sql("UPDATE test_attempts SET status = 'interrupted', endedAt = 900 WHERE id = 31")
                else -> Unit
            }
            if (entrance in setOf(Entrance.CLOSED_FAILURE, Entrance.CLOSED_FAILURE_NO_ACTIVE)) {
                db.testAttemptDao().insert(repo.getAttempt(31)!!.copy(id = 32, attemptOrdinal = 2,
                    aplusState = "CLOSED", aplusLeaseId = "lease-32"))
                db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 32, taskId = taskId,
                    evidenceDigest = "conflicting", committedAt = 1))
                repo.recordUnverifiedOutcome(32, "UNTRUSTED", "conflicting")
            }
        }

        private suspend fun seedOwner(id: Long, ownerSession: Long) {
            db.testAttemptDao().insert(TestAttempt(id = id, taskId = taskId, runSessionId = ownerSession,
                attemptOrdinal = id.toInt(), successOrdinal = null, startedAt = 600, runningObservedAt = null,
                endedAt = null, status = "running", failureReason = null, webBrowsingScore = null, videoStreamingScore = null,
                latitude = 1.0, longitude = 1.0, aplusState = "RELEASED", aplusLeaseId = "lease-$id",
                aplusAnchorScheduleId = "schedule", aplusAnchorItemId = "item", aplusAnchorVersion = 12))
            if (shape == Shape.NEGATIVE) repo.recordUnverifiedOutcome(id, "UNTRUSTED", "negative")
            else db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = id, taskId = taskId,
                evidenceDigest = "trusted-$id", committedAt = 1))
            db.operationReceiptDao().insertIfAbsent(OperationReceiptRow(APlusOperationIdentity.applyIdempotencyKey(id),
                "apply-digest", "APPLIED", 1, "lease-$id", "op-$id"))
            val key = APlusOperationIdentity.releaseIdempotencyKey(id)
            val release = ReleaseReceiptRow(key, "lease-$id", APlusOperationIdentity.releaseDigest("lease-$id"), "RELEASED", 1)
            db.releaseReceiptDao().insertIfAbsent(release)
            var req = request(id)
            if (shape == Shape.FOREIGN_REQUEST) {
                val foreign = req.copy(expectedScheduleId = "foreign")
                req = foreign.copy(requestDigest = CanonicalAdvanceDigestV1.compute(foreign))
            }
            if (shape !in setOf(Shape.UNDER_QUOTA, Shape.NEGATIVE, Shape.MISSING_BOTH, Shape.MISSING_REQUEST)) {
                repo.persistAdvanceReplayCarrier(id, req, 2)
            }
            if (shape in setOf(Shape.HEALTHY_RECEIPT, Shape.BAD_RECEIPT)) repo.persistAdvanceReceipt(id, req, receipt(req), 3)
            when (shape) {
                Shape.MISSING_BOTH, Shape.MISSING_RELEASE -> sql("DELETE FROM release_receipts WHERE idempotencyKey = '$key'")
                Shape.KEY_ONLY -> sql("UPDATE release_receipts SET leaseId = 'foreign-$id' WHERE idempotencyKey = '$key'")
                Shape.LEASE_ONLY -> sql("UPDATE release_receipts SET idempotencyKey = 'foreign-$id' WHERE idempotencyKey = '$key'")
                Shape.DIVERGENT -> {
                    sql("UPDATE release_receipts SET leaseId = 'foreign-$id' WHERE idempotencyKey = '$key'")
                    db.releaseReceiptDao().insertIfAbsent(release.copy(idempotencyKey = "foreign-$id"))
                }
                Shape.DUPLICATE -> db.releaseReceiptDao().insertIfAbsent(release.copy(idempotencyKey = "foreign-$id"))
                Shape.BAD_RELEASE -> sql("UPDATE release_receipts SET releaseDigest = 'wrong' WHERE idempotencyKey = '$key'")
                Shape.BAD_REQUEST -> sql("UPDATE advance_replay_carriers SET requestDigest = 'wrong' WHERE attemptId = $id")
                Shape.BAD_RECEIPT -> sql("UPDATE advance_receipts SET receiptDigest = 'wrong' WHERE attemptId = $id")
                else -> Unit
            }
        }

        fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

        fun engine() = AutomationEngine(
            planId, repo,
            object : CellRebelRunner {
                override suspend fun runTest(startedAt: Long, testTimeoutMs: Long,
                    onStartInteraction: suspend () -> Unit, onRunningObserved: suspend (Long) -> Unit): AttemptOutcome =
                    error("recovery validation cannot launch CellRebel")
            },
            object : GpsLocationSetter { override suspend fun setLocation(lat: Double, lng: Double) = GpsOutcome.Active },
            BufferGate(0) { 10000 }, testTimeoutMs = 90000, gpsSettleMs = 0,
            // Recovery executes before this existing guard. Prevent unrelated new normal work
            // after a healthy under-quota/negative control has converged.
            stageToggles = { StageToggles(false, false) }, nowMs = { 10000 }, delayMs = {},
            attemptDriver = APlusAttemptDriver(db.auditEventDao()),
            recoveryCoordinator = RecoveryCoordinator(backend,
                RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao()),
                observe = ObserveIntentAcquirer { true }, receiptRevision = ReceiptRevisionAcquirer { _, _ -> true },
                trustedQuota = TrustedQuotaAcquirer { true }),
            completionEvidenceSource = object : APlusEvidenceSource {
                override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) = null
                override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) = null
                override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) = null
            }
        )

        suspend fun immutableRows(id: Long): List<Any?> = listOf(
            db.releaseReceiptDao().byKey(APlusOperationIdentity.releaseIdempotencyKey(id)),
            db.releaseReceiptDao().byLease("lease-$id"), db.advanceReplayCarrierDao().byAttempt(id),
            db.advanceReceiptDao().byAttempt(id))

        override fun close() = db.close()
    }

    private suspend fun matrix(entrance: Entrance) {
        var cases = 0
        for (exhausted in listOf(false, true)) for (shape in Shape.entries) {
            Fixture(entrance, shape, exhausted).use { f ->
                val label = "$entrance/$shape/${if (exhausted) "EXHAUSTED" else "READY"}"
                try {
                    f.seed()
                    val before = f.ownerIds.associateWith { f.immutableRows(it) }
                    val sessions = f.db.runSessionDao().getAllSessions().first().map { it.id }.sorted()
                    repeat(if (shape.failure == null) 1 else 2) {
                        f.engine().run()
                        assertEquals("session identities", sessions,
                            f.db.runSessionDao().getAllSessions().first().map { it.id }.sorted())
                        for (id in f.ownerIds) {
                            val owner = f.repo.getAttempt(id)!!
                            if (shape.failure != null) {
                                assertEquals("RECOVERY_REQUIRED", owner.aplusState)
                                val reason = "LEGACY_RELEASED_AUTHORITY:${shape.failure}"
                                assertEquals(reason, owner.failureReason)
                                assertEquals(listOf("RELEASED->RECOVERY_REQUIRED[$reason]"),
                                    f.db.auditEventDao().forAttempt(id).map { event -> event.payloadDigest })
                                assertEquals(before[id], f.immutableRows(id))
                                assertTrue("invalid local history must precede EVERY provider read/effect", f.accesses.isEmpty())
                            } else if (!entrance.admitted) {
                                assertEquals("RELEASED", owner.aplusState)
                                assertNull(owner.failureReason)
                                assertTrue(f.db.auditEventDao().forAttempt(id).isEmpty())
                                assertEquals(before[id], f.immutableRows(id))
                                assertTrue("unadmitted healthy history must not contact provider", f.accesses.isEmpty())
                            } else {
                                assertEquals("CLOSED", owner.aplusState)
                                assertEquals(if (shape == Shape.NEGATIVE) "failed" else "succeeded", owner.status)
                                assertEquals(before[id]!!.take(3), f.immutableRows(id).take(3))
                                if (shape in setOf(Shape.UNDER_QUOTA, Shape.NEGATIVE, Shape.HEALTHY_RECEIPT)) {
                                    assertTrue(f.advances.isEmpty())
                                } else assertEquals(listOf(f.request(id)), f.advances)
                            }
                        }
                        assertFalse(f.accesses.any { it in setOf("apply", "release", "preflight") })
                    }
                    cases++
                } catch (e: AssertionError) { throw AssertionError("$label: ${e.message}", e) }
            }
        }
        println("legacy admission matrix $entrance: $cases READY/EXHAUSTED authority combinations passed")
    }

    @Test fun `active single owner matrix`() = runTest { matrix(Entrance.ACTIVE) }
    @Test fun `inactive owner session matrix`() = runTest { matrix(Entrance.NO_ACTIVE) }
    @Test fun `foreign newer active session matrix`() = runTest { matrix(Entrance.FOREIGN_ACTIVE) }
    @Test fun `multiple current-session owners matrix`() = runTest { matrix(Entrance.MULTIPLE_CURRENT) }
    @Test fun `multiple foreign-session owners matrix`() = runTest { matrix(Entrance.MULTIPLE_FOREIGN) }
    @Test fun `CLOSED sibling projection failure matrix`() = runTest { matrix(Entrance.CLOSED_FAILURE) }
    @Test fun `CLOSED sibling failure without active session matrix`() = runTest { matrix(Entrance.CLOSED_FAILURE_NO_ACTIVE) }
    @Test fun `falsely succeeded legacy owner census matrix`() = runTest { matrix(Entrance.PSEUDO_SUCCEEDED) }
    @Test fun `falsely failed legacy owner census matrix`() = runTest { matrix(Entrance.PSEUDO_FAILED) }
    @Test fun `falsely interrupted legacy owner census matrix`() = runTest { matrix(Entrance.PSEUDO_INTERRUPTED) }

    @Test fun `provider denial matrix cannot advance healthy validation or bypass invalid quarantine`() = runTest {
        var cases = 0
        for (exhausted in listOf(false, true)) for (mode in listOf("unavailable", "skewed", "throws", "unrelated")) {
            if (mode == "unrelated" && !exhausted) continue
            for (shape in listOf(Shape.HEALTHY, Shape.HEALTHY_RECEIPT, Shape.UNDER_QUOTA, Shape.NEGATIVE,
                    Shape.MISSING_BOTH, Shape.KEY_ONLY, Shape.BAD_RECEIPT)) {
                Fixture(Entrance.ACTIVE, shape, exhausted).use { f ->
                    f.seed()
                    f.discoveryUnavailable = mode == "unavailable"
                    f.discoverySkewed = mode == "skewed"
                    f.discoveryThrows = mode == "throws"
                    f.discoveryUnrelated = mode == "unrelated"
                    val before = f.immutableRows(31)
                    repeat(2) {
                        f.engine().run()
                        val owner = f.repo.getAttempt(31)!!
                        assertEquals("$mode/$exhausted/$shape", if (shape.failure == null) "RELEASED" else "RECOVERY_REQUIRED", owner.aplusState)
                        assertEquals(before, f.immutableRows(31))
                        if (shape.failure == null) {
                            assertNull(owner.failureReason)
                            assertTrue(f.db.auditEventDao().forAttempt(31).isEmpty())
                            assertEquals(List(it + 1) { "discover" }, f.accesses)
                        } else {
                            assertTrue(f.accesses.isEmpty())
                            assertEquals("LEGACY_RELEASED_AUTHORITY:${shape.failure}", owner.failureReason)
                            assertEquals(1, f.db.auditEventDao().forAttempt(31).size)
                        }
                    }
                    cases++
                }
            }
        }
        println("legacy provider denial matrix: $cases combinations passed with two restarts each")
    }

    @Test fun `convergence revalidates changed authority after provider admission`() = runTest {
        for (exhausted in listOf(false, true)) for (fault in listOf("release", "request")) {
            Fixture(Entrance.ACTIVE, Shape.HEALTHY, exhausted).use { f ->
                f.seed()
                f.beforeDiscover = {
                    if (fault == "release") f.sql("DELETE FROM release_receipts")
                    else f.sql("UPDATE advance_replay_carriers SET requestDigest = 'changed-after-validation'")
                }
                repeat(2) {
                    f.engine().run()
                    val reason = "LEGACY_RELEASED_AUTHORITY:" +
                        if (fault == "release") "RELEASE_RECEIPT_MISSING" else "ADVANCE_CARRIER_INVALID"
                    assertEquals("RECOVERY_REQUIRED", f.repo.getAttempt(31)!!.aplusState)
                    assertEquals(reason, f.repo.getAttempt(31)!!.failureReason)
                    assertEquals(listOf("RELEASED->RECOVERY_REQUIRED[$reason]"),
                        f.db.auditEventDao().forAttempt(31).map { event -> event.payloadDigest })
                    assertEquals("the stale local classification is not an effect permit", listOf("discover"), f.accesses)
                }
            }
        }
    }

    @Test fun `a concurrent CLOSED owner cannot be revived by its legacy admission snapshot`() = runTest {
        for (exhausted in listOf(false, true)) Fixture(Entrance.ACTIVE, Shape.HEALTHY, exhausted).use { f ->
            f.seed()
            val before = f.immutableRows(31)
            f.beforeDiscover = { f.sql("UPDATE test_attempts SET aplusState = 'CLOSED' WHERE id = 31") }
            f.engine().run()
            assertEquals("CLOSED", f.repo.getAttempt(31)!!.aplusState)
            assertEquals("succeeded", f.repo.getAttempt(31)!!.status)
            assertTrue(f.db.auditEventDao().forAttempt(31).isEmpty())
            assertEquals(before, f.immutableRows(31))
            assertEquals(listOf("discover"), f.accesses)
        }
    }

    @Test fun `local quarantine audit failure rolls back before all admission and provider work`() = runTest {
        for (exhausted in listOf(false, true)) Fixture(Entrance.MULTIPLE_CURRENT, Shape.MISSING_BOTH, exhausted).use { f ->
            f.seed()
            f.sql("""
                CREATE TRIGGER fail_local_quarantine BEFORE INSERT ON auto_audit_events
                WHEN NEW.eventType = 'RECOVERY_REQUIRED'
                BEGIN SELECT RAISE(ABORT, 'injected local quarantine failure'); END
            """.trimIndent())
            f.engine().run()
            for (id in f.ownerIds) {
                assertEquals("RELEASED", f.repo.getAttempt(id)!!.aplusState)
                assertNull(f.repo.getAttempt(id)!!.failureReason)
                assertTrue(f.db.auditEventDao().forAttempt(id).isEmpty())
            }
            assertTrue(f.accesses.isEmpty())
            f.sql("DROP TRIGGER fail_local_quarantine")
            repeat(2) {
                f.engine().run()
                for (id in f.ownerIds) {
                    assertEquals("RECOVERY_REQUIRED", f.repo.getAttempt(id)!!.aplusState)
                    assertEquals(1, f.db.auditEventDao().forAttempt(id).size)
                }
                assertTrue(f.accesses.isEmpty())
            }
        }
    }

    @Test fun `genuine CLOSED history with legacy provenance is not included in the owner census`() = runTest {
        Fixture(Entrance.PSEUDO_SUCCEEDED, Shape.HEALTHY, exhausted = true).use { f ->
            f.seed()
            f.repo.markRecoveryRequired(31, "HISTORICAL_RECOVERY", 1)
            f.repo.markAplusState(31, "CLOSED")
            val before = f.repo.getAttempt(31)
            val audit = f.db.auditEventDao().forAttempt(31)
            assertTrue(f.repo.findAPlusRecoverableAttempts(f.planId).isEmpty())
            f.engine().run()
            assertEquals(before, f.repo.getAttempt(31))
            assertEquals(audit, f.db.auditEventDao().forAttempt(31))
            assertFalse(f.accesses.any { it in setOf("preflight", "apply", "release", "advance") })
        }
    }

    @Test fun `healthy inactive legacy owner can converge on the next explicitly resumed run`() = runTest {
        for (exhausted in listOf(false, true)) Fixture(Entrance.NO_ACTIVE, Shape.HEALTHY, exhausted).use { f ->
            f.seed()
            val original = f.repo.getAdvanceReplayRequest(31)
            f.engine().run()
            assertTrue(f.accesses.isEmpty())
            assertEquals("RELEASED", f.repo.getAttempt(31)!!.aplusState)
            assertEquals("paused", f.db.runSessionDao().getById(f.sessionId)!!.status)
            f.engine().run()
            assertEquals("CLOSED", f.repo.getAttempt(31)!!.aplusState)
            assertEquals("succeeded", f.repo.getAttempt(31)!!.status)
            assertEquals(listOf(original), f.advances)
            assertEquals(original, f.repo.getAdvanceReplayRequest(31))
            assertEquals(listOf(f.sessionId), f.db.runSessionDao().getAllSessions().first().map { it.id })
            assertFalse(f.accesses.any { it in setOf("apply", "release", "preflight") })
        }
    }

    @Test fun `legacy census retains falsely terminal owners across every migrated advance crash phase`() = runTest {
        var cases = 0
        for (entrance in listOf(Entrance.PSEUDO_SUCCEEDED, Entrance.PSEUDO_FAILED, Entrance.PSEUDO_INTERRUPTED)) {
            for (exhausted in listOf(false, true)) for (cut in listOf("dispatch", "verification")) {
                Fixture(entrance, Shape.HEALTHY, exhausted).use { f ->
                    f.seed()
                    val original = f.repo.getAdvanceReplayRequest(31)
                    f.failAdvanceOnce = cut == "dispatch"
                    f.failObserveOnce = cut == "verification" && !exhausted
                    f.failReadbackOnce = cut == "verification" && exhausted
                    f.engine().run()
                    val phase = when {
                        cut == "dispatch" -> "ADVANCE_PENDING"
                        exhausted -> "ADVANCE_STATE_READBACK"
                        else -> "ADVANCE_OBSERVING"
                    }
                    assertEquals("$entrance/$exhausted/$cut", phase, f.repo.getAttempt(31)!!.aplusState)
                    assertEquals("a migrated owner cannot disappear because its old status was terminal",
                        listOf(31L), f.repo.findAPlusRecoverableAttempts(f.planId).map { it.id })
                    f.engine().run()
                    assertEquals("CLOSED", f.repo.getAttempt(31)!!.aplusState)
                    assertEquals("succeeded", f.repo.getAttempt(31)!!.status)
                    assertEquals(original, f.repo.getAdvanceReplayRequest(31))
                    assertEquals("a stored receipt prevents redispatch after verification cuts", listOf(original), f.advances)
                    assertTrue(f.repo.findAPlusRecoverableAttempts(f.planId).isEmpty())
                    assertFalse(f.accesses.any { it in setOf("apply", "release", "preflight") })
                    cases++
                }
            }
        }
        println("legacy migrated-owner census: $cases dispatch/observation/readback crash combinations passed")
    }

    @Test fun `migrated legacy provenance still validates locally without replaying the release transition`() = runTest {
        var cases = 0
        for (phase in listOf("ADVANCE_PENDING", "ADVANCE_OBSERVING", "ADVANCE_STATE_READBACK")) {
            for (exhausted in listOf(false, true)) {
                for (shape in listOf(Shape.HEALTHY, Shape.MISSING_BOTH, Shape.KEY_ONLY, Shape.BAD_REQUEST,
                        Shape.UNDER_QUOTA, Shape.NEGATIVE)) {
                    Fixture(Entrance.PSEUDO_FAILED, shape, exhausted).use { f ->
                        f.seed()
                        f.repo.markRecoveryRequired(31, "LEGACY_SOURCE", 1)
                        f.repo.markAplusState(31, phase)
                        val before = f.immutableRows(31)
                        if (shape == Shape.HEALTHY) {
                            val owner = f.repo.getAttempt(31)
                            val audit = f.db.auditEventDao().forAttempt(31)
                            assertTrue(f.repo.validateLegacyRelease(31, 2) is com.example.cellrebelauto.repository.LegacyReleaseValidation.Valid)
                            assertEquals(com.example.cellrebelauto.repository.LegacyReleaseRecovery.NotLegacy,
                                f.repo.recoverLegacyRelease(31, 2))
                            assertEquals(owner, f.repo.getAttempt(31))
                            assertEquals(audit, f.db.auditEventDao().forAttempt(31))
                        } else repeat(2) {
                            f.engine().run()
                            val failure = shape.failure ?: "NON_QUOTA_ADVANCE_STATE"
                            val reason = "LEGACY_RELEASED_AUTHORITY:$failure"
                            assertEquals("RECOVERY_REQUIRED", f.repo.getAttempt(31)!!.aplusState)
                            assertEquals(reason, f.repo.getAttempt(31)!!.failureReason)
                            assertEquals(listOf(
                                "RELEASED->RECOVERY_REQUIRED[LEGACY_SOURCE]",
                                "$phase->RECOVERY_REQUIRED[$reason]"
                            ), f.db.auditEventDao().forAttempt(31).map { event -> event.payloadDigest })
                        }
                        assertEquals(before, f.immutableRows(31))
                        assertTrue(f.accesses.isEmpty())
                        cases++
                    }
                }
            }
        }
        println("legacy migrated-provenance validation: $cases local-only combinations passed")
    }
}
