package io.github.terryyyc.fakexxx.integration.pr63issue66;

import android.content.Context;
import com.example.cellrebelauto.BuildConfig;
import com.example.cellrebelauto.automation.ProviderPrincipal;
import com.example.cellrebelauto.recovery.ApplyOutcome;
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor;
import com.example.cellrebelauto.recovery.ProviderExecutorAcquisition;
import com.example.cellrebelauto.recovery.ProviderExecutorRegistry;
import com.example.cellrebelauto.recovery.ProviderExecutorRegistryKt;
import com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor;
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1;
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;

/**
 * Integration-test-only Java bridge to Auto's Kotlin-internal production acquisition boundary.
 *
 * <p>Java is intentional: Kotlin module visibility remains unchanged, and the bridge lives only
 * under the isolated integration harness's test sources. It exposes behavior, never a forgeable
 * production capability.</p>
 */
public final class AutoIntegrationBridge implements AutoCloseable {
    private final ProviderExecutorRegistry registry;
    private final ProviderExecutorAcquisition acquisition;
    private final ProviderScopedExternalApplyExecutor executor;

    private AutoIntegrationBridge(
            ProviderExecutorRegistry registry,
            ProviderExecutorAcquisition acquisition,
            ProviderScopedExternalApplyExecutor executor) {
        this.registry = registry;
        this.acquisition = acquisition;
        this.executor = executor;
    }

    public static AutoIntegrationBridge connect(
            Context context,
            String providerApplicationId,
            String expectedSignerDigest) throws InterruptedException {
        ProviderExecutorRegistry registry = new ProviderExecutorRegistry(
                context,
                ignored -> expectedSignerDigest,
                target -> new BinderExternalApplyExecutor(context, target));
        ProviderExecutorAcquisition acquisition =
                registry.acquire(providerApplicationId, expectedSignerDigest);
        Boolean ready = BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                new Function2<CoroutineScope, Continuation<? super Boolean>, Object>() {
                    @Override
                    public Object invoke(
                            CoroutineScope scope,
                            Continuation<? super Boolean> continuation) {
                        return acquisition.awaitBound(5_000L, continuation);
                    }
                });
        if (!Boolean.TRUE.equals(ready)) {
            closeAcquisition(acquisition);
            registry.unbindAll();
            throw new IllegalStateException("exact provider acquisition did not become ready");
        }
        return new AutoIntegrationBridge(
                registry,
                acquisition,
                ProviderExecutorRegistryKt.requireReadyRegistryIssuedExecutor(acquisition));
    }

    public static String autoApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    public static String selectedProviderTarget() {
        return ProviderPrincipal.INSTANCE.getSelected();
    }

    public CapabilitySnapshotV1 discover() {
        return executor.discover();
    }

    public ApplySnapshot apply(
            long attemptId,
            EnvironmentIntentV1 intent,
            String idempotencyKey,
            String requestDigest,
            long nowEpochMs) {
        ApplyOutcome outcome = executor.apply(
                attemptId,
                intent,
                idempotencyKey,
                requestDigest,
                nowEpochMs);
        return new ApplySnapshot(
                outcome.getOutcome(),
                outcome.getLeaseId(),
                outcome.getOperationId(),
                outcome.getAcceptedIntentHash(),
                outcome.getEnvironmentRevision());
    }

    @Override
    public void close() {
        closeAcquisition(acquisition);
        registry.unbindAll();
    }

    private static void closeAcquisition(ProviderExecutorAcquisition acquisition) {
        try {
            acquisition.close();
        } catch (Exception impossibleForKotlinImplementation) {
            throw new IllegalStateException("provider acquisition close failed", impossibleForKotlinImplementation);
        }
    }

    public static final class ApplySnapshot {
        public final String outcome;
        public final String leaseId;
        public final String operationId;
        public final String acceptedIntentHash;
        public final Long environmentRevision;

        private ApplySnapshot(
                String outcome,
                String leaseId,
                String operationId,
                String acceptedIntentHash,
                Long environmentRevision) {
            this.outcome = outcome;
            this.leaseId = leaseId;
            this.operationId = operationId;
            this.acceptedIntentHash = acceptedIntentHash;
            this.environmentRevision = environmentRevision;
        }
    }
}
