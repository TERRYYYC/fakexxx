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
     * Direct-boot safety (#194). The acceptance record lives in credential-encrypted storage,
     * yet this process can start inside the direct-boot window: system_server's boot phase-600
     * bind pulls up the directBootAware OracleBridgeService (and with it this Application)
     * before the user has unlocked the device. Reading CE prefs before unlock throws, and an
     * unprotected throw here killed the whole process at startup — which also took down the
     * system_server oracle bridge registration.
     *
     * A locked device (or any CE read failure) is therefore reported as "no pending work".
     * Callers defer to the next process start after unlock: the durable record is still on
     * disk and recovery runs as usual then. The acceptance harness itself only ever runs
     * unlocked, so deferral can never lose a recoverable transaction.
     */
    fun hasPending(context: Context): Boolean = runCatching {
        isDeviceUnlocked(context) && recordPrefs(context).getBoolean(KEY_PENDING, false)
    }.onFailure {
        Log.w(TAG, "pending_check_deferred", it)
    }.getOrDefault(false)

    /**
     * Fingerprint of the durable payload itself, before any normal Activity publish can mask it.
     * Shares [hasPending]'s direct-boot semantics: a locked CE store yields null instead of
     * throwing.
     */
    fun pendingFingerprint(context: Context): String? = runCatching {
        if (isDeviceUnlocked(context)) pendingPayload(context)?.let(PublishedConfig::fingerprint) else null
    }.onFailure {
        Log.w(TAG, "pending_fingerprint_deferred", it)
    }.getOrNull()

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
