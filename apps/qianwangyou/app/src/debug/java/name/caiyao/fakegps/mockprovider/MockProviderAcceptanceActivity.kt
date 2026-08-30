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
            val report = withContext(Dispatchers.IO) {
                runCatching { seed10a() }.getOrElse { e ->
                    "SEED_FAILED ${e::class.java.simpleName}: ${e.message}"
                }
            }
            // Full mapping to logcat for the evidence pack; READY marker for the
            // runbook's capture_step predicate (parity with prepare_kyiv).
            Log.i(TAG, report)
            complete(COMMAND_PREPARE_10A)
        }
    }

    private suspend fun seed10a(): String {
        val payloadB64 = intent.getStringExtra(EXTRA_FIXTURE_PAYLOAD_B64)
            ?: return "SEED_FAILED missing --es $EXTRA_FIXTURE_PAYLOAD_B64"
        val declaredDigest = intent.getStringExtra(EXTRA_FIXTURE_DIGEST)
            ?: return "SEED_FAILED missing --es $EXTRA_FIXTURE_DIGEST"

        val decoded = Base64.decode(payloadB64, Base64.DEFAULT)
        val computedDigest = sha256Hex(decoded)
        // Byte-exactness: the decoded payload must hash to the digest the
        // executor froze over the fixture file. A mismatch means what reached
        // the device is NOT the frozen fixture — refuse before touching the DB.
        if (!computedDigest.equals(declaredDigest, ignoreCase = true)) {
            return "SEED_FAILED payload digest $computedDigest != declared $declaredDigest " +
                "(the seeded bytes are not the frozen fixture)"
        }

        val items = APlus10AFixtureSeed.parsePayload(String(decoded, Charsets.UTF_8))
        val rows = APlus10AFixtureSeed.toProfileRows(items)

        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.profileDao()
        // Isolated .bench data only (same seam as prepare_kyiv): clear then insert
        // with EXPLICIT ids so expectedScheduleItemId=profile-N stays byte-exact.
        dao.deleteAll()
        val insertedIds = dao.insertAll(rows)

        // Same profile posture prepare_kyiv established and G1 C5 run-2 verified:
        // HOOK delivery + no forced mock-provider cleanup. The A-block EC provider
        // resolves coordinates from the temp table directly; this keeps the
        // hook/self-check diagnostics identical to the proven single-address run.
        val settings = SpoofSettings.getInstance(applicationContext)
        settings.setLocationDeliveryMode(LocationDeliveryMode.HOOK)
        settings.setMockProviderCleanupRequired(false)
        // Publish the first schedule item (profile-1) so --current-profile and the
        // hook self-check load a valid schema-v3 payload; the schedule itself is
        // rebuilt from the temp table on the next provider process start.
        ConfigPrefsSync.sync(applicationContext, profileId = insertedIds.firstOrNull())

        // Throws (IllegalStateException) if any inserted id drifted from the
        // explicit fixture id — a green mapping over a mismatch is forbidden.
        return APlus10AFixtureSeed.seedReport(items, insertedIds, declaredDigest)
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
        const val KYIV_LATITUDE = 50.4501
        const val KYIV_LONGITUDE = 30.5234
        const val KYIV_ALTITUDE = 179.0
        private const val TAG = "MockProviderAcceptance"
    }
}
