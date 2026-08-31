package com.example.cellrebelauto.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.cellrebelauto.automation.ProviderPrincipal
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1

/**
 * R43 (Sol GREEN-review-2 F1/F2): the production [ExternalApplyExecutor] over the frozen
 * IEnvironmentControlV1 Binder contract.
 *
 * F2 wiring: every response passes through [ContractResponseValidator] (schema/kind/payload
 * exclusivity/key binding/releaseComplete); the apply request carries a REAL frozen
 * [com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.intent] preimage (KB-8: no
 * coordinates; runId derived from the owner session — never the invented "auto-run" constant, never
 * the digest stuffed into profileRef).
 *
 * Fail-closed mapping: transport failures, validator failures, and unknown wire codes all map to a
 * typed [ApplyOutcome] with `leaseId == null` — never an exception into the engine, never a
 * fabricated lease.
 *
 * # 生产 apply/release 执行器：冻结 intent preimage + 统一响应校验；一切失败 fail-closed
 */
class BinderExternalApplyExecutor(
    private val context: Context,
    providerApplicationId: String = ProviderPrincipal.selected
) : ExternalApplyExecutor {

    val targetApplicationId: String = providerApplicationId

    @Volatile
    private var remote: IEnvironmentControlV1? = null

    /** Bind to the provider service (idempotent). Returns false when the provider is unavailable. */
    fun bind(): Boolean {
        if (remote != null) return true
        val intent = Intent().setComponent(
            ComponentName(targetApplicationId, ContractV1.SERVICE_CLASS_NAME)
        )
        return try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false // provider not installed / not exported — fail closed
        }
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            // not bound — nothing to do
        }
        remote = null
    }

    val isBound: Boolean get() = remote != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IEnvironmentControlV1.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    override fun apply(
        attemptId: Long,
        intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
        idempotencyKey: String,
        requestDigest: String,
        now: Long
    ): ApplyOutcome {
        val api = remote
            ?: return ApplyOutcome(outcome = "PROVIDER_NOT_BOUND", providerHadAlreadyApplied = false, leaseId = null)
        return try {
            // F2 (R44): the request carries the SAME intent object the caller digested — no
            // recomputation here, so the wire preimage and the digest can never drift apart.
            val request = ApplyRequestV1(
                intent = intent,
                idempotencyKey = idempotencyKey,
                callerProtocolVersion = ContractV1.PROTOCOL_VERSION
            )
            val result: EnvironmentControlResultV1 = api.apply(request)
            when (val v = ContractResponseValidator.validateApply(result, idempotencyKey, expectedIntentHash = requestDigest)) {
                is ContractResponseValidator.ValidatedContractResponse.Success ->
                    ApplyOutcome(
                        outcome = "APPLIED", providerHadAlreadyApplied = false, leaseId = v.payload.leaseId,
                        operationId = v.payload.operationId,
                        acceptedIntentHash = v.payload.acceptedIntentHash,
                        appliedAtEpochMs = v.payload.appliedAtEpochMs,
                        environmentRevision = v.payload.environmentRevision,
                        verificationLevelWire = v.payload.verificationLevelWire
                    )
                is ContractResponseValidator.ValidatedContractResponse.Failure ->
                    ApplyOutcome(outcome = v.typedOutcome, providerHadAlreadyApplied = false, leaseId = null)
            }
        } catch (e: Exception) {
            ApplyOutcome(outcome = "PROVIDER_TRANSPORT_FAILURE", providerHadAlreadyApplied = false, leaseId = null)
        }
    }

    override fun release(
        attemptId: Long,
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        now: Long
    ): ApplyOutcome {
        val api = remote
            ?: return ApplyOutcome(outcome = "PROVIDER_NOT_BOUND", providerHadAlreadyApplied = false)
        return try {
            val request = ReleaseRequestV1(
                leaseId = leaseId,
                operationId = releaseDigest, // §6.3.4 release digest carried as the release operation id
                idempotencyKey = idempotencyKey
            )
            val result: EnvironmentControlResultV1 = api.release(request)
            when (val v = ContractResponseValidator.validateRelease(result, leaseId)) {
                is ContractResponseValidator.ValidatedContractResponse.Success ->
                    ApplyOutcome(outcome = "RELEASED", providerHadAlreadyApplied = false)
                is ContractResponseValidator.ValidatedContractResponse.Failure ->
                    ApplyOutcome(outcome = v.typedOutcome, providerHadAlreadyApplied = false)
            }
        } catch (e: Exception) {
            ApplyOutcome(outcome = "PROVIDER_TRANSPORT_FAILURE", providerHadAlreadyApplied = false)
        }
    }

    // ---- R44 (Sol GREEN-review-3 F1): the frozen §6.1 journey surface, same connection, same
    // validator — every failure mode fail-closes to null. ----

    override fun discover(): io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1? {
        val api = remote ?: return null
        return try {
            when (val v = ContractResponseValidator.validateDiscover(api.discover())) {
                is ContractResponseValidator.ValidatedContractResponse.Success -> v.payload
                is ContractResponseValidator.ValidatedContractResponse.Failure -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun preflight(
        intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
        idempotencyKey: String,
        requestDigest: String
    ): io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1? {
        val api = remote ?: return null
        return try {
            val request = io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1(
                intent = intent,
                idempotencyKey = idempotencyKey,
                callerProtocolVersion = ContractV1.PROTOCOL_VERSION
            )
            when (val v = ContractResponseValidator.validatePreflight(api.preflight(request), requestDigest)) {
                is ContractResponseValidator.ValidatedContractResponse.Success -> v.payload
                is ContractResponseValidator.ValidatedContractResponse.Failure -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun observe(
        leaseId: String,
        operationId: String,
        expectedIntentHash: String
    ): io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1? {
        val api = remote ?: return null
        return try {
            val request = io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1(
                leaseId = leaseId,
                operationId = operationId,
                expectedIntentHash = expectedIntentHash
            )
            when (val v = ContractResponseValidator.validateObserve(api.observe(request), leaseId, expectedIntentHash)) {
                is ContractResponseValidator.ValidatedContractResponse.Success -> v.payload
                is ContractResponseValidator.ValidatedContractResponse.Failure -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun completeAndAdvance(
        request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1,
        expectedIntentHash: String
    ): io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1? {
        val api = remote ?: return null
        return try {
            when (val v = ContractResponseValidator.validateCompleteAndAdvance(api.completeAndAdvance(request), expectedIntentHash, request.requestDigest, request.idempotencyKey)) {
                is ContractResponseValidator.ValidatedContractResponse.Success -> v.payload
                is ContractResponseValidator.ValidatedContractResponse.Failure -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
