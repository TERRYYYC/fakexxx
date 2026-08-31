package com.example.cellrebelauto.recovery

internal const val PROVIDER_PRINCIPAL_UNKNOWN_FAILURE = "PROVIDER_PRINCIPAL_UNKNOWN"
internal const val PROVIDER_PRINCIPAL_CONFLICT_FAILURE = "PROVIDER_PRINCIPAL_CONFLICT"
internal const val PROVIDER_SIGNER_OWNER_UNKNOWN_FAILURE = "PROVIDER_SIGNER_OWNER_UNKNOWN"
internal const val PROVIDER_SIGNER_OWNER_CONFLICT_FAILURE = "PROVIDER_SIGNER_OWNER_CONFLICT"
internal const val PROVIDER_SIGNER_UNTRUSTED_RELEASE_FAILURE = "PROVIDER_SIGNER_UNTRUSTED"

/** Stable failure classes used across coordinator, engine, repository, and UI projection. */
internal enum class ProviderPrincipalFailureKind { UNKNOWN, CONFLICT, UNTRUSTED }

/**
 * Typed in-process carrier for provider-owner failures. [durableCode] is the existing stable Room
 * value; parsing is exact equality only, never message-prefix/substring inference.
 */
internal enum class ProviderPrincipalFailureReason(
    val durableCode: String,
    val kind: ProviderPrincipalFailureKind,
) {
    PRINCIPAL_UNKNOWN(PROVIDER_PRINCIPAL_UNKNOWN_FAILURE, ProviderPrincipalFailureKind.UNKNOWN),
    PRINCIPAL_CONFLICT(PROVIDER_PRINCIPAL_CONFLICT_FAILURE, ProviderPrincipalFailureKind.CONFLICT),
    SIGNER_OWNER_UNKNOWN(PROVIDER_SIGNER_OWNER_UNKNOWN_FAILURE, ProviderPrincipalFailureKind.UNKNOWN),
    SIGNER_OWNER_CONFLICT(PROVIDER_SIGNER_OWNER_CONFLICT_FAILURE, ProviderPrincipalFailureKind.CONFLICT),
    SIGNER_UNTRUSTED(
        PROVIDER_SIGNER_UNTRUSTED_RELEASE_FAILURE,
        ProviderPrincipalFailureKind.UNTRUSTED,
    );

    companion object {
        fun fromDurableCode(code: String?): ProviderPrincipalFailureReason? =
            entries.singleOrNull { it.durableCode == code }
    }
}

/** Typed exception at the repository transaction boundary; callers never parse its message. */
internal class ProviderPrincipalFailureException(
    val reason: ProviderPrincipalFailureReason,
    detail: String,
) : IllegalStateException("${reason.durableCode}: $detail")
