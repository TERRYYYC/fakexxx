package name.caiyao.fakegps.hook.oracle;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final AtomicBoolean INSTALL_STARTED = new AtomicBoolean();
    private static final AtomicBoolean BRIDGE_BIND_STARTED = new AtomicBoolean();

    private static volatile SystemServerOracleBinder oracleBinder;
    private static volatile Context systemContext;

    private SystemServerOracleInstaller() {}

    public static void install(ClassLoader systemClassLoader) {
        if (!INSTALL_STARTED.compareAndSet(false, true)) return;
        ClassLoader loader = systemClassLoader != null
                ? systemClassLoader
                : SystemServerOracleInstaller.class.getClassLoader();
        boolean supportedPlatform = Build.VERSION.SDK_INT == Android15OracleHookPlan.API_LEVEL;
        boolean buildAttested = Android15OracleHookPlan.isFingerprintAttested(Build.FINGERPRINT);

        if (!supportedPlatform) {
            XposedBridge.log(TAG + ": unsupported SDK " + Build.VERSION.SDK_INT);
            return;
        }
        if (!buildAttested) {
            // Pilot safety gate: empty production allowlist means BUILD_UNATTESTED and no hooks.
            XposedBridge.log(TAG + ": " + OracleWireHealth.BUILD_UNATTESTED
                    + " fingerprint=" + Build.FINGERPRINT);
            return;
        }
        oracleBinder = SystemServerOracleBinder.create(
                Build.FINGERPRINT, true, true);

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
        try {
            Class<?> target = XposedHelpers.findClass(className, loader);
            for (String method : methods) {
                Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                        target, method, new CoveredMutationHook());
                if (hooks == null || hooks.isEmpty()) {
                    throw new NoSuchMethodException(className + "#" + method);
                }
            }
            oracleBinder.markInstalled(coverageBit);
        } catch (Throwable failure) {
            // LSPosed catches callback failures and continues the platform call. Poison first.
            oracleBinder.poisonCallback(failure);
            XposedBridge.log(TAG + ": required hook missing " + className + " " + failure);
        }
    }

    private static final class CoveredMutationHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                long token = oracleBinder.beginCoveredMutation();
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
                // Endpoint truth must be sampled while the seqlock is still odd. Publishing the
                // final even sequence first would create a stable window over stale endpoint data.
                Context context = systemContext;
                if (context != null) oracleBinder.refreshEndpoint(context);
                oracleBinder.finishCoveredMutation((Long) value, param.hasThrowable());
            } catch (Throwable callbackFailure) {
                oracleBinder.poisonCallback(callbackFailure);
            }
        }
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
        if (!BRIDGE_BIND_STARTED.compareAndSet(false, true)) return;
        Object rawContext = XposedHelpers.getObjectField(systemServiceManager, "mContext");
        if (!(rawContext instanceof Context)) {
            oracleBinder.poisonCallback(new IllegalStateException("system context unavailable"));
            return;
        }
        Context context = (Context) rawContext;
        systemContext = context;
        oracleBinder.configureExpectedQwyIdentity(context, BuildConfig.APPLICATION_ID);

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID, BRIDGE_SERVICE_CLASS));
        boolean bound = context.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    IContinuityOracleRegistrar registrar =
                            IContinuityOracleRegistrar.Stub.asInterface(service);
                    if (registrar == null) {
                        throw new IllegalStateException("QWY registrar binder unavailable");
                    }
                    registrar.registerOracle(oracleBinder);
                    oracleBinder.onBridgeConnected(context);
                } catch (Throwable callbackFailure) {
                    oracleBinder.poisonCallback(callbackFailure);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                oracleBinder.onBridgeDisconnected();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                oracleBinder.onBridgeDisconnected();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                oracleBinder.poisonCallback(
                        new IllegalStateException("QWY registrar returned a null binding"));
            }
        }, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        if (!bound) {
            oracleBinder.poisonCallback(new IllegalStateException("phase-600 bridge bind rejected"));
        }
    }
}
