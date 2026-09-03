package name.caiyao.fakegps.hook.oracle;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import name.caiyao.fakegps.oracle.IAuthoritativeContinuityOracle;
import name.caiyao.fakegps.oracle.OracleBridgePolicy;
import name.caiyao.fakegps.oracle.OracleBundleCodec;
import name.caiyao.fakegps.oracle.OracleWireHealth;
import name.caiyao.fakegps.oracle.OracleWireSnapshot;

/**
 * Private system-server producer. Both exact-build lists are empty by default. A separately
 * reviewed evidence-only fingerprint may construct it without setting build attestation, so
 * runtime compatibility can be observed without granting authoritative health.
 */
public final class SystemServerOracleBinder extends IAuthoritativeContinuityOracle.Stub {
    static final String KERNEL_BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";

    private final Object lock = new Object();
    private final Object endpointRefreshLock = new Object();
    private final String bootId;
    private final String oracleInstanceId = UUID.randomUUID().toString();
    private final String buildFingerprint;
    private final boolean supportedPlatform;
    private final Android15OracleHookPlan.BuildAdmission buildAdmission;

    private long sequence;
    private long outerBaseSequence;
    private int mutationDepth;
    private long nextMutationToken = 1L;
    private boolean aggregateChanged;
    private boolean aggregateUncertain;
    private boolean aggregateForeignChanged;
    private String aggregateQwyMutationId;
    private String aggregateAfterDigest;
    private final Map<Long, Mutation> activeMutations = new HashMap<>();
    /** Finite timeout tombstones; the finisher is permanently retired after their creation. */
    private final Set<Long> discardedCoveredMutationTokens = new HashSet<>();

    private long installedCoverageMask;
    private boolean callbackPoisoned;
    private boolean invariantFailure;
    private boolean bridgeConnected;
    private boolean bridgeConnectionInProgress;
    private long bridgeConnectionGeneration = Long.MIN_VALUE;
    private long retiredBridgeConnectionGeneration = Long.MIN_VALUE;
    private boolean qwySessionActive;
    private boolean qwySessionUncertain;
    private boolean qwyGenerationLossAccounted = true;
    private Integer expectedQwyUid;
    private Integer expectedQwyPid;
    private String expectedQwyPackage;
    private IBinder qwySessionToken;
    private IBinder.DeathRecipient qwySessionDeathRecipient;

    private Integer ownerUid;
    private String ownerPackage;
    private boolean gpsProviderEnabled;
    private boolean networkProviderEnabled;
    private boolean endpointSampleValid;
    private String qwySemanticDigest;
    private String lastCompletedQwyMutationId;

    private static final class Mutation {
        final String mutationId;
        final String beforeDigest;
        final boolean attributedToQwy;

        Mutation(String mutationId, String beforeDigest, boolean attributedToQwy) {
            this.mutationId = mutationId;
            this.beforeDigest = beforeDigest;
            this.attributedToQwy = attributedToQwy;
        }
    }

    private static final class EndpointSample {
        final Integer ownerUid;
        final String ownerPackage;
        final boolean gpsProviderEnabled;
        final boolean networkProviderEnabled;
        final boolean valid;
        final Throwable failure;

        EndpointSample(
                Integer ownerUid,
                String ownerPackage,
                boolean gpsProviderEnabled,
                boolean networkProviderEnabled,
                boolean valid,
                Throwable failure) {
            this.ownerUid = ownerUid;
            this.ownerPackage = ownerPackage;
            this.gpsProviderEnabled = gpsProviderEnabled;
            this.networkProviderEnabled = networkProviderEnabled;
            this.valid = valid;
            this.failure = failure;
        }
    }

    static SystemServerOracleBinder createForCurrentBuild() {
        String buildFingerprint = Build.FINGERPRINT;
        boolean supportedPlatform =
                Build.VERSION.SDK_INT == Android15OracleHookPlan.API_LEVEL;
        return new SystemServerOracleBinder(
                readKernelBootId(), buildFingerprint, supportedPlatform);
    }

    private SystemServerOracleBinder(
            String bootId,
            String buildFingerprint,
            boolean supportedPlatform) {
        this.bootId = bootId;
        this.buildFingerprint = buildFingerprint;
        this.supportedPlatform = supportedPlatform;
        this.buildAdmission = Android15OracleHookPlan.classifyFingerprint(buildFingerprint);
        installedCoverageMask = Android15OracleHookPlan.initialCoverageMask(this.buildAdmission);
    }

    @Override
    public Bundle snapshot() {
        enforceQwyCaller();
        synchronized (lock) {
            if (!OracleBundleCodec.isKernelBootId(bootId)) {
                throw new IllegalStateException("kernel boot id unavailable");
            }
            return OracleBundleCodec.encode(new OracleWireSnapshot(
                    OracleBundleCodec.PROTOCOL_VERSION,
                    bootId,
                    oracleInstanceId,
                    sequence,
                    ownerUid,
                    ownerPackage,
                    gpsProviderEnabled,
                    networkProviderEnabled,
                    Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
                    installedCoverageMask,
                    healthLocked(),
                    qwySemanticDigest,
                    lastCompletedQwyMutationId));
        }
    }

    @Override
    public void registerQwySession(String semanticDigest, IBinder clientDeathToken)
            throws RemoteException {
        enforceQwyCaller();
        final int registeringPid = Binder.getCallingPid();
        if (semanticDigest == null || semanticDigest.trim().isEmpty()) {
            throw new IllegalArgumentException("QWY semantic digest is required");
        }
        if (clientDeathToken == null) {
            throw new IllegalArgumentException("QWY session death token is required");
        }
        try {
            // Retire every covered completion that was already enqueued by an old QWY call.
            // The exact in-lock gate below rejects a callback that began but has not enqueued yet.
            SystemServerOracleInstaller.awaitCoveredMutationFinisherBarrier();
        } catch (RuntimeException barrierFailure) {
            poisonCallback(barrierFailure);
            throw barrierFailure;
        }
        final SessionDeath candidate = new SessionDeath(clientDeathToken);
        IBinder.DeathRecipient recipient = candidate::binderDied;
        candidate.recipient = recipient;
        clientDeathToken.linkToDeath(recipient, 0);

        IBinder oldToken = null;
        IBinder.DeathRecipient oldRecipient = null;
        boolean diedDuringRegistration;
        boolean coveredMutationInFlight;
        synchronized (lock) {
            diedDuringRegistration = candidate.died;
            coveredMutationInFlight = hasActiveCoveredMutationLocked();
            if (!diedDuringRegistration && !coveredMutationInFlight) {
                oldToken = qwySessionToken;
                oldRecipient = qwySessionDeathRecipient;
                // A distinct still-live token has an unreported generation loss:
                // once replaced, its delayed death callback is intentionally ignored.
                // Represent that loss before the new registration boundary so an
                // intervening foreign +2 cannot counterfeit death + registration.
                if (oldToken != null && oldToken != clientDeathToken) {
                    markQwyGenerationLostLocked();
                }
                // Registration is its own +2 boundary. If generation loss retired
                // an outstanding old-session mutation above, this publish remains
                // separate and preserves the causal restart sequence.
                boolean retiredMutation = retireActiveQwyMutationsLocked();
                if (!retiredMutation) markDiscontinuityLocked();
                qwySessionToken = clientDeathToken;
                qwySessionDeathRecipient = recipient;
                expectedQwyPid = registeringPid;
                qwySessionActive = true;
                qwySessionUncertain = false;
                qwyGenerationLossAccounted = false;
                qwySemanticDigest = semanticDigest;
                installedCoverageMask |=
                        Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION
                                | Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION;
            }
        }
        if (diedDuringRegistration || coveredMutationInFlight) {
            unlinkQuietly(clientDeathToken, recipient);
            if (diedDuringRegistration) {
                throw new RemoteException("QWY session binder died during registration");
            }
            throw new IllegalStateException(
                    "covered platform mutation still active during QWY registration");
        }
        unlinkQuietly(oldToken, oldRecipient);
    }

    @Override
    public long beginQwySemanticMutation(
            String mutationId,
            String beforeDigest,
            IBinder clientDeathToken) throws RemoteException {
        enforceQwyCaller();
        if (mutationId == null || mutationId.trim().isEmpty()) {
            throw new IllegalArgumentException("mutation id is required");
        }
        if (beforeDigest == null || beforeDigest.trim().isEmpty()) {
            throw new IllegalArgumentException("before digest is required");
        }
        if (clientDeathToken == null) {
            throw new IllegalArgumentException("mutation death token is required");
        }

        final Mutation mutation = new Mutation(mutationId, beforeDigest, true);
        final long token;
        synchronized (lock) {
            if (!qwySessionActive || qwySessionToken != clientDeathToken
                    || !beforeDigest.equals(qwySemanticDigest)) {
                markQwyGenerationLostLocked();
                qwySessionActive = false;
                installedCoverageMask &= ~Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION;
                throw new IllegalStateException("QWY session is absent or semantic baseline changed");
            }
            token = beginMutationLocked(mutation);
        }
        return token;
    }

    @Override
    public void finishQwySemanticMutation(
            long token,
            boolean changed,
            boolean uncertain,
            String afterDigest) {
        enforceQwyCaller();
        try {
            // Platform after-hooks enqueue their covered children before the guarded call returns.
            // Drain those FIFO completions on this Binder thread before the parent QWY token can
            // publish an even cursor. No platform callback or oracle state lock waits here.
            SystemServerOracleInstaller.awaitCoveredMutationFinisherBarrier();
        } catch (RuntimeException barrierFailure) {
            synchronized (lock) {
                callbackPoisoned = true;
                Mutation mutation = activeMutations.get(token);
                if (mutation == null || mutation.mutationId == null) {
                    poisonInvariantLocked();
                } else {
                    finishMutationLocked(token, true, true, afterDigest);
                    qwySessionActive = false;
                    qwySessionUncertain = true;
                    installedCoverageMask &=
                            ~Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION;
                }
            }
            throw new IllegalStateException(
                    "covered-mutation completion barrier failed", barrierFailure);
        }
        synchronized (lock) {
            Mutation mutation = activeMutations.get(token);
            if (mutation == null || mutation.mutationId == null) {
                poisonInvariantLocked();
                throw new IllegalStateException("unknown QWY mutation token");
            }
            if (afterDigest == null || afterDigest.trim().isEmpty()) {
                uncertain = true;
            } else if (!changed && !afterDigest.equals(mutation.beforeDigest)) {
                uncertain = true;
            }
            finishMutationLocked(token, changed, uncertain, afterDigest);
            if (uncertain) {
                qwySessionActive = false;
                qwySessionUncertain = true;
                installedCoverageMask &= ~Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION;
            }
        }
    }

    /** Called only by installed platform mutation callbacks inside system_server. */
    long beginCoveredMutation(
            int callingUid,
            int callingPid,
            String callingPackage,
            String attributionTag) {
        synchronized (lock) {
            boolean attributedToQwy = QwyCoveredMutationAttributionPolicy.isAttributed(
                    expectedQwyUid,
                    expectedQwyPid,
                    expectedQwyPackage,
                    callingUid,
                    callingPid,
                    callingPackage,
                    attributionTag,
                    qwySessionActive,
                    hasActiveQwyMutationLocked());
            return beginMutationLocked(new Mutation(null, null, attributedToQwy));
        }
    }

    /**
     * Platform calls are conservatively changed; the serialized final-exit
     * sample is published before the token can expose an even sequence.
     */
    void finishCoveredMutation(long token, boolean uncertain, Context context) {
        synchronized (endpointRefreshLock) {
            if (context != null) refreshEndpointSerialized(context);
            synchronized (lock) {
                // A timeout may retire the token while this worker is blocked in endpoint
                // sampling. Its eventual unwind is completion of the same accepted action, not
                // a second invariant-significant finish.
                if (discardedCoveredMutationTokens.remove(token)) return;
                finishMutationLocked(token, true, uncertain, null);
                if (uncertain) callbackPoisoned = true;
            }
        }
    }

    /** Fails closed without acquiring framework-manager locks on the guarded callback thread. */
    void abandonCoveredMutation(long token, Throwable failure) {
        synchronized (lock) {
            callbackPoisoned = true;
            Mutation mutation = activeMutations.get(token);
            // The worker may have completed the Binder retirement just before the timeout thread
            // reached its discard callback. In that ordering there is nothing left to abandon.
            if (mutation == null) return;
            if (mutation.mutationId != null) {
                poisonInvariantLocked();
                return;
            }
            try {
                finishMutationLocked(token, true, true, null);
                discardedCoveredMutationTokens.add(token);
            } catch (RuntimeException invariantFailure) {
                poisonInvariantLocked();
            }
        }
    }

    void markInstalled(long coverageBit) {
        synchronized (lock) {
            if ((coverageBit & Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED) != 0L) {
                poisonInvariantLocked();
                return;
            }
            installedCoverageMask |= coverageBit;
        }
    }

    void configureExpectedQwyIdentity(Context context, String qwyPackage) {
        try {
            int uid = context.getPackageManager().getPackageUid(qwyPackage, 0);
            synchronized (lock) {
                expectedQwyUid = uid;
                expectedQwyPackage = qwyPackage;
            }
        } catch (RuntimeException | PackageManager.NameNotFoundException e) {
            poisonCallback(e);
        }
    }

    void onBridgeConnected(Context context, long connectionGeneration) {
        synchronized (lock) {
            if (connectionGeneration <= retiredBridgeConnectionGeneration
                    || connectionGeneration < bridgeConnectionGeneration) return;
            bridgeConnectionGeneration = connectionGeneration;
            bridgeConnectionInProgress = true;
            bridgeConnected = false;
            endpointSampleValid = false;
            installedCoverageMask &= ~Android15OracleHookPlan.COVERAGE_BRIDGE_SESSION;
        }
        synchronized (endpointRefreshLock) {
            EndpointSample sample = sampleEndpoint(context);
            synchronized (lock) {
                if (bridgeConnectionGeneration != connectionGeneration
                        || !bridgeConnectionInProgress) return;
                publishEndpointSampleLocked(sample);
                bridgeConnectionInProgress = false;
                bridgeConnected = true;
                installedCoverageMask |= Android15OracleHookPlan.COVERAGE_BRIDGE_SESSION;
            }
        }
    }

    void onBridgeDisconnected(long connectionGeneration) {
        synchronized (lock) {
            if (connectionGeneration < bridgeConnectionGeneration) return;
            bridgeConnectionGeneration = connectionGeneration;
            bridgeConnectionInProgress = false;
            bridgeConnected = false;
            endpointSampleValid = false;
            qwySessionActive = false;
            qwySessionUncertain = true;
            installedCoverageMask &= ~(
                    Android15OracleHookPlan.COVERAGE_BRIDGE_SESSION
                            | Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION
                            | Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION);
            markQwyGenerationLostLocked();
        }
    }

    void onBridgeBindingDied(long connectionGeneration) {
        synchronized (lock) {
            if (connectionGeneration < bridgeConnectionGeneration) return;
            retiredBridgeConnectionGeneration = Math.max(
                    retiredBridgeConnectionGeneration, connectionGeneration);
        }
        onBridgeDisconnected(connectionGeneration);
    }

    /** Re-samples effective owner/provider truth after covered calls and bridge establishment. */
    void refreshEndpoint(Context context) {
        synchronized (endpointRefreshLock) {
            refreshEndpointSerialized(context);
        }
    }

    private void refreshEndpointSerialized(Context context) {
        EndpointSample sample = sampleEndpoint(context);
        synchronized (lock) {
            publishEndpointSampleLocked(sample);
        }
    }

    /** Samples framework services without holding the oracle state lock. */
    private EndpointSample sampleEndpoint(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new EndpointSample(
                    null,
                    null,
                    false,
                    false,
                    false,
                    new IllegalStateException(
                            "effective AppOps sampling requires API 29 or newer"));
        }
        try {
            AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
            LocationManager locations = context.getSystemService(LocationManager.class);
            PackageManager packages = context.getPackageManager();
            if (appOps == null || locations == null) {
                throw new IllegalStateException("required system manager unavailable");
            }

            Integer uniqueUid = null;
            String uniquePackage = null;
            boolean ambiguous = false;
            for (ApplicationInfo app : packages.getInstalledApplications(0)) {
                int mode = appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION, app.uid, app.packageName);
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    if (uniquePackage != null
                            && (uniqueUid == null || uniqueUid != app.uid
                            || !uniquePackage.equals(app.packageName))) {
                        ambiguous = true;
                        break;
                    }
                    uniqueUid = app.uid;
                    uniquePackage = app.packageName;
                }
            }
            boolean gps = locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean network = locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            return new EndpointSample(
                    ambiguous ? null : uniqueUid,
                    ambiguous ? null : uniquePackage,
                    gps,
                    network,
                    !ambiguous,
                    null);
        } catch (RuntimeException failure) {
            return new EndpointSample(null, null, false, false, false, failure);
        }
    }

    private void publishEndpointSampleLocked(EndpointSample sample) {
        ownerUid = sample.ownerUid;
        ownerPackage = sample.ownerPackage;
        gpsProviderEnabled = sample.gpsProviderEnabled;
        networkProviderEnabled = sample.networkProviderEnabled;
        endpointSampleValid = sample.valid;
        if (sample.failure != null) {
            callbackPoisoned = true;
            markDiscontinuityLocked();
        }
    }

    void poisonCallback(Throwable failure) {
        synchronized (lock) {
            callbackPoisoned = true;
            markDiscontinuityLocked();
        }
    }

    OracleWireHealth currentHealthForDiagnostics() {
        synchronized (lock) {
            return healthLocked();
        }
    }

    private void enforceQwyCaller() {
        Integer uid;
        synchronized (lock) {
            uid = expectedQwyUid;
        }
        if (!OracleBridgePolicy.acceptsQwyCaller(Binder.getCallingUid(), uid)) {
            throw new SecurityException("continuity oracle accepts only the resolved QWY UID");
        }
    }

    private long beginMutationLocked(Mutation mutation) {
        if (invariantFailure || sequence < 0L || sequence > Long.MAX_VALUE - 2L) {
            poisonInvariantLocked();
            throw new IllegalStateException("oracle sequence unavailable");
        }
        if (mutationDepth == 0) {
            if ((sequence & 1L) != 0L) {
                poisonInvariantLocked();
                throw new IllegalStateException("stable oracle sequence is odd");
            }
            outerBaseSequence = sequence;
            sequence = outerBaseSequence + 1L;
            aggregateChanged = false;
            aggregateUncertain = false;
            aggregateForeignChanged = false;
            aggregateQwyMutationId = null;
            aggregateAfterDigest = null;
        }
        if (mutationDepth == Integer.MAX_VALUE || nextMutationToken == Long.MAX_VALUE) {
            poisonInvariantLocked();
            throw new IllegalStateException("oracle mutation counter overflow");
        }
        mutationDepth += 1;
        long token = nextMutationToken++;
        activeMutations.put(token, mutation);
        return token;
    }

    private void finishMutationLocked(
            long token,
            boolean changed,
            boolean uncertain,
            String afterDigest) {
        Mutation mutation = activeMutations.remove(token);
        if (mutation == null || mutationDepth <= 0) {
            poisonInvariantLocked();
            throw new IllegalStateException("finish without matching begin");
        }
        aggregateChanged |= changed || uncertain;
        aggregateUncertain |= uncertain;
        if (mutation.mutationId == null && !mutation.attributedToQwy &&
                (changed || uncertain)) {
            aggregateForeignChanged = true;
        }
        if (mutation.mutationId != null) {
            if (aggregateQwyMutationId != null
                    && !aggregateQwyMutationId.equals(mutation.mutationId)) {
                aggregateUncertain = true;
                qwySessionUncertain = true;
            }
            aggregateQwyMutationId = mutation.mutationId;
        }
        if (afterDigest != null && !afterDigest.trim().isEmpty()) aggregateAfterDigest = afterDigest;
        mutationDepth -= 1;
        if (mutationDepth == 0) {
            sequence = aggregateChanged ? outerBaseSequence + 2L : outerBaseSequence;
            if (aggregateAfterDigest != null) qwySemanticDigest = aggregateAfterDigest;
            // A QWY correlation proves only its own isolated +2 interval. A covered platform
            // mutation (even when nested) could be unrelated, so it must make the ID unusable for
            // receipt/revision reservation finalization rather than laundering interleaving.
            if (aggregateChanged) {
                lastCompletedQwyMutationId = !aggregateUncertain
                        && !aggregateForeignChanged
                        ? aggregateQwyMutationId
                        : null;
            }
            // A proved no-op restores the original even sequence and must
            // preserve the entire published semantic snapshot, including the
            // previous completed correlation. Clearing it here would make a
            // no-op externally observable despite claiming unchanged history.
        }
    }

    private void markDiscontinuityLocked() {
        if (mutationDepth > 0) {
            aggregateChanged = true;
            aggregateUncertain = true;
            return;
        }
        if (sequence >= 0L && sequence <= Long.MAX_VALUE - 2L && (sequence & 1L) == 0L) {
            sequence += 2L;
        } else {
            poisonInvariantLocked();
        }
    }

    private boolean hasActiveQwyMutationLocked() {
        for (Mutation mutation : activeMutations.values()) {
            if (mutation.mutationId != null) return true;
        }
        return false;
    }

    private boolean hasActiveCoveredMutationLocked() {
        for (Mutation mutation : activeMutations.values()) {
            if (mutation.mutationId == null) return true;
        }
        return false;
    }

    /** Retires QWY tokens only; covered platform tokens finish on their own callbacks. */
    private boolean retireActiveQwyMutationsLocked() {
        List<Long> abandoned = new ArrayList<>();
        for (Map.Entry<Long, Mutation> entry : activeMutations.entrySet()) {
            if (entry.getValue().mutationId != null) abandoned.add(entry.getKey());
        }
        for (Long token : abandoned) finishMutationLocked(token, true, true, null);
        return !abandoned.isEmpty();
    }

    /** Session-token death and bridge disconnect are one process-generation loss. */
    private void markQwyGenerationLostLocked() {
        if (qwyGenerationLossAccounted) return;
        qwyGenerationLossAccounted = true;
        if (!retireActiveQwyMutationsLocked()) markDiscontinuityLocked();
    }

    private void poisonInvariantLocked() {
        invariantFailure = true;
        callbackPoisoned = true;
    }

    private final class SessionDeath {
        final IBinder token;
        volatile boolean died;
        IBinder.DeathRecipient recipient;

        SessionDeath(IBinder token) {
            this.token = token;
        }

        void binderDied() {
            died = true;
            onQwySessionDeath(token);
        }
    }

    private void onQwySessionDeath(IBinder deadToken) {
        synchronized (lock) {
            if (qwySessionToken != deadToken) return;
            qwySessionActive = false;
            qwySessionUncertain = true;
            installedCoverageMask &= ~(
                    Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION
                            | Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION);
            markQwyGenerationLostLocked();
        }
    }

    private OracleWireHealth healthLocked() {
        boolean endpointValid = endpointSampleValid
                && expectedQwyUid != null
                && expectedQwyPackage != null
                && ownerUid != null
                && expectedQwyUid.equals(ownerUid)
                && expectedQwyPackage.equals(ownerPackage)
                && gpsProviderEnabled
                && networkProviderEnabled;
        return Android15OracleHookPlan.classifyHealth(
                supportedPlatform,
                buildAdmission,
                OracleBundleCodec.isKernelBootId(bootId),
                invariantFailure,
                callbackPoisoned,
                installedCoverageMask,
                bridgeConnected,
                qwySessionActive && !qwySessionUncertain,
                endpointValid);
    }

    private static String readKernelBootId() {
        try (BufferedReader reader = new BufferedReader(new FileReader(KERNEL_BOOT_ID_PATH))) {
            String value = reader.readLine();
            if (value == null) return null;
            String normalized = value.trim();
            return OracleBundleCodec.isKernelBootId(normalized) ? normalized : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void unlinkQuietly(IBinder token, IBinder.DeathRecipient recipient) {
        if (token == null || recipient == null) return;
        try {
            token.unlinkToDeath(recipient, 0);
        } catch (RuntimeException ignored) {
            // The producer is already dead; state was cleared before this best-effort unlink.
        }
    }
}
