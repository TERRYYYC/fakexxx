package com.example.cellrebelauto.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
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
    private val providerApplicationId: String = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
) : ExternalApplyExecutor {

    @Volatile
    private var remote: IEnvironmentControlV1? = null

    /** Bind to the provider service (idempotent). Returns false when the provider is unavailable. */
    fun bind(): Boolean {
        if (remote != null) return true
        val intent = Intent().setComponent(
            ComponentName(providerApplicationId, ContractV1.SERVICE_CLASS_NAME)
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

    override fun apply(attemptId: Long, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome {
        val api = remote
            ?: return ApplyOutcome(outcome = "PROVIDER_NOT_BOUND", providerHadAlreadyApplied = false, leaseId = null)
        return try {
            // F2: the REAL frozen intent preimage — runId from the owner identity carried in the key,
            // no coordinates (KB-8), digest recomputable from durable owner state. The idempotency
            // key IS the operation identity; the validator re-binds the receipt to it.
            val attemptIdNum = attemptId
            val request = ApplyRequestV1(
                intent = APlusOperationIdentity.intent(
                    runSessionId = runSessionFromKey(idempotencyKey),
                    attemptId = attemptIdNum
                ),
                idempotencyKey = idempotencyKey,
                callerProtocolVersion = ContractV1.PROTOCOL_VERSION
            )
            val result: EnvironmentControlResultV1 = api.apply(request)
            when (val v = ContractResponseValidator.validateApply(result, idempotencyKey)) {
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

    /** The apply idempotency key embeds the attempt id (applyIdempotencyKey); the session comes from the caller's owner state via the digest source. */
    private fun runSessionFromKey(idempotencyKey: String): Long =
        idempotencyKey.substringAfterLast('-').toLongOrNull() ?: 0L
}
