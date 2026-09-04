package com.example.cellrebelauto.integration.v1

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-logic unit test for the Auto-side G2 §5A plan seeder (fixture FX-G2-10A).
 *
 * Pinned invariants:
 *
 * 1. KB-8 (PR #62 review P1-1): canonical spec v1.62 freezes coordinate
 *    ownership with the provider — Auto does not import/hold/assert
 *    coordinates. The seeder consumes ONLY {order, journeyCaseId,
 *    requiredSuccesses}; the legacy non-null LocationTask coordinate columns
 *    receive the inert placeholder, never fixture values. (The compile-time
 *    half is FixtureItem having no coordinate fields at all.)
 * 2. Structure bind (P1-2): the payload+digest share a caller, so the parser
 *    independently validates the registered structure — exactly 10 items,
 *    contiguous order, profile-N alignment, schedule id, quota sum 17 —
 *    positive against the committed fixture (registered sha256 pinned here),
 *    negative against tampered/truncated/reordered payloads.
 * 3. Attribution: LocationTask has no journeyCaseId — the seed report's
 *    fixtureIndex ↔ taskId map is the only link from a run outcome back to a
 *    fixture journey, and ordering (csvRow/priority = fixtureIndex) is what
 *    keeps Auto task[i] aligned with provider item profile-(i+1).
 */
class APlus10APlanSeedTest {

    private val frozenQuotas = listOf(2, 1, 3, 1, 2, 1, 1, 3, 1, 2)

    private fun itemJson(index: Int, quota: Int, scheduleItemId: String = "profile-$index"): String = """
        {
          "fixtureIndex": $index, "journeyCaseId": "J10A-${"%02d".format(index)}",
          "expectedScheduleItemId": "$scheduleItemId", "requiredSuccesses": $quota,
          "addname": "G2-A10-${"%02d".format(index)} Place", "latitude": ${50.4 + index * 0.001},
          "longitude": ${30.5 + index * 0.001}, "altitude": 150.0, "accuracy": 3.0,
          "tac": ${27100 + index}, "wifiSsid": "G2-A10-${"%02d".format(index)}"
        }
    """.trimIndent()

    private fun payload(
        items: List<String> = (1..10).map { itemJson(it, frozenQuotas[it - 1]) },
        fixtureId: String = "FX-G2-10A",
        scheduleId: String = "qwy-default-schedule",
        declaredTotal: Int = 17,
    ): String = """
        {
          "fixtureId": "$fixtureId",
          "scheduleId": "$scheduleId",
          "totalRequiredSuccesses": $declaredTotal,
          "items": [${items.joinToString(",")}]
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // POSITIVE — committed registered fixture
    // ------------------------------------------------------------------

    @Test
    fun committedFixtureFileParsesAndMatchesTheRegisteredStructure() {
        val moduleRoot = sequenceOf(File("."), File("app"), File("../app"))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
            ?: error("cannot locate the app module root")
        val bytes = File(moduleRoot, "../../../docs/acceptance/a-plus-10a-fixture.json")
            .normalize().readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "committed fixture must be the registered frozen bytes",
            "cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852",
            sha,
        )

        val items = APlus10APlanSeed.parsePayload(String(bytes, Charsets.UTF_8))
        assertEquals(10, items.size)
        assertEquals((1..10).map { "profile-$it" }, items.map { it.expectedScheduleItemId })
        assertEquals(17, items.sumOf { it.requiredSuccesses })
        assertEquals("frozen quota vector", listOf(2, 1, 3, 1, 2, 1, 1, 3, 1, 2), items.map { it.requiredSuccesses })
    }

    @Test
    fun committedFileSha_feedsThroughRequireRegisteredDigest() {
        // R4 P2: feed the COMPUTED committed-file SHA through the pin so a drift
        // of the runtime constant away from the file goes red here (the byte
        // literal / self-pin tests could both stay green under a constant-only
        // mutation).
        val moduleRoot = sequenceOf(File("."), File("app"), File("../app"))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
            ?: error("cannot locate the app module root")
        val bytes = File(moduleRoot, "../../../docs/acceptance/a-plus-10a-fixture.json").normalize().readBytes()
        val computed = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        APlus10APlanSeed.requireRegisteredDigest(computed, computed)
    }

    // ------------------------------------------------------------------
    // KB-8 — no fixture coordinates reach Auto
    // ------------------------------------------------------------------

    @Test
    fun kb8_tasksCarryOnlyThePlaceholderCoordinates() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val tasks = APlus10APlanSeed.toTasks(items)
        assertEquals(10, tasks.size)
        tasks.forEach { task ->
            assertEquals(
                "KB-8: Auto must not import fixture latitudes — legacy column gets the placeholder",
                APlus10APlanSeed.COORDINATE_PLACEHOLDER, task.latitude, 0.0,
            )
            assertEquals(
                "KB-8: Auto must not import fixture longitudes — legacy column gets the placeholder",
                APlus10APlanSeed.COORDINATE_PLACEHOLDER, task.longitude, 0.0,
            )
        }
        // Order binding survives without coordinates.
        assertEquals((1..10).toList(), tasks.map { it.csvRow })
        assertEquals((1..10).toList(), tasks.map { it.priority })
        assertEquals(frozenQuotas, tasks.map { it.requiredSuccesses })
    }

    @Test
    fun placeholder_isStructurallyOutOfGeographicDomain() {
        // PR #62 R3 P3: assert the SEMANTIC property (out of the legal lat/lng
        // domain), not merely == the constant — the earlier self-test stayed
        // green if the constant were changed back to 0.0 (a real place). The
        // dispatch hard constraint requires a value that cannot be mistaken for
        // a real target.
        val p = APlus10APlanSeed.COORDINATE_PLACEHOLDER
        assertTrue(
            "placeholder $p must be outside lat [-90,90] AND lng [-180,180] on both axes",
            p < -90.0 || p > 90.0,
        )
        assertTrue("placeholder must also be outside the longitude domain", p < -180.0 || p > 180.0)
    }

    @Test
    fun toPlan_totalRequiredSuccessesIsTheSum() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items, globalBufferSeconds = 60)
        assertEquals(10, plan.totalRows)
        assertEquals(17, plan.totalRequiredSuccesses)
        assertEquals("FX-G2-10A", plan.sourceFileName)
    }

    // ------------------------------------------------------------------
    // verifyPlanTopology — start_run binds to the SEEDED plan (P2)
    // ------------------------------------------------------------------

    @Test
    fun verifyPlanTopology_acceptsTheSeededPlan() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items)
        val tasks = APlus10APlanSeed.toTasks(items)
        assertNull("the seeded FX-G2-10A plan must verify", APlus10APlanSeed.verifyPlanTopology(plan, tasks))
    }

    @Test
    fun verifyPlanTopology_rejectsForeignSource() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items).copy(sourceFileName = "user-import.csv")
        assertNotNull(APlus10APlanSeed.verifyPlanTopology(plan, APlus10APlanSeed.toTasks(items)))
    }

    @Test
    fun verifyPlanTopology_rejectsRealCoordinates() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items)
        // A CSV-imported plan with the same shape but REAL coordinates must be
        // refused — it is not this KB-8 seeder's output.
        val csvTasks = APlus10APlanSeed.toTasks(items).mapIndexed { i, t ->
            t.copy(latitude = 50.4 + i * 0.001, longitude = 30.5 + i * 0.001)
        }
        val mismatch = APlus10APlanSeed.verifyPlanTopology(plan, csvTasks)
        assertNotNull(mismatch)
        assertTrue(mismatch!!.contains("real coordinates"))
    }

    @Test
    fun verifyPlanTopology_rejectsWrongRowCount() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items)
        assertNotNull(APlus10APlanSeed.verifyPlanTopology(plan, APlus10APlanSeed.toTasks(items).dropLast(1)))
    }

    @Test
    fun verifyPlanTopology_rejectsSameTotalQuotaRedistribution() {
        // R4 P1-4: a plan whose per-item quotas are a same-total redistribution
        // (items 1↔2 swapped) has total 17 and every other current predicate
        // true — start_run must still refuse it (wrong per-address attribution).
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items)
        val tasks = APlus10APlanSeed.toTasks(items).toMutableList()
        val t0 = tasks[0]; val t1 = tasks[1]
        tasks[0] = t0.copy(requiredSuccesses = t1.requiredSuccesses)
        tasks[1] = t1.copy(requiredSuccesses = t0.requiredSuccesses)
        val mismatch = APlus10APlanSeed.verifyPlanTopology(plan, tasks)
        assertNotNull("same-total quota redistribution must be refused", mismatch)
        assertTrue(mismatch!!.contains("registered"))
    }

    // ------------------------------------------------------------------
    // NEGATIVES — one structural mutation each (P1-2)
    // ------------------------------------------------------------------

    private fun assertRejected(reason: String, json: String) {
        try {
            APlus10APlanSeed.parsePayload(json)
            fail("parser must reject: $reason")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsTruncatedFixture() = assertRejected(
        "9 items",
        payload(items = (1..9).map { itemJson(it, frozenQuotas[it - 1]) }, declaredTotal = 15),
    )

    @Test
    fun rejectsReorderedItems() {
        val items = (1..10).map { itemJson(it, frozenQuotas[it - 1]) }
        assertRejected("items 1/2 swapped", payload(items = listOf(items[1], items[0]) + items.drop(2)))
    }

    @Test
    fun rejectsTamperedQuota() = assertRejected(
        "sum 18",
        payload(items = (1..10).map { itemJson(it, if (it == 2) 2 else frozenQuotas[it - 1]) }, declaredTotal = 18),
    )

    @Test
    fun rejectsSameTotalQuotaRedistribution() {
        // R4 P1-4: swap items 1↔2 quotas (sum still 17) — a sum-only check would
        // pass; the exact ordered vector must reject it.
        val swapped = frozenQuotas.toMutableList().also { it[0] = frozenQuotas[1]; it[1] = frozenQuotas[0] }
        assertRejected(
            "items 1↔2 quota redistribution (same total)",
            payload(items = (1..10).map { itemJson(it, swapped[it - 1]) }),
        )
    }

    @Test
    fun rejectsQuotaSumDisagreeingWithDeclaredTotal() = assertRejected("declared 16", payload(declaredTotal = 16))

    @Test
    fun rejectsWrongScheduleId() = assertRejected("foreign schedule", payload(scheduleId = "other"))

    @Test
    fun rejectsWrongFixtureId() = assertRejected("foreign fixtureId", payload(fixtureId = "FX-OTHER"))

    @Test
    fun rejectsMisalignedScheduleItemId() = assertRejected(
        "item 3 → profile-7",
        payload(items = (1..10).map { itemJson(it, frozenQuotas[it - 1], if (it == 3) "profile-7" else "profile-$it") }),
    )

    // ------------------------------------------------------------------
    // seedReport — attribution map
    // ------------------------------------------------------------------

    @Test
    fun seedReport_emitsFixtureIndexToTaskIdMap() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val report = APlus10APlanSeed.seedReport(
            items = items,
            planId = 7L,
            taskIds = (101L..110L).toList(),
            fixtureDigest = "cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852",
        )
        assertTrue(report.contains("fixtureIndex=1"))
        assertTrue(report.contains("taskId=101"))
        assertTrue(report.contains("J10A-01"))
        assertTrue(report.contains("planId=7"))
        assertTrue(report.contains("NOT consumed (KB-8"))
        assertTrue(report.contains("cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852"))
    }

    // ------------------------------------------------------------------
    // startRunVerdict — request-owned durable-start generation (R6 P1-2 → R7 P1-2)
    // ------------------------------------------------------------------

    private fun row(id: Long, planId: Long?, status: String = "running") =
        APlus10APlanSeed.NewSessionRow(id, planId, status)

    @Test
    fun startRunVerdict_staleSamePlanRun_noNewSession_isNotStarted() {
        // A paused/crashed same-plan A+ attempt keeps old rows nonterminal and
        // isRunning may even be true — but NO NEW session (id > pre-max) means
        // THIS request started nothing.
        val v = APlus10APlanSeed.startRunVerdict(
            newSessions = emptyList(), requestedPlanId = 7L, firstAttemptId = null)
        assertEquals(APlus10APlanSeed.StartRunVerdict.NoNewSession, v)
    }

    @Test
    fun startRunVerdict_newSessionRightPlanWithMilestone_isStarted() {
        // The ONLY green path: exactly one new right-plan session AND a durable
        // first-attempt row bound to it.
        val v = APlus10APlanSeed.startRunVerdict(
            newSessions = listOf(row(42L, 7L)), requestedPlanId = 7L, firstAttemptId = 900L)
        assertEquals(APlus10APlanSeed.StartRunVerdict.Started(sessionId = 42L, planId = 7L, firstAttemptId = 900L), v)
    }

    @Test
    fun startRunVerdict_newRightPlanSessionButNoAttemptYet_isAwaitingMilestone_notStarted() {
        // R7 P1-2: the Engine creates the session BEFORE provider discovery.
        // A right-plan session with no attempt yet is NOT started — the caller
        // keeps polling, it never prints RUN_STARTED on the bare session.
        val v = APlus10APlanSeed.startRunVerdict(
            newSessions = listOf(row(42L, 7L, status = "running")), requestedPlanId = 7L, firstAttemptId = null)
        assertEquals(APlus10APlanSeed.StartRunVerdict.AwaitingMilestone, v)
    }

    @Test
    fun startRunVerdict_zeroAttemptCompletedSession_isDegenerate_notStarted() {
        // Sol R7: an already-complete plan can instantly complete a zero-attempt
        // session; discovery failure can pause one. Either is a typed failure,
        // never RUN_STARTED.
        val completed = APlus10APlanSeed.startRunVerdict(
            newSessions = listOf(row(42L, 7L, status = "completed")), requestedPlanId = 7L, firstAttemptId = null)
        assertEquals(APlus10APlanSeed.StartRunVerdict.DegenerateSession(42L, "completed"), completed)
        val paused = APlus10APlanSeed.startRunVerdict(
            newSessions = listOf(row(43L, 7L, status = "paused")), requestedPlanId = 7L, firstAttemptId = null)
        assertEquals(APlus10APlanSeed.StartRunVerdict.DegenerateSession(43L, "paused"), paused)
    }

    @Test
    fun startRunVerdict_twoNewSessions_isAmbiguous_neverAttributed() {
        // R7 P1-2 cardinality: two starters raced and both created sessions —
        // attribution is ambiguous, so NEITHER is claimed even if one matches
        // the plan and has a milestone. (The activity's single-flight lock
        // prevents this in-process; the verdict fails closed if it ever occurs.)
        val v = APlus10APlanSeed.startRunVerdict(
            newSessions = listOf(row(42L, 7L), row(43L, 7L)), requestedPlanId = 7L, firstAttemptId = 900L)
        val a = v as? APlus10APlanSeed.StartRunVerdict.AmbiguousNewSessions ?: error("expected Ambiguous, got $v")
        assertEquals(listOf(42L, 43L), a.sessionIds)
    }

    @Test
    fun startRunVerdict_raceLoserCannotBorrowWinnerSession() {
        // The concrete Sol counterexample: A wins and created session 42 (plan
        // 7, milestone present). Loser B snapshotted the SAME pre-max (41) but
        // was rejected by startWithPlan as "Already running". Because the
        // activity holds B's whole attempt behind the single-flight lock, B's
        // pre-max is re-evaluated AFTER A's verdict — so from B's fence (42)
        // there is NO new session and B gets NoNewSession, never A's RUN_STARTED.
        val bView = APlus10APlanSeed.startRunVerdict(
            newSessions = emptyList(), requestedPlanId = 7L, firstAttemptId = null)
        assertEquals(APlus10APlanSeed.StartRunVerdict.NoNewSession, bView)
    }

    @Test
    fun startRunVerdict_newSessionWrongPlan_isConflictNotStarted() {
        val v = APlus10APlanSeed.startRunVerdict(
            newSessions = listOf(row(42L, 9L)), requestedPlanId = 7L, firstAttemptId = 900L)
        val c = v as? APlus10APlanSeed.StartRunVerdict.WrongPlanSession ?: error("expected WrongPlanSession, got $v")
        assertEquals(42L, c.sessionId)
        assertEquals(9L, c.sessionPlanId)
    }

    @Test
    fun startRunVerdict_newLegacySessionNullPlan_isConflictNotStarted() {
        // A legacy/null-plan session cannot be claimed — checked BEFORE the
        // milestone, so even a present attempt does not rescue it.
        val v = APlus10APlanSeed.startRunVerdict(
            newSessions = listOf(row(42L, null)), requestedPlanId = 7L, firstAttemptId = 900L)
        assertTrue(v is APlus10APlanSeed.StartRunVerdict.WrongPlanSession)
    }

    @Test
    fun planBindingMismatch_flagsTaskVsSessionLegDivergence() {
        // R6 P1-2 "mismatched task/session plan legs": a running attempt whose
        // task belongs to plan 7 but whose session belongs to plan 9 is a
        // mis-attributed row and must be flagged, never silently accepted.
        assertNull(APlus10APlanSeed.planBindingMismatch(taskPlanId = 7L, sessionPlanId = 7L))
        assertNotNull(APlus10APlanSeed.planBindingMismatch(taskPlanId = 7L, sessionPlanId = 9L))
        assertNotNull(APlus10APlanSeed.planBindingMismatch(taskPlanId = 7L, sessionPlanId = null))
    }

    @Test
    fun seedReport_failsWhenTaskCountDoesNotMatch() {
        val items = APlus10APlanSeed.parsePayload(payload())
        try {
            APlus10APlanSeed.seedReport(items, planId = 7L, taskIds = listOf(101L), fixtureDigest = "x")
            fail("a taskId list shorter than the fixture must be rejected — unattributable seed")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // PR #62 P1-1 — registered-digest pin (Auto runtime path)
    // ------------------------------------------------------------------

    @Test
    fun requireRegisteredDigest_acceptsTheRegisteredPin() {
        APlus10APlanSeed.requireRegisteredDigest(
            APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST,
            APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST,
        )
    }

    /** changed-bytes + recomputed self-consistent digest: still rejected by the pin. */
    @Test
    fun requireRegisteredDigest_rejectsChangedBytes() {
        val fabricatedHash = MessageDigest.getInstance("SHA-256")
            .digest("fabricated FX-G2-10A payload with a same-total quota swap".toByteArray())
            .joinToString("") { "%02x".format(it) }
        try {
            APlus10APlanSeed.requireRegisteredDigest(fabricatedHash, fabricatedHash)
            fail("a payload that does not hash to the registered digest must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    /** caller-substituted registration: real bytes, foreign declared digest — rejected. */
    @Test
    fun requireRegisteredDigest_rejectsCallerSubstitutedDeclaration() {
        try {
            APlus10APlanSeed.requireRegisteredDigest(
                APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST,
                "0000000000000000000000000000000000000000000000000000000000000000",
            )
            fail("the caller may not substitute its own declared digest")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // observeStartReceipt / onlyIfAccepted — R8 P1-2 (Option D): the start
    // receipt is OBSERVED after the product call, never predicted before it.
    //
    // Product facts (AutomationService; pinned by P10CollectorSurfaceGuardTest):
    //   reject branch  → addLog("Already running, ignoring start request"); return
    //   accept branch  → _isRunning.value = true, NO log before launch
    //   addLog         → "[HH:mm:ss] $message", published on the public `logs`
    //   engine forwarder OVERWRITES `_logs` wholesale once a run's engine exists
    // ------------------------------------------------------------------

    private fun stamped(message: String) = "[12:34:56] $message"
    private val rejectEntry = stamped(APlus10APlanSeed.PRODUCT_REJECT_SENTINEL)
    private val l0 = listOf(stamped("Accessibility service connected"), stamped("A+ provider service bind requested"))

    @Test
    fun observeStartReceipt_unchangedLogsAndRunning_isAcceptedObserved() {
        // The accept branch publishes nothing and flips isRunning synchronously
        // before startAutomation returns: unchanged logs + running == the
        // product's check-and-set accepted THIS call (happens-before, not poll).
        val r = APlus10APlanSeed.observeStartReceipt(logsBefore = l0, logsAfter = l0.toList(), runningAfter = true)
        assertEquals(APlus10APlanSeed.StartReceipt.AcceptedObserved, r)
    }

    @Test
    fun observeStartReceipt_unchangedLogsButNotRunning_isNotAccepted() {
        // startAutomation with instance == null is a Log.e no-op: it publishes
        // NOTHING on `logs`. "Unchanged" alone would read as accept — the
        // isRunning leg (set synchronously only by the accept branch) is required.
        val r = APlus10APlanSeed.observeStartReceipt(logsBefore = l0, logsAfter = l0.toList(), runningAfter = false)
        assertTrue("a no-op call must not be accepted, got $r", r is APlus10APlanSeed.StartReceipt.Indeterminate)
    }

    @Test
    fun observeStartReceipt_exactlyOneRejectEntryAppended_isRejected() {
        val r = APlus10APlanSeed.observeStartReceipt(logsBefore = l0, logsAfter = l0 + rejectEntry, runningAfter = true)
        assertEquals(APlus10APlanSeed.StartReceipt.RejectedAlreadyRunning, r)
    }

    @Test
    fun observeStartReceipt_emptyBeforeThenReject_isRejected() {
        val r = APlus10APlanSeed.observeStartReceipt(logsBefore = emptyList(), logsAfter = listOf(rejectEntry), runningAfter = true)
        assertEquals(APlus10APlanSeed.StartReceipt.RejectedAlreadyRunning, r)
    }

    @Test
    fun observeStartReceipt_forwarderClobber_isIndeterminate_neverAccepted() {
        // THE clobber: the product rejected us (sentinel appended), then the
        // foreign run's engine forwarder overwrote `_logs` with ITS list — the
        // sentinel is GONE. "Sentinel absent ⇒ accepted" would flip a true
        // REJECT into a false ACCEPT (fail-open). The shape is neither
        // unchanged nor one-appended, so it must be indeterminate.
        val clobbered = listOf(stamped("Engine: plan #7 loaded"), stamped("Engine: task 1/10"))
        val r = APlus10APlanSeed.observeStartReceipt(logsBefore = l0, logsAfter = clobbered, runningAfter = true)
        assertTrue("a clobbered window must be indeterminate, got $r", r is APlus10APlanSeed.StartReceipt.Indeterminate)
        assertFalse(r is APlus10APlanSeed.StartReceipt.AcceptedObserved)
    }

    @Test
    fun observeStartReceipt_rejectPlusAnotherEntry_isIndeterminate() {
        val r = APlus10APlanSeed.observeStartReceipt(
            logsBefore = l0, logsAfter = l0 + rejectEntry + stamped("Stopping automation..."), runningAfter = true)
        assertTrue(r is APlus10APlanSeed.StartReceipt.Indeterminate)
    }

    @Test
    fun observeStartReceipt_appendedEntryIsNotTheProductRejectLine_isIndeterminate() {
        // Same text without the product's "[HH:mm:ss] " stamp, a near-miss
        // text, and the coroutine's ERROR line: none is the reject entry.
        val unstamped = APlus10APlanSeed.observeStartReceipt(l0, l0 + APlus10APlanSeed.PRODUCT_REJECT_SENTINEL, true)
        assertTrue(unstamped is APlus10APlanSeed.StartReceipt.Indeterminate)
        val nearMiss = APlus10APlanSeed.observeStartReceipt(l0, l0 + stamped("Already running"), true)
        assertTrue(nearMiss is APlus10APlanSeed.StartReceipt.Indeterminate)
        val errorLine = APlus10APlanSeed.observeStartReceipt(l0, l0 + stamped("ERROR: plan #7 not found"), false)
        assertTrue(errorLine is APlus10APlanSeed.StartReceipt.Indeterminate)
    }

    @Test
    fun observeStartReceipt_prefixDriftWithRejectTail_isIndeterminate() {
        // addLog trims to the last 200 entries: at the cap a real reject drops
        // the oldest entry. That shape is NOT recognised on purpose — without
        // knowing the cap it is indistinguishable from an overwritten window,
        // so it fails closed (RUN_NOT_STARTED, never a false accept).
        val r = APlus10APlanSeed.observeStartReceipt(logsBefore = l0, logsAfter = l0.drop(1) + rejectEntry, runningAfter = true)
        assertTrue(r is APlus10APlanSeed.StartReceipt.Indeterminate)
    }

    @Test
    fun solR8Counterexample_uiWinsSamePlan_harnessRejected_neverPollsNeverStarted() {
        // Sol R8 P1-2, step by step, against the canonical product entry's
        // PUBLISHED verdict (not an emptyList() stand-in):
        //   1. harness fast path sees isRunning=false, pre-max M=41
        //   2. the UI starts the SAME plan: the product accepts (no log,
        //      isRunning=true) and creates session 42 + its first attempt
        //   3. the harness's product call is rejected: exactly one reject entry
        //      is appended to the public logs
        //   4. session 42 > M with a milestone now exists — the OLD harness
        //      polled it and printed RUN_STARTED for its rejected request.
        val receipt = APlus10APlanSeed.observeStartReceipt(logsBefore = l0, logsAfter = l0 + rejectEntry, runningAfter = true)
        assertEquals(APlus10APlanSeed.StartReceipt.RejectedAlreadyRunning, receipt)
        var polled = false
        val verdict = APlus10APlanSeed.onlyIfAccepted(receipt) {
            polled = true
            APlus10APlanSeed.startRunVerdict(
                newSessions = listOf(row(42L, 7L)), requestedPlanId = 7L, firstAttemptId = 900L)
        }
        assertFalse("a rejected request must never poll the generation", polled)
        assertNull("a rejected request has no verdict — RUN_NOT_STARTED", verdict)
    }

    @Test
    fun onlyIfAccepted_pollsOnlyAfterAnObservedAccept() {
        var polled = 0
        val ok = APlus10APlanSeed.onlyIfAccepted(APlus10APlanSeed.StartReceipt.AcceptedObserved) { polled++; "v" }
        assertEquals("v", ok)
        assertEquals(1, polled)
        val ind = APlus10APlanSeed.onlyIfAccepted(APlus10APlanSeed.StartReceipt.Indeterminate("x")) { polled++; "v" }
        assertNull(ind)
        assertEquals(1, polled)
    }
}
