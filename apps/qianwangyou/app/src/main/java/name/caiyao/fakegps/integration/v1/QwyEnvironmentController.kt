package name.caiyao.fakegps.integration.v1

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.LocationManager
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.config.ConfigCodec
import name.caiyao.fakegps.config.ConfigHolder
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.SpoofConfig
import name.caiyao.fakegps.mockprovider.AndroidMockProviderGateway
import name.caiyao.fakegps.mockprovider.CoordinatedMockProviderGateway
import name.caiyao.fakegps.mockprovider.EffectiveMockLocationResolution
import name.caiyao.fakegps.mockprovider.EffectiveMockLocationResolver
import name.caiyao.fakegps.mockprovider.FusedMockProviderGateway
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderGateway

/**
 * Seam between the v1 provider and qianwangyou's existing capabilities.
 * See [QwyEnvironment] interface for contract docs.
 */
interface QwyEnvironment {

    fun scheduleSnapshot(): ScheduleSnapshot?
    fun advancePointer(fromItemId: String): AdvancePointerOutcome
    fun applyScheduleRestart(targetVersion: Long, firstItemId: String): Boolean = false
    fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome
    fun cleanup(leaseId: String): CleanupOutcome
    fun observeEffective(): EffectiveEnvironment
    fun scheduleDecisionWire(scheduleRef: String): Int

    /**
     * F-17: the honest CEILING of verification an apply could reach right
     * now, derived from the same capability reads [applyEnvironment] itself
     * gates on (gateway availability, current schedule item, qwy-owned
     * coordinates for that item). Any known blocker → NONE — preflight must
     * never claim a level the environment cannot currently back (INV-08).
     *
     * NOT a prediction of the apply outcome: the publish result is measured
     * truth, knowable only at apply time. The apply receipt (F-14 fix:
     * computed from the real ConfigPrefsSync result) and observeEffective()
     * remain the only trusted sources of an ACHIEVED level.
     */
    fun achievableVerificationLevelWire(): Int
    fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit)
}

data class ScheduleSnapshot(
    val scheduleId: String,
    val scheduleVersion: Long,
    val currentItemId: String?,
    val itemIds: List<String>,
    val exhausted: Boolean,
)

sealed class AdvancePointerOutcome {
    data class Advanced(val toItemId: String, val versionAfter: Long) : AdvancePointerOutcome()
    data class Exhausted(val versionAfter: Long) : AdvancePointerOutcome()
}

data class ApplyOutcome(
    val effectiveLatitude: Double?,
    val effectiveLongitude: Double?,
    val deliveryModeWire: Int?,
    val verificationLevelWire: Int,
)

sealed class CleanupOutcome {
    object Complete : CleanupOutcome()
    data class Incomplete(val residualReasonWires: List<Int>) : CleanupOutcome()
}

data class EffectiveEnvironment(
    val latitude: Double?,
    val longitude: Double?,
    val isMock: Boolean?,
    val deliveryModeWire: Int?,
    val verificationLevelWire: Int,
    val environmentFingerprint: String,
)

/**
 * Production adapter over qianwangyou's mockprovider / hook / config / schedule
 * capabilities.
 *
 * P1 fixes (dsf round-1 review):
 * - P1-1: schedule initialized from profile DB on construction (synchronous
 *   SQLite query on the existing "temp" table)
 * - P1-2: verificationLevel reflects actual mockGateway state; fail-loud when
 *   the gateway is unavailable instead of hardcoding INDEPENDENTLY_VERIFIED
 * - P1-3: observeEffective reads from ConfigPrefsSync (persistent) not
 *   in-memory ConfigHolder, so restart does not lie about passthrough
 *
 * KNOWN BOUNDARY: schedule items are derived from the existing profile DB
 * (ProfileEntity rows, id ASC). An operator-facing schedule editor with
 * explicit ordering and priority is a separate feature.
 */
class QwyEnvironmentController(
    private val context: Context,
) : QwyEnvironment {

    private val appContext = context.applicationContext
    private val scheduleStore = QwyScheduleStore(appContext)
    private val configHolder = ConfigHolder()
    private var changeListener: ((RevisionBumpReason) -> Unit)? = null

    init {
        // P1-1 fix: initialize schedule from existing profile DB.
        // Synchronous raw SQLite query — the handler API is synchronous, and
        // scheduleSnapshot() must return real state on the first call.
        initScheduleFromDb()
    }

    private fun initScheduleFromDb() {
        val dbFile = appContext.getDatabasePath("fakegps.db")
        if (!dbFile.exists()) return
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        )
        val profileIds = try {
            val cursor = db.rawQuery("SELECT id FROM temp ORDER BY id ASC", null)
            val ids = mutableListOf<Long>()
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0))
            }
            cursor.close()
            ids
        } finally {
            db.close()
        }
        if (profileIds.isNotEmpty()) {
            scheduleStore.initFromProfileIds(profileIds)
        }
    }

    override fun applyScheduleRestart(targetVersion: Long, firstItemId: String): Boolean =
        scheduleStore.applyRestart(targetVersion, firstItemId)

    // P1-2 fix: mockGateway construction failure is tracked; apply/cleanup
    // must report honestly when it is null.
    private val mockGateway: MockProviderGateway? = try {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        CoordinatedMockProviderGateway(
            AndroidMockProviderGateway(lm),
            NoopFusedGateway,
        )
    } catch (e: Throwable) {
        null
    }

    override fun scheduleSnapshot(): ScheduleSnapshot? {
        val scheduleId = scheduleStore.getScheduleId() ?: return null
        return ScheduleSnapshot(
            scheduleId = scheduleId,
            scheduleVersion = scheduleStore.getScheduleVersion(),
            currentItemId = scheduleStore.getCurrentItemId(),
            itemIds = scheduleStore.getItemIds(),
            exhausted = scheduleStore.isExhausted(),
        )
    }

    override fun advancePointer(fromItemId: String): AdvancePointerOutcome =
        scheduleStore.advancePointer(fromItemId)

    override fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome {
        // P1-2 fix: fail loud when mockGateway is unavailable — never claim
        // verification the provider cannot back (INV-08).
        if (mockGateway == null) {
            throw IllegalStateException(
                "mock provider gateway unavailable; cannot apply environment"
            )
        }

        // KB-8 (v1.62): coordinates are QWY-OWNED. The intent no longer carries
        // them — resolve from the CURRENT SCHEDULE ITEM's profile row, the
        // single coordinate owner. Auto supplies only the reference.
        val currentItem = scheduleStore.getCurrentItemId()
            ?: throw IllegalStateException("no current schedule item; environment cannot be applied without qwy-owned coordinates")
        val coords = resolveItemCoordinates(currentItem)
            ?: throw IllegalStateException(
                "schedule item $currentItem has no profile coordinates; the schedule owner must provide them"
            )

        val config = SpoofConfig(
            location = SpoofConfig.Location(
                latitude = coords.first,
                longitude = coords.second,
            ),
        )
        configHolder.update(ConfigCodec.toJson(config))

        mockGateway!!.replaceGpsProvider()
        mockGateway!!.publish(
            MockLocationConfig(
                latitude = coords.first,
                longitude = coords.second,
            ),
        )

        val published = ConfigPrefsSync.sync(appContext, profileId = null, clearIfMissing = false)

        // P2 fix (dsf round-3/4): persist the applied coordinates + publish
        // outcome so observeEffective returns what the mock provider actually
        // has, with verification level matching the real sync result.
        scheduleStore.recordLastApplied(
            coords.first, coords.second, android.os.SystemClock.elapsedRealtime(),
            verified = published,
        )

        // P1-2 fix: verification level reflects actual publish outcome.
        val verificationLevel = if (published)
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        else
            VerificationLevelV1.NONE.wire

        return ApplyOutcome(
            effectiveLatitude = coords.first,
            effectiveLongitude = coords.second,
            deliveryModeWire = 1,
            verificationLevelWire = verificationLevel,
        )
    }

    /**
     * KB-8 coordinate resolution: schedule item "profile-{dbId}" → that profile
     * row's latitude/longitude from the existing temp table. Null when the row
     * is missing or its coordinates are null — the schedule owner's data is
     * the truth, and missing truth is reported, never guessed.
     */
    private fun resolveItemCoordinates(itemId: String): Pair<Double, Double>? {
        if (!itemId.startsWith("profile-")) return null
        val dbId = itemId.removePrefix("profile-").toLongOrNull() ?: return null
        val dbFile = appContext.getDatabasePath("fakegps.db")
        if (!dbFile.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT latitude, longitude FROM temp WHERE id = ?",
                    arrayOf(dbId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val lat = cursor.getDouble(0)
                    val lng = cursor.getDouble(1)
                    if (lat == 0.0 && lng == 0.0 && cursor.isNull(0) && cursor.isNull(1)) null
                    else if (!cursor.isNull(0) && !cursor.isNull(1)) lat to lng
                    else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun achievableVerificationLevelWire(): Int {
        // F-17: mirror applyEnvironment()'s own preconditions exactly — every
        // branch here is a state where apply would throw (or refuse to publish)
        // before any verification could happen, so claiming VERIFIED over it
        // from preflight would be the same constant-lie F-14 killed in the
        // apply receipt (Handler:247), wearing preflight's clothes (Handler:113).
        if (mockGateway == null) return VerificationLevelV1.NONE.wire
        val currentItem = scheduleStore.getCurrentItemId()
            ?: return VerificationLevelV1.NONE.wire
        return if (resolveItemCoordinates(currentItem) != null) {
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        } else {
            VerificationLevelV1.NONE.wire
        }
    }

    override fun cleanup(leaseId: String): CleanupOutcome {
        // P3-1 fix: report honestly whether removal actually happened.
        // P2-2 fix (dsf round-4): clear lastApplied so observe no longer
        // reports stale mock coordinates after cleanup.
        // P3-2 fix (dsf round-5): clear lastApplied in all branches, including
        // when removeGpsProvider throws.
        return if (mockGateway != null) {
            try {
                mockGateway!!.removeGpsProvider()
                CleanupOutcome.Complete
            } catch (e: Throwable) {
                CleanupOutcome.Incomplete(emptyList())
            } finally {
                scheduleStore.clearLastApplied()
            }
        } else {
            scheduleStore.clearLastApplied()
            CleanupOutcome.Incomplete(emptyList())
        }
    }

    override fun observeEffective(): EffectiveEnvironment {
        // P2 fix (dsf round-3): prefer the last-applied intent coordinates
        // (what the mock provider is actually publishing) over ConfigPrefsSync
        // (which reads DB active-profile coords that may differ).
        // Fall back to ConfigPrefsSync only when no intent was applied (cold
        // start with a pre-existing hook config).
        val lastApplied = scheduleStore.getLastApplied()
        val payload = ConfigPrefsSync.readPublished(appContext)

        val lat: Double?
        val lng: Double?
        val isMock: Boolean
        val fingerprint: String

        if (lastApplied != null) {
            // Intent coordinates are what the mock provider has right now.
            lat = lastApplied.latitude
            lng = lastApplied.longitude
            // P2-1 fix (dsf round-4): verification must match the actual
            // publish outcome recorded at apply time, not just "apply happened".
            isMock = lastApplied.verified
            fingerprint = "intent:${lastApplied.latitude},${lastApplied.longitude}@${lastApplied.atMs}:verified=${lastApplied.verified}"
        } else {
            // No intent applied yet — read what the hook transport says.
            val published = when (payload) {
                is PayloadRead.Raw -> PublishedConfig.parse(payload.text)
                else -> null
            }
            val resolution = EffectiveMockLocationResolver.resolve(published)
            when (resolution) {
                is EffectiveMockLocationResolution.Ready -> {
                    lat = resolution.config.latitude
                    lng = resolution.config.longitude
                    isMock = true
                }
                is EffectiveMockLocationResolution.Invalid -> {
                    lat = null
                    lng = null
                    isMock = false
                }
            }
            fingerprint = when (payload) {
                is PayloadRead.Raw -> PublishedConfig.fingerprint(payload.text)
                is PayloadRead.ReadError -> "read-error:${payload.cause}"
                PayloadRead.Absent -> "passthrough"
            }
        }

        val verificationLevel = if (isMock)
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        else
            VerificationLevelV1.NONE.wire

        return EffectiveEnvironment(
            latitude = lat,
            longitude = lng,
            isMock = isMock,
            deliveryModeWire = if (isMock) 1 else null,
            verificationLevelWire = verificationLevel,
            environmentFingerprint = fingerprint,
        )
    }

    override fun scheduleDecisionWire(scheduleRef: String): Int {
        // P2-2 fix: return proper ScheduleDecisionV1 wire, not 0.
        // No schedule → DENIED; matching schedule → ALLOWED_NOW.
        val snap = scheduleSnapshot()
            ?: return ScheduleDecisionV1.DENIED.wire
        return if (scheduleRef == snap.scheduleId)
            ScheduleDecisionV1.ALLOWED_NOW.wire
        else
            ScheduleDecisionV1.DENIED.wire
    }

    override fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit) {
        changeListener = listener
    }
}

private object NoopFusedGateway : FusedMockProviderGateway {
    override fun enable() {}
    override fun publish(config: MockLocationConfig) {}
    override fun disable() {}
}
