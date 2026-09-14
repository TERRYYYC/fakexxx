package name.caiyao.fakegps.probe

import android.content.Context
import android.os.UserManager
import android.util.Log
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig

internal object HookAcceptanceRecovery {
    const val TAG = "FakeGPSAcceptanceRecovery"
    const val RECORD_PREFS_NAME = "hook_acceptance_recovery"
    const val KEY_PENDING = "pending"
    private const val KEY_PREVIOUS_JSON = "previous_json"

    /**
     * Direct-boot safety (#194), with the locked/unlocked log distinction from the #195 review.
     * The acceptance record lives in credential-encrypted storage, yet this process can start
     * inside the direct-boot window: system_server's boot phase-600 bind pulls up the
     * directBootAware OracleBridgeService (and with it this Application) before the user has
     * unlocked the device. Reading CE prefs before unlock throws, and an unprotected throw here
     * killed the whole process at startup — which also took down the system_server oracle
     * bridge registration.
     *
     * The probes deliberately attempt the CE read BEFORE the unlock gate so that a locked-state
     * read failure stays observable as the expected WARN `*_deferred` (the log the #194 device
     * validation greps for) instead of vanishing behind a short-circuit; the gate then ANDs the
     * read result with the unlock state, so a locked-but-readable store still reports "no
     * pending work" and the durable record is left untouched for the next start after unlock.
     * The acceptance harness itself only ever runs unlocked, so deferral can never lose a
     * recoverable transaction. A CE read failure while the device is ALREADY unlocked is a
     * different beast — nothing will ever re-trigger recovery for this start, so "deferred"
     * would mislead; it is logged at ERROR (`*_unlocked_read_failure`).
     */
    fun hasPending(context: Context): Boolean =
        directBootGuarded(
            context,
            default = false,
            lockedLog = "pending_check_deferred",
            unlockedFailureLog = "pending_check_unlocked_read_failure",
        ) { unlocked ->
            recordPrefs(context).getBoolean(KEY_PENDING, false) && unlocked
        }

    /**
     * Fingerprint of the durable payload itself, before any normal Activity publish can mask it.
     * Shares [hasPending]'s direct-boot semantics: a locked CE store yields null instead of
     * throwing, and an unlocked-state read failure escalates to ERROR instead of "deferred".
     */
    fun pendingFingerprint(context: Context): String? =
        directBootGuarded(
            context,
            default = null,
            lockedLog = "pending_fingerprint_deferred",
            unlockedFailureLog = "pending_fingerprint_unlocked_read_failure",
        ) { unlocked ->
            val payload = pendingPayload(context)
            if (unlocked) payload?.let(PublishedConfig::fingerprint) else null
        }

    /**
     * Runs a CE probe under the direct-boot contract: failures never propagate. The unlock
     * probe itself runs inside the catch scope — if even that fails, the failure is reported
     * on the unlocked (real-anomaly) branch, consistent with [isDeviceUnlocked] treating an
     * unprobeable device as unlocked.
     */
    private inline fun <T> directBootGuarded(
        context: Context,
        default: T,
        lockedLog: String,
        unlockedFailureLog: String,
        op: (deviceUnlocked: Boolean) -> T,
    ): T {
        var deviceUnlocked = true
        return runCatching { op(isDeviceUnlocked(context).also { deviceUnlocked = it }) }
            .onFailure { failure ->
                if (deviceUnlocked) {
                    Log.e(TAG, unlockedFailureLog, failure)
                } else {
                    Log.w(TAG, lockedLog, failure)
                }
            }
            .getOrDefault(default)
    }

    /**
     * True only while credential-encrypted storage is readable. A missing UserManager (host
     * JVM / early shadows) counts as unlocked so the gate never blocks a real recovery.
     */
    private fun isDeviceUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    fun prepare(context: Context): Boolean = runCatching {
        coordinator(context).prepare()
    }.onFailure {
        Log.e(TAG, "prepare_failed", it)
    }.getOrDefault(false)

    fun recoverIfPending(context: Context): Boolean = runCatching {
        if (hasPending(context) && pendingPayload(context) == null) {
            false
        } else {
            coordinator(context).recoverIfPending()
        }
    }.onFailure {
        Log.e(TAG, "recovery_failed", it)
    }.getOrDefault(false)

    fun complete(context: Context): Boolean = runCatching {
        coordinator(context).complete()
    }.onFailure {
        Log.e(TAG, "clear_failed", it)
    }.getOrDefault(false)

    private fun coordinator(context: Context) = HookAcceptanceRecoveryCoordinator(
        record = object : HookAcceptanceRecoveryCoordinator.Record {
            override fun readPending(): String? {
                val prefs = recordPrefs(context)
                if (!prefs.getBoolean(KEY_PENDING, false)) return null
                return prefs.getString(KEY_PREVIOUS_JSON, null)
            }

            override fun savePending(previousPayload: String): Boolean =
                recordPrefs(context)
                    .edit()
                    .putString(KEY_PREVIOUS_JSON, previousPayload)
                    .putBoolean(KEY_PENDING, true)
                    .commit()

            override fun clear(): Boolean =
                recordPrefs(context).edit().clear().commit()
        },
        transport = object : HookAcceptanceRecoveryCoordinator.Transport {
            @Suppress("DEPRECATION")
            override fun readCurrent(): String? =
                context.getSharedPreferences(
                    ConfigPrefsSync.PREFS_NAME,
                    Context.MODE_WORLD_READABLE,
                ).getString(ConfigPrefsSync.KEY_JSON, null)

            @Suppress("DEPRECATION")
            override fun publish(payload: String): Boolean =
                context.getSharedPreferences(
                    ConfigPrefsSync.PREFS_NAME,
                    Context.MODE_WORLD_READABLE,
                )
                    .edit()
                    .putString(ConfigPrefsSync.KEY_JSON, payload)
                    .commit()
        },
    )

    private fun recordPrefs(context: Context) =
        context.getSharedPreferences(RECORD_PREFS_NAME, Context.MODE_PRIVATE)

    private fun pendingPayload(context: Context): String? {
        val prefs = recordPrefs(context)
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        return prefs.getString(KEY_PREVIOUS_JSON, null)
    }
}
