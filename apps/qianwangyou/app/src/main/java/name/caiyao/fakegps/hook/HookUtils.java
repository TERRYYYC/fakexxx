package name.caiyao.fakegps.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.gsm.GsmCellLocation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import name.caiyao.fakegps.verify.RuntimeEvidence;

/**
 * Hook registration — called ONCE from {@link MainHook#handleLoadPackage}.
 * Every hook callback reads {@link MainHook#CURRENT}{@code .get()} at invocation time.
 * null field = passthrough (real device value).
 */
class HookUtils {

    private static final String TAG = "FakeGPS";

    /**
     * Verbose hook tracing, debug builds only.
     *
     * These logs run INSIDE the target app's process, so in a release build they are both noise and
     * a detection surface: anything reading logcat would see a module announcing every spoof. Gating
     * on BuildConfig.DEBUG keeps the diagnostics available to scripts/test-hook.sh while ensuring a
     * release module stays silent.
     */
    private static void debug(String msg) {
        if (name.caiyao.fakegps.BuildConfig.DEBUG) {
            XposedBridge.log(TAG + ": [DIAG] " + msg);
        }
    }
    private static final Random RND = new Random();
    /** Guards against re-hooking methods discovered at runtime (e.g. inside constructors). */
    private static final Set<String> HOOKED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /**
     * Neighbor cell bypass: CellInfo, CellIdentity*, and CellSignalStrength* instances
     * created from neighbor_cells_json are added here. Global getter hooks (Section C/D)
     * and isRegistered() skip these objects so their constructor-set values aren't
     * overwritten by serving cell snapshot.
     * Uses weak identity membership because these framework value objects mutate their hash code
     * after construction; ordinary WeakHashMap membership would be lost after their setters run.
     */
    private static final NeighborBypassRegistry NEIGHBOR_BYPASS =
            new NeighborBypassRegistry();

    /**
     * Single entry point: registers ALL hooks exactly once.
     * Hooks read current config from MainHook.CURRENT at invocation time.
     */
    static void registerAllHooks(ClassLoader cl) {
        safeHook("Location", () -> hookLocation(cl));
        safeHook("CellIdentity", () -> hookCellIdentity(cl));
        safeHook("CellIdentityGetters", () -> hookCellIdentityGetters(cl));
        safeHook("SignalStrength", () -> hookSignalStrengthGetters(cl));
        safeHook("Telephony", () -> hookTelephony(cl));
        safeHook("ServiceState", () -> hookServiceState(cl));
        safeHook("WiFi", () -> hookWifi(cl));
        safeHook("Network", () -> hookNetwork(cl));
        safeHook("PhoneStateListener", () -> hookPhoneStateListener(cl));
        safeHook("GpsStatus", () -> hookGpsStatus(cl));
        safeHook("LocationManagerCtor", () -> hookLocationManagerConstructor(cl));
        safeHook("TelephonyCallback", () -> hookTelephonyCallback(cl));
        safeHook("Connectivity", () -> hookConnectivity(cl));
        safeHook("PhysicalChannelConfig", () -> hookPhysicalChannelConfig(cl));
        safeHook("SubscriptionAware", () -> hookSubscriptionAware(cl));
        safeHook("FusedLocation", () -> hookFusedLocation(cl));
    }

    private static void safeHook(String group, Runnable r) {
        try {
            r.run();
            XposedBridge.log(TAG + ": " + group + " hooks registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + group + " hooks FAILED: " + t.getMessage());
        }
    }

    /** All hook groups share one bypass decision while this process reads a real baseline. */
    private static Snapshot currentSnapshot() {
        return Snapshot.forHookInvocation(
                MainHook.CURRENT.get(), BaselineExtractionGuard.isActive());
    }

    // ==========================================================================
    // A. LOCATION HOOKS
    // ==========================================================================

    private static void hookLocation(ClassLoader cl) {

        // LocationManager.getLastLocation()
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "getLastLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        param.setResult(createFakeLocation(s));
                    }
                }));

        // LocationManager.getLastKnownLocation(String provider)
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "getLastKnownLocation", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        param.setResult(createFakeLocation(s));
                    }
                }));

        // LocationManager.getProviders(...)
        tryHook(() -> XposedBridge.hookAllMethods(
                LocationManager.class, "getProviders", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        ArrayList<String> providers = new ArrayList<>();
                        providers.add(LocationManager.GPS_PROVIDER);
                        providers.add(LocationManager.NETWORK_PROVIDER);
                        param.setResult(providers);
                    }
                }));

        // LocationManager.getBestProvider(Criteria, boolean)
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "getBestProvider",
                Criteria.class, Boolean.TYPE, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        param.setResult(LocationManager.GPS_PROVIDER);
                    }
                }));

        // LocationManager.requestLocationUpdates(...) — all public overloads
        for (Method method : LocationManager.class.getDeclaredMethods()) {
            if (!"requestLocationUpdates".equals(method.getName())) continue;
            if (Modifier.isAbstract(method.getModifiers())) continue;
            if (!Modifier.isPublic(method.getModifiers())) continue;

            tryHook(() -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Snapshot s = currentSnapshot();
                    if (!s.hasLocation()) return;

                    LocationListener ll = findLocationListener(param.args);
                    if (ll != null) {
                        // Take over EVERY future delivery to this listener, otherwise real
                        // updates keep arriving and overwrite the fake fix we push below.
                        hookSystemLocationListener(ll);
                        try {
                            ll.onLocationChanged(createFakeLocation(s));
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": requestLocationUpdates callback: " + e);
                        }
                    }
                }
            }));
        }

        // LocationManager.requestSingleUpdate(...) — all public overloads
        // NOTE: old code had trailing space "requestSingleUpdate " (P0 bug) — fixed here
        for (Method method : LocationManager.class.getDeclaredMethods()) {
            if (!"requestSingleUpdate".equals(method.getName())) continue;
            if (Modifier.isAbstract(method.getModifiers())) continue;
            if (!Modifier.isPublic(method.getModifiers())) continue;

            tryHook(() -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Snapshot s = currentSnapshot();
                    if (!s.hasLocation()) return;

                    LocationListener ll = findLocationListener(param.args);
                    if (ll != null) {
                        hookSystemLocationListener(ll);
                        try {
                            ll.onLocationChanged(createFakeLocation(s));
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": requestSingleUpdate callback: " + e);
                        }
                    }
                }
            }));
        }

        // LocationManager.addNmeaListener — block NMEA data to prevent real location leak
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "addNmeaListener",
                GpsStatus.NmeaListener.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        param.setResult(false);
                    }
                }));

        // LocationManager.getCurrentLocation (API 30+) — new one-shot API
        // Multiple overloads: (String, CancellationSignal, Executor, Consumer)
        //                     (String, LocationRequest, CancellationSignal, Executor, Consumer)
        if (Build.VERSION.SDK_INT >= 30) {
            tryHook(() -> XposedBridge.hookAllMethods(
                    LocationManager.class, "getCurrentLocation", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!s.hasLocation()) return;

                            // Find the Consumer<Location> arg by interface type
                            // (lambda/anonymous classes don't have "Consumer" in their class name)
                            for (int i = param.args.length - 1; i >= 0; i--) {
                                if (param.args[i] instanceof java.util.function.Consumer) {
                                    try {
                                        @SuppressWarnings("unchecked")
                                        java.util.function.Consumer<Location> consumer =
                                                (java.util.function.Consumer<Location>) param.args[i];
                                        consumer.accept(createFakeLocation(s));
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + ": getCurrentLocation consumer: " + t);
                                    }
                                    param.setResult(null); // prevent real location request
                                    return;
                                }
                            }
                        }
                    }));
        }
    }

    // ==========================================================================
    // B. CELL IDENTITY — getCellLocation / getAllCellInfo
    // ==========================================================================

    private static void hookCellIdentity(ClassLoader cl) {

        // TelephonyManager.getCellLocation()
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "getCellLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!shouldTransformGsmCellLocation(s)) return;
                        GsmCellLocation loc = spoofedGsmLocationOrPassthrough(s, param.getResult());
                        if (loc == null) return;
                        param.setResult(loc);
                    }
                }));

        // TelephonyManager.getPhoneCount() — only override when spoofing cells, otherwise passthrough
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager", cl,
                    "getPhoneCount", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (shouldRebuildServingCells(s)) {
                                param.setResult(1);
                            }
                            // else: passthrough real phone count (preserves dual-SIM)
                        }
                    }));
        }

        // TelephonyManager.getNeighboringCellInfo() — deprecated but still queried
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "getNeighboringCellInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasGsmRatConstruction()) return;
                        param.setResult(new ArrayList<>());
                    }
                }));

        // TelephonyManager.getAllCellInfo()
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "getAllCellInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!shouldMutateCellList(s)) {
                            registerRealNeighborBypasses(param.getResult());
                            return;
                        }
                        // The real result is the passthrough baseline for fields the user left unset.
                        ArrayList spoofed = spoofedCellsOrPassthrough(s, param.getResult());
                        if (spoofed == null) return;
                        param.setResult(spoofed);
                    }
                }));

        // TelephonyManager.requestCellInfoUpdate() — intercept the call, hook concrete callback class
        if (Build.VERSION.SDK_INT >= 29) {
            tryHook(() -> XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.telephony.TelephonyManager", cl),
                    "requestCellInfoUpdate", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Class<?> cbBase = XposedHelpers.findClass(
                                    "android.telephony.TelephonyManager$CellInfoCallback", cl);
                            for (Object arg : param.args) {
                                if (arg != null && cbBase.isInstance(arg)) {
                                    hookCellInfoCallbackInstance(arg, cl);
                                    break;
                                }
                            }
                        }
                    }));
        }

        // CellInfo.isRegistered() — true for serving cells, false for neighbors
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.CellInfo", cl,
                "isRegistered", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (BaselineExtractionGuard.isActive()) return;
                        if (NEIGHBOR_BYPASS.contains(param.thisObject)) {
                            param.setResult(false);
                            return;
                        }
                        Snapshot s = currentSnapshot();
                        if (shouldRebuildServingCells(s)) {
                            param.setResult(true);
                        }
                    }
                }));
    }

    // ==========================================================================
    // C. CELL IDENTITY GETTER HOOKS (per-subclass field overrides)
    // ==========================================================================

    private static void hookCellIdentityGetters(ClassLoader cl) {
        // Common carrier names. Hook the base class so every radio identity — including NR —
        // exposes the same configured network operator through its inherited public getters.
        hookNullableCarrierGetter(cl, "android.telephony.CellIdentity", "getOperatorAlphaLong",
                "operator_name", s -> s.operatorName);
        hookNullableCarrierGetter(cl, "android.telephony.CellIdentity", "getOperatorAlphaShort",
                "operator_name", s -> s.operatorName);

        // GSM
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getMcc", s -> s.mcc);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getMnc", s -> s.mnc);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getLac", s -> s.lac);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getCid", s -> s.cid);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getArfcn", s -> s.arfcn);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getBsic", s -> s.bsic);

        // WCDMA
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getMcc", s -> s.mcc);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getMnc", s -> s.mnc);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getLac", s -> s.lac);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getCid", s -> s.cid);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getPsc", s -> s.psc);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getUarfcn", s -> s.uarfcn);

        // LTE
        hookGetter(cl, "android.telephony.CellIdentityLte", "getMcc", s -> s.mcc);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getMnc", s -> s.mnc);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getTac", s -> s.tac);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getCi", s -> s.ci);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getPci", s -> s.pci);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getEarfcn", s -> s.earfcn);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getBandwidth", s -> s.lteBandwidth);

        // CDMA — map lac→networkId, cid→baseStationId
        hookGetter(cl, "android.telephony.CellIdentityCdma", "getNetworkId", s -> s.lac);
        hookGetter(cl, "android.telephony.CellIdentityCdma", "getBasestationId", s -> s.cid);

        // NR/5G (API 29+)
        if (Build.VERSION.SDK_INT >= 29) {
            hookPlmnStringGetter(cl, "android.telephony.CellIdentityNr", "getMccString", "mcc",
                    s -> configuredPlmnString("mcc", s));
            hookPlmnStringGetter(cl, "android.telephony.CellIdentityNr", "getMncString", "mnc",
                    s -> configuredPlmnString("mnc", s));
            hookGetter(cl, "android.telephony.CellIdentityNr", "getNci", s -> s.nci);
            hookGetter(cl, "android.telephony.CellIdentityNr", "getNrarfcn", s -> s.nrarfcn);
            hookGetter(cl, "android.telephony.CellIdentityNr", "getPci", s -> s.nrPci);
            hookGetter(cl, "android.telephony.CellIdentityNr", "getTac", s -> s.nrTac);
        }

        // Common: CellIdentity.getMccString / getMncString (API 28+)
        if (Build.VERSION.SDK_INT >= 28) {
            for (String cls : new String[]{
                    "android.telephony.CellIdentityGsm",
                    "android.telephony.CellIdentityWcdma",
                    "android.telephony.CellIdentityLte"}) {
                hookPlmnStringGetter(cl, cls, "getMccString", "mcc",
                        s -> configuredPlmnString("mcc", s));
                hookPlmnStringGetter(cl, cls, "getMncString", "mnc",
                        s -> configuredPlmnString("mnc", s));
            }
        }
    }

    static String configuredPlmnString(String field, Snapshot snapshot) {
        Integer configured;
        if ("mcc".equals(field)) {
            configured = snapshot.mcc;
        } else if ("mnc".equals(field)) {
            configured = snapshot.mnc;
        } else {
            throw new IllegalArgumentException("not a PLMN field: " + field);
        }
        return Snapshot.resolvePlmnString(field, configured, null, false);
    }

    // ==========================================================================
    // D. SIGNAL STRENGTH GETTER HOOKS
    // ==========================================================================

    private static void hookSignalStrengthGetters(ClassLoader cl) {
        // GSM signal
        hookSignal(cl, "android.telephony.CellSignalStrengthGsm", "getDbm", s -> s.gsmRssi);
        hookSignal(cl, "android.telephony.CellSignalStrengthGsm", "getBitErrorRate", s -> s.gsmBer);
        if (Build.VERSION.SDK_INT >= 26) {
            hookSignal(cl, "android.telephony.CellSignalStrengthGsm", "getTimingAdvance", s -> s.gsmTa);
        }

        // WCDMA signal
        hookSignal(cl, "android.telephony.CellSignalStrengthWcdma", "getDbm",
                s -> Snapshot.resolveWcdmaDbm(s.wcdmaRscp, s.wcdmaRssi));
        if (Build.VERSION.SDK_INT >= 28) {
            hookSignal(cl, "android.telephony.CellSignalStrengthWcdma", "getEcNo", s -> s.wcdmaEcno);
        }

        // LTE signal
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getDbm", s -> s.lteRsrp);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getRsrp", s -> s.lteRsrp);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getRsrq", s -> s.lteRsrq);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getRssnr", s -> s.lteSinr);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getCqi", s -> s.lteCqi);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getTimingAdvance", s -> s.lteTa);
        if (Build.VERSION.SDK_INT >= 29) {
            hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getRssi", s -> s.lteRssi);
        }

        // NR/5G signal (API 29+)
        if (Build.VERSION.SDK_INT >= 29) {
            hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getSsRsrp", s -> s.nrSsRsrp);
            hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getSsRsrq", s -> s.nrSsRsrq);
            hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getSsSinr", s -> s.nrSsSinr);
            hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getDbm", s -> s.nrSsRsrp);

            if (Build.VERSION.SDK_INT >= 31) {
                hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getCsiRsrp", s -> s.nrCsiRsrp);
                hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getCsiRsrq", s -> s.nrCsiRsrq);
                hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getCsiSinr", s -> s.nrCsiSinr);
            }
        }

        // CDMA signal — map to GSM values as fallback
        hookSignal(cl, "android.telephony.CellSignalStrengthCdma", "getDbm", s -> s.gsmRssi);
    }

    // ==========================================================================
    // E. TELEPHONY MANAGER — operator, network type
    // ==========================================================================

    private static void hookTelephony(ClassLoader cl) {
        // Operator info
        hookGetter(cl, "android.telephony.TelephonyManager", "getNetworkOperatorName",
                s -> s.operatorName);
        hookGetter(cl, "android.telephony.TelephonyManager", "getNetworkOperator",
                s -> s.operatorNumeric);
        hookGetter(cl, "android.telephony.TelephonyManager", "getSimOperator",
                s -> s.simOperator);
        hookGetter(cl, "android.telephony.TelephonyManager", "getSimOperatorName",
                s -> s.simOperatorName);
        hookGetter(cl, "android.telephony.TelephonyManager", "getSimCountryIso",
                s -> s.simCountryIso);
        hookGetter(cl, "android.telephony.TelephonyManager", "getNetworkCountryIso",
                s -> s.networkCountryIso);
        hookGetter(cl, "android.telephony.TelephonyManager", "isNetworkRoaming",
                s -> s.isRoaming);
        hookGetter(cl, "android.telephony.TelephonyManager", "getPhoneType",
                s -> s.phoneType);

        // Network type
        hookGetter(cl, "android.telephony.TelephonyManager", "getNetworkType",
                s -> s.networkType);

        // API 24+: getDataNetworkType, getVoiceNetworkType
        if (Build.VERSION.SDK_INT >= 24) {
            hookGetter(cl, "android.telephony.TelephonyManager", "getDataNetworkType",
                    s -> s.dataNetworkType != null ? s.dataNetworkType : s.networkType);
            hookGetter(cl, "android.telephony.TelephonyManager", "getVoiceNetworkType",
                    s -> s.voiceNetworkType);
        }
    }

    // ==========================================================================
    // F. SERVICE STATE
    // ==========================================================================

    private static void hookServiceState(ClassLoader cl) {
        hookGetter(cl, "android.telephony.TelephonyManager", "getDataState",
                s -> s.dataState);
        hookGetter(cl, "android.telephony.TelephonyManager", "getDataActivity",
                s -> s.dataActivity);

        // ServiceState.getState()
        hookGetter(cl, "android.telephony.ServiceState", "getState",
                s -> s.serviceState);
        hookNullableCarrierGetter(cl, "android.telephony.ServiceState", "getOperatorAlphaLong",
                "operator_name", s -> s.operatorName);
        hookNullableCarrierGetter(cl, "android.telephony.ServiceState", "getOperatorAlphaShort",
                "operator_name", s -> s.operatorName);
        hookNullableCarrierGetter(cl, "android.telephony.ServiceState", "getOperatorNumeric",
                "operator_numeric", s -> s.operatorNumeric);
        hookGetter(cl, "android.telephony.ServiceState", "getRoaming",
                s -> s.isRoaming);

        // NetworkRegistrationInfo is absent on old Android releases. hookGetter is fail-closed:
        // registration is skipped when either the class or method does not exist.
        hookNullableCarrierGetter(cl, "android.telephony.NetworkRegistrationInfo",
                "getRegisteredPlmn", "operator_numeric", s -> s.operatorNumeric);
        hookGetter(cl, "android.telephony.NetworkRegistrationInfo", "isRoaming",
                s -> s.isRoaming);
        hookGetter(cl, "android.telephony.NetworkRegistrationInfo", "isNetworkRoaming",
                s -> s.isRoaming);

        // TelephonyDisplayInfo.getOverrideNetworkType() (API 30+)
        if (Build.VERSION.SDK_INT >= 30) {
            hookGetter(cl, "android.telephony.TelephonyDisplayInfo", "getNetworkType",
                    s -> Snapshot.resolveDisplayNetworkType(s.networkType));
            hookGetter(cl, "android.telephony.TelephonyDisplayInfo", "getOverrideNetworkType",
                    s -> s.overrideNetworkType);
        }

        // PhysicalChannelConfig: Section N. TelephonyCallback: Section L.
    }

    // ==========================================================================
    // G. WIFI HOOKS
    // ==========================================================================

    private static void hookWifi(ClassLoader cl) {

        // WifiManager.getScanResults() — return empty to hide real APs
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", cl,
                "getScanResults", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.wifiHidden != null && s.wifiHidden) {
                            param.setResult(new ArrayList<>());
                        }
                    }
                }));

        // WifiManager.getWifiState() — must be consistent with isWifiEnabled
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", cl,
                "getWifiState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.wifiEnabled != null) {
                            // WIFI_STATE_ENABLED=3, WIFI_STATE_DISABLED=1
                            param.setResult(s.wifiEnabled ? 3 : 1);
                        }
                    }
                }));

        // WifiManager.isWifiEnabled()
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", cl,
                "isWifiEnabled", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.wifiEnabled != null) {
                            param.setResult(s.wifiEnabled);
                        }
                    }
                }));

        // WifiInfo getters
        hookGetter(cl, "android.net.wifi.WifiInfo", "getMacAddress", s -> s.wifiMac);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getSSID",
                s -> s.wifiSsid != null ? "\"" + s.wifiSsid + "\"" : null);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getBSSID", s -> s.wifiBssid);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getRssi", s -> s.wifiRssi);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getFrequency", s -> s.wifiFrequency);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getLinkSpeed", s -> s.wifiLinkSpeed);

        // API 29+
        if (Build.VERSION.SDK_INT >= 29) {
            hookGetter(cl, "android.net.wifi.WifiInfo", "getTxLinkSpeedMbps", s -> s.wifiTxLinkSpeed);
            hookGetter(cl, "android.net.wifi.WifiInfo", "getRxLinkSpeedMbps", s -> s.wifiRxLinkSpeed);
        }
        // API 30+
        if (Build.VERSION.SDK_INT >= 30) {
            hookGetter(cl, "android.net.wifi.WifiInfo", "getWifiStandard", s -> s.wifiStandard);
        }
        // API 31+
        if (Build.VERSION.SDK_INT >= 31) {
            hookGetter(cl, "android.net.wifi.WifiInfo", "getCurrentSecurityType", s -> s.wifiSecurityType);
        }

        // WifiInfo.getIpAddress() — convert dotted string to int if configured
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo", cl,
                "getIpAddress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.wifiIp != null) {
                            param.setResult(ipToInt(s.wifiIp));
                        }
                    }
                }));
    }

    // ==========================================================================
    // H. NETWORK CONNECTIVITY HOOKS
    // ==========================================================================

    private static void hookNetwork(ClassLoader cl) {
        // NetworkInfo.getTypeName()
        hookGetter(cl, "android.net.NetworkInfo", "getTypeName", s -> s.connectionType);

        // NetworkInfo connectivity — always connected if we have config
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", cl,
                "isConnected", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.connectionType != null) {
                            param.setResult(true);
                        }
                    }
                }));

        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", cl,
                "isConnectedOrConnecting", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.connectionType != null) {
                            param.setResult(true);
                        }
                    }
                }));

        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", cl,
                "isAvailable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.connectionType != null) {
                            param.setResult(true);
                        }
                    }
                }));

        // LinkProperties hooks (modern path)
        hookGetter(cl, "android.net.LinkProperties", "getInterfaceName", s -> s.interfaceName);

        // ConnectivityManager.getActiveNetworkInfo() — ensure type matches connectionType
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.ConnectivityManager", cl,
                "getActiveNetworkInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.connectionType == null) return;
                        // Let existing NetworkInfo hooks handle field overrides on the result
                    }
                }));

        // LinkProperties.getDnsServers() — replace DNS list if configured
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.LinkProperties", cl,
                "getDnsServers", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.dnsPrimary == null) return;
                        try {
                            List<java.net.InetAddress> dns = new ArrayList<>();
                            dns.add(java.net.InetAddress.getByName(s.dnsPrimary));
                            if (s.dnsSecondary != null) {
                                dns.add(java.net.InetAddress.getByName(s.dnsSecondary));
                            }
                            param.setResult(dns);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": DNS override failed: " + t);
                        }
                    }
                }));

        // NetworkInterface.getName() — override interface name
        hookGetter(cl, "java.net.NetworkInterface", "getName", s -> s.interfaceName);
    }

    // ==========================================================================
    // I. PHONE STATE LISTENER — intercept TelephonyManager.listen(),
    //    hook concrete listener class methods (NOT base class)
    // ==========================================================================

    private static void hookPhoneStateListener(ClassLoader cl) {
        // Intercept TelephonyManager.listen(PhoneStateListener, int) to discover concrete listeners
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "listen",
                XposedHelpers.findClass("android.telephony.PhoneStateListener", cl),
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object listener = param.args[0];
                        if (listener == null) return;
                        hookPhoneStateListenerInstance(listener, cl);
                    }
                }));
    }

    /** Hook methods on a concrete PhoneStateListener subclass. Dedup via HOOKED set. */
    private static void hookPhoneStateListenerInstance(Object listener, ClassLoader cl) {
        Class<?> listenerClass = listener.getClass();
        String className = listenerClass.getName();

        // onCellLocationChanged(CellLocation) — VOID: modify args[0]
        String key1 = "psl#" + className + "#onCellLocationChanged";
        if (HOOKED.add(key1)) {
            tryHook(() -> XposedHelpers.findAndHookMethod(listenerClass,
                    "onCellLocationChanged", CellLocation.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!shouldTransformGsmCellLocation(s)) return;
                            GsmCellLocation loc = spoofedGsmLocationOrPassthrough(s, param.args[0]);
                            if (loc == null) return;
                            param.args[0] = loc;
                        }
                    }));
        }

        // onCellInfoChanged(List<CellInfo>) — VOID: modify args[0]
        String key2 = "psl#" + className + "#onCellInfoChanged";
        if (HOOKED.add(key2)) {
            tryHook(() -> XposedHelpers.findAndHookMethod(listenerClass,
                    "onCellInfoChanged", List.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!shouldMutateCellList(s)) {
                                registerRealNeighborBypasses(param.args[0]);
                                return;
                            }
                            ArrayList spoofed = spoofedCellsOrPassthrough(s, param.args[0]);
                            if (spoofed == null) return;
                            param.args[0] = spoofed;
                        }
                    }));
        }

        // onSignalStrengthsChanged(SignalStrength) — VOID: getter hooks handle fields
        String key3 = "psl#" + className + "#onSignalStrengthsChanged";
        if (HOOKED.add(key3)) {
            tryHook(() -> {
                Class<?> ssClass = XposedHelpers.findClass("android.telephony.SignalStrength", cl);
                XposedHelpers.findAndHookMethod(listenerClass,
                        "onSignalStrengthsChanged", ssClass, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                // Individual field overrides handled by CellSignalStrength getter hooks
                            }
                        });
            });
        }

        // onServiceStateChanged(ServiceState) — VOID: modify internal fields
        String key4 = "psl#" + className + "#onServiceStateChanged";
        if (HOOKED.add(key4)) {
            tryHook(() -> {
                Class<?> stateClass = XposedHelpers.findClass("android.telephony.ServiceState", cl);
                XposedHelpers.findAndHookMethod(listenerClass,
                        "onServiceStateChanged", stateClass, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Snapshot s = currentSnapshot();
                                if (s.serviceState != null && param.args[0] != null) {
                                    try {
                                        XposedHelpers.setIntField(param.args[0], "mVoiceRegState", s.serviceState);
                                        if (s.dataState != null) {
                                            XposedHelpers.setIntField(param.args[0], "mDataRegState", s.dataState);
                                        }
                                    } catch (Throwable ignored) {
                                        // Field names may vary across Android versions
                                    }
                                }
                            }
                        });
            });
        }
    }

    // ==========================================================================
    // J. GPS STATUS HOOKS
    // ==========================================================================

    private static void hookGpsStatus(ClassLoader cl) {

        // addGpsStatusListener — simulate GPS fix events
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "addGpsStatusListener",
                GpsStatus.Listener.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        if (param.args[0] != null) {
                            GpsStatus.Listener listener = (GpsStatus.Listener) param.args[0];
                            listener.onGpsStatusChanged(1); // GPS_EVENT_STARTED
                            listener.onGpsStatusChanged(3); // GPS_EVENT_FIRST_FIX
                        }
                    }
                }));

        // getGpsStatus — fake satellite constellation
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.location.LocationManager", cl,
                "getGpsStatus", GpsStatus.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;

                        GpsStatus gss = (GpsStatus) param.getResult();
                        if (gss == null) return;

                        try {
                            int svCount = 5;
                            int[] prns = {1, 2, 3, 4, 5};
                            float[] snrs = {30f, 28f, 25f, 22f, 20f};
                            float[] elevations = {60f, 45f, 30f, 15f, 10f};
                            float[] azimuths = {0f, 72f, 144f, 216f, 288f};
                            int ephemerisMask = 0x1f;
                            int almanacMask = 0x1f;
                            int usedInFixMask = 0x1f;

                            XposedHelpers.callMethod(gss, "setStatus",
                                    svCount, prns, snrs, elevations, azimuths,
                                    ephemerisMask, almanacMask, usedInFixMask);
                            param.setResult(gss);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": GpsStatus.setStatus failed: " + t);
                        }
                    }
                }));
    }

    // ==========================================================================
    // K. LOCATION MANAGER CONSTRUCTOR — hook internal reportLocation
    // ==========================================================================

    private static void hookLocationManagerConstructor(ClassLoader cl) {
        tryHook(() -> XposedBridge.hookAllConstructors(LocationManager.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length < 2) return;

                Context context = (Context) param.args[0];
                String ctxClass = context.getClass().getName();

                // Dedup: only hook each context class once
                if (HOOKED.add("ctx#" + ctxClass + "#checkCallingOrSelfPermission")) {
                    tryHook(() -> XposedHelpers.findAndHookMethod(
                            context.getClass(), "checkCallingOrSelfPermission",
                            String.class, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p) {
                                    if (String.valueOf(p.args[0]).contains("INSTALL_LOCATION_PROVIDER")) {
                                        p.setResult(PackageManager.PERMISSION_GRANTED);
                                    }
                                }
                            }));
                }

                // Hook internal service methods — dedup by Method identity
                Object service = param.args[1];
                String svcClass = service.getClass().getName();
                for (Method m : service.getClass().getMethods()) {
                    String key = "svc#" + svcClass + "#" + m.getName();
                    if ("reportLocation".equals(m.getName()) && HOOKED.add(key)) {
                        m.setAccessible(true);
                        tryHook(() -> XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                Snapshot s = currentSnapshot();
                                if (!s.hasLocation()) return;
                                if (p.args[0] instanceof Location) {
                                    Location loc = (Location) p.args[0];
                                    loc.setLatitude(s.latitude);
                                    loc.setLongitude(s.longitude);
                                    if (s.altitude != null) loc.setAltitude(s.altitude);
                                    if (s.accuracy != null) loc.setAccuracy(s.accuracy);
                                }
                            }
                        }));
                    } else if (("getLastLocation".equals(m.getName())
                            || "getLastKnownLocation".equals(m.getName())) && HOOKED.add(key)) {
                        tryHook(() -> XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                Snapshot s = currentSnapshot();
                                if (!s.hasLocation()) return;
                                if (p.getResult() instanceof Location) {
                                    Location loc = (Location) p.getResult();
                                    loc.setLatitude(s.latitude);
                                    loc.setLongitude(s.longitude);
                                    if (s.altitude != null) loc.setAltitude(s.altitude);
                                    if (s.accuracy != null) loc.setAccuracy(s.accuracy);
                                }
                            }
                        }));
                    }
                }
            }
        }));
    }

    // ==========================================================================
    // L. TELEPHONY CALLBACK (API 31+) — dual-path with PhoneStateListener
    // ==========================================================================

    private static void hookTelephonyCallback(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 31) return;

        try {
            Class<?> tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", cl);
            Class<?> callbackClass = XposedHelpers.findClass("android.telephony.TelephonyCallback", cl);

            // hookAllMethods covers both 2-param (Executor, TelephonyCallback)
            // and 3-param API 33+ (Executor, int includeLocationData, TelephonyCallback)
            XposedBridge.hookAllMethods(tmClass, "registerTelephonyCallback",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Find the TelephonyCallback arg among all params
                            for (Object arg : param.args) {
                                if (arg != null && callbackClass.isInstance(arg)) {
                                    hookTelephonyCallbackInstance(arg, cl);
                                    break;
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TelephonyCallback registration hook failed: " + t);
        }
    }

    /** Hook methods on a concrete TelephonyCallback instance based on which interfaces it implements. */
    private static void hookTelephonyCallbackInstance(Object callback, ClassLoader cl) {
        Class<?> cbClass = callback.getClass();
        String cbName = cbClass.getName();

        // CellInfoListener.onCellInfoChanged(List<CellInfo>)
        hookCallbackMethod(cl, cbClass, cbName,
                "android.telephony.TelephonyCallback$CellInfoListener",
                "onCellInfoChanged", List.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!shouldMutateCellList(s)) {
                            registerRealNeighborBypasses(param.args[0]);
                            return;
                        }
                        ArrayList spoofed = spoofedCellsOrPassthrough(s, param.args[0]);
                        if (spoofed == null) return;
                        param.args[0] = spoofed;
                    }
                });

        // CellLocationListener.onCellLocationChanged(CellLocation)
        hookCallbackMethod(cl, cbClass, cbName,
                "android.telephony.TelephonyCallback$CellLocationListener",
                "onCellLocationChanged", CellLocation.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!shouldTransformGsmCellLocation(s)) return;
                        GsmCellLocation loc = spoofedGsmLocationOrPassthrough(s, param.args[0]);
                        if (loc == null) return;
                        param.args[0] = loc;
                    }
                });

        // SignalStrengthsListener.onSignalStrengthsChanged(SignalStrength)
        try {
            Class<?> ssClass = XposedHelpers.findClass("android.telephony.SignalStrength", cl);
            hookCallbackMethod(cl, cbClass, cbName,
                    "android.telephony.TelephonyCallback$SignalStrengthsListener",
                    "onSignalStrengthsChanged", ssClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Individual field overrides handled by CellSignalStrength getter hooks
                        }
                    });
        } catch (Throwable ignored) {}

        // ServiceStateListener.onServiceStateChanged(ServiceState)
        try {
            Class<?> stateClass = XposedHelpers.findClass("android.telephony.ServiceState", cl);
            hookCallbackMethod(cl, cbClass, cbName,
                    "android.telephony.TelephonyCallback$ServiceStateListener",
                    "onServiceStateChanged", stateClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (s.serviceState != null && param.args[0] != null) {
                                try {
                                    XposedHelpers.setIntField(param.args[0], "mVoiceRegState", s.serviceState);
                                    if (s.dataState != null) {
                                        XposedHelpers.setIntField(param.args[0], "mDataRegState", s.dataState);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        // DisplayInfoListener.onDisplayInfoChanged(TelephonyDisplayInfo)
        try {
            Class<?> dispClass = XposedHelpers.findClass("android.telephony.TelephonyDisplayInfo", cl);
            hookCallbackMethod(cl, cbClass, cbName,
                    "android.telephony.TelephonyCallback$DisplayInfoListener",
                    "onDisplayInfoChanged", dispClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Override handled by TelephonyDisplayInfo getter hooks
                        }
                    });
        } catch (Throwable ignored) {}

        // PhysicalChannelConfigListener.onPhysicalChannelConfigChanged(List<PhysicalChannelConfig>)
        hookCallbackMethod(cl, cbClass, cbName,
                "android.telephony.TelephonyCallback$PhysicalChannelConfigListener",
                "onPhysicalChannelConfigChanged", List.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasPhysicalChannelConfig()) return;
                        // Replace list with single fake PCC — getter hooks override fields
                        try {
                            Class<?> pccClass = XposedHelpers.findClass(
                                    "android.telephony.PhysicalChannelConfig", cl);
                            Object fakePcc =
                                    CellConstructorCompat.newPhysicalChannelConfig(pccClass);
                            ArrayList<Object> pccList = new ArrayList<>();
                            pccList.add(fakePcc);
                            param.args[0] = pccList;
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": PCC callback override failed: " + t);
                        }
                    }
                });
    }

    /** Hook a callback method if the concrete class implements the given interface. Dedup via HOOKED set. */
    private static void hookCallbackMethod(ClassLoader cl, Class<?> cbClass, String cbName,
                                           String interfaceName, String methodName,
                                           Class<?> paramType, XC_MethodHook hook) {
        try {
            Class<?> iface = XposedHelpers.findClass(interfaceName, cl);
            if (!iface.isAssignableFrom(cbClass)) return;
            String key = "tcb#" + cbName + "#" + methodName;
            if (!HOOKED.add(key)) return;
            XposedHelpers.findAndHookMethod(cbClass, methodName, paramType, hook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TelephonyCallback." + methodName + " hook skipped: " + t.getMessage());
        }
    }

    /** Hook concrete CellInfoCallback subclass's onCellInfo() method. Dedup via HOOKED set. */
    private static void hookCellInfoCallbackInstance(Object callback, ClassLoader cl) {
        Class<?> cbClass = callback.getClass();
        String key = "cic#" + cbClass.getName() + "#onCellInfo";
        if (!HOOKED.add(key)) return;
        tryHook(() -> XposedHelpers.findAndHookMethod(cbClass, "onCellInfo", List.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!shouldMutateCellList(s)) {
                            registerRealNeighborBypasses(param.args[0]);
                            return;
                        }
                        ArrayList spoofed = spoofedCellsOrPassthrough(s, param.args[0]);
                        if (spoofed == null) return;
                        param.args[0] = spoofed;
                    }
                }));
    }

    // ==========================================================================
    // M. CONNECTIVITY — LinkProperties, NetworkInterface, DNS
    // ==========================================================================

    private static void hookConnectivity(ClassLoader cl) {
        // LinkProperties.getLinkAddresses() — for local IP discovery
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.LinkProperties", cl,
                "getLinkAddresses", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.localIpv4 == null && s.localIpv6 == null) return;
                        // Replace addresses with fake ones
                        try {
                            List<?> original = (List<?>) param.getResult();
                            if (original == null || original.isEmpty()) return;
                            ArrayList<Object> fakeAddrs = new ArrayList<>();
                            for (Object linkAddr : original) {
                                java.net.InetAddress addr = (java.net.InetAddress)
                                        XposedHelpers.callMethod(linkAddr, "getAddress");
                                String addrStr = addr.getHostAddress();
                                if (addrStr != null && addrStr.contains(".") && s.localIpv4 != null) {
                                    // Replace IPv4
                                    Object fake = XposedHelpers.newInstance(
                                            linkAddr.getClass(),
                                            java.net.InetAddress.getByName(s.localIpv4),
                                            XposedHelpers.callMethod(linkAddr, "getPrefixLength"));
                                    fakeAddrs.add(fake);
                                } else if (addrStr != null && addrStr.contains(":") && s.localIpv6 != null) {
                                    // Replace IPv6
                                    Object fake = XposedHelpers.newInstance(
                                            linkAddr.getClass(),
                                            java.net.InetAddress.getByName(s.localIpv6),
                                            XposedHelpers.callMethod(linkAddr, "getPrefixLength"));
                                    fakeAddrs.add(fake);
                                } else {
                                    fakeAddrs.add(linkAddr);
                                }
                            }
                            param.setResult(fakeAddrs);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": LinkAddress override failed: " + t);
                        }
                    }
                }));

        // NetworkInterface.getInetAddresses() — for apps enumerating interfaces
        tryHook(() -> XposedBridge.hookAllMethods(
                java.net.NetworkInterface.class, "getInetAddresses", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.localIpv4 == null && s.localIpv6 == null) return;
                        @SuppressWarnings("unchecked")
                        java.util.Enumeration<java.net.InetAddress> original =
                                (java.util.Enumeration<java.net.InetAddress>) param.getResult();
                        if (original == null) return;

                        ArrayList<java.net.InetAddress> modified = new ArrayList<>();
                        while (original.hasMoreElements()) {
                            java.net.InetAddress addr = original.nextElement();
                            String addrStr = addr.getHostAddress();
                            try {
                                if (addrStr != null && addrStr.contains(".") && s.localIpv4 != null
                                        && !addr.isLoopbackAddress()) {
                                    modified.add(java.net.InetAddress.getByName(s.localIpv4));
                                } else if (addrStr != null && addrStr.contains(":") && s.localIpv6 != null
                                        && !addr.isLoopbackAddress()) {
                                    modified.add(java.net.InetAddress.getByName(s.localIpv6));
                                } else {
                                    modified.add(addr);
                                }
                            } catch (Throwable t) {
                                modified.add(addr);
                            }
                        }
                        param.setResult(Collections.enumeration(modified));
                    }
                }));

        // LinkProperties.getRoutes() — replace gateway address
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.LinkProperties", cl,
                "getRoutes", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.gateway == null) return;
                        try {
                            @SuppressWarnings("unchecked")
                            List<Object> original = (List<Object>) param.getResult();
                            if (original == null || original.isEmpty()) return;
                            ArrayList<Object> fakeRoutes = new ArrayList<>();
                            java.net.InetAddress fakeGw = java.net.InetAddress.getByName(s.gateway);
                            Class<?> routeInfoClass = XposedHelpers.findClass(
                                    "android.net.RouteInfo", cl);
                            for (Object route : original) {
                                java.net.InetAddress gw = (java.net.InetAddress)
                                        XposedHelpers.callMethod(route, "getGateway");
                                if (gw != null && !gw.isAnyLocalAddress()) {
                                    // Replace route with same destination but fake gateway
                                    Object dest = XposedHelpers.callMethod(route, "getDestination");
                                    String iface = (String) XposedHelpers.callMethod(route, "getInterface");
                                    try {
                                        Object fake = XposedHelpers.newInstance(routeInfoClass,
                                                dest, fakeGw, iface);
                                        fakeRoutes.add(fake);
                                        continue;
                                    } catch (Throwable ignored) {}
                                }
                                fakeRoutes.add(route);
                            }
                            param.setResult(fakeRoutes);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": Route/gateway override failed: " + t);
                        }
                    }
                }));

        // WifiManager.getDhcpInfo() — override gateway + subnet mask
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", cl,
                "getDhcpInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (s.gateway == null && s.subnetMask == null
                                && s.localIpv4 == null && s.dnsPrimary == null
                                && s.dnsSecondary == null) return;
                        Object dhcp = param.getResult();
                        if (dhcp == null) return;
                        try {
                            if (s.gateway != null) {
                                XposedHelpers.setIntField(dhcp, "gateway",
                                        ipToInt(s.gateway));
                            }
                            if (s.subnetMask != null) {
                                XposedHelpers.setIntField(dhcp, "netmask",
                                        ipToInt(s.subnetMask));
                            }
                            if (s.localIpv4 != null) {
                                XposedHelpers.setIntField(dhcp, "ipAddress",
                                        ipToInt(s.localIpv4));
                            }
                            if (s.dnsPrimary != null) {
                                XposedHelpers.setIntField(dhcp, "dns1",
                                        ipToInt(s.dnsPrimary));
                            }
                            if (s.dnsSecondary != null) {
                                XposedHelpers.setIntField(dhcp, "dns2",
                                        ipToInt(s.dnsSecondary));
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": DhcpInfo override failed: " + t);
                        }
                    }
                }));
    }


    // ==========================================================================
    // N. PHYSICAL CHANNEL CONFIG (API 29+) — getter hooks + callback interception
    // ==========================================================================

    private static void hookPhysicalChannelConfig(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 29) return;

        try {
            XposedHelpers.findClass("android.telephony.PhysicalChannelConfig", cl);

            // These unavailable values coincide with a no-arg Builder's defaults, so dynamic
            // acceptance alone cannot prove their individual getter hooks. Install all from one
            // tested registry whose API census supplies the missing static evidence.
            for (PhysicalChannelHookRegistry.Entry binding
                    : PhysicalChannelHookRegistry.entriesForApi(Build.VERSION.SDK_INT)) {
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        binding.methodName, s -> physicalChannelUnavailableValue(
                                binding.field, s));
            }

            // PhysicalChannelConfig.getConnectionStatus() (API 29+) — PRIMARY_SERVING=1
            hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                    "getConnectionStatus", s -> s.hasPhysicalChannelConfig() ? 1 : null);

            // API 30+: getNetworkType()
            if (Build.VERSION.SDK_INT >= 30) {
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        "getNetworkType", s -> s.networkType);
            }

            // API 31+: getBand(), getDownlinkChannelNumber(), getUplinkChannelNumber()
            if (Build.VERSION.SDK_INT >= 31) {
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        "getDownlinkChannelNumber", s -> {
                            // Map to appropriate ARFCN based on available cell config
                            if (s.nrarfcn != null) return s.nrarfcn;
                            if (s.earfcn != null) return s.earfcn;
                            if (s.uarfcn != null) return s.uarfcn;
                            return s.arfcn;
                        });
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        "getUplinkChannelNumber", s -> {
                            // Symmetric with downlink — same ARFCN cascade
                            if (s.nrarfcn != null) return s.nrarfcn;
                            if (s.earfcn != null) return s.earfcn;
                            if (s.uarfcn != null) return s.uarfcn;
                            return s.arfcn;
                        });
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PhysicalChannelConfig class not found, skipping: " + t.getMessage());
        }
    }

    private static Object physicalChannelUnavailableValue(String field, Snapshot snapshot) {
        switch (field) {
            case "cell_bandwidth_downlink": return snapshot.cellBandwidthDownlink;
            case "physical_cell_id": return snapshot.physicalCellId;
            case "band": return snapshot.band;
            case "channel_bandwidth":
                return Snapshot.resolvePhysicalUplinkBandwidth(snapshot.channelBandwidth);
            default: throw new IllegalArgumentException(
                    "unknown physical-channel field: " + field);
        }
    }

    // ==========================================================================
    // O. SUBSCRIPTION-AWARE — intercept createForSubscriptionId() for dual-SIM
    // ==========================================================================

    private static void hookSubscriptionAware(ClassLoader cl) {
        // TelephonyManager.createForSubscriptionId(int) returns a new TelephonyManager
        // bound to a specific SIM slot. If we don't intercept it, the sub-specific
        // TM will bypass our hooks on the default TM's getter results.
        // Strategy: hook createForSubscriptionId itself, and then hook all relevant
        // getter methods on the returned TM's concrete class (if different from default).

        if (Build.VERSION.SDK_INT < 24) return;

        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "createForSubscriptionId", int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // The returned TelephonyManager is still android.telephony.TelephonyManager
                        // but bound to a specific sub. Since our getter hooks are on the class
                        // (not instance), they already apply. But we need to ensure
                        // listen()/registerTelephonyCallback() on the sub-TM also gets intercepted.
                        // Those are already hooked via class-level hooks, so nothing extra needed.
                        // However, log for debugging that a sub-TM was created.
                        Snapshot s = currentSnapshot();
                        if (shouldRebuildServingCells(s)) {
                            XposedBridge.log(TAG + ": createForSubscriptionId(" + param.args[0]
                                    + ") intercepted — class-level hooks will apply");
                        }
                    }
                }));

        // SubscriptionManager.getActiveSubscriptionInfoList() — if spoofing cells,
        // force single-SIM appearance to prevent cross-slot leakage
        if (Build.VERSION.SDK_INT >= 22) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getActiveSubscriptionInfoList", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!shouldRebuildServingCells(s)) return;

                            // Trim to first subscription only to prevent dual-SIM detection
                            Object result = param.getResult();
                            if (result instanceof List) {
                                List<?> subs = (List<?>) result;
                                if (subs.size() > 1) {
                                    ArrayList<Object> trimmed = new ArrayList<>();
                                    trimmed.add(subs.get(0));
                                    param.setResult(trimmed);
                                }
                            }
                        }
                    }));
        }

        // SubscriptionManager.getActiveSubscriptionInfoCount() — match trimmed list
        if (Build.VERSION.SDK_INT >= 22) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getActiveSubscriptionInfoCount", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!shouldRebuildServingCells(s)) return;
                            int count = (int) param.getResult();
                            if (count > 1) {
                                param.setResult(1);
                            }
                        }
                    }));
        }

        // SubscriptionManager.getCompleteActiveSubscriptionInfoList() (API 30+)
        if (Build.VERSION.SDK_INT >= 30) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getCompleteActiveSubscriptionInfoList", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!shouldRebuildServingCells(s)) return;
                            Object result = param.getResult();
                            if (result instanceof List) {
                                List<?> subs = (List<?>) result;
                                if (subs.size() > 1) {
                                    ArrayList<Object> trimmed = new ArrayList<>();
                                    trimmed.add(subs.get(0));
                                    param.setResult(trimmed);
                                }
                            }
                        }
                    }));
        }

        // SubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(int) (API 24+)
        // — return null for slot > 0 to hide second SIM
        if (Build.VERSION.SDK_INT >= 24) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getActiveSubscriptionInfoForSimSlotIndex", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!shouldRebuildServingCells(s)) return;
                            int slotIndex = (int) param.args[0];
                            if (slotIndex > 0) {
                                param.setResult(null);
                            }
                        }
                    }));
        }

        // getDefaultDataSubscriptionId / getDefaultSmsSubscriptionId — passthrough is safe
        // because list/count/slot-index trimming already hides the second SIM from discovery.
        // No need to hook default sub IDs; they point to slot 0 by default on most devices.

        // SubscriptionManager.getActiveSubscriptionInfoCountMax() (API 22+)
        // — hardware slot count; cap to 1 to hide second SIM capability
        if (Build.VERSION.SDK_INT >= 22) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getActiveSubscriptionInfoCountMax", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!shouldRebuildServingCells(s)) return;
                            int max = (int) param.getResult();
                            if (max > 1) {
                                param.setResult(1);
                            }
                        }
                    }));
        }

        // SubscriptionManager.getSubscriptionIds(int slotIndex) (API 34+)
        // — return empty array for slot > 0
        if (Build.VERSION.SDK_INT >= 34) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getSubscriptionIds", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!shouldRebuildServingCells(s)) return;
                            int slotIndex = (int) param.args[0];
                            if (slotIndex > 0) {
                                param.setResult(new int[0]);
                            }
                        }
                    }));
        }
    }

    // ==========================================================================
    // P. FUSED LOCATION (Google Play Services)
    // ==========================================================================

    private static final String LOCATION_SERVICES =
            "com.google.android.gms.location.LocationServices";
    private static final String FUSED_CONTRACT =
            "com.google.android.gms.location.FusedLocationProviderClient";
    private static final String GMS_LOCATION_RESULT =
            "com.google.android.gms.location.LocationResult";
    private static final String GMS_LOCATION_AVAILABILITY =
            "com.google.android.gms.location.LocationAvailability";

    /**
     * Per-Method hook dedup (Sol R5 #3): two runtime subclasses inheriting the same
     * implementation Method must not double-hook it, and a method whose installation
     * failed stays unclaimed so a later discovery retries it. The old per-class set
     * violated both. Discovery logging is deduped separately (evidence only).
     */
    private static final FusedHookRegistry FUSED_HOOK_REGISTRY = new FusedHookRegistry();
    private static final Set<Class<?>> FUSED_DISCOVERY_LOGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Fused discovery (2026-08-04 frozen design, project-research/2026-08-04-gms-fused-runtime-discovery):
     *
     * GMS renames the concrete client every release (zzbp &rarr; zzbi &rarr; zzcg, and app-side
     * R8 turns it into meaningless names like {@code bkmc}), so internal-name guessing is dead —
     * it failed on this device and left Google Maps showing the REAL location. The stable seam
     * is the public {@code LocationServices.getFusedLocationProviderClient} factory, which
     * survives every inspected SDK and current Maps 26.31. We arm the factory eagerly, validate
     * each returned object against the public contract, resolve its exact implementation
     * {@link Method}s via {@link FusedClientMethodPlan}, and hook those exact Methods — Xposed's
     * {@code hookAllMethods} only sees declared methods, so interface/inherited implementations
     * must be resolved explicitly.
     */
    private static void hookFusedLocation(ClassLoader cl) {
        String fusedApi = "com.google.android.gms.location.FusedLocationProviderApi";

        // 1. Arm the public factory. The after-hook instruments the returned client before
        //    application code receives it.
        tryHook(() -> {
            Class<?> services = XposedHelpers.findClass(LOCATION_SERVICES, cl);
            final Class<?> contract = XposedHelpers.findClass(FUSED_CONTRACT, cl);
            int overloads = 0;
            for (Method m : services.getDeclaredMethods()) {
                if ("getFusedLocationProviderClient".equals(m.getName())) overloads++;
            }
            final int armedOverloads = overloads;
            XposedBridge.hookAllMethods(services, "getFusedLocationProviderClient",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            installFusedClientHooks(param.getResult(), contract, cl);
                        }
                    });
            XposedBridge.log(RuntimeEvidence.fusedFactoryArmed(armedOverloads));
        });

        // 2. Pre-21 shape: the public client is itself a concrete class (no factory
        //    indirection needed) — install on it eagerly.
        tryHook(() -> {
            Class<?> contract = XposedHelpers.findClass(FUSED_CONTRACT, cl);
            if (!contract.isInterface() && !Modifier.isAbstract(contract.getModifiers())) {
                installFusedClientHooksOnClass(contract, contract, cl);
            }
        });

        // 3. Legacy FusedLocationProviderApi (used via LocationServices.FusedLocationApi)
        tryHook(() -> {
            Class<?> apiClass = XposedHelpers.findClass(fusedApi, cl);
            XposedBridge.hookAllMethods(apiClass, "getLastLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Snapshot s = currentSnapshot();
                    if (!s.hasLocation()) return;
                    param.setResult(createFakeLocation(s));
                }
            });
        });

        // 4. Public value-object seams (defense in depth). Capabilities are resolved by
        //    signature SHAPE, never by member name — on the exact Maps APK R8 renamed even
        //    LocationResult.extractResult (Sol R5 #2).
        tryHook(() -> {
            final Class<?> lrClass = XposedHelpers.findClass(GMS_LOCATION_RESULT, cl);

            // getLastLocation capability: instance zero-arg -> android.location.Location
            try {
                final Method locationAccessor = FusedDeliveryPlan.resolveLocationAccessor(lrClass);
                installObserved("LocationResult#locationAccessor", locationAccessor,
                        method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Snapshot s = currentSnapshot();
                                if (!s.hasLocation()) return;
                                param.setResult(createFakeLocation(s));
                            }
                        }));
            } catch (NoSuchMethodException missing) {
                XposedBridge.log(RuntimeEvidence.fusedSurfaceMissing("LocationResult#locationAccessor"));
            }

            // getLocations capability: instance zero-arg -> List
            try {
                final Method listAccessor = FusedDeliveryPlan.resolveListAccessor(lrClass);
                installObserved("LocationResult#listAccessor", listAccessor,
                        method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Snapshot s = currentSnapshot();
                                if (!s.hasLocation()) return;
                                ArrayList<Location> fakeList = new ArrayList<>();
                                fakeList.add(createFakeLocation(s));
                                param.setResult(fakeList);
                            }
                        }));
            } catch (NoSuchMethodException missing) {
                XposedBridge.log(RuntimeEvidence.fusedSurfaceMissing("LocationResult#listAccessor"));
            }

            // extractResult capability (documented PendingIntent path): static (Intent)->self,
            // answered via the public construction seam (factory or (List) constructor).
            try {
                final Method extractResult = FusedDeliveryPlan.resolveStaticResultFactory(lrClass);
                installObserved("LocationResult#staticFactory", extractResult,
                        method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Snapshot s = currentSnapshot();
                                if (!s.hasLocation()) return;
                                try {
                                    ArrayList<Location> fakeList = new ArrayList<>();
                                    fakeList.add(createFakeLocation(s));
                                    param.setResult(FusedDeliveryPlan.buildResult(lrClass, fakeList));
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": LocationResult extractResult: " + t);
                                }
                            }
                        }));
            } catch (NoSuchMethodException missing) {
                XposedBridge.log(RuntimeEvidence.fusedSurfaceMissing("LocationResult#staticFactory"));
            }
        });

        tryHook(() -> {
            Class<?> laClass = XposedHelpers.findClass(GMS_LOCATION_AVAILABILITY, cl);
            try {
                final Method booleanAccessor = FusedDeliveryPlan.resolveBooleanAccessor(laClass);
                installObserved("LocationAvailability#booleanAccessor", booleanAccessor,
                        method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Snapshot s = currentSnapshot();
                                if (!s.hasLocation()) return;
                                param.setResult(true);
                            }
                        }));
            } catch (NoSuchMethodException missing) {
                XposedBridge.log(RuntimeEvidence.fusedSurfaceMissing("LocationAvailability#booleanAccessor"));
            }
        });
    }

    /**
     * The single observed-install path for every fused hook outside the Task delivery
     * aggregator (Sol R9 #1): the terminal result is ALWAYS consumed — a failed install
     * immediately emits bounded surface-missing evidence, so a hooked-looking
     * registration can never mask an uninstalled delivery. The result is returned for
     * callers that additionally distinguish fresh vs repeat installs.
     */
    private static FusedHookRegistry.InstallResult installObserved(
            String evidenceLabel, Method method, FusedHookRegistry.Installer installer) {
        FusedHookRegistry.InstallResult result =
                FUSED_HOOK_REGISTRY.claimAndInstall(method, installer);
        if (result == FusedHookRegistry.InstallResult.FAILED) {
            XposedBridge.log(RuntimeEvidence.fusedSurfaceMissing(evidenceLabel));
        }
        return result;
    }

    /** Validate a factory-returned client and instrument its runtime class. */
    private static void installFusedClientHooks(Object client, Class<?> contract, ClassLoader cl) {
        if (client == null) return;
        Class<?> runtime = client.getClass();
        if (!FusedClientMethodPlan.isEligible(contract, runtime) || !contract.isInstance(client)) {
            XposedBridge.log(RuntimeEvidence.fusedClientRejected("NOT_ASSIGNABLE"));
            return;
        }
        installFusedClientHooksOnClass(contract, runtime, cl);
    }

    /**
     * Plan and install exact-Method hooks for one runtime class. A class whose plan fully
     * installed is marked complete and never re-planned (Sol R8 #3); a class with any
     * failed install stays eligible so the next factory call retries it.
     */
    private static void installFusedClientHooksOnClass(
            Class<?> contract, Class<?> runtime, ClassLoader cl) {
        if (FUSED_COMPLETED_CLASSES.contains(runtime)) return;
        if (FUSED_DISCOVERY_LOGGED.add(runtime)) {
            XposedBridge.log(RuntimeEvidence.fusedClientDiscovered(
                    runtime.getName(), System.identityHashCode(cl)));
        }

        List<FusedClientMethodPlan.Entry> plan = FusedClientMethodPlan.plan(contract, runtime);
        if (plan.isEmpty()) {
            XposedBridge.log(RuntimeEvidence.fusedClientRejected("NO_RESOLVABLE_SURFACES"));
            return;
        }
        boolean allInstalled = true;
        for (FusedClientMethodPlan.Entry entry : plan) {
            allInstalled &= installFusedSurfaceHook(entry, cl);
        }
        if (allInstalled) {
            FUSED_COMPLETED_CLASSES.add(runtime);
        }
    }

    /** Runtime classes whose surface plan fully installed — never re-planned. */
    private static final Set<Class<?>> FUSED_COMPLETED_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Install one surface hook via the terminal-state transaction.
     *
     * @return true when the surface is hooked (fresh or already); false on failure.
     *         Success evidence fires ONLY on a fresh install (Sol R8 #3 — a re-discovery
     *         must never be logged as a new install).
     */
    private static boolean installFusedSurfaceHook(
            FusedClientMethodPlan.Entry entry, ClassLoader cl) {
        final FusedHookRegistry.InstallResult result;
        switch (entry.surface) {
            case LAST_LOCATION_TASK:
            case CURRENT_LOCATION_TASK:
                // Sol R6 #1: the declared return type is the ABSTRACT Task (bkwo on
                // exact Maps) — delivery hooks must be planned from the ACTUAL returned
                // object's runtime class, and wrapping is gated to the exact instances
                // this API handed out (FusedTaskTracker), not every GMS task.
                result = installObserved(entry.contract.getName(), entry.implementation,
                        method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object task = param.getResult();
                                if (task == null) return;
                                // Track per surface: LAST and CURRENT share this branch and
                                // often share a runtime Task class, so identity alone cannot
                                // tell a recenter (getCurrentLocation) from the steady
                                // last-location stream at delivery time.
                                if (entry.surface
                                        == FusedClientMethodPlan.Surface.CURRENT_LOCATION_TASK) {
                                    FUSED_CURRENT_TASK_TRACKER.mark(task);
                                } else {
                                    FUSED_LAST_TASK_TRACKER.mark(task);
                                }
                                installTaskDeliveryHooks(task.getClass(), cl);
                            }
                        }));
                break;
            case CALLBACK_REGISTRATION:
                result = installObserved(entry.contract.getName(), entry.implementation,
                        method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                for (Object arg : param.args) {
                                    if (arg != null && entry.callbackType.isInstance(arg)) {
                                        hookFusedLocationCallback(arg, cl);
                                        return;
                                    }
                                }
                            }
                        }));
                break;
            case LISTENER_REGISTRATION:
                result = installObserved(entry.contract.getName(), entry.implementation,
                        method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                for (Object arg : param.args) {
                                    if (arg != null && entry.callbackType.isInstance(arg)) {
                                        hookFusedLocationListener(arg, cl);
                                        return;
                                    }
                                }
                            }
                        }));
                break;
            default:
                return true;
        }
        switch (result) {
            case INSTALLED:
                XposedBridge.log(RuntimeEvidence.fusedSurfaceHooked(
                        entry.surface.name(),
                        entry.implementation.getDeclaringClass().getName()));
                return true;
            case ALREADY_INSTALLED:
                return true;
            default:
                // failure evidence already emitted by installObserved
                return false;
        }
    }

    private static final FusedTaskTracker FUSED_LAST_TASK_TRACKER = new FusedTaskTracker();
    private static final FusedTaskTracker FUSED_CURRENT_TASK_TRACKER = new FusedTaskTracker();
    /** Runtime task classes whose delivery outcome was already fully reported. */
    private static final Set<Class<?>> FUSED_DELIVERY_EVIDENCED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Runtime task classes with at least one actually-wrapped success listener. */
    private static final Set<Class<?>> FUSED_WRAP_EVIDENCED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Wrap success-listener registrations on a runtime Task class, but only for instances
     * handed out by hooked fused APIs (identity gate — the runtime Task class is shared by
     * every GMS call in the process). Reports the terminal install summary per entry —
     * "planner found N" can never masquerade as "N installed" (Sol R8 #2); while any
     * install is failing, the class stays eligible for re-planning and re-reporting.
     */
    private static void installTaskDeliveryHooks(Class<?> taskClass, ClassLoader cl) {
        if (FUSED_DELIVERY_EVIDENCED.contains(taskClass)) return;
        java.util.List<FusedDeliveryPlan.TaskDelivery> plan =
                FusedDeliveryPlan.planTaskDelivery(taskClass);
        int installed = 0;
        int already = 0;
        int failed = 0;
        String reason = null;
        for (FusedDeliveryPlan.TaskDelivery delivery : plan) {
            FusedHookRegistry.InstallResult result =
                    FUSED_HOOK_REGISTRY.claimAndInstall(delivery.registrationMethod,
                            method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    Snapshot s = currentSnapshot();
                                    if (!s.hasLocation()) return;
                                    boolean current = FUSED_CURRENT_TASK_TRACKER
                                            .isTracked(param.thisObject);
                                    boolean last = !current && FUSED_LAST_TASK_TRACKER
                                            .isTracked(param.thisObject);
                                    if (!current && !last) return;
                                    String surface = current
                                            ? "CURRENT_LOCATION_TASK" : "LAST_LOCATION_TASK";
                                    DeliveryEvidencePolicy gate = current
                                            ? CURRENT_TASK_DELIVERY_EVIDENCE
                                            : LAST_TASK_DELIVERY_EVIDENCE;
                                    for (int i = 0; i < param.args.length; i++) {
                                        Object arg = param.args[i];
                                        if (arg != null && delivery.listenerType.isInstance(arg)) {
                                            param.args[i] = wrapSuccessListener(
                                                    delivery, arg, cl, surface, gate);
                                            if (FUSED_WRAP_EVIDENCED.add(taskClass)) {
                                                XposedBridge.log(
                                                        RuntimeEvidence.fusedListenerWrapped(
                                                                taskClass.getName()));
                                            }
                                        }
                                    }
                                }
                            }));
            switch (result) {
                case INSTALLED:
                    installed++;
                    break;
                case ALREADY_INSTALLED:
                    already++;
                    break;
                default:
                    failed++;
                    reason = FUSED_HOOK_REGISTRY.lastFailure(delivery.registrationMethod);
            }
        }
        XposedBridge.log(RuntimeEvidence.fusedDeliverySummary(
                taskClass.getName(), plan.size(), installed, already, failed, reason));
        if (failed == 0) {
            FUSED_DELIVERY_EVIDENCED.add(taskClass);
        }
    }

    /**
     * Proxy a success listener: the real Task's delivery is swallowed and the fake location
     * is delivered through the listener's own (name-free) delivery method instead. When the
     * snapshot carries no location, the real value passes through (passthrough semantics).
     */
    private static Object wrapSuccessListener(
            FusedDeliveryPlan.TaskDelivery delivery, Object listener, ClassLoader cl,
            String surface, DeliveryEvidencePolicy gate) {
        return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{delivery.listenerType},
                (proxy, method, args) -> {
                    if (method.equals(delivery.listenerMethod)) {
                        Snapshot s = currentSnapshot();
                        Object incoming = (args != null && args.length > 0) ? args[0] : null;
                        Object value = s.hasLocation()
                                ? deliverWithEvidence(gate, surface, s, incoming)
                                : incoming;
                        try {
                            delivery.listenerMethod.invoke(listener, value);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": fused listener delivery failed: " + t);
                        }
                        return null;
                    }
                    try {
                        return method.invoke(listener, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }

    /** Hook a concrete LocationCallback subclass's delivery methods (shape-resolved). */
    private static void hookFusedLocationCallback(Object callback, ClassLoader cl) {
        Class<?> cbClass = callback.getClass();
        FusedDeliveryPlan.CallbackDelivery delivery =
                FusedDeliveryPlan.planCallbackDelivery(cbClass);
        if (delivery == null) {
            XposedBridge.log(RuntimeEvidence.fusedSurfaceMissing(
                    "LocationCallback#" + cbClass.getName()));
            return;
        }

        // Result delivery: replace the argument wholesale via the public construction
        // seam (static (List)->self factory, or the public (List) constructor current
        // Maps actually ships — Sol R6 #2). Private field layouts are prohibited.
        final Method resultMethod = delivery.resultMethod;
        final Class<?> lrClass = resultMethod.getParameterTypes()[0];
        installObserved("LocationCallback#resultDelivery", resultMethod,
                method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        try {
                            ArrayList<Location> fakeList = new ArrayList<>();
                            fakeList.add(createFakeLocation(s));
                            param.args[0] = FusedDeliveryPlan.buildResult(lrClass, fakeList);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": fused callback result delivery: " + t);
                        }
                    }
                }));

        // Availability delivery: force the availability object's boolean accessor true.
        Class<?> availabilityClass = delivery.availabilityMethod.getParameterTypes()[0];
        try {
            final Method booleanAccessor = FusedDeliveryPlan.resolveBooleanAccessor(availabilityClass);
            installObserved("LocationCallback#availabilityDelivery", booleanAccessor,
                    method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = currentSnapshot();
                            if (!s.hasLocation()) return;
                            param.setResult(true);
                        }
                    }));
        } catch (NoSuchMethodException ignored) {}
    }

    /**
     * Hook a SYSTEM {@link LocationListener}'s onLocationChanged so EVERY delivered update is
     * replaced, not just the one we inject at registration time.
     *
     * Without this, requestLocationUpdates only pushed a single fake fix and then let the real
     * request proceed, so genuine GPS/network updates kept flowing to the same listener and
     * overwrote the spoof (the blue dot snapped back to the real position). Dedup via HOOKED.
     */
    private static void hookSystemLocationListener(Object listener) {
        if (listener == null) return;
        Class<?> listenerClass = listener.getClass();
        String key = "sll#" + listenerClass.getName() + "#onLocationChanged";
        if (!HOOKED.add(key)) return;

        tryHook(() -> XposedHelpers.findAndHookMethod(listenerClass,
                "onLocationChanged", Location.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        param.args[0] = deliverWithEvidence(
                                SYSTEM_LISTENER_EVIDENCE, "SYSTEM_LISTENER", s, param.args[0]);
                    }
                }));

        // API 31+ also delivers batched updates via onLocationChanged(List<Location>)
        tryHook(() -> XposedHelpers.findAndHookMethod(listenerClass,
                "onLocationChanged", java.util.List.class, new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        Object firstSeen = null;
                        if (param.args[0] instanceof java.util.List) {
                            java.util.List<?> batch = (java.util.List<?>) param.args[0];
                            if (!batch.isEmpty()) firstSeen = batch.get(0);
                        }
                        ArrayList<Location> fake = new ArrayList<>();
                        fake.add(deliverWithEvidence(
                                SYSTEM_BATCH_EVIDENCE, "SYSTEM_LISTENER_BATCH", s, firstSeen));
                        param.args[0] = fake;
                    }
                }));
    }

    /** Hook a concrete GMS LocationListener impl's delivery method (shape-resolved). */
    private static void hookFusedLocationListener(Object listener, ClassLoader cl) {
        final Method delivery = FusedDeliveryPlan.planListenerDelivery(listener.getClass());
        if (delivery == null) return;

        installObserved("GmsLocationListener#delivery", delivery,
                method -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = currentSnapshot();
                        if (!s.hasLocation()) return;
                        param.args[0] = deliverWithEvidence(
                                GMS_LISTENER_EVIDENCE, "GMS_LISTENER", s, param.args[0]);
                    }
                }));
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    /** Creates a fully populated fake Location from Snapshot. */
    private static Location createFakeLocation(Snapshot s) {
        // Debug builds only: every hook that applies a spoofed location funnels through here, so
        // the caller trace shows WHICH location API the target app actually uses. In a release
        // build this would announce each spoof in logcat, so it is compiled behind the flag.
        if (name.caiyao.fakegps.BuildConfig.DEBUG) {
            try {
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                StringBuilder sb = new StringBuilder();
                for (int i = 3; i < Math.min(st.length, 9); i++) {
                    sb.append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(" <- ");
                }
                debug("[HIT] createFakeLocation via " + sb);
            } catch (Throwable ignored) {}
        }

        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(s.latitude);
        l.setLongitude(s.longitude);
        l.setAccuracy(s.accuracy != null ? s.accuracy : 10.0f);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        if (s.altitude != null) l.setAltitude(s.altitude);
        if (s.speed != null) l.setSpeed(s.speed);
        if (s.bearing != null) l.setBearing(s.bearing);

        return l;
    }

    // ---- Per-delivery evidence (release-safe) ----
    //
    // The DEBUG trace above cannot ship: it fires on every spoof and prints a stack. This
    // does ship, because DeliveryEvidencePolicy bounds it (edge-triggered + heartbeat) and
    // the line carries a classification token instead of the coordinate.
    //
    // One gate per surface. LAST and CURRENT location tasks are separate surfaces even
    // though one API family hands out both: a recenter is a getCurrentLocation delivery, so
    // sharing a heartbeat window with the steady last-location stream would let a healthy
    // LAST window swallow the CURRENT evidence and leave recenter unattributable.
    private static final DeliveryEvidencePolicy LAST_TASK_DELIVERY_EVIDENCE = new DeliveryEvidencePolicy();
    private static final DeliveryEvidencePolicy CURRENT_TASK_DELIVERY_EVIDENCE = new DeliveryEvidencePolicy();
    private static final DeliveryEvidencePolicy GMS_LISTENER_EVIDENCE = new DeliveryEvidencePolicy();
    private static final DeliveryEvidencePolicy SYSTEM_LISTENER_EVIDENCE = new DeliveryEvidencePolicy();
    private static final DeliveryEvidencePolicy SYSTEM_BATCH_EVIDENCE = new DeliveryEvidencePolicy();

    /**
     * Build the value this delivery will carry, describe it, and hand back the SAME object.
     *
     * <p>Construction, classification and return are deliberately fused into one function.
     * The first cut classified the pre-replacement input and then built the outgoing value
     * separately; every field was individually correct but the emitted meaning was
     * inverted, because a healthy interception reported "not the profile" while handing the
     * profile to the app. Keeping one construction point makes "the classified object is
     * the delivered object" a property of the shape rather than of reviewer attention.
     */
    private static Location deliverWithEvidence(
            DeliveryEvidencePolicy gate, String surface, Snapshot s, Object incoming) {
        Location outgoing = createFakeLocation(s);
        recordDelivery(gate, surface, s, outgoing, incoming);
        return outgoing;
    }

    /**
     * Emit bounded evidence on two disjoint axes: {@code class=} describes what the target
     * app received, {@code input=} describes what it would have received without us.
     */
    private static void recordDelivery(DeliveryEvidencePolicy gate, String surface,
                                       Snapshot s, Object outgoing, Object incoming) {
        try {
            String delivered = DeliveryEvidencePolicy.classify(
                    s.latitude, s.longitude, latOf(outgoing), lonOf(outgoing));
            String intercepted = DeliveryEvidencePolicy.classifyInput(
                    s.latitude, s.longitude, latOf(incoming), lonOf(incoming));
            // Both axes enter the gate. Keying on `delivered` alone made the edge trigger
            // dead where it mattered: the outgoing value is built from the same snapshot it
            // is compared against, so that axis is near-constant, while `input` -- the one
            // that actually varies -- never fired an edge.
            //
            // elapsedRealtime, not wall clock: a time sync must not be able to move the
            // heartbeat window backwards and silence healthy deliveries.
            // Every line in the chain, not just the head: a state change owes both the
            // closing run and the newly opened edge, and dropping the tail defers the edge
            // to a delivery that may never come.
            for (DeliveryEvidencePolicy.Emission e =
                            gate.record(delivered, intercepted, SystemClock.elapsedRealtime());
                    e != null; e = e.next) {
                // The emission's own tokens, never this delivery's: a line must name the
                // run it describes, or its count silently spans states it did not cover.
                XposedBridge.log(RuntimeEvidence.fusedDelivered(
                        surface, e.delivered, e.input, e.deliveries));
            }
        } catch (Throwable ignored) {
            // Evidence must never break delivery.
        }
    }

    private static Double latOf(Object o) {
        return (o instanceof Location) ? ((Location) o).getLatitude() : null;
    }

    private static Double lonOf(Object o) {
        return (o instanceof Location) ? ((Location) o).getLongitude() : null;
    }

    /** Scan method args for a LocationListener instance (any position). */
    private static LocationListener findLocationListener(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof LocationListener) {
                return (LocationListener) arg;
            }
        }
        return null;
    }

    /**
     * Build CellInfo list from Snapshot cell identity fields.
     * Each CellInfo has BOTH CellIdentity AND CellSignalStrength set,
     * so apps traversing the list won't hit null on getCellSignalStrength().
     * Individual field values are further refined by getter hooks at read time.
     */
    @SuppressWarnings("unchecked")
    /**
     * The device's REAL cellular values, used as the per-field passthrough baseline.
     *
     * Without this, unconfigured fields fell back to hardcoded constants (mcc=460 — China Mobile —
     * mnc=0, rsrp=-100…). A profile that set only `tac` therefore produced a cell claiming a
     * Chinese operator while the SIM, operator name and neighbours all said otherwise: an
     * internally inconsistent environment that is trivially detectable — worse than not spoofing.
     * Now every field the user did not configure keeps the device's actual value.
     */
    private static final class CellBaseline {
        Integer mcc, mnc, lac, cid, tac, ci, pci, earfcn;
        String mccString, mncString;
        Integer gsmArfcn, gsmBsic, wcdmaPsc, wcdmaUarfcn;
        Integer lteRssi, lteRsrp, lteRsrq, lteSinr, lteCqi, lteBandwidth;
        Integer gsmRssi;
        CellIdentityMetadata lteMetadata = CellIdentityMetadata.EMPTY;
        CellIdentityMetadata gsmMetadata = CellIdentityMetadata.EMPTY;
        CellIdentityMetadata wcdmaMetadata = CellIdentityMetadata.EMPTY;
        boolean present;
        final java.util.List<RealCell> realCells = new java.util.ArrayList<>();

        private static final class RealCell {
            final Object value;
            final boolean registered;

            RealCell(Object value, boolean registered) {
                this.value = value;
                this.registered = registered;
            }
        }

        /**
         * Most recent NON-EMPTY real baseline.
         *
         * getAllCellInfo() legitimately returns an empty list at times — the platform serves a
         * cached snapshot that is empty right after process start, during a handover, or while the
         * radio is settling. Without this we would see "no baseline" on exactly those calls and
         * fall back to passthrough, so spoofing would flicker on and off. Keeping the last real
         * reading gives a stable, genuine basis to overlay the user's edits onto.
         */
        static volatile CellBaseline lastReal;

        /**
         * Extract from the live getAllCellInfo() result. Absent/unsupported fields stay null.
         *
         * Picks the SERVING cell, not merely the last one in the list: getAllCellInfo() also
         * reports neighbours, and a neighbour's identity/signal describes a different tower, so
         * overlaying edits onto it would emit a self-contradictory environment. Registration state
         * (CellInfo.isRegistered) identifies the serving cell; an unregistered-only list is still
         * usable as a weaker baseline, but only if it actually yielded identity fields.
         */
        static CellBaseline from(Object realResult) {
            return BaselineExtractionGuard.call(() -> extract(realResult));
        }

        private static CellBaseline extract(Object realResult) {
            CellBaseline b = new CellBaseline();
            if (!(realResult instanceof java.util.List)) return withFallback(b);

            Object servingLte = null, anyLte = null, servingGsm = null, anyGsm = null;
            Object servingWcdma = null, anyWcdma = null;
            for (Object info : (java.util.List<?>) realResult) {
                boolean registered = false;
                try { registered = (Boolean) XposedHelpers.callMethod(info, "isRegistered"); }
                catch (Throwable ignored) { /* pre-API-17 or unsupported: treat as neighbour */ }
                b.realCells.add(new RealCell(info, registered));
                if (info instanceof CellInfoLte) {
                    if (anyLte == null) anyLte = info;
                    if (registered && servingLte == null) servingLte = info;
                } else if (info instanceof CellInfoGsm) {
                    if (anyGsm == null) anyGsm = info;
                    if (registered && servingGsm == null) servingGsm = info;
                } else if (info instanceof CellInfoWcdma) {
                    if (anyWcdma == null) anyWcdma = info;
                    if (registered && servingWcdma == null) servingWcdma = info;
                }
            }

            Object lteCell = servingLte != null ? servingLte : anyLte;
            if (lteCell != null) {
                try {
                    Object id = XposedHelpers.callMethod(lteCell, "getCellIdentity");
                    Object sig = XposedHelpers.callMethod(lteCell, "getCellSignalStrength");
                    b.lteMetadata = CellIdentityMetadata.from(id);
                    b.ci = valid((Integer) XposedHelpers.callMethod(id, "getCi"));
                    b.pci = valid((Integer) XposedHelpers.callMethod(id, "getPci"));
                    b.tac = valid((Integer) XposedHelpers.callMethod(id, "getTac"));
                    b.earfcn = valid((Integer) XposedHelpers.callMethod(id, "getEarfcn"));
                    b.lteBandwidth = valid(CellIdentityMetadata.readInteger(id, "getBandwidth"));
                    b.mccString = CellIdentityMetadata.readPlmn(
                            id, "getMccString", "getMcc");
                    b.mncString = CellIdentityMetadata.readPlmn(
                            id, "getMncString", "getMnc");
                    b.mcc = parseOrNull(b.mccString);
                    b.mnc = parseOrNull(b.mncString);
                    b.lteRsrp = valid((Integer) XposedHelpers.callMethod(sig, "getRsrp"));
                    b.lteRsrq = valid((Integer) XposedHelpers.callMethod(sig, "getRsrq"));
                    b.lteSinr = valid((Integer) XposedHelpers.callMethod(sig, "getRssnr"));
                    b.lteCqi = valid((Integer) XposedHelpers.callMethod(sig, "getCqi"));
                    b.lteRssi = valid(CellIdentityMetadata.readInteger(sig, "getRssi"));
                } catch (Throwable t) {
                    debug("baseline LTE extract failed: " + t);
                }
            }

            Object gsmCell = servingGsm != null ? servingGsm : anyGsm;
            if (gsmCell != null) {
                try {
                    Object id = XposedHelpers.callMethod(gsmCell, "getCellIdentity");
                    Object sig = XposedHelpers.callMethod(gsmCell, "getCellSignalStrength");
                    b.gsmMetadata = CellIdentityMetadata.from(id);
                    b.lac = valid((Integer) XposedHelpers.callMethod(id, "getLac"));
                    b.cid = valid((Integer) XposedHelpers.callMethod(id, "getCid"));
                    b.gsmArfcn = valid((Integer) XposedHelpers.callMethod(id, "getArfcn"));
                    b.gsmBsic = valid((Integer) XposedHelpers.callMethod(id, "getBsic"));
                    if (b.mcc == null) {
                        b.mccString = CellIdentityMetadata.readPlmn(
                                id, "getMccString", "getMcc");
                        b.mcc = parseOrNull(b.mccString);
                    }
                    if (b.mnc == null) {
                        b.mncString = CellIdentityMetadata.readPlmn(
                                id, "getMncString", "getMnc");
                        b.mnc = parseOrNull(b.mncString);
                    }
                    b.gsmRssi = valid((Integer) XposedHelpers.callMethod(sig, "getDbm"));
                } catch (Throwable t) {
                    debug("baseline GSM extract failed: " + t);
                }
            }

            Object wcdmaCell = servingWcdma != null ? servingWcdma : anyWcdma;
            if (wcdmaCell != null) {
                try {
                    Object id = XposedHelpers.callMethod(wcdmaCell, "getCellIdentity");
                    b.wcdmaMetadata = CellIdentityMetadata.from(id);
                    if (b.lac == null) {
                        b.lac = valid((Integer) XposedHelpers.callMethod(id, "getLac"));
                    }
                    if (b.cid == null) {
                        b.cid = valid((Integer) XposedHelpers.callMethod(id, "getCid"));
                    }
                    b.wcdmaPsc = valid((Integer) XposedHelpers.callMethod(id, "getPsc"));
                    b.wcdmaUarfcn = valid((Integer) XposedHelpers.callMethod(id, "getUarfcn"));
                    if (b.mcc == null) {
                        b.mccString = CellIdentityMetadata.readPlmn(
                                id, "getMccString", "getMcc");
                        b.mcc = parseOrNull(b.mccString);
                    }
                    if (b.mnc == null) {
                        b.mncString = CellIdentityMetadata.readPlmn(
                                id, "getMncString", "getMnc");
                        b.mnc = parseOrNull(b.mncString);
                    }
                } catch (Throwable t) {
                    debug("baseline WCDMA extract failed: " + t);
                }
            }

            // "present" must mean we actually have real identity to build on. Setting it merely
            // because a CellInfo object existed let an all-null baseline pass the fail-safe check
            // and fall straight back into the constant defaults it was meant to prevent.
            b.present = Snapshot.isUsableCellBaseline(b.ci, b.tac, b.pci, b.earfcn,
                    b.lac, b.cid, b.mcc);
            return withFallback(b);
        }

        /**
         * Remember a genuine reading; reuse the last one when this call yielded nothing.
         *
         * getAllCellInfo() legitimately returns an empty/identity-less list right after process
         * start, during handover, or while the radio settles. Without the carry-over the fail-safe
         * would trip on exactly those calls and spoofing would flicker on and off — itself a signal.
         */
        private static CellBaseline withFallback(CellBaseline b) {
            if (b.present) {
                lastReal = b;
            } else if (lastReal != null) {
                b = lastReal;
            }
            debug("baseline present=" + b.present + " ci=" + b.ci + " mcc=" + b.mcc
                    + " tac=" + b.tac + " lac=" + b.lac + " rsrp=" + b.lteRsrp);
            return b;
        }

        /** Android reports "unknown" as Integer.MAX_VALUE — treat that as absent. */
        static Integer valid(Integer v) {
            return (v == null || v == Integer.MAX_VALUE) ? null : v;
        }
        private static Integer parseOrNull(Object v) {
            try { return v == null ? null : Integer.valueOf(String.valueOf(v)); }
            catch (Exception e) { return null; }
        }
    }

    /** Configured value wins; otherwise the device's real value; otherwise the last-resort default. */
    private static int pick(Integer configured, Integer real, int fallback) {
        if (configured != null) return configured;
        if (real != null) return real;
        return fallback;
    }

    /**
     * Spoofed cell list, or {@code null} when there is no genuine baseline to overlay onto.
     *
     * EVERY call site must funnel through here. Previously only the synchronous getAllCellInfo
     * getter checked {@code base.present}; the three callback paths (PhoneStateListener,
     * TelephonyCallback, CellInfoCallback) built a list regardless, so with no baseline they
     * emitted the constant fallbacks (mcc=460 / mnc=0) — a cell contradicting the SIM and operator,
     * i.e. exactly the fabricated-inconsistency this design is meant to avoid.
     *
     * @return null => caller must leave the real value untouched (fail-safe passthrough)
     */
    private static ArrayList spoofedCellsOrPassthrough(Snapshot s, Object realResult) {
        CellBaseline base = CellBaseline.from(realResult);
        if (!base.present) {
            debug("no cell baseline -> passthrough (not fabricating)");
            return null;
        }
        ArrayList built = buildCellInfoList(s, base);
        preserveUnconfiguredRealCells(s, base, built);
        if (built.isEmpty()) {
            debug("cell construction produced no entries -> passthrough");
        }
        return Snapshot.acceptBuiltCellListOrPassthrough(built);
    }

    /** NULL neighbor JSON means passthrough, so retain real neighbours and untouched RATs. */
    private static void preserveUnconfiguredRealCells(
            Snapshot s, CellBaseline base, ArrayList built) {
        boolean configuredNeighbors = s.neighborCellsJson != null;
        for (CellBaseline.RealCell real : base.realCells) {
            boolean replacingRat =
                    (real.value instanceof CellInfoLte && s.hasLteRatConstruction())
                    || (real.value instanceof CellInfoGsm && s.hasGsmRatConstruction())
                    || (real.value instanceof CellInfoWcdma
                        && s.hasWcdmaRatConstruction())
                    || (Build.VERSION.SDK_INT >= 29
                        && real.value instanceof android.telephony.CellInfoNr
                        && s.hasNrRatConstruction());
            if (!Snapshot.shouldPreserveRealCell(
                    real.registered, replacingRat, configuredNeighbors)) {
                continue;
            }
            if (Snapshot.shouldBypassPreservedRealCell(
                    real.registered, s.hasCellReconstructionDecision())) {
                registerNeighborBypassTree(real.value);
            }
            built.add(real.value);
        }
    }

    private static void registerNeighborBypassTree(Object info) {
        NEIGHBOR_BYPASS.add(info);
        try { NEIGHBOR_BYPASS.add(XposedHelpers.callMethod(info, "getCellIdentity")); }
        catch (Throwable ignored) {}
        try { NEIGHBOR_BYPASS.add(XposedHelpers.callMethod(info, "getCellSignalStrength")); }
        catch (Throwable ignored) {}
    }

    /** A passthrough list still needs a neighbor census before global serving getter hooks run. */
    private static void registerRealNeighborBypasses(Object cellInfos) {
        if (!(cellInfos instanceof List)) return;
        for (Object info : (List<?>) cellInfos) {
            boolean registered = false;
            try { registered = (Boolean) XposedHelpers.callMethod(info, "isRegistered"); }
            catch (Throwable ignored) {}
            if (!registered) registerNeighborBypassTree(info);
        }
    }

    /**
     * Spoofed {@link GsmCellLocation}, or {@code null} to pass the real one through.
     *
     * Fixes an unboxing NPE: {@code setLacAndCid(s.lac, s.cid)} dereferenced null whenever the
     * profile configured a CellLocation field without both lac/cid values. Because
     * {@code tryHook} only guards hook REGISTRATION, that NPE surfaced inside the target app's
     * own listener callback.
     * Unset fields now fall back to the device's real values, and if neither side supplies lac/cid
     * we pass through rather than inventing a cell. Activation is governed separately by
     * {@link Snapshot#hasGsmCellLocationDecision()} so unavailable-only decisions reach this
     * surface without activating CellInfo reconstruction.
     */
    private static GsmCellLocation spoofedGsmLocationOrPassthrough(Snapshot s, Object realLoc) {
        Integer realLac = null, realCid = null;
        if (realLoc instanceof GsmCellLocation) {
            GsmCellLocation real = (GsmCellLocation) realLoc;
            realLac = CellBaseline.valid(real.getLac());
            realCid = CellBaseline.valid(real.getCid());
        }
        CellBaseline last = CellBaseline.lastReal;
        if (realLac == null && last != null) realLac = last.lac;
        if (realCid == null && last != null) realCid = last.cid;

        Integer lac = Snapshot.resolveGsmCellLocationField(
                "lac", s.lac, realLac, s.isUnavailable("lac"));
        Integer cid = Snapshot.resolveGsmCellLocationField(
                "cid", s.cid, realCid, s.isUnavailable("cid"));
        if (lac == null || cid == null) {
            debug("no GSM cell-location baseline -> passthrough");
            return null;
        }
        GsmCellLocation loc = new GsmCellLocation();
        loc.setLacAndCid(lac, cid);
        // setPsc is @hide on some API levels — apply reflectively and skip if unavailable rather
        // than failing the whole spoof for one optional UMTS field.
        if (s.psc != null) {
            Integer psc = Snapshot.resolveGsmCellLocationField(
                    "psc", s.psc, null, s.isUnavailable("psc"));
            try { XposedHelpers.callMethod(loc, "setPsc", psc); }
            catch (Throwable t) { debug("setPsc unavailable: " + t); }
        }
        return loc;
    }

    /** Shared guard for all three existing-object GsmCellLocation surfaces. */
    private static boolean shouldTransformGsmCellLocation(Snapshot s) {
        return s.hasGsmCellLocationDecision();
    }

    /** One topology guard shared by getters, callbacks and subscription surfaces. */
    private static boolean shouldRebuildServingCells(Snapshot s) {
        return s.hasCellReconstructionDecision();
    }

    /** Neighbor replacement mutates the list without selecting or rebuilding a serving RAT. */
    private static boolean shouldMutateCellList(Snapshot s) {
        return s.hasCellListMutationDecision();
    }

    private static ArrayList buildCellInfoList(Snapshot s, CellBaseline base) {
        // Weak identity registry: old neighbor objects are GC'd naturally
        // when the app drops references. No manual clear() needed.
        ArrayList list = new ArrayList();
        // Per-field passthrough: configured value > device's real value > last-resort default.
        String mccString = Snapshot.resolvePlmnString(
                "mcc", s.mcc, base.mccString, s.isUnavailable("mcc"));
        String mncString = Snapshot.resolvePlmnString(
                "mnc", s.mnc, base.mncString, s.isUnavailable("mnc"));

        // GSM cell
        if (s.hasGsmRatConstruction()) {
            try {
                CellInfoGsm gsm = (CellInfoGsm) XposedHelpers.newInstance(CellInfoGsm.class);
                int lac = pick(s.lac, base.lac, 0);
                int cid = pick(s.cid, base.cid, 0);
                Integer arfcn = s.arfcn != null ? s.arfcn : base.gsmArfcn;
                Integer bsic = s.bsic != null ? s.bsic : base.gsmBsic;
                Object identity = CellConstructorCompat.newGsmIdentity(
                        CellIdentityGsm.class, mccString, mncString, lac, cid, arfcn, bsic,
                        base.gsmMetadata.withOperatorName(s.operatorName));
                XposedHelpers.callMethod(gsm, "setCellIdentity", identity);
                // Set signal strength — getter hooks will override individual fields
                try {
                    int rssi = s.gsmRssi != null ? s.gsmRssi : -85;
                    int ber = s.gsmBer != null ? s.gsmBer : 0;
                    int ta = s.gsmTa != null ? s.gsmTa : Integer.MAX_VALUE;
                    Object gsmSig = XposedHelpers.newInstance(
                            XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", null),
                            rssi, ber, ta);
                    XposedHelpers.callMethod(gsm, "setCellSignalStrength", gsmSig);
                } catch (Throwable sigErr) {
                    // Fallback: default-constructed signal (getter hooks still apply)
                    try {
                        Object gsmSig = XposedHelpers.newInstance(
                                XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", null));
                        XposedHelpers.callMethod(gsm, "setCellSignalStrength", gsmSig);
                    } catch (Throwable ignored) {}
                }
                list.add(gsm);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": GSM CellInfo creation failed: " + t);
            }
        }

        // LTE cell
        if (s.hasLteRatConstruction()) {
            try {
                CellInfoLte lte = (CellInfoLte) XposedHelpers.newInstance(CellInfoLte.class);
                // NB: `int ci = s.ci` used to unbox a null whenever the profile configured any LTE
                // field other than ci — NPE, swallowed by the catch below, so NO LTE cell was
                // reported at all (getAllCellInfo returned an empty list, itself a red flag).
                int ci = pick(s.ci, base.ci, 0);
                int pci = pick(s.pci, base.pci, 0);
                int tac = pick(s.tac, base.tac != null ? base.tac : s.lac, 0);
                Integer earfcn = s.earfcn != null ? s.earfcn : base.earfcn;
                Integer bandwidth = s.lteBandwidth != null ? s.lteBandwidth : base.lteBandwidth;
                Object identity = CellConstructorCompat.newLteIdentity(
                        CellIdentityLte.class, mccString, mncString,
                        ci, pci, tac, earfcn, bandwidth,
                        base.lteMetadata.withOperatorName(s.operatorName));
                XposedHelpers.callMethod(lte, "setCellIdentity", identity);
                // Set signal strength
                try {
                    int rssi = pick(s.lteRssi, base.lteRssi, -90);
                    int rsrp = pick(s.lteRsrp, base.lteRsrp, -100);
                    int rsrq = pick(s.lteRsrq, base.lteRsrq, -10);
                    int sinr = pick(s.lteSinr, base.lteSinr, 15);
                    int cqi = pick(s.lteCqi, base.lteCqi, 10);
                    int lteTa = s.lteTa != null ? s.lteTa : Integer.MAX_VALUE;
                    Object lteSig = CellConstructorCompat.newLteSignal(
                            XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", null),
                            rssi, rsrp, rsrq, sinr, cqi, lteTa);
                    XposedHelpers.callMethod(lte, "setCellSignalStrength", lteSig);
                } catch (Throwable sigErr) {
                    try {
                        Object lteSig = XposedHelpers.newInstance(
                                XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", null));
                        XposedHelpers.callMethod(lte, "setCellSignalStrength", lteSig);
                    } catch (Throwable ignored) {}
                }
                list.add(lte);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": LTE CellInfo creation failed: " + t);
            }
        }

        // WCDMA is selected only by WCDMA-specific identity, never shared LAC/CID/PLMN.
        if (s.hasWcdmaRatConstruction()) {
            try {
                CellInfoWcdma wcdma = (CellInfoWcdma) XposedHelpers.newInstance(CellInfoWcdma.class);
                int lac = pick(s.lac, base.lac, 0);
                int cid = pick(s.cid, base.cid, 0);
                Integer psc = s.psc != null ? s.psc : base.wcdmaPsc;
                Integer uarfcn = s.uarfcn != null ? s.uarfcn : base.wcdmaUarfcn;
                Object identity = CellConstructorCompat.newWcdmaIdentity(
                        CellIdentityWcdma.class, mccString, mncString,
                        lac, cid, psc, uarfcn,
                        base.wcdmaMetadata.withOperatorName(s.operatorName));
                XposedHelpers.callMethod(wcdma, "setCellIdentity", identity);
                // Set signal strength
                try {
                    Object wcdmaSig = XposedHelpers.newInstance(
                            XposedHelpers.findClass("android.telephony.CellSignalStrengthWcdma", null));
                    XposedHelpers.callMethod(wcdma, "setCellSignalStrength", wcdmaSig);
                } catch (Throwable ignored) {}
                list.add(wcdma);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": WCDMA CellInfo creation failed: " + t);
            }
        }

        // NR/5G cell (API 29+)
        if (s.hasNrRatConstruction() && Build.VERSION.SDK_INT >= 29) {
            try {
                Class<?> cellInfoNrClass = XposedHelpers.findClass("android.telephony.CellInfoNr", null);
                Class<?> cellIdNrClass = XposedHelpers.findClass("android.telephony.CellIdentityNr", null);
                Class<?> cellSigNrClass = XposedHelpers.findClass("android.telephony.CellSignalStrengthNr", null);

                Object nr = XposedHelpers.newInstance(cellInfoNrClass);

                // Set CellIdentityNr via internal field (no public setter on CellInfoNr)
                Object nrIdentity = XposedHelpers.newInstance(cellIdNrClass);
                XposedHelpers.setObjectField(nr, "mCellIdentity", nrIdentity);

                // Set CellSignalStrengthNr
                Object nrSignal = XposedHelpers.newInstance(cellSigNrClass);
                XposedHelpers.setObjectField(nr, "mCellSignalStrength", nrSignal);

                // Individual fields overridden by CellIdentityNr/CellSignalStrengthNr getter hooks
                list.add(nr);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": NR CellInfo creation failed: " + t);
            }
        }

        // Neighbor cells from JSON
        // Format: [{"type":"gsm","mcc":460,"mnc":0,"lac":1234,"cid":5678,"rssi":-85}, ...]
        // Supported types: gsm (rssi,ber,ta), lte (rssi,rsrp,rsrq,sinr,cqi,ta), wcdma (rssi,rscp,ecno)
        if (s.neighborCellsJson != null && !s.neighborCellsJson.isEmpty()) {
            debug("building configured neighbor cells bytes=" + s.neighborCellsJson.length());
            try {
                org.json.JSONArray arr = new org.json.JSONArray(s.neighborCellsJson);
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        org.json.JSONObject obj = arr.getJSONObject(i);
                        String type = obj.optString("type", "gsm");
                        Object neighborMcc = obj.has("mcc") && !obj.isNull("mcc")
                                ? obj.opt("mcc") : null;
                        Object neighborMnc = obj.has("mnc") && !obj.isNull("mnc")
                                ? obj.opt("mnc") : null;
                        String nMccString = Snapshot.resolvePlmnStringValue(
                                "mcc", neighborMcc, mccString);
                        String nMncString = Snapshot.resolvePlmnStringValue(
                                "mnc", neighborMnc, mncString);
                        if ("lte".equals(type)) {
                            CellInfoLte lte = (CellInfoLte) XposedHelpers.newInstance(CellInfoLte.class);
                            NEIGHBOR_BYPASS.add(lte);
                            Object id = CellConstructorCompat.newLteIdentity(
                                    CellIdentityLte.class, nMccString, nMncString,
                                    obj.optInt("ci", 0),
                                    obj.optInt("pci", 0),
                                    obj.optInt("tac", 0),
                                    obj.has("earfcn") ? obj.optInt("earfcn") : null,
                                    obj.has("bandwidth") ? obj.optInt("bandwidth") : null,
                                    CellIdentityMetadata.EMPTY.withOperatorName(s.operatorName));
                            NEIGHBOR_BYPASS.add(id);
                            XposedHelpers.callMethod(lte, "setCellIdentity", id);
                            try {
                                Object sig = CellConstructorCompat.newLteSignal(
                                        XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", null),
                                        obj.optInt("rssi", -90),
                                        obj.optInt("rsrp", -100),
                                        obj.optInt("rsrq", -10),
                                        obj.optInt("sinr", 15),
                                        obj.optInt("cqi", Integer.MAX_VALUE),
                                        obj.optInt("ta", Integer.MAX_VALUE));
                                NEIGHBOR_BYPASS.add(sig);
                                XposedHelpers.callMethod(lte, "setCellSignalStrength", sig);
                            } catch (Throwable ignored) {}
                            list.add(lte);
                        } else if ("wcdma".equals(type)) {
                            CellInfoWcdma w = (CellInfoWcdma) XposedHelpers.newInstance(CellInfoWcdma.class);
                            NEIGHBOR_BYPASS.add(w);
                            Object id = CellConstructorCompat.newWcdmaIdentity(
                                    CellIdentityWcdma.class, nMccString, nMncString,
                                    obj.optInt("lac", 0),
                                    obj.optInt("cid", 0),
                                    obj.optInt("psc", 0),
                                    obj.has("uarfcn") ? obj.optInt("uarfcn") : null,
                                    CellIdentityMetadata.EMPTY.withOperatorName(s.operatorName));
                            NEIGHBOR_BYPASS.add(id);
                            XposedHelpers.callMethod(w, "setCellIdentity", id);
                            try {
                                Object sig = XposedHelpers.newInstance(
                                        XposedHelpers.findClass("android.telephony.CellSignalStrengthWcdma", null),
                                        obj.optInt("rssi", -85),
                                        Integer.MAX_VALUE, // ber
                                        obj.optInt("rscp", -100),
                                        obj.optInt("ecno", -10));
                                NEIGHBOR_BYPASS.add(sig);
                                XposedHelpers.callMethod(w, "setCellSignalStrength", sig);
                            } catch (Throwable ignored) {}
                            list.add(w);
                        } else {
                            // Default: GSM
                            CellInfoGsm gsm = (CellInfoGsm) XposedHelpers.newInstance(CellInfoGsm.class);
                            NEIGHBOR_BYPASS.add(gsm);
                            Object id = CellConstructorCompat.newGsmIdentity(
                                    CellIdentityGsm.class, nMccString, nMncString,
                                    obj.optInt("lac", 0),
                                    obj.optInt("cid", 0),
                                    obj.has("arfcn") ? obj.optInt("arfcn") : null,
                                    obj.has("bsic") ? obj.optInt("bsic") : null,
                                    CellIdentityMetadata.EMPTY.withOperatorName(s.operatorName));
                            NEIGHBOR_BYPASS.add(id);
                            XposedHelpers.callMethod(gsm, "setCellIdentity", id);
                            try {
                                Object sig = XposedHelpers.newInstance(
                                        XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", null),
                                        obj.optInt("rssi", -85),
                                        obj.optInt("ber", 0),
                                        obj.optInt("ta", Integer.MAX_VALUE));
                                NEIGHBOR_BYPASS.add(sig);
                                XposedHelpers.callMethod(gsm, "setCellSignalStrength", sig);
                            } catch (Throwable ignored) {}
                            list.add(gsm);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Neighbor cell " + i + " creation failed: " + t);
                    }
                }
                debug("configured neighbor cells processed=" + arr.length());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Neighbor cells JSON parse failed: " + t);
            }
        }

        return list;
    }

    /**
     * Hook a no-arg getter method. If the Snapshot field is non-null, override the return value.
     * Silently skips if the method doesn't exist on this Android version.
     */
    private static void hookGetter(ClassLoader cl, String className, String methodName,
                                   FieldGetter getter) {
        tryHook(() -> XposedHelpers.findAndHookMethod(className, cl, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (BaselineExtractionGuard.isActive()) return;
                        // Skip neighbor cell objects — their constructor values are correct
                        if (NEIGHBOR_BYPASS.contains(param.thisObject)) return;
                        Snapshot s = currentSnapshot();
                        Object val = getter.get(s);
                        if (val != null) {
                            param.setResult(val);
                        }
                    }
                }));
    }

    /**
     * PLMN string getters need an explicit-null override. The generic getter helper treats null as
     * passthrough, but null is Android's actual unavailable result for getMccString/getMncString.
     */
    private static void hookPlmnStringGetter(
            ClassLoader cl, String className, String methodName, String field, FieldGetter getter) {
        tryHook(() -> XposedHelpers.findAndHookMethod(className, cl, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (BaselineExtractionGuard.isActive()) return;
                        if (NEIGHBOR_BYPASS.contains(param.thisObject)) return;
                        Snapshot s = currentSnapshot();
                        if (s.isUnavailable(field)) {
                            UnavailableValueResolver.Resolution r =
                                    UnavailableValueResolver.resolve(
                                            field,
                                            UnavailableValueResolver.Surface
                                                    .CELL_IDENTITY_PLMN_STRING);
                            if (r.handled()) param.setResult(r.value());
                            return;
                        }
                        Object val = getter.get(s);
                        if (val != null) param.setResult(val);
                    }
                }));
    }

    /**
     * Object carrier surfaces use null for unknown, unlike TelephonyManager's empty string.
     * The unavailable decision must therefore override even though its resolved value is null.
     */
    private static void hookNullableCarrierGetter(
            ClassLoader cl, String className, String methodName, String field,
            FieldGetter getter) {
        tryHook(() -> XposedHelpers.findAndHookMethod(className, cl, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (BaselineExtractionGuard.isActive()) return;
                        if (NEIGHBOR_BYPASS.contains(param.thisObject)) return;
                        Snapshot s = currentSnapshot();
                        if (s.isUnavailable(field)) {
                            UnavailableValueResolver.Resolution resolution =
                                    UnavailableValueResolver.resolve(
                                            field,
                                            UnavailableValueResolver.Surface.CARRIER_OBJECT_TEXT);
                            if (resolution.handled()) param.setResult(resolution.value());
                            return;
                        }
                        Object value = getter.get(s);
                        if (value != null) param.setResult(value);
                    }
                }));
    }

    /**
     * Hook a signal strength getter. Same as hookGetter but applies fluctuation.
     * Skips neighbor cell objects (NEIGHBOR_BYPASS).
     */
    private static void hookSignal(ClassLoader cl, String className, String methodName,
                                   FieldGetter getter) {
        tryHook(() -> XposedHelpers.findAndHookMethod(className, cl, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (BaselineExtractionGuard.isActive()) return;
                        if (NEIGHBOR_BYPASS.contains(param.thisObject)) return;
                        Snapshot s = currentSnapshot();
                        Object val = getter.get(s);
                        if (val instanceof Integer) {
                            param.setResult(s.fluctuate((Integer) val, RND));
                        }
                    }
                }));
    }

    /** Try to run a hook registration; log and continue on failure. */
    private static void tryHook(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook registration skipped: " + t.getMessage());
        }
    }

    /** Convert dotted IPv4 string to int (little-endian for Android WifiInfo). */
    private static int ipToInt(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return 0;
            return (Integer.parseInt(parts[0]))
                    | (Integer.parseInt(parts[1]) << 8)
                    | (Integer.parseInt(parts[2]) << 16)
                    | (Integer.parseInt(parts[3]) << 24);
        } catch (Exception e) {
            return 0;
        }
    }

    @FunctionalInterface
    interface FieldGetter {
        Object get(Snapshot s);
    }
}
