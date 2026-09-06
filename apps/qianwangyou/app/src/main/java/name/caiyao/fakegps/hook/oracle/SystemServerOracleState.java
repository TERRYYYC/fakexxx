package name.caiyao.fakegps.hook.oracle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import name.caiyao.fakegps.oracle.OracleBridgePolicy;
import name.caiyao.fakegps.oracle.OracleBundleCodec;
import name.caiyao.fakegps.oracle.OracleWireHealth;
import name.caiyao.fakegps.oracle.OracleWireSnapshot;

/**
 * State owner delegated to by the real system-server Binder. Android identity, endpoint reads,
 * and death transport enter through narrow interfaces so host tests execute this exact owner.
 * The empty build allowlist and absent current-main semantic writer coverage remain fail-closed.
 */
final class SystemServerOracleState {
    interface CallerIdentity { int uid(); int pid(); }
    interface SessionToken { void link(Runnable onDeath); void unlink(Runnable onDeath); }
    interface EndpointReader { EndpointSample read(); }
    private final CallerIdentity callerIdentity;
    private final Runnable awaitCoveredMutations;
    private final EndpointReader endpointReader;

    private final Object lock = new Object();
    private final Object endpointRefreshLock = new Object();
    private final String bootId;
    private final String oracleInstanceId = UUID.randomUUID().toString();
    private final boolean supportedPlatform;
    private final boolean buildAttested;

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
    private SessionToken qwySessionToken;
    private Runnable qwySessionDeathRecipient;

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

    static final class EndpointSample {
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

    SystemServerOracleState(
            String bootId, boolean supportedPlatform, boolean buildAttested,
            CallerIdentity callerIdentity, Runnable awaitCoveredMutations,
            EndpointReader endpointReader) {
        this.bootId = bootId;
        this.supportedPlatform = supportedPlatform;
        this.buildAttested = buildAttested;
        this.callerIdentity = java.util.Objects.requireNonNull(callerIdentity);
        this.awaitCoveredMutations = java.util.Objects.requireNonNull(awaitCoveredMutations);
        this.endpointReader = java.util.Objects.requireNonNull(endpointReader);
        if (buildAttested) {
            installedCoverageMask |= Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED;
        }
    }

    public OracleWireSnapshot snapshot() {
        enforceQwyCaller();
        synchronized (lock) {
            if (!OracleBundleCodec.isKernelBootId(bootId)) {
                throw new IllegalStateException("kernel boot id unavailable");
            }
            return new OracleWireSnapshot(
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
                    lastCompletedQwyMutationId);
        }
    }

    public void registerQwySession(String semanticDigest, SessionToken clientDeathToken) {
        enforceQwyCaller();
        final int registeringPid = callerIdentity.pid();
        if (semanticDigest == null || semanticDigest.trim().isEmpty()) {
            throw new IllegalArgumentException("QWY semantic digest is required");
        }
        if (clientDeathToken == null) {
            throw new IllegalArgumentException("QWY session death token is required");
        }
        try {
            // Retire every covered completion that was already enqueued by an old QWY call.
            // The exact in-lock gate below rejects a callback that began but has not enqueued yet.
            awaitCoveredMutations.run();
        } catch (RuntimeException barrierFailure) {
            poisonCallback(barrierFailure);
            throw barrierFailure;
        }
        final SessionDeath candidate = new SessionDeath(clientDeathToken);
        Runnable recipient = candidate::binderDied;
        candidate.recipient = recipient;
        clientDeathToken.link(recipient);

        SessionToken oldToken = null;
        Runnable oldRecipient = null;
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
                if (oldToken != null && !oldToken.equals(clientDeathToken)) {
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
                        Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION;
                // A death token proves only service generation. Current-main semantic writers
                // are not all bracketed yet: registration must not invent their coverage bit.
            }
        }
        if (diedDuringRegistration || coveredMutationInFlight) {
            unlinkQuietly(clientDeathToken, recipient);
            if (diedDuringRegistration) {
                throw new IllegalStateException("QWY session binder died during registration");
            }
            throw new IllegalStateException(
                    "covered platform mutation still active during QWY registration");
        }
        unlinkQuietly(oldToken, oldRecipient);
    }

    public long beginQwySemanticMutation(
            String mutationId,
            String beforeDigest,
            SessionToken clientDeathToken) {
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
            if (!qwySessionActive || !clientDeathToken.equals(qwySessionToken)
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
            awaitCoveredMutations.run();
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
    void finishCoveredMutation(long token, boolean uncertain) {
        synchronized (endpointRefreshLock) {
            refreshEndpointSerialized();
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
            installedCoverageMask |= coverageBit;
        }
    }

    void configureExpectedQwyIdentity(int uid, String qwyPackage) {
        if (uid < 0 || qwyPackage == null || qwyPackage.trim().isEmpty()) {
            throw new IllegalArgumentException("resolved QWY identity is required");
        }
        synchronized (lock) {
            expectedQwyUid = uid;
            expectedQwyPackage = qwyPackage;
        }
    }

    void onBridgeConnected(long connectionGeneration) {
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
            EndpointSample sample = endpointReader.read();
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
    void refreshEndpoint() {
        synchronized (endpointRefreshLock) {
            refreshEndpointSerialized();
        }
    }

    private void refreshEndpointSerialized() {
        EndpointSample sample = endpointReader.read();
        synchronized (lock) {
            publishEndpointSampleLocked(sample);
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
        if (!OracleBridgePolicy.acceptsQwyCaller(callerIdentity.uid(), uid)) {
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
        final SessionToken token;
        volatile boolean died;
        Runnable recipient;

        SessionDeath(SessionToken token) {
            this.token = token;
        }

        void binderDied() {
            died = true;
            onQwySessionDeath(token);
        }
    }

    private void onQwySessionDeath(SessionToken deadToken) {
        synchronized (lock) {
            if (!deadToken.equals(qwySessionToken)) return;
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
                buildAttested,
                OracleBundleCodec.isKernelBootId(bootId),
                invariantFailure,
                callbackPoisoned,
                installedCoverageMask,
                bridgeConnected,
                qwySessionActive && !qwySessionUncertain,
                endpointValid);
    }

    private static void unlinkQuietly(SessionToken token, Runnable recipient) {
        if (token == null || recipient == null) return;
        try {
            token.unlink(recipient);
        } catch (RuntimeException ignored) {
            // The producer is already dead; state was cleared before this best-effort unlink.
        }
    }
}
