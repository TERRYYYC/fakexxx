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
                    // Full mapping to logcat for the evidence pack; READY marker
                    // only on a PROVEN seed (parity with prepare_kyiv's predicate).
                    Log.i(TAG, report)
                    complete(COMMAND_PREPARE_10A)
                },
                onFailure = { e ->
                    // PR #62 P1-3: a failed seed must NOT emit the READY marker —
                    // the runbook's capture_step predicate greps for READY, so an
                    // unconditional READY made every failure a false green. The
                    // failure marker is loud and distinct; the step times out or
                    // greps SEED_FAILED, never both.
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
        // PR #62 R3 P1-2 — MONOTONIC generation reset (replaces the earlier
        // clear(): clearing let the next boot re-Initialize at version 1, a
        // rollback that lets old (schedule,item,version) identities collide
        // with the new run — M-AD-24 / spec L1895-2056 require V → V+1 on
        // every reinit, including a same-topology exhausted reset).
        //
        // Read the CURRENT stored version, compute the pure V+1 plan, write
        // every schedule field in ONE atomic commit (bump + pointer reset +
        // exhausted clear ride the same write, the M-AD-24 pairing; stale
        // last-applied projections are removed by the same commit), then READ
        // BACK and fail the seed on any mismatch. On the next provider boot,
        // initFromProfileIds sees the same item set + present scheduleId and
        // NoOps — preserving this V+1 generation rather than fighting it.
        val schedulePrefs = applicationContext
            .getSharedPreferences(APlus10AScheduleReset.PREFS_NAME, MODE_PRIVATE)
        val versionBefore = schedulePrefs.getLong(APlus10AScheduleReset.KEY_SCHEDULE_VERSION, 0L)
        val resetPlan = APlus10AScheduleReset.plan(
            existingVersion = versionBefore,
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
        val readBack = applicationContext
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
        APlus10AScheduleReset.verifyReadback(readBack, resetPlan)?.let { mismatch ->
            throw IllegalStateException("schedule generation readback mismatch — $mismatch")
        }

        // Isolated .bench data only (same seam as prepare_kyiv): clear then insert
        // with EXPLICIT ids so expectedScheduleItemId=profile-N stays byte-exact.
        dao.deleteAll()
        val insertedIds = dao.insertAll(rows)

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

        // Throws (IllegalStateException) if any inserted id drifted from the
        // explicit fixture id — a green mapping over a mismatch is forbidden.
        // PR #62 P1-1: the report emits only the independently verified
        // REGISTERED digest, never the caller-declared value.
        return APlus10AFixtureSeed.seedReport(items, insertedIds, APlus10AFixtureSeed.REGISTERED_FIXTURE_DIGEST) +
            "SCHEDULE_GENERATION versionBefore=$versionBefore versionAfter=${resetPlan.scheduleVersion} " +
            "pointer=${resetPlan.currentItemId} exhausted=${resetPlan.exhausted} (monotonic V+1, readback verified)\n" +
            "NEXT: force-stop $QWY_BENCH_HINT then bind; readback via discover(): currentItemId=profile-1 and " +
            "scheduleVersion=${resetPlan.scheduleVersion} (the executable subset — the full ordered profile-1..10 " +
            "list readback awaits the P1-3 profileRefs projection scope decision)"
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
