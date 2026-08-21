package com.example.cellrebelauto.automation.advance

import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1

/**
 * Stub [ProviderGateway] for the pre-PR#36 phase.
 *
 * The real ProviderGateway wraps the AIDL ContentResolver client that PR #36
 * introduces. Until that lands, every provider call throws
 * [ProviderNotAvailableException]. The engine catches this and resolves the
 * advance record as "provider_unavailable" — a TERMINAL state (R5 P1-1).
 *
 * Records created with synthesized identity values (pre-PR#36) are NOT
 * replayed when a real gateway arrives. New sessions with the real gateway
 * create fresh records using real provider identity. This prevents synthetic
 * leaseIds/scheduleIds from reaching a real provider.
 *
 * This is NOT a test double. It is the PRODUCTION gateway before the provider
 * binding exists. It makes the production path reachable: the coordinator
 * runs, the quota gate fires, and the advance state is persisted.
 */
class PendingProviderGateway : ProviderGateway {

    override suspend fun completeAndAdvance(
        request: CompleteAndAdvanceRequestV1,
    ): AdvanceReceiptV1 {
        throw ProviderNotAvailableException(
            "ProviderGateway not yet wired — PR #36 scope. " +
                "Advance record persisted; recovery will retry.",
        )
    }

    override suspend fun observe(
        leaseId: String,
        context: ScheduleContext,
    ): EnvironmentObservationV1 {
        throw ProviderNotAvailableException("observe() not yet wired — PR #36 scope.")
    }

    override suspend fun discover(): CapabilitySnapshotV1 {
        throw ProviderNotAvailableException("discover() not yet wired — PR #36 scope.")
    }
}

/**
 * Thrown when the production provider binding is not yet available.
 * Distinct from a runtime failure (which uses "recovery_required").
 * The engine resolves "provider_unavailable" as TERMINAL — these records
 * carry synthesized identity and must not be replayed (R5 P1-1).
 */
class ProviderNotAvailableException(message: String) : RuntimeException(message)
