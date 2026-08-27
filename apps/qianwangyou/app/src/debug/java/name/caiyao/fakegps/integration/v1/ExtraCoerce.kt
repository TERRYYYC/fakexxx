package name.caiyao.fakegps.integration.v1

/**
 * P10DBG-COLLECTOR-V1 — adb extra type coercion (R2, gpt55 P1-1).
 *
 * WHY
 * ---
 * `am start --ei name 30000` stores an **Integer**; `--el` stores a Long.
 * `Intent.getLongExtra` delegates to `BaseBundle.getLong`, which returns the
 * DEFAULT when the stored value is not a Long — so the documented `--ei
 * hold_ms 30000` style command silently armed a 0ms hold, and `--es
 * revoke_run_cleanup 1` (a String) silently meant false. A collector whose
 * documented commands do not do what they say produces wrong evidence on a
 * device that will never be debugged interactively.
 *
 * Every numeric/boolean extra is therefore read with `extras?.get(key)` and
 * coerced HERE, where the rules are JVM-testable. Accepted forms are
 * deliberately narrow: Int/Long pass through, a String must parse; anything
 * else is null (→ caller refuses or uses its documented default), never a
 * guess.
 */
object ExtraCoerce {

    fun longOf(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    fun boolOf(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Int -> when (value) { 0 -> false; 1 -> true; else -> null }
        is String -> when (value.trim().lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
        else -> null
    }
}
