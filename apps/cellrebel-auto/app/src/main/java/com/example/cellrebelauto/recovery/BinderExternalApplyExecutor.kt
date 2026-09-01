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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    providerApplicationId: String = ProviderPrincipal.selected,
) : ProviderScopedExternalApplyExecutor {

    override val targetApplicationId: String =
        ProviderPrincipal.requireKnownApplicationId(providerApplicationId)

    private val expectedComponent = ComponentName(targetApplicationId, ContractV1.SERVICE_CLASS_NAME)

    private enum class LifecyclePhase { NEW, BINDING, BOUND, TERMINAL }

    private val lifecycleLock = Any()

    @Volatile
    private var remote: IEnvironmentControlV1? = null

    private var lifecyclePhase: LifecyclePhase = LifecyclePhase.NEW
    private var lifecycleGeneration: Long = 0L
    private var bindAccepted: Boolean = false
    private var connectionIdentityValidator: (() -> Boolean)? = null

    private val boundState = MutableStateFlow(false)

    /** Installed exactly once by the registry before bind; never supplied by a production caller. */
    internal fun installConnectionIdentityValidator(validator: () -> Boolean) {
        synchronized(lifecycleLock) {
            require(lifecyclePhase == LifecyclePhase.NEW && connectionIdentityValidator == null) {
                "connection identity validator must be installed once before bind"
            }
            connectionIdentityValidator = validator
        }
    }

    /** Bind to the provider service (idempotent). Returns false when the provider is unavailable. */
    fun bind(): Boolean {
        val generation = synchronized(lifecycleLock) {
            when (lifecyclePhase) {
                LifecyclePhase.NEW -> {
                    lifecyclePhase = LifecyclePhase.BINDING
                    ++lifecycleGeneration
                }
                LifecyclePhase.BINDING, LifecyclePhase.BOUND -> return bindAccepted || remote != null
                LifecyclePhase.TERMINAL -> return false
            }
        }
        val intent = Intent().setComponent(expectedComponent)
        val requested = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false // provider not installed / not exported — fail closed
        }
        var unbindAcceptedAfterTerminal = false
        val usable = synchronized(lifecycleLock) {
            if (lifecycleGeneration != generation || lifecyclePhase == LifecyclePhase.TERMINAL) {
                // Terminal cleanup may win while bindService is still returning. If Android then
                // reports an accepted bind, close that accepted binding exactly once here.
                unbindAcceptedAfterTerminal = requested
                false
            } else if (!requested) {
                // A hostile/test Context can deliver a synchronous callback and still return false.
                // The bind result is authoritative: discard the callback without unbinding a
                // binding Android says was never established.
                lifecyclePhase = LifecyclePhase.TERMINAL
                lifecycleGeneration++
                bindAccepted = false
                remote = null
                boundState.value = false
                false
            } else {
                bindAccepted = true
                lifecyclePhase = if (remote != null) LifecyclePhase.BOUND else LifecyclePhase.BINDING
                true
            }
        }
        if (unbindAcceptedAfterTerminal) {
            try {
                context.unbindService(connection)
            } catch (_: Exception) {
                // The terminal state is authoritative even if Android already dropped the bind.
            }
        }
        return usable
    }

    /** Wait until the exact component's asynchronous callback publishes a usable interface. */
    suspend fun awaitBound(timeoutMs: Long): Boolean {
        val eligible = synchronized(lifecycleLock) {
            bindAccepted && lifecyclePhase != LifecyclePhase.TERMINAL
        }
        if (!eligible || timeoutMs <= 0L) {
            // Readiness failure is terminal for this executor instance. In particular, a zero
            // timeout must revoke an already-accepted bind request before its callback can race in.
            unbind()
            return false
        }
        if (synchronized(lifecycleLock) {
                lifecyclePhase == LifecyclePhase.BOUND && remote != null
            }
        ) return true
        val ready = withTimeoutOrNull(timeoutMs) {
            boundState.first { it }
            true
        } ?: false
        if (!ready) {
            unbind()
            return false
        }
        return synchronized(lifecycleLock) {
            bindAccepted && lifecyclePhase == LifecyclePhase.BOUND && remote != null
        }
    }

    fun unbind() {
        val shouldUnbind = synchronized(lifecycleLock) {
            if (lifecyclePhase == LifecyclePhase.TERMINAL) return
            lifecyclePhase = LifecyclePhase.TERMINAL
            lifecycleGeneration++
            val accepted = bindAccepted
            bindAccepted = false
            remote = null
            boundState.value = false
            accepted
        }
        try {
            if (shouldUnbind) context.unbindService(connection)
        } catch (e: Exception) {
            // not bound — nothing to do
        }
    }

    val isBound: Boolean
        get() = synchronized(lifecycleLock) {
            lifecyclePhase == LifecyclePhase.BOUND && remote != null
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val admittedGeneration = synchronized(lifecycleLock) {
                if (lifecyclePhase == LifecyclePhase.TERMINAL || name != expectedComponent) return
                lifecycleGeneration
            }
            // Interface conversion can call into a local Binder implementation. Do it outside the
            // lock, then re-check the generation before publication so terminal timeout cannot be
            // blocked by or lose to a slow/hostile conversion.
            val candidate = IEnvironmentControlV1.Stub.asInterface(service)
            val identityMatches = try {
                // Direct executor construction is an explicit low-level test seam. Production
                // composition accepts only registry acquisitions, and the registry always installs
                // this exact-signer validator before bind.
                connectionIdentityValidator?.invoke() ?: true
            } catch (_: Exception) {
                false
            }
            if (!identityMatches) {
                unbind()
                return
            }
            synchronized(lifecycleLock) {
                if (lifecycleGeneration != admittedGeneration ||
                    lifecyclePhase == LifecyclePhase.TERMINAL ||
                    name != expectedComponent
                ) return
                remote = candidate
                lifecyclePhase = if (candidate != null) LifecyclePhase.BOUND else LifecyclePhase.BINDING
                boundState.value = candidate != null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearPublishedRemote(name)
        }

        override fun onBindingDied(name: ComponentName?) {
            if (name != expectedComponent) return
            unbind()
        }

        override fun onNullBinding(name: ComponentName?) {
            if (name != expectedComponent) return
            unbind()
        }
    }

    private fun clearPublishedRemote(name: ComponentName?) {
        synchronized(lifecycleLock) {
            if (name != expectedComponent || lifecyclePhase == LifecyclePhase.TERMINAL) return
            remote = null
            lifecyclePhase = LifecyclePhase.BINDING
            boundState.value = false
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
