package name.caiyao.fakegps.integration.v1

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.LocationManager
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
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
import name.caiyao.fakegps.mockprovider.MockProviderEmissionTrigger
import name.caiyao.fakegps.mockprovider.MockProviderGateway
import org.json.JSONObject

/**
 * Seam between the v1 provider and qianwangyou's existing capabilities.
 * See [QwyEnvironment] interface for contract docs.
 */
interface QwyEnvironment {

    /** Ordered, read-only profile owner projection. Empty is an honest unavailable projection. */
    fun profileRefsSnapshot(): List<String> = emptyList()

    /**
     * v1.81: the effective schedule item's cellular columns for the discover()
     * CI-attestation group. Null = not evaluable right now — discover() then
     * answers all-null columns + cellularHookConfigured=false (fail-closed
     * honesty: an unreadable configuration is attested as absent, never
     * guessed). Default null so inert/test environments stay honest without
     * implementing the group.
     */
    fun configuredCellSnapshot(): ConfiguredCellSnapshot? = null
    fun scheduleSnapshot(): ScheduleSnapshot?
    fun advancePointer(fromItemId: String): AdvancePointerOutcome
    fun applyScheduleRestart(targetVersion: Long, firstItemId: String): Boolean = false

    /**
     * #140 quick reset: committed reset to a fresh generation WITHOUT the
     * operator restart's exhaustion guard (any durable generation is resettable,
     * not only an exhausted one). Default delegates to [applyScheduleRestart];
     * production implements the unguarded monotonic write.
     */
    fun resetScheduleToFreshGeneration(targetVersion: Long, firstItemId: String): Boolean =
        applyScheduleRestart(targetVersion, firstItemId)

    /**
     * #140 quick reset: re-anchor the effective profile (the CURRENT schedule
     * item's row) and republish it to the hook transport. Returns the published
     * profile ref, or null when the publish did not happen or failed (honest
     * partial — never a guessed ref). Default null: environments without a
     * publish path report the failure instead of faking success.
     */
    fun republishCurrentItem(): String? = null

    /**
     * #168: apply's legacy publish-chain linkage. The contract apply just moved
     * the effective schedule item, but two legacy carriers do not follow the
     * pointer by themselves: the anchored profile (publish_state's
     * activeProfileId) and the spoof_config payload published from it — the
     * payload the hook projects (CellRebel's view) AND the payload
     * MockProviderMain's 1 Hz refresh re-delivers to the system mock provider.
     * Left alone, both keep the PREVIOUS item's row while the contract chain
     * (oracle/observation) already names the new one — the three-chain split of
     * #168: every stale-coordinate re-delivery reads as a covered semantic
     * change, the oracle cursor keeps advancing, and each attempt's PRE/POST
     * window drifts fail-closed.
     *
     * Implementations re-anchor the chains to the CURRENT schedule item and
     * republish it — but ONLY when the anchored item actually lags: a
     * same-item apply replay must stay publish-silent (no redundant IO, no
     * fresh cross-process payload write). The outcome is honest:
     * [LegacyAnchorSync.Current] proves alignment, [LegacyAnchorSync.Republished]
     * names the item the chains now carry, and [LegacyAnchorSync.PublishFailed]
     * admits the chains still lag — the caller treats that as an honest partial
     * (the contract apply stays authoritative) and warns.
     *
     * Default [LegacyAnchorSync.Current]: environments without a modeled legacy
     * publish chain (inert harnesses) have nothing to move.
     */
    fun syncLegacyAnchorToCurrentItem(): LegacyAnchorSync = LegacyAnchorSync.Current

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

    // profileRefsSnapshot() above — discover() projects that one into
    // CapabilitySnapshotV1.profileRefs; a second, unconsumed projection would
    // also violate LegacyRecoveryDirectOpenGuardTest (no recovery call, no
    // third direct open in the read-only controller).
}

data class ScheduleSnapshot(
    val scheduleId: String,
    val scheduleVersion: Long,
    val currentItemId: String?,
    val itemIds: List<String>,
    val exhausted: Boolean,
)

/**
 * v1.81 CI-attestation projection source: the EFFECTIVE schedule item's
 * profile-row cellular columns — the values the hook would inject into
 * serving-cell reads while that item is applied. Null (from
 * [QwyEnvironment.configuredCellSnapshot]) = not evaluable right now (no
 * current item, profile DB unavailable, read failure); discover() then
 * projects all-null columns with cellularHookConfigured=false, never a
 * guessed value.
 *
 * ATTESTATION-ONLY: this projection exists so Auto can cross-check what the
 * device reports against what QWY configured. It never feeds the §6.4 trust
 * predicates — the observation evidence chain stays the only trust path.
 *
 * Column mapping is the LTE-named profile columns only (`ci`/`tac`/`pci`
 * + `mcc`/`mnc`), widened to the contract carrier types; mcc/mnc travel as
 * the decimal strings TelephonyManager reports so both sides of the
 * cross-attestation compare one representation. NR-only profiles (nci /
 * nr_tac / nr_pci filled, LTE columns empty) project all-null + hook=false —
 * an honest "no LTE identity attested", never a substituted NR value.
 */
data class ConfiguredCellSnapshot(
    val ci: Long?,
    val tac: Int?,
    val pci: Int?,
    val mcc: String?,
    val mnc: String?,
) {
    /** 蜂窝组任一字段有值 = true；全空组 = 完全透传（false）。 */
    val cellularHookConfigured: Boolean
        get() = ci != null || tac != null || pci != null || mcc != null || mnc != null
}

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

/**
 * #168: outcome of apply's legacy publish-chain linkage
 * ([QwyEnvironment.syncLegacyAnchorToCurrentItem]). Honest by construction —
 * "skipped" and "moved" and "failed" are three different answers, never folded
 * into one.
 */
sealed interface LegacyAnchorSync {
    /** The anchored item already names the applied item — chains aligned, publish-silent. */
    data object Current : LegacyAnchorSync

    /** The chains lagged and were re-anchored + republished to the applied item. */
    data class Republished(val itemId: String) : LegacyAnchorSync

    /**
     * The republish did not happen (publish failed / current item not
     * resolvable to a profile row) — the chains still lag the contract
     * effective. Honest partial: the apply stays authoritative and the caller
     * warns; the split keeps observations fail-closed until the chains
     * realign, exactly as before the fix but now named where it is created.
     */
    data object PublishFailed : LegacyAnchorSync
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
    private val profileDatabaseAvailable: Boolean,
    // #173: the process emission hub — when MockProviderService is live, the
    // republish below can trigger its one immediate delivery instead of
    // waiting for the next 1 Hz tick. Null (tests without a delivery runtime)
    // degrades to the pre-#173 behavior honestly.
    private val emissionTrigger: MockProviderEmissionTrigger? = null,
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
        val profileIds = readProfileIds()
        if (profileIds.isNotEmpty()) {
            scheduleStore.initFromProfileIds(profileIds)
        }
    }

    override fun profileRefsSnapshot(): List<String> =
        ProfileRefProjection.fromLegacyIds(readProfileIds())

    /**
     * #140 quick reset: apply a committed reset instruction — generation+1,
     * pointer back to the FIRST item, exhausted cleared — WITHOUT the operator
     * restart's exhaustion guard, removing last-applied residue in the same
     * commit. Idempotent against its exact target (already-applied replays
     * true) so a crash between the reset commit and this external write
     * converges on re-entry.
     */
    override fun resetScheduleToFreshGeneration(targetVersion: Long, firstItemId: String): Boolean =
        scheduleStore.resetToFirstItem(targetVersion, firstItemId)

    /**
     * #140 quick reset: re-anchor the effective profile (the CURRENT schedule
     * item's row) and republish it to the hook transport. Returns the published
     * profile ref, or null when the publish did not happen or failed — an
     * honest partial, never a guessed ref.
     */
    override fun republishCurrentItem(): String? {
        val itemId = currentItemItemId() ?: return null
        val dbId = itemId.removePrefix("profile-").toLongOrNull() ?: return null
        val published = ConfigPrefsSync.sync(appContext, profileId = dbId)
        return if (published) itemId else null
    }

    /**
     * The current schedule item's id, resolvable only for profile-anchored rows
     * over an available profile DB — the shared preconditions of both publish
     * paths below ([republishCurrentItem], [syncLegacyAnchorToCurrentItem]).
     */
    private fun currentItemItemId(): String? {
        if (!profileDatabaseAvailable) return null
        val itemId = scheduleStore.getCurrentItemId() ?: return null
        return itemId.takeIf { it.startsWith("profile-") }
    }

    /**
     * #168: re-anchor the legacy publish chains at the CURRENT schedule item
     * when they lag it. The anchor (publish_state's activeProfileId, persisted
     * by every verified publish) is the durable record of which profile row the
     * published payload reflects; comparing it against the applied item's dbId
     * is the whole gate — equal means the chains already carry this item and
     * the same-item apply replay stays publish-silent, lagging means the same
     * [ConfigPrefsSync.sync] write [republishCurrentItem] performs, now
     * carrying the applied row to BOTH legacy readers (the hook projection and
     * MockProviderMain's 1 Hz refresh).
     *
     * #173: the payload write alone is not enough — the refresh's FIRST
     * delivery of the new coordinates used to wait for the next 1 Hz tick,
     * whose phase does not respect the caller's semantic bracket. The first
     * emission then landed outside the bracket as an unacked changed +2 and
     * the resulting cursor backlog let #167's guard 4 refuse every later ack
     * on the row. So the Republished branch triggers the emission hub
     * SYNCHRONOUSLY: the delivery (and its covered mutation) completes inside
     * the caller's still-open bracket, deep-aggregates into the owner's single
     * +2, and the existing #166/#167 ack covers it. Subsequent ticks then
     * re-deliver bit-identical coordinates — never journalled (the semantic
     * comparator classifies them as no-ops before any mutation begins).
     *
     * Threading: the bracket runs on a binder thread, but the delivery is NOT
     * called directly here — [MockProviderEmissionTrigger] hands it to the
     * service, which runs it on the SAME single worker executor its 1 Hz ticks
     * use (the serialization point for the mock session's provider state) and
     * bounds the wait. Best-effort: a false/absent trigger degrades to the
     * pre-#173 behavior — the next tick delivers, and observations keep
     * reporting any resulting cursor change fail-closed. The payload write
     * itself already succeeded and is not invalidated either way.
     */
    override fun syncLegacyAnchorToCurrentItem(): LegacyAnchorSync {
        val itemId = currentItemItemId() ?: return LegacyAnchorSync.PublishFailed
        val dbId = itemId.removePrefix("profile-").toLongOrNull()
            ?: return LegacyAnchorSync.PublishFailed
        if (ConfigPrefsSync.readActiveProfileId(appContext) == dbId) {
            return LegacyAnchorSync.Current
        }
        return if (ConfigPrefsSync.sync(appContext, profileId = dbId)) {
            runCatching { emissionTrigger?.deliverPublishedNow() }
            LegacyAnchorSync.Republished(itemId)
        } else {
            LegacyAnchorSync.PublishFailed
        }
    }

    /**
     * v1.81 CI-attestation source: the CURRENT schedule item's profile row,
     * resolved by the SAME qwy-owned path as the KB-8 coordinates
     * ([resolveItemCoordinates]) — currentItemId is the single effective-item
     * authority, read here so the projection is internally consistent with the
     * item it names. Read failure = null (discover attests "not configured"),
     * never a partial or zero-filled row.
     */
    override fun configuredCellSnapshot(): ConfiguredCellSnapshot? {
        val itemId = scheduleStore.getCurrentItemId() ?: return null
        return readProfileCellRow(itemId)
    }

    private fun readProfileCellRow(itemId: String): ConfiguredCellSnapshot? {
        if (!itemId.startsWith("profile-")) return null
        if (!profileDatabaseAvailable) return null
        val dbId = itemId.removePrefix("profile-").toLongOrNull() ?: return null
        val dbFile = appContext.getDatabasePath("fakegps.db")
        if (!dbFile.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT ci, tac, pci, mcc, mnc FROM temp WHERE id = ?",
                    arrayOf(dbId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    // Every column is independently nullable: NULL = the operator
                    // left this field unconfigured = passthrough for that field.
                    ConfiguredCellSnapshot(
                        ci = if (cursor.isNull(0)) null else cursor.getLong(0),
                        tac = if (cursor.isNull(1)) null else cursor.getInt(1),
                        pci = if (cursor.isNull(2)) null else cursor.getInt(2),
                        mcc = if (cursor.isNull(3)) null else cursor.getInt(3).toString(),
                        mnc = if (cursor.isNull(4)) null else cursor.getInt(4).toString(),
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readProfileIds(): List<Long> {
        if (!profileDatabaseAvailable) return emptyList()
        val dbFile = appContext.getDatabasePath("fakegps.db")
        if (!dbFile.exists()) return emptyList()
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT id FROM temp ORDER BY id ASC", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
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
        // single coordinate owner.
        val currentItem = scheduleStore.getCurrentItemId()
            ?: throw IllegalStateException("no current schedule item; environment cannot be applied without qwy-owned coordinates")
        val itemProfile = resolveItemProfile(currentItem)
            ?: throw IllegalStateException(
                "schedule item $currentItem has no profile coordinates; the schedule owner must provide them"
            )
        val coords = itemProfile.first to itemProfile.second

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

        // KB-8 completeness: the HOOK payload must be the CURRENT SCHEDULE ITEM's own
        // profile row, not the UI-anchored effective profile. profileId=null resolved to
        // activeProfileId, which nothing advances with the schedule pointer — the hook then
        // spoofed the anchored profile's location for EVERY attempt (device evidence
        // 2026-09-09 ZY22JHW9M4: whole run stuck at loc-01 while the pointer advanced; the
        // 2026-09-06 stress test had the same defect, masked by provider-side assertions).
        // The item id IS a profile dbId (resolveItemCoordinates above already required it).
        val itemProfileDbId = currentItem.removePrefix("profile-").toLongOrNull()
        val published = ConfigPrefsSync.sync(
            appContext,
            profileId = itemProfileDbId,
            clearIfMissing = false,
        )

        // #176 A+C: readback verification + follow. The delivery above is what we INTEND;
        // the readback below is what the device ACTUALLY has (LocationManager fact + the
        // payload file the hook consumes). Three device incidents (payload pinned #175,
        // appops reset 2026-09-11, Vector half-injection 2026-09-06) all had correct-looking
        // provider-side records while the fact was wrong — verify the fact, repair once,
        // and refuse the receipt (typed, fail-closed) if the address still does not follow.
        var publishOutcome = published
        val readbackGate = DeliveryReadbackGate(
            log = { android.util.Log.w("EnvControl", it) },
        )
        val readbackNow = {
            val mock = mockGateway!!.readbackLastLocation()
            val payload = readPublishedPayloadFields()
            DeliveryReadbackGate.Readback(
                mockLatitude = mock?.latitude,
                mockLongitude = mock?.longitude,
                payloadLatitude = payload?.first,
                payloadLongitude = payload?.second,
                payloadAddname = payload?.third,
            )
        }
        val readbackOutcome = readbackGate.enforce(
            expectedLatitude = coords.first,
            expectedLongitude = coords.second,
            expectedAddname = itemProfile.third,
            initialReadback = readbackNow(),
            repairAndReadback = {
                // 修复阶梯第 1 级：完整重投递（重注册 → 重发布 mock → 重发布载荷）后读回。
                mockGateway!!.replaceGpsProvider()
                mockGateway!!.publish(
                    MockLocationConfig(
                        latitude = coords.first,
                        longitude = coords.second,
                    ),
                )
                publishOutcome = ConfigPrefsSync.sync(
                    appContext,
                    profileId = itemProfileDbId,
                    clearIfMissing = false,
                )
                readbackNow()
            },
        )
        if (readbackOutcome is DeliveryReadbackGate.Outcome.Mismatch) {
            throw ContractException(
                ContractErrorCodeV1.CAPABILITY_UNAVAILABLE,
                "environment readback mismatch — delivery does not follow the schedule " +
                    "item $currentItem: ${readbackOutcome.detail}",
            )
        }

        // P2 fix (dsf round-3/4): persist the applied coordinates + publish
        // outcome so observeEffective returns what the mock provider actually
        // has, with verification level matching the real sync result.
        scheduleStore.recordLastApplied(
            coords.first, coords.second, android.os.SystemClock.elapsedRealtime(),
            verified = publishOutcome,
        )

        // P1-2 fix: verification level reflects actual publish outcome.
        val verificationLevel = if (publishOutcome)
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
    companion object {
        /** SharedPreferences 写出的传输文件中 `json` 键的取值（XML 实体转义形态）。 */
        private val JSON_VALUE_REGEX =
            Regex("""<string name="json">([^\x00]*?)</string>""")

    }

    private fun resolveItemCoordinates(itemId: String): Pair<Double, Double>? =
        resolveItemProfile(itemId)?.let { it.first to it.second }

    /**
     * One read-only open resolves the schedule item's whole delivery identity:
     * latitude, longitude and addname. addname is the #176 readback's payload
     * identity probe (#175 pinned the payload while the pointer moved — the
     * coordinates alone matched, the name exposed the drift). Guarded by
     * LegacyRecoveryDirectOpenGuardTest as one of the three allowed direct
     * opens: READONLY, never runs recovery itself — owner-start recovery
     * precedes controller construction.
     */
    private fun resolveItemProfile(itemId: String): Triple<Double, Double, String?>? {
        if (!itemId.startsWith("profile-")) return null
        if (!profileDatabaseAvailable) return null
        val dbId = itemId.removePrefix("profile-").toLongOrNull() ?: return null
        val dbFile = appContext.getDatabasePath("fakegps.db")
        if (!dbFile.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT latitude, longitude, addname FROM temp WHERE id = ?",
                    arrayOf(dbId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val lat = cursor.getDouble(0)
                    val lng = cursor.getDouble(1)
                    val addname = if (cursor.isNull(2)) null else cursor.getString(2)
                    if (lat == 0.0 && lng == 0.0 && cursor.isNull(0) && cursor.isNull(1)) null
                    else if (!cursor.isNull(0) && !cursor.isNull(1)) Triple(lat, lng, addname)
                    else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * #176 读回：hook 消费的载荷**文件**实际内容（lat, lng, addname）。
     *
     * 故意不走 `readPublished`（SharedPreferences 进程内缓存——刚写完再读等于自己对自己，
     * 抓不到"缓存有值但文件未落盘"的半套注入形态），而是读传输文件字节后解析。
     * 读不到/解析失败 → null（门按不可信处理，fail-closed）。
     */
    private fun readPublishedPayloadFields(): Triple<Double, Double, String?>? = try {
        val bytes = ConfigPrefsSync.readPublishedFileBytes(appContext) ?: return null
        val xml = String(bytes, Charsets.UTF_8)
        // 传输文件由本 app 的 SharedPreferences 写出，形状固定：<string name="json">{…}</string>
        val jsonRaw = JSON_VALUE_REGEX.find(xml)?.groupValues?.get(1) ?: return null
        val fields = JSONObject(unescapeXml(jsonRaw)).optJSONObject("fields") ?: return null
        if (!fields.has("latitude") || !fields.has("longitude")) null
        else Triple(
            fields.getDouble("latitude"),
            fields.getDouble("longitude"),
            fields.optString("addname", null as String?),
        )
    } catch (e: Exception) {
        null
    }

    private fun unescapeXml(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

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

/**
 * Deliberately a no-op for now — the contract lane ships without GMS FLP mocking.
 *
 * Wiring the real fused gateway (tried 2026-09-12, ZY22JHW9M4) DID deliver: the
 * fused provider served the payload coordinates and consumer apps (Google Maps)
 * showed the mock. It was reverted because ORACLE_WINDOW_INVALID failures were
 * observed concurrently (GMS re-registers its location requests asynchronously
 * when mock mode flips, and those foreign-uid mutations land inside the
 * post-apply oracle window). CAVEAT: a concurrent UNTRUSTED regression
 * independent of fused (#179 — a revision bump from the test host's own
 * request registration) confounds that attribution; re-test the fused path
 * after #179 is fixed before treating the incompatibility as final. The
 * delivery-side wrapper that worked is preserved in PR #177's history
 * (BestEffortFusedGateway: per-op try/log, 2s bound, constructor falls back
 * to this no-op when GMS is absent).
 */
private object NoopFusedGateway : FusedMockProviderGateway {
    override fun enable() {}
    override fun publish(config: MockLocationConfig) {}
    override fun disable() {}
}


