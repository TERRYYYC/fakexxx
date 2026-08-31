package com.example.cellrebelauto.environment

import java.util.Locale

/** Single canonical representation for provider signing-certificate SHA-256 digests. */
internal object ProviderSignerDigest {
    private val canonicalPattern = Regex("^sha256:[0-9a-f]{64}$")

    fun normalizeOrNull(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        return normalized.takeIf(canonicalPattern::matches)
    }

    fun requireCanonical(value: String): String = requireNotNull(normalizeOrNull(value)) {
        "provider signer digest must be canonical sha256:<64 lowercase hex>"
    }
}
