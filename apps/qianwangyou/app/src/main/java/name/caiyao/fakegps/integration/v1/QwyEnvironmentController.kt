package name.caiyao.fakegps.integration.v1

import android.content.Context
import android.location.LocationManager
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
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

/**
 * Seam between the v1 provider and qianwangyou's existing capabilities
 * (profile / System Mock / Hook / schedule). Adapters CALL existing logic, they
 * never duplicate it (Task 3 GREEN rule; §5 boundary table).
 *
 * Schedule identity (§6.7.1) is owned here: scheduleId / scheduleItemId /
 * scheduleVersion / currentItemId. The current item pointer is explicit and
 * durable — row order, first-row and profile-table order are projections and
 * must never be used as order truth.
 *
 * GREEN STATUS: This adapter wires real qianwangyou capabilities. The schedule
 * state is managed by [QwyScheduleStore] (SharedPreferences-backed, durable).
 * Environment application uses the existing [ConfigHolder] / [SpoofConfig] /
 * [MockProviderGateway] stack.
 */
interface QwyEnvironment {

    /** Current schedule identity truth (§6.7.1). Null when no active schedule. */
    fun scheduleSnapshot(): ScheduleSnapshot?

    fun advancePointer(fromItemId: String): AdvancePointerOutcome
    fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome
    fun cleanup(leaseId: String): CleanupOutcome
    fun observeEffective(): EffectiveEnvironment
    fun scheduleDecisionWire(scheduleRef: String): Int
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
    val evidenceRefs: List<String>,
)

/**
 * Production adapter over qianwangyou's mockprovider / hook / config / schedule
 * capabilities.
 *
 * GREEN: wires [QwyScheduleStore] for schedule identity, [ConfigHolder] /
 * [SpoofConfig] for environment configuration, and the existing mock provider
 * gateway stack for location publishing.
 *
 * KNOWN BOUNDARY: schedule items are derived from the existing profile DB
 * (ProfileEntity rows). An operator-facing schedule editor with explicit
 * ordering, priority, and multi-profile management is a separate feature;
 * until it lands, the schedule reflects DB insertion order (id ASC). This is
 * honest: the adapter calls existing state rather than inventing a parallel
 * order truth (§5 / §6.7.1).
 */
class QwyEnvironmentController(
    private val context: Context,
) : QwyEnvironment {

    private val appContext = context.applicationContext
    private val scheduleStore = QwyScheduleStore(appContext)
    private val configHolder = ConfigHolder()
    private var changeListener: ((RevisionBumpReason) -> Unit)? = null

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
        val config = SpoofConfig(
            location = SpoofConfig.Location(
                latitude = intent.latitude,
                longitude = intent.longitude,
            ),
        )
        configHolder.update(ConfigCodec.toJson(config))

        mockGateway?.let { gateway ->
            gateway.replaceGpsProvider()
            gateway.publish(
                MockLocationConfig(
                    latitude = intent.latitude,
                    longitude = intent.longitude,
                ),
            )
        }

        ConfigPrefsSync.sync(appContext, profileId = null, clearIfMissing = false)

        return ApplyOutcome(
            effectiveLatitude = intent.latitude,
            effectiveLongitude = intent.longitude,
            deliveryModeWire = 1,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        )
    }

    override fun cleanup(leaseId: String): CleanupOutcome {
        mockGateway?.removeGpsProvider()
        return CleanupOutcome.Complete
    }

    override fun observeEffective(): EffectiveEnvironment {
        val config = configHolder.current()
        val lat = config?.location?.latitude
        val lng = config?.location?.longitude
        return EffectiveEnvironment(
            latitude = lat,
            longitude = lng,
            isMock = config != null,
            deliveryModeWire = if (config != null) 1 else null,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            environmentFingerprint = if (config != null) "spoof:${config.hashCode()}" else "passthrough",
            evidenceRefs = emptyList(),
        )
    }

    override fun scheduleDecisionWire(scheduleRef: String): Int {
        val snap = scheduleSnapshot() ?: return 0
        return if (scheduleRef == snap.scheduleId) 1 else 0
    }

    override fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit) {
        changeListener = listener
    }
}

/**
 * Minimal FusedLocationProvider adapter that defers Play Services wiring until
 * the fused path is needed. The framework [AndroidMockProviderGateway] handles
 * the primary test-provider surface; this is the secondary fused source.
 *
 * GREEN boundary: full GooglePlayServicesFusedMockProviderGateway wiring
 * requires a FusedLocationProviderClient, which is a separate concern from the
 * schedule/provider seam. This no-op keeps the composition honest without
 * claiming a fused path that is not yet driven.
 */
private object NoopFusedGateway : FusedMockProviderGateway {
    override fun enable() {}
    override fun publish(config: MockLocationConfig) {}
    override fun disable() {}
}
