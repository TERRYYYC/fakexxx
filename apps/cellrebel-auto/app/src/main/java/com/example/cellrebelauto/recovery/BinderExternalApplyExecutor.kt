package com.example.cellrebelauto.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractResultKindV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1

/**
 * R43 (Sol GREEN-review P1-1): the production [ExternalApplyExecutor] over the frozen
 * IEnvironmentControlV1 Binder contract.
 *
 * The provider (Qianwangyou) exposes the service declared by the contract's
 * SERVICE_CLASS_NAME / PROVIDER_APPLICATION_ID constants. Each call resolves the caller from
 * Binder.getCallingUid() provider-side (INV-02); Auto never self-declares.
 *
 * Fail-closed mapping: transport failures (not bound, RemoteException), non-APPLY result kinds,
 * and unknown wire codes all map to a typed [ApplyOutcome] with `leaseId == null` — never an
 * exception into the engine, never a fabricated lease.
 *
 * # 生产 apply/release 执行器：走冻结 Binder 契约；一切失败 fail-closed，绝不发明 lease
 */
class BinderExternalApplyExecutor(
    private val context: Context,
    private val providerApplicationId: String =
        io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
) : ExternalApplyExecutor {

    private var remote: IEnvironmentControlV1? = null

    /** Bind to the provider service (idempotent). Returns false when the provider is unavailable. */
    fun bind(): Boolean {
        if (remote != null) return true
        val intent = Intent().setComponent(
            ComponentName(
                providerApplicationId,
                io.github.terryyyc.fakexxx.contract.v1.ContractV1.SERVICE_CLASS_NAME
            )
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
            val request = ApplyRequestV1(
                intent = EnvironmentIntentV1(
                    runId = "auto-run",
                    attemptId = attemptId.toString(),
                    profileRef = requestDigest, // §6.3.4 canonical digest as the profile ref carrier (single-intent batch v1)
                    scheduleRef = idempotencyKey,
                    requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                    notBeforeEpochMs = now,
                    deadlineEpochMs = now + 600_000L
                ),
                idempotencyKey = idempotencyKey,
                callerProtocolVersion = io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROTOCOL_VERSION
            )
            val result: EnvironmentControlResultV1 = api.apply(request)
            val receipt: ApplyReceiptV1 = result.applyReceipt
                ?: return failOutcome(result)
            if (receipt.idempotencyKey != idempotencyKey) {
                return ApplyOutcome(outcome = "RECEIPT_KEY_MISMATCH", providerHadAlreadyApplied = false, leaseId = null)
            }
            ApplyOutcome(
                outcome = "APPLIED",
                providerHadAlreadyApplied = false,
                leaseId = receipt.leaseId
            )
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
            val receipt: ReleaseReceiptV1 = result.releaseReceipt
                ?: return failOutcome(result)
            if (receipt.leaseId != leaseId) {
                return ApplyOutcome(outcome = "RELEASE_LEASE_MISMATCH", providerHadAlreadyApplied = false)
            }
            ApplyOutcome(outcome = "RELEASED", providerHadAlreadyApplied = false)
        } catch (e: Exception) {
            ApplyOutcome(outcome = "PROVIDER_TRANSPORT_FAILURE", providerHadAlreadyApplied = false)
        }
    }

    private fun failOutcome(result: EnvironmentControlResultV1): ApplyOutcome {
        // Non-APPLY result kinds (incl. typed ERROR with a stable errorCodeWire) map to a typed
        // fail-closed outcome — the diagnostic string is human-only and never enters a decision.
        val kind = result.resultKindOrNull()
        val code = result.errorCodeWire?.toString() ?: kind?.name ?: "UNKNOWN"
        return ApplyOutcome(outcome = "PROVIDER_$code", providerHadAlreadyApplied = false, leaseId = null)
    }
}
