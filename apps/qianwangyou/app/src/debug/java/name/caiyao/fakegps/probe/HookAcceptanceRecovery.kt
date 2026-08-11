package name.caiyao.fakegps.probe

import android.content.Context
import android.util.Log
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig

internal object HookAcceptanceRecovery {
    const val TAG = "FakeGPSAcceptanceRecovery"
    const val RECORD_PREFS_NAME = "hook_acceptance_recovery"
    const val KEY_PENDING = "pending"
    private const val KEY_PREVIOUS_JSON = "previous_json"

    fun hasPending(context: Context): Boolean =
        recordPrefs(context).getBoolean(KEY_PENDING, false)

    /** Fingerprint of the durable payload itself, before any normal Activity publish can mask it. */
    fun pendingFingerprint(context: Context): String? =
        pendingPayload(context)?.let(PublishedConfig::fingerprint)

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
