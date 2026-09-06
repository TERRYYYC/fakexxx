package name.caiyao.fakegps.hook.oracle;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import name.caiyao.fakegps.oracle.IAuthoritativeContinuityOracle;
import name.caiyao.fakegps.oracle.OracleBundleCodec;

/** Android transport adapter; all journal/lifecycle decisions belong to the tested state owner. */
public final class SystemServerOracleBinder extends IAuthoritativeContinuityOracle.Stub {
    private static final String KERNEL_BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";
    private volatile Context systemContext;
    private final SystemServerOracleState state;

    private SystemServerOracleBinder(String bootId, boolean supported, boolean attested) {
        state = new SystemServerOracleState(bootId, supported, attested,
                new SystemServerOracleState.CallerIdentity() {
                    public int uid() { return Binder.getCallingUid(); }
                    public int pid() { return Binder.getCallingPid(); }
                },
                SystemServerOracleInstaller::awaitCoveredMutationFinisherBarrier,
                () -> AndroidOracleEndpointReader.sample(systemContext));
    }

    static SystemServerOracleBinder create(String fingerprint, boolean supported, boolean attested) {
        // The adapter independently checks the same allowlist; call-site booleans cannot attest it.
        return new SystemServerOracleBinder(readKernelBootId(), supported,
                attested && Android15OracleHookPlan.isFingerprintAttested(fingerprint));
    }

    @Override public Bundle snapshot() { return OracleBundleCodec.encode(state.snapshot()); }

    @Override public void registerQwySession(String digest, IBinder deathToken) {
        state.registerQwySession(digest, token(deathToken));
    }

    @Override public long beginQwySemanticMutation(String id, String before, IBinder deathToken) {
        return state.beginQwySemanticMutation(id, before, token(deathToken));
    }

    @Override public void finishQwySemanticMutation(long token, boolean changed, boolean uncertain, String after) {
        state.finishQwySemanticMutation(token, changed, uncertain, after);
    }

    long beginCoveredMutation(int uid, int pid, String pkg, String tag) {
        return state.beginCoveredMutation(uid, pid, pkg, tag);
    }
    void finishCoveredMutation(long token, boolean uncertain) {
        state.finishCoveredMutation(token, uncertain);
    }
    void abandonCoveredMutation(long token, Throwable failure) { state.abandonCoveredMutation(token, failure); }
    void markInstalled(long bit) { state.markInstalled(bit); }
    void poisonCallback(Throwable failure) { state.poisonCallback(failure); }

    void configureExpectedQwyIdentity(Context context, String pkg) {
        systemContext = context;
        try {
            state.configureExpectedQwyIdentity(context.getPackageManager().getPackageUid(pkg, 0), pkg);
        } catch (RuntimeException | PackageManager.NameNotFoundException failure) {
            state.poisonCallback(failure);
        }
    }

    void onBridgeConnected(Context context, long generation) {
        systemContext = java.util.Objects.requireNonNull(context, "system Context is required");
        state.onBridgeConnected(generation);
    }
    void onBridgeDisconnected(long generation) { state.onBridgeDisconnected(generation); }
    void onBridgeBindingDied(long generation) { state.onBridgeBindingDied(generation); }

    private static SystemServerOracleState.SessionToken token(IBinder binder) {
        return binder == null ? null : new BinderSessionToken(binder);
    }

    private static final class BinderSessionToken implements SystemServerOracleState.SessionToken {
        private final IBinder binder;
        private final Map<Runnable, IBinder.DeathRecipient> recipients = new IdentityHashMap<>();
        BinderSessionToken(IBinder binder) { this.binder = binder; }
        @Override public synchronized void link(Runnable death) {
            IBinder.DeathRecipient recipient = death::run;
            try {
                binder.linkToDeath(recipient, 0);
                recipients.put(death, recipient);
            } catch (RemoteException failure) {
                throw new IllegalStateException("QWY session binder already dead", failure);
            }
        }
        @Override public synchronized void unlink(Runnable death) {
            IBinder.DeathRecipient recipient = recipients.remove(death);
            if (recipient != null) binder.unlinkToDeath(recipient, 0);
        }
        @Override public boolean equals(Object other) {
            return other instanceof BinderSessionToken && binder.equals(((BinderSessionToken) other).binder);
        }
        @Override public int hashCode() { return binder.hashCode(); }
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
}
