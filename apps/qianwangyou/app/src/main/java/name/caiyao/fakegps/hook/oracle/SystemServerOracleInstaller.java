package name.caiyao.fakegps.hook.oracle;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import name.caiyao.fakegps.BuildConfig;
import name.caiyao.fakegps.oracle.IContinuityOracleRegistrar;
import name.caiyao.fakegps.oracle.OracleWireHealth;

/** Dedicated legacy LSPosed system_server branch. It never installs generic spoof hooks. */
public final class SystemServerOracleInstaller {
    private static final String TAG = "FakeGPS-ContinuityOracle";
    private static final String BRIDGE_SERVICE_CLASS =
            "name.caiyao.fakegps.oracle.OracleBridgeService";
    private static final String MUTATION_TOKEN_EXTRA =
            "name.caiyao.fakegps.oracle.MUTATION_TOKEN";
    private static final String PREVIOUS_CALLER_PROVENANCE_EXTRA =
            "name.caiyao.fakegps.oracle.PREVIOUS_CALLER_PROVENANCE";
    private static final AtomicBoolean INSTALL_STARTED = new AtomicBoolean();
    private static final AtomicBoolean BRIDGE_BIND_STARTED = new AtomicBoolean();
    private static final AtomicLong BRIDGE_CONNECTION_GENERATION = new AtomicLong();
    private static final ThreadLocal<CoveredCallerProvenance> COVERED_CALLER_PROVENANCE =
            new ThreadLocal<>();
    private static final OrderedCoveredMutationFinisher COVERED_MUTATION_FINISHER =
            new OrderedCoveredMutationFinisher("fakexxx-oracle-endpoint-sampler");

    private static volatile SystemServerOracleBinder oracleBinder;
    private static volatile Context systemContext;

    private static final class CoveredCallerProvenance {
        final int uid;
        final int pid;
        final String packageName;
        final String attributionTag;

        CoveredCallerProvenance(
                int uid,
                int pid,
                String packageName,
                String attributionTag) {
            this.uid = uid;
            this.pid = pid;
            this.packageName = packageName;
            this.attributionTag = attributionTag;
        }
    }

    private SystemServerOracleInstaller() {}

    public static void install(ClassLoader systemClassLoader) {
        if (!INSTALL_STARTED.compareAndSet(false, true)) return;
        ClassLoader loader = systemClassLoader != null
                ? systemClassLoader
                : SystemServerOracleInstaller.class.getClassLoader();
        boolean supportedPlatform = Build.VERSION.SDK_INT == Android15OracleHookPlan.API_LEVEL;
        Android15OracleHookPlan.BuildAdmission buildAdmission =
                Android15OracleHookPlan.classifyFingerprint(Build.FINGERPRINT);

        if (!supportedPlatform) {
            XposedBridge.log(TAG + ": unsupported SDK " + Build.VERSION.SDK_INT);
            return;
        }
        if (buildAdmission == Android15OracleHookPlan.BuildAdmission.UNLISTED) {
            // Both exact lists are empty by default. An unlisted build never
            // constructs the Binder and never installs system-server hooks.
            XposedBridge.log(TAG + ": " + OracleWireHealth.BUILD_UNATTESTED
                    + " fingerprint=" + Build.FINGERPRINT);
            return;
        }
        oracleBinder = SystemServerOracleBinder.createForCurrentBuild();
        if (buildAdmission == Android15OracleHookPlan.BuildAdmission.EVIDENCE_ONLY) {
            XposedBridge.log(TAG + ": evidence-only exact build; hooks enabled, "
                    + "authoritative health disabled fingerprint=" + Build.FINGERPRINT);
        }

        tryInstallMutationGroup(
                loader,
                Android15OracleHookPlan.APP_OPS_WRAPPER_CLASS,
                Android15OracleHookPlan.APP_OPS_WRAPPER_MUTATION_METHODS,
                Android15OracleHookPlan.COVERAGE_APP_OPS_WRAPPER);
        tryInstallMutationGroup(
                loader,
                Android15OracleHookPlan.ACCESS_CHECKING_DELEGATE_CLASS,
                Android15OracleHookPlan.ACCESS_CHECKING_MUTATION_METHODS,
                Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_DELEGATE);
        tryInstallMutationGroup(
                loader,
                Android15OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_CLASS,
                Android15OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_METHODS,
                Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_LIFECYCLE);
        tryInstallMutationGroup(
                loader,
                Android15OracleHookPlan.LOCATION_MANAGER_SERVICE_CLASS,
                Android15OracleHookPlan.LOCATION_QWY_MUTATION_ENTRY_METHODS,
                0L,
                true);
        tryInstallLocationSemanticCoverage(loader);
        tryInstallMutationGroup(
                loader,
                Android15OracleHookPlan.LOCATION_PROVIDER_MANAGER_CLASS,
                new String[] {"onStateChanged"},
                Android15OracleHookPlan.COVERAGE_LOCATION_PROVIDER_STATE);
        tryInstallMutationGroup(
                loader,
                Android15OracleHookPlan.LOCATION_PROVIDER_MANAGER_CLASS,
                new String[] {"onEnabledChanged"},
                Android15OracleHookPlan.COVERAGE_LOCATION_EFFECTIVE_ENABLED);
        installPhase600Bridge(loader);
    }

    private static void tryInstallMutationGroup(
            ClassLoader loader,
            String className,
            String[] methods,
            long coverageBit) {
        tryInstallMutationGroup(loader, className, methods, coverageBit, false);
    }

    private static void tryInstallMutationGroup(
            ClassLoader loader,
            String className,
            String[] methods,
            long coverageBit,
            boolean captureCallerProvenance) {
        try {
            Class<?> target = XposedHelpers.findClass(className, loader);
            for (String method : methods) {
                Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                        target, method, new CoveredMutationHook(captureCallerProvenance));
                if (hooks == null || hooks.isEmpty()) {
                    throw new NoSuchMethodException(className + "#" + method);
                }
            }
            if (coverageBit != 0L) oracleBinder.markInstalled(coverageBit);
        } catch (Throwable failure) {
            // LSPosed catches callback failures and continues the platform call. Poison first.
            oracleBinder.poisonCallback(failure);
            XposedBridge.log(TAG + ": required hook missing " + className + " " + failure);
        }
    }

    private static final class CoveredMutationHook extends XC_MethodHook {
        private final boolean captureCallerProvenance;

        CoveredMutationHook(boolean captureCallerProvenance) {
            this.captureCallerProvenance = captureCallerProvenance;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                CoveredCallerProvenance provenance;
                if (captureCallerProvenance) {
                    captureCallerProvenance(param);
                    provenance = COVERED_CALLER_PROVENANCE.get();
                } else {
                    provenance = currentCallerProvenance();
                }
                long token = oracleBinder.beginCoveredMutation(
                        provenance.uid,
                        provenance.pid,
                        provenance.packageName,
                        provenance.attributionTag);
                param.setObjectExtra(MUTATION_TOKEN_EXTRA, token);
            } catch (Throwable callbackFailure) {
                oracleBinder.poisonCallback(callbackFailure);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object value = param.getObjectExtra(MUTATION_TOKEN_EXTRA);
                if (!(value instanceof Long)) {
                    oracleBinder.poisonCallback(
                            new IllegalStateException("covered callback lost mutation token"));
                    return;
                }
                scheduleCoveredMutationFinish((Long) value, param.hasThrowable());
            } catch (Throwable callbackFailure) {
                oracleBinder.poisonCallback(callbackFailure);
            } finally {
                if (captureCallerProvenance) {
                    restoreCallerProvenance(param);
                }
            }
        }
    }

    /** Carries the original Binder identity through API-35's clearCallingIdentity() boundary. */
    private static final class CallerProvenanceHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                captureCallerProvenance(param);
            } catch (Throwable callbackFailure) {
                oracleBinder.poisonCallback(callbackFailure);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                restoreCallerProvenance(param);
            } catch (Throwable callbackFailure) {
                oracleBinder.poisonCallback(callbackFailure);
            }
        }
    }

    /**
     * Runs inside LocationProviderManager's multiplexer lock and MockableLocationProvider's owner
     * lock. It journals only exact coordinate-bit changes; after-hook work is enqueue-only so no
     * framework manager is re-entered while those platform locks are held.
     */
    private static final class SemanticLocationMutationHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object stored = XposedHelpers.getObjectField(param.thisObject, "mLocation");
                if (stored != null && !(stored instanceof Location)) {
                    throw new IllegalStateException("mock provider mLocation has unexpected type");
                }
                Location previous = (Location) stored;
                Location incoming = locationArgument(param.args);
                if (!LocationSemanticChangePolicy.hasChanged(
                        latitude(previous),
                        longitude(previous),
                        latitude(incoming),
                        longitude(incoming))) {
                    return;
                }
                CoveredCallerProvenance provenance = currentCallerProvenance();
                long token = oracleBinder.beginCoveredMutation(
                        provenance.uid,
                        provenance.pid,
                        provenance.packageName,
                        provenance.attributionTag);
                param.setObjectExtra(MUTATION_TOKEN_EXTRA, token);
            } catch (Throwable callbackFailure) {
                oracleBinder.poisonCallback(callbackFailure);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object value = param.getObjectExtra(MUTATION_TOKEN_EXTRA);
                if (value == null) return;
                if (!(value instanceof Long)) {
                    oracleBinder.poisonCallback(
                            new IllegalStateException("semantic location callback lost token"));
                    return;
                }
                scheduleCoveredMutationFinish((Long) value, param.hasThrowable());
            } catch (Throwable callbackFailure) {
                oracleBinder.poisonCallback(callbackFailure);
            }
        }
    }

    private static void tryInstallLocationSemanticCoverage(ClassLoader loader) {
        try {
            Class<?> entry = XposedHelpers.findClass(
                    Android15OracleHookPlan.LOCATION_MANAGER_SERVICE_CLASS, loader);
            Set<XC_MethodHook.Unhook> provenanceHooks = XposedBridge.hookAllMethods(
                    entry,
                    Android15OracleHookPlan.LOCATION_QWY_PROVENANCE_ENTRY_METHOD,
                    new CallerProvenanceHook());
            if (provenanceHooks == null || provenanceHooks.isEmpty()) {
                throw new NoSuchMethodException(
                        Android15OracleHookPlan.LOCATION_MANAGER_SERVICE_CLASS + "#"
                                + Android15OracleHookPlan.LOCATION_QWY_PROVENANCE_ENTRY_METHOD);
            }

            Class<?> provider = XposedHelpers.findClass(
                    Android15OracleHookPlan.LOCATION_MOCK_PROVIDER_CLASS, loader);
            Set<XC_MethodHook.Unhook> semanticHooks = XposedBridge.hookAllMethods(
                    provider,
                    Android15OracleHookPlan.LOCATION_SEMANTIC_MUTATION_METHOD,
                    new SemanticLocationMutationHook());
            if (semanticHooks == null || semanticHooks.isEmpty()) {
                throw new NoSuchMethodException(
                        Android15OracleHookPlan.LOCATION_MOCK_PROVIDER_CLASS + "#"
                                + Android15OracleHookPlan.LOCATION_SEMANTIC_MUTATION_METHOD);
            }
            oracleBinder.markInstalled(
                    Android15OracleHookPlan.COVERAGE_LOCATION_SEMANTIC_COORDINATE);
        } catch (Throwable failure) {
            oracleBinder.poisonCallback(failure);
            XposedBridge.log(TAG + ": required semantic location hook missing " + failure);
        }
    }

    private static void captureCallerProvenance(XC_MethodHook.MethodHookParam param) {
        CoveredCallerProvenance inherited = COVERED_CALLER_PROVENANCE.get();
        param.setObjectExtra(PREVIOUS_CALLER_PROVENANCE_EXTRA, inherited);
        COVERED_CALLER_PROVENANCE.set(new CoveredCallerProvenance(
                Binder.getCallingUid(),
                Binder.getCallingPid(),
                stringArgumentFromEnd(param.args, 2),
                stringArgumentFromEnd(param.args, 1)));
    }

    private static void restoreCallerProvenance(XC_MethodHook.MethodHookParam param) {
        Object previous = param.getObjectExtra(PREVIOUS_CALLER_PROVENANCE_EXTRA);
        if (previous instanceof CoveredCallerProvenance) {
            COVERED_CALLER_PROVENANCE.set((CoveredCallerProvenance) previous);
        } else {
            COVERED_CALLER_PROVENANCE.remove();
        }
    }

    private static CoveredCallerProvenance currentCallerProvenance() {
        CoveredCallerProvenance inherited = COVERED_CALLER_PROVENANCE.get();
        return inherited != null
                ? inherited
                : new CoveredCallerProvenance(
                        Binder.getCallingUid(), Binder.getCallingPid(), null, null);
    }

    private static Location locationArgument(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return null;
        if (!(args[0] instanceof Location)) {
            throw new IllegalStateException("mock provider location argument has unexpected type");
        }
        return (Location) args[0];
    }

    private static Double latitude(Location location) {
        return location != null ? location.getLatitude() : null;
    }

    private static Double longitude(Location location) {
        return location != null ? location.getLongitude() : null;
    }

    private static String stringArgumentFromEnd(Object[] args, int offset) {
        if (args == null || offset <= 0 || args.length < offset) return null;
        Object value = args[args.length - offset];
        return value instanceof String ? (String) value : null;
    }

    /**
     * Provider callbacks may still hold per-provider framework locks here. They only enqueue and
     * return; the single finisher samples all framework managers without making the callback wait.
     */
    private static void scheduleCoveredMutationFinish(long token, boolean uncertain) {
        Context context = systemContext;
        AtomicBoolean tokenRetired = new AtomicBoolean();
        Runnable abandonOnce = () -> {
            if (tokenRetired.compareAndSet(false, true)) {
                oracleBinder.abandonCoveredMutation(
                        token,
                        new IllegalStateException("covered completion was discarded"));
            }
        };
        try {
            COVERED_MUTATION_FINISHER.execute(
                    () -> {
                        if (tokenRetired.get()) return;
                        try {
                            oracleBinder.finishCoveredMutation(token, uncertain, context);
                            tokenRetired.compareAndSet(false, true);
                        } catch (Throwable callbackFailure) {
                            if (tokenRetired.compareAndSet(false, true)) {
                                oracleBinder.abandonCoveredMutation(token, callbackFailure);
                            }
                        }
                    },
                    abandonOnce);
        } catch (Throwable schedulingFailure) {
            abandonOnce.run();
        }
    }

    /** Called only from the QWY Binder finish path, never from a guarded platform callback. */
    static void awaitCoveredMutationFinisherBarrier() {
        COVERED_MUTATION_FINISHER.awaitDrained();
    }

    private static void installPhase600Bridge(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    Android15OracleHookPlan.SYSTEM_SERVICE_MANAGER_CLASS, loader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    manager,
                    "startBootPhase",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int phase = findPhase(param.args);
                                if (SystemServerOracleEntryPolicy.shouldBindBridgeAtPhase(phase)) {
                                    bindBridgeAtPhase600(param.thisObject);
                                }
                            } catch (Throwable callbackFailure) {
                                oracleBinder.poisonCallback(callbackFailure);
                            }
                        }
                    });
            if (hooks == null || hooks.isEmpty()) {
                throw new NoSuchMethodException("SystemServiceManager#startBootPhase");
            }
        } catch (Throwable failure) {
            oracleBinder.poisonCallback(failure);
        }
    }

    private static int findPhase(Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Integer) return (Integer) arg;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static void bindBridgeAtPhase600(Object systemServiceManager) {
        Object rawContext = XposedHelpers.getObjectField(systemServiceManager, "mContext");
        if (!(rawContext instanceof Context)) {
            oracleBinder.poisonCallback(new IllegalStateException("system context unavailable"));
            return;
        }
        Context context = (Context) rawContext;
        systemContext = context;
        oracleBinder.configureExpectedQwyIdentity(context, BuildConfig.APPLICATION_ID);
        bindBridge(context);
    }

    private static void bindBridge(Context context) {
        if (!BRIDGE_BIND_STARTED.compareAndSet(false, true)) return;
        final long connectionGeneration = BRIDGE_CONNECTION_GENERATION.incrementAndGet();
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID, BRIDGE_SERVICE_CLASS));
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    IContinuityOracleRegistrar registrar =
                            IContinuityOracleRegistrar.Stub.asInterface(service);
                    if (registrar == null) {
                        throw new IllegalStateException("QWY registrar binder unavailable");
                    }
                    registrar.registerOracle(oracleBinder);
                    oracleBinder.onBridgeConnected(context, connectionGeneration);
                } catch (Throwable callbackFailure) {
                    oracleBinder.poisonCallback(callbackFailure);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                oracleBinder.onBridgeDisconnected(connectionGeneration);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                oracleBinder.onBridgeBindingDied(connectionGeneration);
                try {
                    context.unbindService(this);
                } catch (RuntimeException callbackFailure) {
                    oracleBinder.poisonCallback(callbackFailure);
                }
                BRIDGE_BIND_STARTED.set(false);
                bindBridge(context);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                oracleBinder.poisonCallback(
                        new IllegalStateException("QWY registrar returned a null binding"));
            }
        };
        final boolean bound;
        try {
            bound = context.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        } catch (RuntimeException failure) {
            BRIDGE_BIND_STARTED.set(false);
            oracleBinder.poisonCallback(failure);
            return;
        }
        if (!bound) {
            BRIDGE_BIND_STARTED.set(false);
            oracleBinder.poisonCallback(new IllegalStateException("phase-600 bridge bind rejected"));
        }
    }
}
