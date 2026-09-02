package name.caiyao.fakegps.mockprovider

import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.repository.ProfileRepository
import java.security.MessageDigest

/** Shell-only debug acceptance seam. It never ships in release and only mutates .bench data. */
class MockProviderAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_PREPARE_KYIV -> prepareKyiv()
            COMMAND_PREPARE_10A -> prepare10a()
            COMMAND_STOP -> {
                MockProviderRuntime.useHookAndStopSystemMock(applicationContext)
                complete(COMMAND_STOP)
            }
            else -> complete("rejected")
        }
    }

    private fun prepareKyiv() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val repository = ProfileRepository(
                    AppDatabase.getInstance(applicationContext),
                    applicationContext,
                )
                repository.deleteAll()
                repository.save(
                    ProfileEntity(
                        addname = "Kyiv acceptance",
                        latitude = KYIV_LATITUDE,
                        longitude = KYIV_LONGITUDE,
                        altitude = KYIV_ALTITUDE,
                        accuracy = 3f,
                        tac = 27101,
                        wifiSsid = "Kyiv-Acceptance",
                    ),
                )
                val settings = SpoofSettings.getInstance(applicationContext)
                settings.setLocationDeliveryMode(LocationDeliveryMode.HOOK)
                settings.setMockProviderCleanupRequired(false)
                ConfigPrefsSync.sync(applicationContext)
            }
            complete(COMMAND_PREPARE_KYIV)
        }
    }

    /**
     * G2 §5A 10-address seed (fixture FX-G2-10A). Mirrors [prepareKyiv]'s
     * shell-only, .bench-only discipline but seeds the 10 frozen journeys with
     * EXPLICIT ids (see [APlus10AFixtureSeed] — the autoGenerate/deleteAll drift
     * this defends against). The consumed payload IS the frozen fixture JSON, so
     * the SHA-256 this seeder recomputes over the decoded bytes must equal the
     * `fixture_digest` the executor recorded over the file — proof that what was
     * seeded is byte-identical to the frozen fixture.
     *
     *   --es command prepare_10a
     *   --es fixture_payload_base64 <base64 of a-plus-10a-fixture.json>
     *   --es fixture_digest <sha256 the executor recorded over that file>
     */
    private fun prepare10a() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { seed10a() } }
            result.fold(
                onSuccess = { report ->
                    Log.i(TAG, report)
                    // R4 P1-1 / gap⑦: this seed proves only its LOCAL legs
                    // (digest pin, structure+quota vector, explicit id, monotonic
                    // owner-quiescent generation, publish). The §3 seed contract
                    // ALSO requires an ordered profile-1..10 discover() readback
                    // that has NO executable command today
                    // (EnvironmentControlHandler profileRefs=emptyList). So this
                    // deliberately does NOT emit the full-seed-PASS "READY" marker
                    // a complete seed would — a READY here would be the exact
                    // false green opus5 ruled blocks merge pending operator scope.
                    // Distinct markers: local legs proven, contract still open.
                    Log.i(TAG, "SEED_LOCAL_VERIFIED command=$COMMAND_PREPARE_10A")
                    Log.i(
                        TAG,
                        "SEED_CONTRACT_INCOMPLETE command=$COMMAND_PREPARE_10A gap=7 " +
                            "reason=ordered-discover-readback-unavailable " +
                            "(profileRefs=emptyList; needs authorized projection). NOT a full §3 seed PASS.",
                    )
                    finish()
                },
                onFailure = { e ->
                    // P1-3: a failed seed must NOT emit any success marker.
                    Log.i(TAG, "SEED_FAILED command=$COMMAND_PREPARE_10A ${e::class.java.simpleName}: ${e.message}")
                    finish()
                },
            )
        }
    }

    private suspend fun seed10a(): String {
        val payloadB64 = intent.getStringExtra(EXTRA_FIXTURE_PAYLOAD_B64)
        require(payloadB64 != null) { "missing --es $EXTRA_FIXTURE_PAYLOAD_B64" }
        val declaredDigest = intent.getStringExtra(EXTRA_FIXTURE_DIGEST)
        require(declaredDigest != null) { "missing --es $EXTRA_FIXTURE_DIGEST" }

        val decoded = Base64.decode(payloadB64, Base64.DEFAULT)
        val computedDigest = sha256Hex(decoded)
        // PR #62 P1-1: pin to the REGISTERED digest, not a caller-supplied one.
        // The recomputed digest must equal the frozen registration (any byte
        // edit fails) AND the caller's declared digest must equal it too (the
        // caller may not register its own). Structure is still independently
        // validated by parsePayload below; the digest pin is what covers the
        // per-item vector the structure bind cannot.
        APlus10AFixtureSeed.requireRegisteredDigest(computedDigest, declaredDigest)

        val items = APlus10AFixtureSeed.parsePayload(String(decoded, Charsets.UTF_8))
        val rows = APlus10AFixtureSeed.toProfileRows(items)

        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.profileDao()
        // R6 P1 — REAL owner serialization (supersedes the R4/R5 observational
        // probes, which Sol defeated with concrete interleavings: debug
        // surfaces boot the handler without the service; handler CONSTRUCTION
        // can reinit the schedule with no audit row; runRevokedLeaseCleanup is
        // fenced but appends no audit row).
        //
        // 1. Boot FIRST: ProviderRuntime.handler() is bootLock-serialized and
        //    returns only after construction (incl. the controller schedule
        //    reinit) completed — construction cannot straddle the seed. NOTE:
        //    booting runs §8.4 recovery; that is correct for a SEED (the
        //    "never boot" rule protects the fault collector's EVIDENCE dumps,
        //    which still never boot).
        // 2. Hold the SAME monitor withOwnerFence synchronizes on
        //    (APlus10AOwnerFence.lockOf, reflection; pinned by the surface
        //    guard + a latch-driven race test) across the ENTIRE
        //    reset/profile/publish region: every fenced owner op — apply,
        //    release, advance, revoke cleanup — blocks until the seed exits.
        //    Real mutual exclusion, not a timing observation.
        // R3 stays: never clear() (version-1 rollback, M-AD-24 / L1895-2056).
        // R4 stays: PARTIAL prior stores are fail-closed, never laundered.
        val ownerHandler = name.caiyao.fakegps.integration.v1.ProviderRuntime.handler(applicationContext)
        val ownerLock = APlus10AOwnerFence.lockOf(ownerHandler)
        val schedulePrefs = applicationContext
            .getSharedPreferences(APlus10AScheduleReset.PREFS_NAME, MODE_PRIVATE)

        return synchronized(ownerLock) {
        // Preconditions under the held fence + audit-seq belt (defense-in-depth;
        // the LOCK is the serialization proof, the seq is a tripwire).
        val auditSeqBefore = fencedPreconditionOrThrow("before write")

        // Classify the prior state from durable keys — an absent version key
        // over surviving keys is Partial (corrupt), not "version 0".
        val presentKeys = APlus10AScheduleReset.GENERATION_KEYS
            .filter { schedulePrefs.contains(it) }.toSet()
        val storedVersion = if (schedulePrefs.contains(APlus10AScheduleReset.KEY_SCHEDULE_VERSION)) {
            runCatching { schedulePrefs.getLong(APlus10AScheduleReset.KEY_SCHEDULE_VERSION, Long.MIN_VALUE) }
                .getOrNull()?.takeIf { it != Long.MIN_VALUE }
        } else null
        val priorState = APlus10AScheduleReset.classifyPriorState(presentKeys, storedVersion)
        val resetPlan = APlus10AScheduleReset.plan(
            prior = priorState,
            itemIds = rows.map { "${APlus10AFixtureSeed.SCHEDULE_ITEM_PREFIX}${it.id}" },
        )
        val committed = schedulePrefs.edit()
            .putString(APlus10AScheduleReset.KEY_SCHEDULE_ID, resetPlan.scheduleId)
            .putLong(APlus10AScheduleReset.KEY_SCHEDULE_VERSION, resetPlan.scheduleVersion)
            .putString(APlus10AScheduleReset.KEY_ITEM_IDS, APlus10AScheduleReset.encodeItemIds(resetPlan.itemIds))
            .putString(APlus10AScheduleReset.KEY_CURRENT_ITEM_ID, resetPlan.currentItemId)
            .putBoolean(APlus10AScheduleReset.KEY_EXHAUSTED, resetPlan.exhausted)
            .putLong(APlus10AScheduleReset.KEY_ADVANCE_COUNT, resetPlan.advanceCount)
            .remove(APlus10AScheduleReset.KEY_LAST_APPLIED_LAT)
            .remove(APlus10AScheduleReset.KEY_LAST_APPLIED_LNG)
            .remove(APlus10AScheduleReset.KEY_LAST_APPLIED_AT)
            .remove(APlus10AScheduleReset.KEY_LAST_APPLIED_VERIFIED)
            .commit()
        check(committed) { "schedule generation write did not commit — stale generation would survive" }
        // Readback through a fresh handle: what is durably stored, not the editor's echo.
        val readBack = readBackSchedule()
        APlus10AScheduleReset.verifyReadback(readBack, resetPlan)?.let { mismatch ->
            throw IllegalStateException("schedule generation readback mismatch — $mismatch")
        }
        // Mid-bracket precondition re-check (belt; the lock is held throughout).
        fencedPreconditionOrThrow("after write")

        // Isolated .bench data only (same seam as prepare_kyiv): clear then insert
        // with EXPLICIT ids so expectedScheduleItemId=profile-N stays byte-exact.
        // runBlocking: suspension points are illegal inside a critical section,
        // and BLOCKING this IO thread while holding the owner monitor is exactly
        // the semantics we want — the fence stays held across the DB rewrite.
        val insertedIds = kotlinx.coroutines.runBlocking {
            dao.deleteAll()
            dao.insertAll(rows)
        }

        // Same profile posture prepare_kyiv established and G1 C5 run-2 verified:
        // HOOK delivery + no forced mock-provider cleanup. The A-block EC provider
        // resolves coordinates from the temp table directly; this keeps the
        // hook/self-check diagnostics identical to the proven single-address run.
        val settings = SpoofSettings.getInstance(applicationContext)
        check(settings.setLocationDeliveryMode(LocationDeliveryMode.HOOK)) { "could not set HOOK delivery mode" }
        check(settings.setMockProviderCleanupRequired(false)) { "could not clear mock-provider cleanup flag" }
        // Publish the first schedule item (profile-1) so --current-profile and the
        // hook self-check load a valid schema payload; the schedule itself is
        // rebuilt from the temp table on the next provider process start.
        // PR #62 P1-3: the publish outcome is load-bearing — sync() returning
        // false means the hook-visible transport does NOT carry the seeded
        // profile, and a READY over that state is a false green.
        val published = ConfigPrefsSync.sync(applicationContext, profileId = insertedIds.firstOrNull())
        check(published) { "ConfigPrefsSync.sync returned false — seeded profile not published to the hook transport" }

        // R5 P1 — END-OF-SEED fail-closed bracket over the WHOLE transaction
        // (reset + profile rewrite + publish). Observational timing checks
        // cannot eliminate TOCTOU; these are the owner's own DURABLE side
        // effects, which a racing fenced write cannot avoid leaving:
        //   1. audit seq unchanged  → no fenced owner op (apply/release/
        //      advance/revoke all append) ran anywhere inside the seed;
        //   2. quiescence still holds (service down, lease converged,
        //      ADVANCE_PENDING still empty) → nothing is queued to replay;
        //   3. schedule generation still reads back EXACTLY as planned → no
        //      boot-reinit rewrote it (e.g. off a mid-rewrite profile table).
        // Any mutation between "before write" and here trips at least one.
        val auditSeqEnd = fencedPreconditionOrThrow("end of seed")
        check(auditSeqEnd == auditSeqBefore) {
            "owner audit seq moved ($auditSeqBefore → $auditSeqEnd) during the seed — a fenced " +
                "owner mutation interleaved; the seeded generation cannot be trusted"
        }
        APlus10AScheduleReset.verifyReadback(readBackSchedule(), resetPlan)?.let { mismatch ->
            throw IllegalStateException("end-of-seed schedule drift — $mismatch")
        }

        // R7 P1-1 (Sol): the ownerLock serializes only EnvironmentControlHandler's
        // OWN fenced ops. prepareKyiv / ProfileRepository / the settings UI mutate
        // the SAME profile table + transport WITHOUT that lock, so the checks
        // above (lease/pending/audit/schedule) would stay green over rewritten
        // profile bytes. SEED_LOCAL_VERIFIED must therefore terminally re-read
        // the FULL written domain and prove every byte is still exactly what the
        // seed committed. Concurrency is additionally excluded operationally by
        // the runbook's pre-seed force-stop + fresh-PID single-flight gate (no
        // other writer is alive during the sole seed launch); this readback is
        // the in-process proof that closes the residual window.
        verifyWrittenDomainOrThrow(dao, rows, settings)

        // Throws (IllegalStateException) if any inserted id drifted from the
        // explicit fixture id — a green mapping over a mismatch is forbidden.
        // PR #62 P1-1: the report emits only the independently verified
        // REGISTERED digest, never the caller-declared value.
        APlus10AFixtureSeed.seedReport(items, insertedIds, APlus10AFixtureSeed.REGISTERED_FIXTURE_DIGEST) +
            "SCHEDULE_GENERATION priorState=${priorState::class.simpleName} versionAfter=${resetPlan.scheduleVersion} " +
            "pointer=${resetPlan.currentItemId} exhausted=${resetPlan.exhausted} " +
            "(monotonic V+1, owner-fenced, schedule + full-domain readback verified)\n" +
            "NEXT: force-stop $QWY_BENCH_HINT then bind; readback via discover(): currentItemId=profile-1 and " +
            "scheduleVersion=${resetPlan.scheduleVersion} (the executable subset — the full ordered profile-1..10 " +
            "list readback awaits the gap⑦ profileRefs projection scope decision)"
        } // synchronized(ownerLock)
    }

    /**
     * R6 P1 — seed preconditions read UNDER the held owner fence, via
     * QwyDurableSnapshot (fresh FileDurableKv, pure reads). With the real
     * monitor held, no fenced owner op can interleave; these check the durable
     * states that make a seed semantically safe anyway (pending advance would
     * replay at the NEXT fenced entry; a non-converged lease references the
     * old generation). Returns the audit seq as defense-in-depth only — the
     * held lock, not the seq, is the serialization proof.
     */
    private fun fencedPreconditionOrThrow(phase: String): Long {
        val snap = name.caiyao.fakegps.integration.v1.QwyDurableSnapshot
            .capture(name.caiyao.fakegps.integration.v1.QwyDurableSnapshot.durableDir(applicationContext))
        APlus10AScheduleReset.fencedSeedPreconditionMismatch(
            blockingLeaseState = snap.lease.leaseState,
            advancePendingPresent = snap.advancePendingRaw != null,
        )?.let { reason ->
            throw IllegalStateException("schedule reset refused ($phase): $reason")
        }
        return snap.maxAuditSeq
    }

        /** Re-read the durable schedule generation through a fresh handle. */
    private fun readBackSchedule(): APlus10AScheduleReset.ResetPlan? =
        applicationContext
            .getSharedPreferences(APlus10AScheduleReset.PREFS_NAME, MODE_PRIVATE)
            .let { p ->
                val id = p.getString(APlus10AScheduleReset.KEY_SCHEDULE_ID, null)
                val item = p.getString(APlus10AScheduleReset.KEY_CURRENT_ITEM_ID, null)
                val encoded = p.getString(APlus10AScheduleReset.KEY_ITEM_IDS, null)
                if (id == null || item == null || encoded == null) null
                else APlus10AScheduleReset.ResetPlan(
                    scheduleId = id,
                    scheduleVersion = p.getLong(APlus10AScheduleReset.KEY_SCHEDULE_VERSION, -1L),
                    itemIds = org.json.JSONArray(encoded).let { arr -> (0 until arr.length()).map { arr.getString(it) } },
                    currentItemId = item,
                    exhausted = p.getBoolean(APlus10AScheduleReset.KEY_EXHAUSTED, true),
                    advanceCount = p.getLong(APlus10AScheduleReset.KEY_ADVANCE_COUNT, -1L),
                )
            }

    /**
     * R7 P1-1 — terminal readback of the FULL written domain (profile rows +
     * settings + published transport). Called at end-of-seed UNDER the held
     * owner fence: it proves every byte the seed committed is still exactly the
     * seed's, so a concurrent profile/transport rewrite (prepareKyiv, UI
     * save/delete/import) cannot pass SEED_LOCAL_VERIFIED on the schedule/lease
     * checks alone. Throws (loud fail-closed) on any drift.
     */
    private fun verifyWrittenDomainOrThrow(
        dao: name.caiyao.fakegps.data.db.ProfileDao,
        expectedRows: List<name.caiyao.fakegps.data.db.ProfileEntity>,
        settings: SpoofSettings,
    ) {
        // (1) Profile table: exactly the ten rows, byte-exact on every field
        //     the provider apply path + A-block trust predicate read.
        val actual = kotlinx.coroutines.runBlocking { dao.getAll() }
        check(actual.size == expectedRows.size) {
            "written-domain drift: profile row count ${actual.size} != seeded ${expectedRows.size} " +
                "(a concurrent writer changed the table)"
        }
        val actualById = actual.associateBy { it.id }
        expectedRows.forEach { want ->
            val got = actualById[want.id]
                ?: throw IllegalStateException("written-domain drift: seeded profile id=${want.id} is gone")
            check(got == want) {
                "written-domain drift: profile id=${want.id} bytes changed since the seed committed " +
                    "(expected $want, durable $got)"
            }
        }
        // (2) Settings: the exact posture the seed set.
        check(settings.readLocationDeliveryMode() == LocationDeliveryMode.HOOK) {
            "written-domain drift: delivery mode is not HOOK — a concurrent settings write changed it"
        }
        check(!settings.isMockProviderCleanupRequired()) {
            "written-domain drift: mock-provider cleanup flag was re-enabled after the seed"
        }
        // (3) Transport payload: readable and carrying the seeded profile-1's
        //     identity (addname + coordinates) — not a stale/foreign publish.
        val want1 = expectedRows.first()
        when (val read = ConfigPrefsSync.readPublished(applicationContext)) {
            is name.caiyao.fakegps.config.PayloadRead.Raw -> {
                val fields = org.json.JSONObject(read.text).optJSONObject("fields")
                    ?: throw IllegalStateException("written-domain drift: published transport has no fields object")
                val publishedName = fields.optString("addname", "")
                check(publishedName == want1.addname) {
                    "written-domain drift: published transport carries addname='$publishedName', " +
                        "not the seeded profile-1 '${want1.addname}' — a concurrent publish overwrote it"
                }
                want1.latitude?.let { lat ->
                    check(fields.optDouble("latitude", Double.NaN) == lat) {
                        "written-domain drift: published transport latitude != seeded profile-1"
                    }
                }
            }
            name.caiyao.fakegps.config.PayloadRead.Absent ->
                throw IllegalStateException("written-domain drift: published transport is ABSENT after the seed published it")
            is name.caiyao.fakegps.config.PayloadRead.ReadError ->
                throw IllegalStateException("written-domain readback failed to read the transport: ${read.cause}")
        }
    }

        private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun complete(command: String) {
        Log.i(TAG, "READY command=$command")
        finish()
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_FIXTURE_PAYLOAD_B64 = "fixture_payload_base64"
        const val EXTRA_FIXTURE_DIGEST = "fixture_digest"
        const val COMMAND_PREPARE_KYIV = "prepare_kyiv"
        const val COMMAND_PREPARE_10A = "prepare_10a"
        const val COMMAND_STOP = "stop"

        private const val QWY_BENCH_HINT = "name.caiyao.fakegps.bench"
        const val KYIV_LATITUDE = 50.4501
        const val KYIV_LONGITUDE = 30.5234
        const val KYIV_ALTITUDE = 179.0
        private const val TAG = "MockProviderAcceptance"
    }
}
