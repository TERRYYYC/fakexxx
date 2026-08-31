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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import name.caiyao.fakegps.oracle.IAuthoritativeContinuityOracle;
import name.caiyao.fakegps.oracle.OracleBridgePolicy;
import name.caiyao.fakegps.oracle.OracleBundleCodec;
import name.caiyao.fakegps.oracle.OracleWireHealth;
import name.caiyao.fakegps.oracle.OracleWireSnapshot;

/**
 * Private system-server producer. The pilot has an empty build allowlist, so the installer never
 * constructs this implementation in production; its state machine exists for host/emulator
 * evidence before any fingerprint can be admitted.
 */
public final class SystemServerOracleBinder extends IAuthoritativeContinuityOracle.Stub {
    static final String KERNEL_BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";

    private final Object lock = new Object();
    private final String bootId;
    private final String oracleInstanceId = UUID.randomUUID().toString();
    private final String buildFingerprint;
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

    private long installedCoverageMask;
    private boolean callbackPoisoned;
    private boolean invariantFailure;
    private boolean bridgeConnected;
    private boolean qwySessionActive;
    private boolean qwySessionUncertain;
    private Integer expectedQwyUid;
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
        final IBinder clientDeathToken;
        IBinder.DeathRecipient deathRecipient;

        Mutation(String mutationId, String beforeDigest, IBinder clientDeathToken) {
            this.mutationId = mutationId;
            this.beforeDigest = beforeDigest;
            this.clientDeathToken = clientDeathToken;
        }
    }

    public static SystemServerOracleBinder create(
            String buildFingerprint,
            boolean supportedPlatform,
            boolean buildAttested) {
        return new SystemServerOracleBinder(
                readKernelBootId(), buildFingerprint, supportedPlatform, buildAttested);
    }

    SystemServerOracleBinder(
            String bootId,
            String buildFingerprint,
            boolean supportedPlatform,
            boolean buildAttested) {
        this.bootId = bootId;
        this.buildFingerprint = buildFingerprint;
        this.supportedPlatform = supportedPlatform;
        this.buildAttested = buildAttested;
        if (buildAttested) {
            installedCoverageMask |= Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED;
        }
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
        if (semanticDigest == null || semanticDigest.trim().isEmpty()) {
            throw new IllegalArgumentException("QWY semantic digest is required");
        }
        if (clientDeathToken == null) {
            throw new IllegalArgumentException("QWY session death token is required");
        }
        final SessionDeath candidate = new SessionDeath(clientDeathToken);
        IBinder.DeathRecipient recipient = candidate::binderDied;
        candidate.recipient = recipient;
        clientDeathToken.linkToDeath(recipient, 0);

        IBinder oldToken;
        IBinder.DeathRecipient oldRecipient;
        synchronized (lock) {
            if (candidate.died) {
                clientDeathToken.unlinkToDeath(recipient, 0);
                throw new RemoteException("QWY session binder died during registration");
            }
            oldToken = qwySessionToken;
            oldRecipient = qwySessionDeathRecipient;
            markDiscontinuityLocked();
            qwySessionToken = clientDeathToken;
            qwySessionDeathRecipient = recipient;
            qwySessionActive = true;
            qwySessionUncertain = false;
            qwySemanticDigest = semanticDigest;
            installedCoverageMask |=
                    Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION
                            | Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION;
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

        final Mutation mutation = new Mutation(mutationId, beforeDigest, clientDeathToken);
        final long token;
        synchronized (lock) {
            if (!qwySessionActive || !beforeDigest.equals(qwySemanticDigest)) {
                markDiscontinuityLocked();
                qwySessionActive = false;
                installedCoverageMask &= ~Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION;
                throw new IllegalStateException("QWY session is absent or semantic baseline changed");
            }
            token = beginMutationLocked(mutation);
        }

        IBinder.DeathRecipient recipient = () -> onQwyMutationDeath(token);
        mutation.deathRecipient = recipient;
        try {
            clientDeathToken.linkToDeath(recipient, 0);
        } catch (RemoteException e) {
            synchronized (lock) {
                finishMutationLocked(token, true, true, null);
                qwySessionActive = false;
                qwySessionUncertain = true;
                installedCoverageMask &= ~Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION;
            }
            throw e;
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
        Mutation mutation;
        synchronized (lock) {
            mutation = activeMutations.get(token);
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
        unlinkQuietly(mutation.clientDeathToken, mutation.deathRecipient);
    }

    /** Called only by installed platform mutation callbacks inside system_server. */
    long beginCoveredMutation() {
        synchronized (lock) {
            return beginMutationLocked(new Mutation(null, null, null));
        }
    }

    /** Platform calls are conservatively treated as changed; sample publication is never hooked. */
    void finishCoveredMutation(long token, boolean uncertain) {
        synchronized (lock) {
            finishMutationLocked(token, true, uncertain, null);
            if (uncertain) callbackPoisoned = true;
        }
    }

    void markInstalled(long coverageBit) {
        synchronized (lock) {
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

    void onBridgeConnected(Context context) {
        synchronized (lock) {
            bridgeConnected = true;
            installedCoverageMask |= Android15OracleHookPlan.COVERAGE_BRIDGE_SESSION;
        }
        refreshEndpoint(context);
    }

    void onBridgeDisconnected() {
        synchronized (lock) {
            bridgeConnected = false;
            qwySessionActive = false;
            qwySessionUncertain = true;
            installedCoverageMask &= ~(
                    Android15OracleHookPlan.COVERAGE_BRIDGE_SESSION
                            | Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION
                            | Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION);
            markDiscontinuityLocked();
        }
    }

    /** Re-samples effective owner/provider truth after covered calls and bridge establishment. */
    void refreshEndpoint(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            poisonCallback(new IllegalStateException(
                    "effective AppOps sampling requires API 29 or newer"));
            return;
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
            synchronized (lock) {
                ownerUid = ambiguous ? null : uniqueUid;
                ownerPackage = ambiguous ? null : uniquePackage;
                gpsProviderEnabled = gps;
                networkProviderEnabled = network;
                endpointSampleValid = !ambiguous;
            }
        } catch (RuntimeException e) {
            synchronized (lock) {
                endpointSampleValid = false;
                ownerUid = null;
                ownerPackage = null;
                gpsProviderEnabled = false;
                networkProviderEnabled = false;
            }
            poisonCallback(e);
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
        if (mutation.mutationId == null && (changed || uncertain)) {
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
            List<Long> abandoned = new ArrayList<>();
            for (Map.Entry<Long, Mutation> entry : activeMutations.entrySet()) {
                if (entry.getValue().clientDeathToken != null) abandoned.add(entry.getKey());
            }
            if (abandoned.isEmpty()) {
                markDiscontinuityLocked();
            } else {
                for (Long token : abandoned) finishMutationLocked(token, true, true, null);
            }
        }
    }

    private void onQwyMutationDeath(long token) {
        synchronized (lock) {
            if (!activeMutations.containsKey(token)) return;
            finishMutationLocked(token, true, true, null);
            qwySessionActive = false;
            qwySessionUncertain = true;
            installedCoverageMask &= ~Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION;
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
