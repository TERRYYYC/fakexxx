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
 * [ProviderNotAvailableException]. The engine catches this and leaves the
 * advance record in "provider_unavailable" state — the recovery sweep will
 * retry when a real gateway is wired.
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
 * Distinct from a runtime failure: the engine persists the advance state
 * as "provider_unavailable" rather than "recovery_required".
 */
class ProviderNotAvailableException(message: String) : RuntimeException(message)
