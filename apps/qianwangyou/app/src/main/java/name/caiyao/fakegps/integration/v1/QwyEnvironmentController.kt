package name.caiyao.fakegps.integration.v1

import android.app.AppOpsManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.LocationManager
import android.os.SystemClock
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.config.ConfigCodec
import name.caiyao.fakegps.config.ConfigHolder
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.SpoofConfig
import name.caiyao.fakegps.mockprovider.AndroidMockProviderGateway
import name.caiyao.fakegps.mockprovider.CoordinatedMockProviderGateway
import name.caiyao.fakegps.mockprovider.FusedMockProviderGateway
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderGateway
import java.util.concurrent.ScheduledThreadPoolExecutor

/**
 * Seam between the v1 provider and qianwangyou's existing capabilities.
 * See [QwyEnvironment] interface for contract docs.
 */
interface QwyEnvironment {

    fun scheduleSnapshot(): ScheduleSnapshot?
    /**
     * Idempotently converges both the durable schedule pointer and the target
     * item's effective projection. Recovery with an already-moved pointer must
     * still rebuild and verify the projection before returning.
     */
    fun convergeAdvance(
        fromItemId: String,
        expectedToItemId: String?,
        expectedVersionAfter: Long,
    ): AdvancePointerOutcome
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
     * NOT a prediction of the apply outcome: command publication and fresh OS
     * readback are measured truth, knowable only at apply/observe time. Those
     * two surfaces remain the only sources of an ACHIEVED level.
     */
    fun achievableVerificationLevelWire(): Int
    /** Strength of the installed transition-history sources (§6.4). */
    fun continuityEvidenceCapability(): ContinuityEvidenceCapability
    fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit)

    /**
     * Releases every process-lifetime resource installed during owner startup
     * when startup cannot finish. The failed graph must not survive a thrown
     * [EnvironmentControlHandler.onOwnerProcessStart] and compete with the next
     * composition attempt.
     */
    fun abortOwnerStart()
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
    val evidenceRefs: List<String>,
    /** Raw required-source evidence time; never the handler's current clock. */
    val evidenceObservedAtElapsedRealtimeMs: Long? = null,
    /** Present only when all required OS samples independently verified. */
    val verifiedSourceElapsedRealtimeMs: Map<String, Long> = emptyMap(),
)

/**
 * Production adapter over qianwangyou's mockprovider / hook / config / schedule
 * capabilities.
 *
 * P1 fixes (dsf round-1 review):
 * - P1-1: schedule initialized from profile DB on construction (synchronous
 *   SQLite query on the existing "temp" table)
 * - P1-2: verificationLevel reflects fresh framework-provider readback;
 *   fail-loud when the write gateway is unavailable and fail-closed when the
 *   independent read side cannot corroborate the publish
 * - P1-3: observeEffective reads LocationManager provider state, never
 *   ConfigHolder, ConfigPrefs, or the persisted desired command coordinates
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
    private val relevantChangeMonitor = QwyRelevantChangeMonitor(
        (appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager)?.let { manager ->
            AndroidMockLocationOwnerChangeSource(
                manager,
                appContext.packageName,
                appContext.applicationInfo.uid,
            )
        } ?: MockLocationOwnerChangeSource { false },
    )

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

    private val locationManager: LocationManager? = try {
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    } catch (_: Throwable) {
        null
    }

    // P1-2 fix: mockGateway construction failure is tracked; apply/cleanup
    // must report honestly when it is null.
    private val mockGateway: MockProviderGateway? = locationManager?.let { manager ->
        CoordinatedMockProviderGateway(
            AndroidMockProviderGateway(manager),
            NoopFusedGateway,
        )
    }

    /**
     * Fresh sample publisher for the active provider lease. Its one-second tick
     * exists only to keep GPS/network evidence live; it is never counted as a
     * continuity source (the public AppOps monitor remains INCOMPLETE).
     */
    private val mockRefreshSession: FrameworkMockRefreshSession? = mockGateway?.let { gateway ->
        val executor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "qwy-framework-mock-refresh").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
        }
        FrameworkMockRefreshSession(
            gateway = gateway,
            scheduler = ScheduledExecutorFrameworkMockRefreshScheduler(executor),
            onRelevantChange = relevantChangeMonitor::reportRelevantChange,
        )
    }

    /**
     * KB-8 read side. This is intentionally independent of [scheduleStore]'s
     * last-applied command record: requested coordinates are not observations.
     */
    private val systemMockTrustPolicy: SystemMockTrustPolicy? = locationManager?.let { manager ->
        SystemMockTrustPolicy(AndroidSystemMockLocationReader(manager))
    }

    override fun scheduleSnapshot(): ScheduleSnapshot? {
        return scheduleStore.readScheduleState()
    }

    override fun convergeAdvance(
        fromItemId: String,
        expectedToItemId: String?,
        expectedVersionAfter: Long,
    ): AdvancePointerOutcome {
        val outcome = scheduleStore.convergeAdvance(
            fromItemId = fromItemId,
            expectedToItemId = expectedToItemId,
            expectedVersionAfter = expectedVersionAfter,
        )
        if (expectedToItemId != null) {
            convergeAdvancedProjection(expectedToItemId, expectedVersionAfter)
        }
        return outcome
    }

    /**
     * Release removes the old framework providers and apply anchor. A
     * non-terminal advance therefore owns a fresh projection convergence of
     * the new qwy-owned item before the handler may clear its pending marker.
     */
    private fun convergeAdvancedProjection(itemId: String, expectedVersionAfter: Long) {
        val refreshSession = mockRefreshSession
            ?: throw IllegalStateException(
                "mock provider refresh session unavailable; cannot project advanced item $itemId"
            )
        val coords = resolveItemCoordinates(itemId)
            ?: throw IllegalStateException(
                "advanced schedule item $itemId has no profile coordinates"
            )
        val trustPolicy = systemMockTrustPolicy
            ?: throw IllegalStateException(
                "system mock readback unavailable; cannot verify advanced item $itemId"
            )

        val config = SpoofConfig(
            location = SpoofConfig.Location(
                latitude = coords.first,
                longitude = coords.second,
            ),
        )
        configHolder.update(ConfigCodec.toJson(config))

        val publishNotBeforeElapsedRealtimeMs = SystemClock.elapsedRealtime()
        refreshSession.startOrReconfigure(
            MockLocationConfig(latitude = coords.first, longitude = coords.second),
        )
        val published = ConfigPrefsSync.sync(appContext, profileId = null, clearIfMissing = false)
        val readback = trustPolicy.evaluate(
            targetLatitude = coords.first,
            targetLongitude = coords.second,
            publishNotBeforeElapsedRealtimeMs = publishNotBeforeElapsedRealtimeMs,
        )
        scheduleStore.recordLastApplied(
            latitude = coords.first,
            longitude = coords.second,
            publishNotBeforeElapsedRealtimeMs = publishNotBeforeElapsedRealtimeMs,
            transportPublished = published,
            scheduleItemId = itemId,
            scheduleVersion = expectedVersionAfter,
            purpose = ProjectionPurpose.POST_ADVANCE,
        )

        check(
            relevantChangeMonitor.canVerifyCurrentOwner() &&
                published && readback.verified
        ) {
            "advanced item $itemId did not converge to a verified system-mock projection"
        }
    }

    override fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome {
        // P1-2 fix: fail loud when mockGateway is unavailable — never claim
        // verification the provider cannot back (INV-08).
        val refreshSession = mockRefreshSession
            ?: throw IllegalStateException(
                "mock provider refresh session unavailable; cannot apply environment"
            )

        // KB-8 (v1.62): coordinates are QWY-OWNED. The intent no longer carries
        // them — resolve from the CURRENT SCHEDULE ITEM's profile row, the
        // single coordinate owner. Auto supplies only the reference.
        val schedule = scheduleStore.readScheduleState()
            ?: throw IllegalStateException("no active schedule; environment cannot be applied")
        val currentItem = schedule.currentItemId
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

        // Capture the lower freshness bound BEFORE the system mutation. A
        // cached fix from an earlier address can never satisfy this apply.
        val publishNotBeforeElapsedRealtimeMs = SystemClock.elapsedRealtime()
        refreshSession.startOrReconfigure(
            MockLocationConfig(latitude = coords.first, longitude = coords.second),
        )

        val published = ConfigPrefsSync.sync(appContext, profileId = null, clearIfMissing = false)

        val readback = systemMockTrustPolicy?.evaluate(
            targetLatitude = coords.first,
            targetLongitude = coords.second,
            publishNotBeforeElapsedRealtimeMs = publishNotBeforeElapsedRealtimeMs,
        )

        // Persist only the command/audit record and the freshness anchor. Its
        // coordinates and transport flag are never reused as effective state.
        scheduleStore.recordLastApplied(
            latitude = coords.first,
            longitude = coords.second,
            publishNotBeforeElapsedRealtimeMs = publishNotBeforeElapsedRealtimeMs,
            transportPublished = published,
            scheduleItemId = currentItem,
            scheduleVersion = schedule.scheduleVersion,
            purpose = ProjectionPurpose.LEASE,
        )

        // Config transport success is necessary but not sufficient. The
        // achieved level is VERIFIED only when fresh OS readback independently
        // corroborates both framework providers within the frozen 1 m bound.
        val verificationLevel = if (
            relevantChangeMonitor.canVerifyCurrentOwner() && published && readback?.verified == true
        )
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        else
            VerificationLevelV1.NONE.wire

        return ApplyOutcome(
            effectiveLatitude = readback?.latitude,
            effectiveLongitude = readback?.longitude,
            deliveryModeWire = if (readback?.isMock == true) {
                DeliveryModeV1.SYSTEM_MOCK.wire
            } else {
                null
            },
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
        if (!relevantChangeMonitor.canVerifyCurrentOwner() ||
            mockRefreshSession == null || systemMockTrustPolicy == null
        ) {
            return VerificationLevelV1.NONE.wire
        }
        val currentItem = scheduleStore.getCurrentItemId()
            ?: return VerificationLevelV1.NONE.wire
        return if (resolveItemCoordinates(currentItem) != null) {
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        } else {
            VerificationLevelV1.NONE.wire
        }
    }

    override fun continuityEvidenceCapability(): ContinuityEvidenceCapability =
        relevantChangeMonitor.continuityEvidenceCapability()

    override fun cleanup(leaseId: String): CleanupOutcome {
        // P3-1 fix: report honestly whether removal actually happened.
        // Clear the apply anchor in all branches, including when removal
        // throws, so a cached provider sample cannot be attributed to a lease
        // that has already been released.
        return if (mockRefreshSession != null) {
            try {
                mockRefreshSession.stop()
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
        val schedule = scheduleStore.readScheduleState()
        var appliedCommand = scheduleStore.getLastApplied()
        val currentTarget = schedule?.currentItemId?.let(::resolveItemCoordinates)
        val refreshSession = mockRefreshSession

        // A completed non-terminal advance keeps a durable reconstruction
        // contract until the next lease takes ownership. After process death the
        // scheduled loop is gone even though the pending-advance marker was
        // correctly cleared, so rebuild it lazily for the released-lease observe.
        // This only republishes endpoint evidence; continuity remains capped by
        // the independent AppOps source (INCOMPLETE in production).
        if (schedule != null && refreshSession != null && !refreshSession.isActive) {
            val restartable = scheduleStore.postAdvanceProjectionFor(schedule)
            if (restartable != null && currentTarget != null) {
                val publishNotBeforeElapsedRealtimeMs = SystemClock.elapsedRealtime()
                refreshSession.startOrReconfigure(
                    MockLocationConfig(
                        latitude = currentTarget.first,
                        longitude = currentTarget.second,
                    ),
                )
                val published = ConfigPrefsSync.sync(
                    appContext,
                    profileId = null,
                    clearIfMissing = false,
                )
                scheduleStore.recordLastApplied(
                    latitude = currentTarget.first,
                    longitude = currentTarget.second,
                    publishNotBeforeElapsedRealtimeMs = publishNotBeforeElapsedRealtimeMs,
                    transportPublished = published,
                    scheduleItemId = restartable.scheduleItemId,
                    scheduleVersion = restartable.scheduleVersion,
                    purpose = ProjectionPurpose.POST_ADVANCE,
                )
                appliedCommand = scheduleStore.getLastApplied()
            }
        }
        // A production observe must advance the actual framework samples; the
        // previous one-shot path could only replay apply's cache at POST.
        val refreshed = runCatching {
            mockRefreshSession?.refreshNow() == true
        }.getOrDefault(false)
        val readback = if (
            refreshed && appliedCommand != null && currentTarget != null &&
            systemMockTrustPolicy != null
        ) {
            systemMockTrustPolicy.evaluate(
                targetLatitude = currentTarget.first,
                targetLongitude = currentTarget.second,
                publishNotBeforeElapsedRealtimeMs =
                    appliedCommand.publishNotBeforeElapsedRealtimeMs,
            )
        } else {
            null
        }

        // `transportPublished` is a command-side prerequisite only. Effective
        // coordinates, mock identity and distance come exclusively from the OS
        // readback above; no lastApplied coordinate can enter this projection.
        val verified = relevantChangeMonitor.canVerifyCurrentOwner() &&
            appliedCommand?.transportPublished == true && readback?.verified == true
        val verificationLevel = if (verified)
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        else
            VerificationLevelV1.NONE.wire
        val fingerprint = when {
            readback != null -> "${readback.fingerprint}:transport=${appliedCommand?.transportPublished == true}"
            appliedCommand == null -> "system-mock:no-apply-anchor"
            currentTarget == null -> "system-mock:no-current-target"
            else -> "system-mock:readback-unavailable"
        }

        return EffectiveEnvironment(
            latitude = readback?.latitude,
            longitude = readback?.longitude,
            isMock = readback?.isMock,
            deliveryModeWire = if (readback?.isMock == true) {
                DeliveryModeV1.SYSTEM_MOCK.wire
            } else {
                null
            },
            verificationLevelWire = verificationLevel,
            environmentFingerprint = fingerprint,
            evidenceRefs = emptyList(),
            evidenceObservedAtElapsedRealtimeMs = readback?.evidenceObservedAtElapsedRealtimeMs,
            verifiedSourceElapsedRealtimeMs = if (verified) {
                readback?.verifiedSourceElapsedRealtimeMs.orEmpty()
            } else {
                emptyMap()
            },
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
        relevantChangeMonitor.bind(listener)
    }

    override fun abortOwnerStart() {
        var failure: Throwable? = null

        // Detach the failed handler before provider removal can emit another
        // AppOps callback into it.
        try {
            relevantChangeMonitor.shutdown()
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            mockRefreshSession?.shutdown()
        } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }

        failure?.let { throw it }
    }
}

private object NoopFusedGateway : FusedMockProviderGateway {
    override fun enable() {}
    override fun publish(config: MockLocationConfig) {}
    override fun disable() {}
}
