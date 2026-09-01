package io.github.terryyyc.fakexxx.integration.pr63issue66;

import android.content.Context;
import com.example.cellrebelauto.automation.APlusComposition;
import com.example.cellrebelauto.automation.AttemptOutcome;
import com.example.cellrebelauto.automation.AutomationEngine;
import com.example.cellrebelauto.automation.AutomationEngineFactory;
import com.example.cellrebelauto.automation.CellRebelRunner;
import com.example.cellrebelauto.automation.GpsLocationSetter;
import com.example.cellrebelauto.automation.aplus.APlusBackend;
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource;
import com.example.cellrebelauto.db.AppDatabase;
import com.example.cellrebelauto.model.plan.LocationPlan;
import com.example.cellrebelauto.model.plan.StageToggles;
import com.example.cellrebelauto.automation.GpsOutcome;
import com.example.cellrebelauto.recovery.ApplyOutcome;
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor;
import com.example.cellrebelauto.recovery.ProviderExecutorAcquisition;
import com.example.cellrebelauto.recovery.ProviderExecutorRegistry;
import com.example.cellrebelauto.recovery.RecordedReleaseReceipt;
import com.example.cellrebelauto.recovery.RecoveryCoordinator;
import com.example.cellrebelauto.repository.PlanRepository;
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1;
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1;
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1;
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1;
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import java.util.function.Function;

/**
 * Test-only bridge into Auto's internal production composition root.
 *
 * <p>The selected app artifact is the real release variant. This bridge reads the manually seeded
 * durable provider owner from Room after a file-database reopen. It does not model an installed
 * build switch or Android process death.</p>
 */
public final class AutoDurableJourneyBridge implements AutoCloseable {
    private final ProviderExecutorRegistry registry;
    private final ProviderExecutorAcquisition acquisition;
    private final AppDatabase db;
    private final long planId;
    private final APlusBackend backend;
    private final RecoveryCoordinator coordinator;
    private final APlusEvidenceSource evidenceSource;

    private AutoDurableJourneyBridge(
            ProviderExecutorRegistry registry,
            ProviderExecutorAcquisition acquisition,
            AppDatabase db,
            long planId,
            APlusBackend backend,
            RecoveryCoordinator coordinator,
            APlusEvidenceSource evidenceSource) {
        this.registry = registry;
        this.acquisition = acquisition;
        this.db = db;
        this.planId = planId;
        this.backend = backend;
        this.coordinator = coordinator;
        this.evidenceSource = evidenceSource;
    }

    public static AutoDurableJourneyBridge connect(
            Context context,
            AppDatabase db,
            long planId,
            Function<String, String> currentSignerResolver) throws InterruptedException {
        PlanRepository repository = new PlanRepository(db);
        LocationPlan plan = BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                new Function2<CoroutineScope, Continuation<? super LocationPlan>, Object>() {
                    @Override
                    public Object invoke(
                            CoroutineScope scope,
                            Continuation<? super LocationPlan> continuation) {
                        return repository.getPlan(planId, continuation);
                    }
                });
        if (plan == null) {
            throw new IllegalStateException("durable plan is missing");
        }
        if (plan.getProviderApplicationId() == null) {
            BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    new Function2<CoroutineScope, Continuation<? super Integer>, Object>() {
                        @Override
                        public Object invoke(
                                CoroutineScope scope,
                                Continuation<? super Integer> continuation) {
                            return repository.persistProviderPrincipalRecovery(
                                    planId,
                                    "PROVIDER_PRINCIPAL_UNKNOWN",
                                    continuation);
                        }
                    });
            throw new IllegalStateException("durable plan provider owner is unknown");
        }
        String durableProviderApplicationId = plan.getProviderApplicationId();
        String durableProviderSignerDigest = currentSignerResolver.apply(durableProviderApplicationId);
        String principalFailure = BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                new Function2<CoroutineScope, Continuation<? super String>, Object>() {
                    @Override
                    public Object invoke(
                            CoroutineScope scope,
                            Continuation<? super String> continuation) {
                        return repository.guardRecoveryProviderPrincipal(
                                planId,
                                durableProviderApplicationId,
                                durableProviderSignerDigest,
                                continuation);
                    }
                });
        if (principalFailure != null) {
            throw new IllegalStateException("durable provider owner rejected: " + principalFailure);
        }
        ProviderExecutorRegistry registry = new ProviderExecutorRegistry(
                context,
                currentSignerResolver::apply,
                target -> new BinderExternalApplyExecutor(context, target));
        ProviderExecutorAcquisition acquisition =
                registry.acquire(durableProviderApplicationId, durableProviderSignerDigest);
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
            throw new IllegalStateException("durable provider acquisition did not become ready");
        }

        Function1<String, String> signerResolver = currentSignerResolver::apply;
        APlusBackend backend = APlusComposition.INSTANCE.productionBackend$app_release(
                context,
                db,
                acquisition,
                signerResolver,
                90_000L);
        Pair<RecoveryCoordinator, APlusEvidenceSource> engineAplusParams =
                APlusComposition.INSTANCE.engineAplusParams$app_release(backend);
        RecoveryCoordinator coordinator = engineAplusParams.getFirst();
        APlusEvidenceSource evidenceSource = engineAplusParams.getSecond();
        return new AutoDurableJourneyBridge(
                registry,
                acquisition,
                db,
                planId,
                backend,
                coordinator,
                evidenceSource);
    }

    public CapabilitySnapshotV1 discover(long attemptId) {
        return coordinator.discoverForAttempt(attemptId);
    }

    public ApplySnapshot dispatchApply(
            long attemptId,
            EnvironmentIntentV1 intent,
            String idempotencyKey,
            String requestDigest,
            long nowEpochMs) {
        ApplyOutcome outcome = coordinator.dispatchApply(
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

    public ReleaseSnapshot release(
            long attemptId,
            String idempotencyKey,
            String leaseId,
            String releaseDigest,
            long nowEpochMs) {
        RecordedReleaseReceipt receipt = coordinator.releaseLease(
                attemptId,
                idempotencyKey,
                leaseId,
                releaseDigest,
                nowEpochMs);
        return receipt == null ? null : new ReleaseSnapshot(
                receipt.getIdempotencyKey(),
                receipt.getLeaseId(),
                receipt.getReleaseDigest(),
                receipt.getResultOutcome(),
                receipt.getProviderApplicationId());
    }

    public AdvanceReceiptV1 completeAndAdvance(
            long attemptId,
            CompleteAndAdvanceRequestV1 request,
            String expectedIntentHash) {
        return coordinator.completeAndAdvanceForAttempt(attemptId, request, expectedIntentHash);
    }

    public EnvironmentObservationV1 observe(
            long attemptId,
            String leaseId,
            String operationId,
            String expectedIntentHash) {
        return coordinator.observeForAttempt(
                attemptId,
                leaseId,
                operationId,
                expectedIntentHash);
    }

    /** Runs the real production engine; every normal-work dependency is a killing oracle. */
    public void runRecoveryEngine(long nowEpochMs, long monotonicMs) throws InterruptedException {
        CellRebelRunner forbiddenRunner = new CellRebelRunner() {
            @Override
            public Object runTest(
                    long startedAt,
                    long testTimeoutMs,
                    Function2<? super Long, ? super Continuation<? super Unit>, ? extends Object> onRunningObserved,
                    Continuation<? super AttemptOutcome> continuation) {
                throw new AssertionError("recovery attempted a fresh CellRebel execution");
            }
        };
        GpsLocationSetter forbiddenGps = new GpsLocationSetter() {
            @Override
            public Object setLocation(
                    double latitude,
                    double longitude,
                    Continuation<? super GpsOutcome> continuation) {
                throw new AssertionError("recovery attempted a fresh GPS mutation");
            }
        };
        Function1<Continuation<? super StageToggles>, Object> stageToggles =
                ignored -> new StageToggles(true, true);
        Function0<Long> now = () -> nowEpochMs;
        Function0<Long> monotonic = () -> monotonicMs;
        Function2<Long, Continuation<? super Unit>, Object> noDelay =
                (ignored, continuation) -> Unit.INSTANCE;
        AutomationEngine engine = AutomationEngineFactory.INSTANCE.productionEngine$app_release(
                planId,
                new PlanRepository(db),
                forbiddenRunner,
                forbiddenGps,
                0,
                90_000L,
                0L,
                stageToggles,
                db.auditEventDao(),
                coordinator,
                evidenceSource,
                null,
                now,
                noDelay,
                monotonic,
                monotonic);
        BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                new Function2<CoroutineScope, Continuation<? super Unit>, Object>() {
                    @Override
                    public Object invoke(
                            CoroutineScope scope,
                            Continuation<? super Unit> continuation) {
                        return engine.run(continuation);
                    }
                });
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

    public static final class ReleaseSnapshot {
        public final String idempotencyKey;
        public final String leaseId;
        public final String releaseDigest;
        public final String outcome;
        public final String providerApplicationId;

        private ReleaseSnapshot(
                String idempotencyKey,
                String leaseId,
                String releaseDigest,
                String outcome,
                String providerApplicationId) {
            this.idempotencyKey = idempotencyKey;
            this.leaseId = leaseId;
            this.releaseDigest = releaseDigest;
            this.outcome = outcome;
            this.providerApplicationId = providerApplicationId;
        }
    }
}
