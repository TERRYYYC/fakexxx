package com.example.cellrebelauto.integration.v1

/**
 * P10DBG-COLLECTOR-V1 — adb extra type coercion (R2, gpt55 P1-1).
 *
 * Same rationale as the qwy-side helper (no shared module: INV-19):
 * `am start --ei` stores an Integer, `--el` a Long, `--es` a String;
 * `getLongExtra`/`getBooleanExtra` silently return defaults on type mismatch,
 * so documented commands would arm the wrong window. Coerce here, where the
 * rules are JVM-testable.
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
