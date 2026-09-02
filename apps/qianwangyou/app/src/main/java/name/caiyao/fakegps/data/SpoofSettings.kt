package name.caiyao.fakegps.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishPropagation
import name.caiyao.fakegps.integration.v1.QwySemanticWriterRuntime

enum class LocationDeliveryMode(val wireValue: String) {
    HOOK("hook"),
    SYSTEM_MOCK("system_mock");

    companion object {
        fun fromWireValue(value: String?): LocationDeliveryMode =
            entries.firstOrNull { it.wireValue == value } ?: HOOK
    }
}

/**
 * SharedPreferences wrapper for spoof configuration.
 * Read by UI (Compose) and exposed to hooks via AppInfoProvider.
 */
class SpoofSettings internal constructor(
    private val prefs: SharedPreferences,
    private val authoritativePublisher: (() -> Boolean)? = null,
) {

    companion object {
        private const val PREFS_NAME = "spoof_settings"

        const val KEY_SPOOF_MODE = "spoof_mode"
        const val KEY_ACTIVE_HOUR_START = "active_hour_start"
        const val KEY_ACTIVE_HOUR_END = "active_hour_end"
        const val KEY_LOCATION_DELIVERY_MODE = "location_delivery_mode"
        const val KEY_MOCK_PROVIDER_CLEANUP_REQUIRED = "mock_provider_cleanup_required"

        /**
         * How often the hook re-reads the published payload, in seconds.
         *
         * Valid values are defined by [PublishPropagation]; every read goes through
         * `sanitizeInterval` so a corrupt or stale preference degrades to the default cadence
         * rather than to "never refresh".
         */
        const val KEY_REFRESH_INTERVAL_SEC = "refresh_interval_sec"

        /** Mode values — also used by MainHook when reading from ContentProvider */
        const val MODE_ALWAYS_ON = "always_on"
        const val MODE_TIME_BASED = "time_based"
        const val MODE_OFF = "off"

        @Volatile
        private var INSTANCE: SpoofSettings? = null

        fun getInstance(context: Context): SpoofSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: context.applicationContext.let { appContext ->
                    SpoofSettings(
                        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                        authoritativePublisher = { ConfigPrefsSync.sync(appContext) },
                    )
                }.also { INSTANCE = it }
            }
        }
    }

    private val _spoofMode = MutableStateFlow(prefs.getString(KEY_SPOOF_MODE, MODE_ALWAYS_ON)!!)
    val spoofMode: StateFlow<String> = _spoofMode

    private val _activeHourStart = MutableStateFlow(prefs.getInt(KEY_ACTIVE_HOUR_START, 7))
    val activeHourStart: StateFlow<Int> = _activeHourStart

    private val _activeHourEnd = MutableStateFlow(prefs.getInt(KEY_ACTIVE_HOUR_END, 22))
    val activeHourEnd: StateFlow<Int> = _activeHourEnd

    private val _refreshIntervalSec = MutableStateFlow(readRefreshIntervalSec())
    val refreshIntervalSec: StateFlow<Int> = _refreshIntervalSec

    private val _locationDeliveryMode = MutableStateFlow(readLocationDeliveryMode())
    val locationDeliveryMode: StateFlow<LocationDeliveryMode> = _locationDeliveryMode

    fun setSpoofMode(mode: String) {
        QwySemanticWriterRuntime.mutate("spoof-mode") { authoritative ->
            if (authoritative) {
                check(prefs.edit().putString(KEY_SPOOF_MODE, mode).commit()) {
                    "authoritative spoof-mode preference commit failed"
                }
            } else {
                prefs.edit().putString(KEY_SPOOF_MODE, mode).apply()
            }
            _spoofMode.value = mode
            if (authoritative) publishInsideAuthoritativeBracket()
        }
    }

    fun setActiveHourStart(hour: Int) {
        QwySemanticWriterRuntime.mutate("active-hour-start") { authoritative ->
            val sanitized = hour.coerceIn(0, 23)
            if (authoritative) {
                check(prefs.edit().putInt(KEY_ACTIVE_HOUR_START, sanitized).commit()) {
                    "authoritative active-hour-start preference commit failed"
                }
            } else {
                prefs.edit().putInt(KEY_ACTIVE_HOUR_START, sanitized).apply()
            }
            _activeHourStart.value = sanitized
            if (authoritative) publishInsideAuthoritativeBracket()
        }
    }

    fun setActiveHourEnd(hour: Int) {
        QwySemanticWriterRuntime.mutate("active-hour-end") { authoritative ->
            val sanitized = hour.coerceIn(0, 23)
            if (authoritative) {
                check(prefs.edit().putInt(KEY_ACTIVE_HOUR_END, sanitized).commit()) {
                    "authoritative active-hour-end preference commit failed"
                }
            } else {
                prefs.edit().putInt(KEY_ACTIVE_HOUR_END, sanitized).apply()
            }
            _activeHourEnd.value = sanitized
            if (authoritative) publishInsideAuthoritativeBracket()
        }
    }

    /**
     * Persist the hook's refresh cadence.
     *
     * The value is sanitised through [PublishPropagation] on the way IN as well as on the way out,
     * so an out-of-policy interval can never reach storage in the first place.
     */
    fun setRefreshIntervalSec(seconds: Int) {
        val sanitized = PublishPropagation.sanitizeInterval(seconds)
        prefs.edit { putInt(KEY_REFRESH_INTERVAL_SEC, sanitized) }
        _refreshIntervalSec.value = sanitized
    }

    fun setLocationDeliveryMode(mode: LocationDeliveryMode): Boolean {
        return QwySemanticWriterRuntime.mutate("location-delivery-mode") { authoritative ->
            val committed = prefs.edit()
                .putString(KEY_LOCATION_DELIVERY_MODE, mode.wireValue)
                .commit()
            if (committed) _locationDeliveryMode.value = mode
            if (authoritative) {
                check(committed) {
                    "authoritative location-delivery-mode preference commit failed"
                }
                publishInsideAuthoritativeBracket()
            }
            committed
        }
    }

    fun readLocationDeliveryMode(): LocationDeliveryMode = LocationDeliveryMode.fromWireValue(
        prefs.getString(KEY_LOCATION_DELIVERY_MODE, LocationDeliveryMode.HOOK.wireValue),
    )

    fun setMockProviderCleanupRequired(required: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_MOCK_PROVIDER_CLEANUP_REQUIRED, required).commit()

    fun isMockProviderCleanupRequired(): Boolean =
        prefs.getBoolean(KEY_MOCK_PROVIDER_CLEANUP_REQUIRED, false)

    /**
     * Current cadence, always a value the hook can honour.
     *
     * Also the read path for the hook-side scheduler: it must never receive a raw preference,
     * because a 0 or a negative would translate into a busy loop or a frozen snapshot.
     */
    fun readRefreshIntervalSec(): Int = PublishPropagation.sanitizeInterval(
        prefs.getInt(KEY_REFRESH_INTERVAL_SEC, PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC)
    )

    /** Read raw prefs — used by AppInfoProvider (no Flow needed). */
    fun getRawMode(): String = prefs.getString(KEY_SPOOF_MODE, MODE_ALWAYS_ON)!!
    fun getRawHourStart(): Int = prefs.getInt(KEY_ACTIVE_HOUR_START, 7)
    fun getRawHourEnd(): Int = prefs.getInt(KEY_ACTIVE_HOUR_END, 22)

    /** The hook-visible payload is part of the same semantic state as these raw settings. */
    private fun publishInsideAuthoritativeBracket() {
        val publisher = checkNotNull(authoritativePublisher) {
            "authoritative settings publisher is unavailable"
        }
        check(publisher()) {
            "authoritative settings publication failed"
        }
    }
}
