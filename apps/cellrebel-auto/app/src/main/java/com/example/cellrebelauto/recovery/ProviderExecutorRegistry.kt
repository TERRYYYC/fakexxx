package com.example.cellrebelauto.recovery

import android.content.Context
import com.example.cellrebelauto.automation.ProviderPrincipal
import com.example.cellrebelauto.environment.ProviderSignerDigest
import com.example.cellrebelauto.environment.ProviderTrustGate
import java.util.concurrent.atomic.AtomicBoolean

internal class ProviderSignerIdentityException(
    val failureReason: String,
) : IllegalStateException(failureReason)

/**
 * A lease on one registry-owned Binder executor. The interface is intentionally insufficient as a
 * production authority: [requireReadyRegistryIssuedExecutor] also verifies the private concrete
 * implementation, its successful readiness observation, and the executor's current exact bind.
 */
internal interface ProviderExecutorAcquisition : AutoCloseable {
    val executor: ProviderScopedExternalApplyExecutor
    val providerSignerDigest: String
    val bindRequested: Boolean
    suspend fun awaitBound(timeoutMs: Long): Boolean
}

private class RegistryIssuedProviderExecutorAcquisition(
    private val binderExecutor: BinderExternalApplyExecutor,
    override val providerSignerDigest: String,
    override val bindRequested: Boolean,
    private val awaitBoundAction: suspend (Long) -> Boolean,
    private val timeoutAction: () -> Unit,
    private val closeAction: () -> Unit,
) : ProviderExecutorAcquisition {
    override val executor: ProviderScopedExternalApplyExecutor
        get() = binderExecutor

    private val closed = AtomicBoolean(false)

    @Volatile
    private var readinessObserved = false

    override suspend fun awaitBound(timeoutMs: Long): Boolean {
        if (closed.get()) return false
        val ready = awaitBoundAction(timeoutMs)
        if (!ready) {
            readinessObserved = false
            if (closed.compareAndSet(false, true)) timeoutAction()
            return false
        }
        if (closed.get() || !binderExecutor.isBound) return false
        readinessObserved = true
        if (closed.get()) {
            readinessObserved = false
            return false
        }
        return true
    }

    override fun close() {
        readinessObserved = false
        if (closed.compareAndSet(false, true)) closeAction()
    }

    fun requireReadyExecutor(): ProviderScopedExternalApplyExecutor {
        require(!closed.get() && readinessObserved && binderExecutor.isBound) {
            "production provider executor requires a live registry acquisition whose exact bind is ready"
        }
        return binderExecutor
    }

    fun requireReadySignerDigest(): String {
        requireReadyExecutor()
        return providerSignerDigest
    }
}

/** Rejects interface/type claims: only the file-private registry-issued implementation can pass. */
internal fun ProviderExecutorAcquisition.requireReadyRegistryIssuedExecutor():
    ProviderScopedExternalApplyExecutor {
    val issued = this as? RegistryIssuedProviderExecutorAcquisition
        ?: throw IllegalArgumentException("production provider executor capability was not issued by the registry")
    return issued.requireReadyExecutor()
}

/** The signer half of the same opaque, ready registry capability as the Binder executor. */
internal fun ProviderExecutorAcquisition.requireReadyRegistryIssuedSignerDigest(): String {
    val issued = this as? RegistryIssuedProviderExecutorAcquisition
        ?: throw IllegalArgumentException("production provider signer capability was not issued by the registry")
    return issued.requireReadySignerDigest()
}

internal fun ProviderExecutorAcquisition.isReadyRegistryIssued(): Boolean =
    try {
        requireReadyRegistryIssuedExecutor()
        true
    } catch (_: IllegalArgumentException) {
        false
    }

private class RegistryBoundProductionExecutor(
    val issuingAcquisition: RegistryIssuedProviderExecutorAcquisition,
    private val delegate: ProviderScopedExternalApplyExecutor,
) : ProviderScopedExternalApplyExecutor, ExternalApplyExecutor by delegate {
    override val targetApplicationId: String
        get() = delegate.targetApplicationId
}

/** Bind the trust-decorated executor back to the exact private acquisition that issued its Binder. */
internal fun ProviderExecutorAcquisition.bindRegistryIssuedProductionExecutor(
    delegate: ProviderScopedExternalApplyExecutor,
): ProviderScopedExternalApplyExecutor {
    val issued = this as? RegistryIssuedProviderExecutorAcquisition
        ?: throw IllegalArgumentException("production provider executor capability was not issued by the registry")
    val raw = issued.requireReadyExecutor()
    require(delegate.targetApplicationId == raw.targetApplicationId) {
        "production executor target does not match its registry acquisition"
    }
    return RegistryBoundProductionExecutor(issued, delegate)
}

internal fun ProviderExecutorAcquisition.ownsRegistryBoundProductionExecutor(
    executor: ExternalApplyExecutor,
): Boolean {
    val issued = this as? RegistryIssuedProviderExecutorAcquisition ?: return false
    val bound = executor as? RegistryBoundProductionExecutor ?: return false
    return bound.issuingAcquisition === issued && isReadyRegistryIssued()
}

/**
 * Accessibility-service owner for provider Binder executors. Entries are keyed by the frozen plan
 * identity, reference-counted, and removed before unbind so a late callback can only reach its
 * closed executor instance; it can never populate a newer entry for the same applicationId.
 */
internal class ProviderExecutorRegistry(
    context: Context,
    private val currentSignerDigest: (String) -> String? = { applicationId ->
        ProviderTrustGate.packageManagerSignerDigest(context.packageManager, applicationId)
    },
    private val executorFactory: (String) -> BinderExternalApplyExecutor = { applicationId ->
        BinderExternalApplyExecutor(context, applicationId)
    },
) {
    private data class Principal(
        val applicationId: String,
        val signerDigest: String,
    )

    private data class Entry(
        val executor: BinderExternalApplyExecutor,
        var references: Int,
        val bindRequested: Boolean,
    )

    private val entries = mutableMapOf<Principal, Entry>()

    @Synchronized
    fun acquire(
        providerApplicationId: String,
        expectedSignerDigest: String,
    ): ProviderExecutorAcquisition {
        val target = ProviderPrincipal.requireKnownApplicationId(providerApplicationId)
        val expectedSigner = ProviderSignerDigest.normalizeOrNull(expectedSignerDigest)
            ?: throw ProviderSignerIdentityException(PROVIDER_SIGNER_OWNER_UNKNOWN_FAILURE)
        val currentSigner = ProviderSignerDigest.normalizeOrNull(currentSignerDigest(target))
            ?: throw ProviderSignerIdentityException(PROVIDER_SIGNER_OWNER_UNKNOWN_FAILURE)
        if (currentSigner != expectedSigner) {
            throw ProviderSignerIdentityException(PROVIDER_SIGNER_OWNER_CONFLICT_FAILURE)
        }
        val principal = Principal(target, expectedSigner)
        val entry = entries[principal]?.also { it.references++ } ?: run {
            val executor = executorFactory(target)
            executor.installConnectionIdentityValidator {
                ProviderSignerDigest.normalizeOrNull(currentSignerDigest(target)) == expectedSigner
            }
            Entry(executor, references = 1, bindRequested = executor.bind()).also {
                entries[principal] = it
            }
        }
        return RegistryIssuedProviderExecutorAcquisition(
            binderExecutor = entry.executor,
            providerSignerDigest = expectedSigner,
            bindRequested = entry.bindRequested,
            awaitBoundAction = { timeoutMs ->
                entry.executor.awaitBound(timeoutMs) &&
                    ProviderSignerDigest.normalizeOrNull(currentSignerDigest(target)) == expectedSigner
            },
            timeoutAction = { fail(principal, entry.executor) },
            closeAction = { release(principal, entry.executor) },
        )
    }

    @Synchronized
    private fun fail(
        principal: Principal,
        expectedExecutor: BinderExternalApplyExecutor,
    ) {
        val entry = entries[principal] ?: return
        if (entry.executor !== expectedExecutor) return
        entries.remove(principal)
        entry.executor.unbind()
    }

    @Synchronized
    private fun release(
        principal: Principal,
        expectedExecutor: BinderExternalApplyExecutor,
    ) {
        val entry = entries[principal] ?: return
        if (entry.executor !== expectedExecutor) return
        entry.references--
        if (entry.references == 0) {
            // Remove first. A callback racing with unbind belongs only to this now-orphaned object.
            entries.remove(principal)
            entry.executor.unbind()
        }
    }

    @Synchronized
    fun unbindAll() {
        val stale = entries.values.map { it.executor }
        entries.clear()
        stale.forEach { it.unbind() }
    }
}
