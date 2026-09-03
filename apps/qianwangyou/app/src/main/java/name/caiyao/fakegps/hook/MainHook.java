package name.caiyao.fakegps.hook;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import name.caiyao.fakegps.config.ConfigCodec;
import name.caiyao.fakegps.config.SpoofConfig;
import name.caiyao.fakegps.config.ConfigPrefsSync;
import name.caiyao.fakegps.config.PublishedConfig;
import name.caiyao.fakegps.config.TransportSchemaContract;
import name.caiyao.fakegps.hook.oracle.SystemServerOracleEntryPolicy;
import name.caiyao.fakegps.hook.oracle.SystemServerOracleInstaller;
import name.caiyao.fakegps.verify.RuntimeSelfHookPolicy;
import name.caiyao.fakegps.verify.RuntimeHookSentinel;
import name.caiyao.fakegps.verify.RuntimeEvidence;

/**
 * Xposed module entry point.
 *
 * Architecture:
 *   1. Register ALL hooks exactly ONCE in handleLoadPackage()
 *   2. Hooks read from a shared AtomicReference<Snapshot> at invocation time
 *   3. A background timer refreshes the Snapshot (coordinates + config)
 *      WITHOUT re-registering hooks
 *
 * Config transport = XSharedPreferences (world-readable prefs file), NOT the exported
 * ContentProvider. WHY: Android 11+ package-visibility filtering means a target app that
 * doesn't declare <queries> for us cannot even resolve our provider ("Failed to find provider
 * info"), so cross-process queries return null. XSharedPreferences reads a file directly
 * (Vector redirects MODE_WORLD_READABLE prefs into a permissive-SELinux safe-zone), bypassing
 * package visibility. WRITE side = {@code name.caiyao.fakegps.config.ConfigPrefsSync}.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FakeGPS";

    private static final String PREFS_NAME = ConfigPrefsSync.PREFS_NAME;
    private static final String PREFS_KEY_JSON = ConfigPrefsSync.KEY_JSON;
    private static final int TRANSPORT_SCHEMA_VERSION = ConfigPrefsSync.SCHEMA_VERSION;
    private static final int LEGACY_TRANSPORT_SCHEMA_VERSION = ConfigPrefsSync.LEGACY_SCHEMA_VERSION;

    /**
     * Verbose diagnostics, debug builds only. These run inside the TARGET app's process, so in a
     * release build they would both add noise and advertise the module's presence in logcat.
     */
    private static void debug(String msg) {
        if (name.caiyao.fakegps.BuildConfig.DEBUG) {
            XposedBridge.log(TAG + ": [DIAG] " + msg);
        }
    }

    /** Current spoofing config. Hooks read this atomically via CURRENT.get(). */
    static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.PASSTHROUGH);
    /** Fingerprint paired with the last structurally accepted Snapshot. */
    private static final AtomicReference<String> CURRENT_FINGERPRINT = new AtomicReference<>();
    /** Hour-of-day when the last fingerprint-accepted Snapshot was evaluated.
     *  Ensures time_based mode re-evaluates on hour boundaries even when fingerprint matches.
     *  (Review finding #1, Sol) */
    private static final AtomicInteger LAST_EVALUATED_HOUR = new AtomicInteger(-1);
    /** Serializes timer and verification-triggered reloads into one coherent publish order. */
    private static final Object SNAPSHOT_LOCK = new Object();

    /** One owner per module classloader/process, even if LSPosed repeats the callback. */
    private static final HookRuntimeOwnership RUNTIME_OWNERSHIP = new HookRuntimeOwnership();
    private static final HookRefreshScheduler REFRESH_SCHEDULER = new HookRefreshScheduler();
    /** Strong reference prevents GC from silently dropping the inotify watch. */
    private static PrefsDirectoryObserver prefsObserver;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // LSPosed's legacy callback names system_server as (android, android), while its scope key
        // is `system`. This exact branch must run before every generic self-hook/config decision:
        // system_server receives only the continuity producer and never spoof/scheduler hooks.
        if (SystemServerOracleEntryPolicy.isSystemServer(lpparam.packageName, lpparam.processName)) {
            SystemServerOracleInstaller.install(lpparam.classLoader);
            return;
        }

        // Release and codexBench do not spoof their configuration/ordinary self processes.
        // Their sole self-hook exception is the exact private one-shot :hook_verify process.
        //
        // Ordinary debug hooks its own process so it can act as a controlled probe: it reads plain
        // LocationManager (MapScreen/VerifyViewModel, no GMS fused path), which is what lets
        // scripts/test-hook.sh tell "the hook machinery is broken" apart from "the target app uses
        // an API we don't cover". A release build must never spoof its own UI — that would make the
        // configuration screen display the fake values back to the user as if they were real. The
        // private probe keeps that isolation while providing a deliberately hook-enabled reader.
        // The generated build policy, not DEBUG/log verbosity, controls eligibility. This early
        // return leaves framework XSharedPreferences support alone: LSPosed installs that before
        // dispatching module callbacks, separately from this module's spoof hooks.
        if (!RuntimeSelfHookPolicy.shouldHook(
                lpparam.packageName,
                lpparam.processName)) {
            return;
        }

        // 1. Load initial config (XSharedPreferences works here — it's a file read, no app context needed)
        Snapshot initial = reloadSnapshot(null);
        XposedBridge.log(TAG + ": Loaded config for " + lpparam.packageName
                + " | location=" + initial.hasLocation()
                + " | cellRebuild=" + initial.hasCellReconstructionDecision());

        // 2. Register once per target classloader. LSPosed may repeat handleLoadPackage in the
        // same process; without this gate every getter receives duplicate hooks.
        ClassLoader targetClassLoader = lpparam.classLoader != null
                ? lpparam.classLoader
                : MainHook.class.getClassLoader();
        if (RUNTIME_OWNERSHIP.claimHooks(targetClassLoader)) {
            installProbeSentinelIfPresent(targetClassLoader, lpparam.processName);
            HookUtils.registerAllHooks(targetClassLoader);
        }

        // 3. One background refresh per process. Duplicate callbacks must not multiply timers.
        if (!RUNTIME_OWNERSHIP.claimScheduler()) {
            debug("duplicate load-package callback; refresh scheduler already owned");
            return;
        }
        XposedBridge.log(RuntimeEvidence.schedulerOwned(
                lpparam.processName, REFRESH_SCHEDULER.currentDelayMs()));

        // Phase B: event-driven observer (primary) + timer heartbeat (safety net).
        // tryArmObserver handles pre-flight checks and evidence logging.
        // If initial arm fails, heartbeat will retry on each tick.
        boolean observerArmed = tryArmObserver(lpparam.processName);
        if (!observerArmed) {
            XposedBridge.log(RuntimeEvidence.observerArmFailed(
                    lpparam.processName, "initial arm failed"));
            XposedBridge.log(RuntimeEvidence.timerFallback(
                    lpparam.processName, REFRESH_SCHEDULER.currentDelayMs()));
        }
        final Handler handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == 1) {
                    // Lazy retry: re-arm observer if initial arm failed or observer died.
                    // Cost: one tryArmObserver per heartbeat tick until success — acceptable
                    // because ticks are ≥5s apart and arm() is a single stat+inotify syscall.
                    if (prefsObserver == null || !prefsObserver.isArmed()) {
                        tryArmObserver(lpparam.processName);
                    }
                    Snapshot refreshed = reloadSnapshot(lpparam.processName);
                    debug("timer refresh -> hasLocation=" + refreshed.hasLocation());
                }
                sendEmptyMessageDelayed(1, REFRESH_SCHEDULER.currentDelayMs());
            }
        };
        handler.sendEmptyMessageDelayed(1, HookRefreshScheduler.INITIAL_DELAY_MS);
    }

    /**
     * Load the effective spoof config from the world-readable prefs snapshot the app publishes
     * (see ConfigPrefsSync). Parses via Fable's canonical {@link ConfigCodec} → {@link SpoofConfig}
     * and maps to a hook-layer {@link Snapshot} via {@link SpoofConfigMapper}.
     *
     * NULL/absent field == passthrough (real device value). On read/parse failure we keep the
     * last-known-good CURRENT rather than reverting to real data mid-test.
     */
    private Snapshot reloadSnapshot(String evidenceProcess) {
        synchronized (SNAPSHOT_LOCK) {
            return reloadSnapshotLocked(evidenceProcess);
        }
    }

    /** Target-classloader bridge used by the private verification process before observation. */
    private String reloadSnapshotForProbe(String expectedFingerprint, String processName) {
        synchronized (SNAPSHOT_LOCK) {
            reloadSnapshotLocked(processName);
            String activeFingerprint = CURRENT_FINGERPRINT.get();
            if (expectedFingerprint != null && !expectedFingerprint.equals(activeFingerprint)) {
                XposedBridge.log(TAG + ": probe snapshot mismatch expected="
                        + expectedFingerprint + " actual=" + activeFingerprint);
            }
            return activeFingerprint;
        }
    }

    /** Commit one reload and its interval evidence under the same process-wide ordering lock. */
    private Snapshot reloadSnapshotLocked(String evidenceProcess) {
        long previousDelayMs = REFRESH_SCHEDULER.currentDelayMs();
        Snapshot refreshed = loadSnapshot();
        CURRENT.set(refreshed);
        long nextDelayMs = REFRESH_SCHEDULER.currentDelayMs();
        if (evidenceProcess != null && previousDelayMs != nextDelayMs) {
            XposedBridge.log(RuntimeEvidence.intervalChanged(
                    evidenceProcess, previousDelayMs, nextDelayMs));
        }
        return refreshed;
    }

    private Snapshot loadSnapshot() {
        try {
            // Single time sample for the entire evaluation. Used by fingerprint skip,
            // active-hours check, and hour recording. Separate reads would race at hour
            // boundaries. (Review finding #2 R2, Sol)
            final int evaluationHour = java.util.Calendar.getInstance()
                    .get(java.util.Calendar.HOUR_OF_DAY);

            XSharedPreferences prefs = new XSharedPreferences(
                    name.caiyao.fakegps.BuildConfig.APPLICATION_ID, PREFS_NAME);
            prefs.makeWorldReadable();
            prefs.reload();

            String jsonStr = prefs.getString(PREFS_KEY_JSON, null);
            if (jsonStr == null) {
                // last-known-good (review FC-2): a MISSING payload on a refresh tick is not the
                // same as "no config". The prefs file can be transiently unreadable (mid-write,
                // permission flap), and reverting an ACTIVE spoof to real device data would leak
                // the true environment to the app under test. Only pass through when nothing has
                // ever been published (first launch), where real values are the safe default.
                Snapshot resolved = Snapshot.keepLastKnownGoodOr(CURRENT.get());
                debug("no prefs json (canRead=" + prefs.getFile().canRead()
                        + " path=" + prefs.getFile().getPath() + ") -> "
                        + (resolved == Snapshot.PASSTHROUGH ? "passthrough (never published)"
                                                            : "keep last-known-good"));
                return resolved;
            }

            // Fingerprint-based skip: if the payload AND the hour haven't changed since the
            // last accepted load, skip the full JSON parse. The hour check is required because
            // time_based mode depends on wall-clock time, not config content: same fingerprint
            // across an active-hours boundary must re-evaluate the time window, not return
            // the stale Snapshot. (Review finding #1, Sol)
            String fingerprint = PublishedConfig.Companion.fingerprint(jsonStr);
            String previousFingerprint = CURRENT_FINGERPRINT.get();
            if (shouldSkipLoad(fingerprint, previousFingerprint,
                    evaluationHour, LAST_EVALUATED_HOUR.get())) {
                debug("fingerprint and hour unchanged (" + fingerprint + ") -> skip parse");
                return CURRENT.get();
            }

            org.json.JSONObject root = new org.json.JSONObject(jsonStr);

            Integer refreshIntervalSec = null;
            Object rawRefreshInterval = root.opt("refreshIntervalSec");
            if (rawRefreshInterval instanceof Number) {
                refreshIntervalSec = ((Number) rawRefreshInterval).intValue();
            }

            // schemaVersion gate: refuse to interpret a payload written by an incompatible build
            // rather than silently mis-reading it. Keep last-known-good (never revert to real data
            // mid-test) instead of falling back to passthrough.
            int version = root.optInt("schemaVersion", -1);
            boolean legacyV2 = version == LEGACY_TRANSPORT_SCHEMA_VERSION;
            if (!TransportSchemaContract.supports(version)) {
                XposedBridge.log(TAG + ": transport rejected schema=" + version
                        + " expected=" + TRANSPORT_SCHEMA_VERSION + " fp=" + fingerprint);
                return CURRENT.get();
            }
            XposedBridge.log(TAG + ": transport accepted schema=" + version
                    + " fp=" + fingerprint);

            String mode = root.optString("mode", "always_on");
            if ("off".equals(mode)) {
                debug("mode=off -> passthrough");
                return acceptLoadedSnapshot(
                        Snapshot.PASSTHROUGH, refreshIntervalSec, fingerprint, evaluationHour);
            }
            if ("time_based".equals(mode)) {
                org.json.JSONObject hours = root.optJSONObject("activeHours");
                if (hours != null) {
                    int start = hours.optInt("start", 0);
                    int end = hours.optInt("end", 24);
                    boolean inRange = (start <= end)
                            ? (evaluationHour >= start && evaluationHour < end)
                            : (evaluationHour >= start || evaluationHour < end);
                    if (!inRange) {
                        debug("outside active hours (" + start + "-" + end
                                + ") current=" + evaluationHour + " -> passthrough");
                        return acceptLoadedSnapshot(
                                Snapshot.PASSTHROUGH, refreshIntervalSec, fingerprint, evaluationHour);
                    }
                }
            }

            // Flat field map -> Snapshot via the SAME field list used for DB cursors, so transport
            // coverage equals the profile table instead of a hand-maintained subset.
            org.json.JSONObject fields = root.optJSONObject("fields");
            if (fields == null) {
                // last-known-good (review FC-2): a v3-valid payload that carries no `fields` object
                // is structurally incomplete, not an instruction to stop spoofing. Publishing an
                // all-null Snapshot here would silently drop an active spoof back to real data.
                Snapshot resolved = Snapshot.keepLastKnownGoodOr(CURRENT.get());
                debug("payload has no 'fields' -> "
                        + (resolved == Snapshot.PASSTHROUGH ? "passthrough (never published)"
                                                            : "keep last-known-good"));
                return resolved;
            }
            org.json.JSONArray unavailableJson = root.optJSONArray("unavailable");
            if (unavailableJson == null && !legacyV2) {
                Snapshot resolved = Snapshot.keepLastKnownGoodOr(CURRENT.get());
                debug("payload has no 'unavailable' array -> keep last-known-good");
                return resolved;
            }
            java.util.List<String> requestedUnavailable = new java.util.ArrayList<>();
            if (unavailableJson != null) {
                for (int i = 0; i < unavailableJson.length(); i++) {
                    Object value = unavailableJson.get(i);
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException("unavailable entry is not a string");
                    }
                    requestedUnavailable.add((String) value);
                }
            }
            java.util.Set<String> configuredFields = new java.util.HashSet<>();
            java.util.Iterator<String> keys = fields.keys();
            while (keys.hasNext()) configuredFields.add(keys.next());
            name.caiyao.fakegps.config.UnavailablePayloadContract.Validated unavailable =
                    name.caiyao.fakegps.config.UnavailablePayloadContract.validate(
                            configuredFields, requestedUnavailable);
            Snapshot s = Snapshot.fromJson(fields, unavailable.asSet());
            String locationDeliveryMode = root.optString("locationDeliveryMode", "hook");
            LocationDeliveryPolicy.apply(s, locationDeliveryMode);
            debug("prefs loaded fields=" + (fields == null ? 0 : fields.length())
                    + " unavailable=" + unavailable.asList().size()
                    + " locationDelivery=" + locationDeliveryMode
                    + " hasLocation=" + s.hasLocation() + " lat=" + s.latitude + " lng=" + s.longitude
                    + " rebuildCells=" + s.hasCellReconstructionDecision());
            return acceptLoadedSnapshot(s, refreshIntervalSec, fingerprint, evaluationHour);
        } catch (Throwable t) {
            // Read/parse failure: keep last-known-good (do NOT revert to real device data mid-test).
            debug("loadSnapshot prefs error: " + t);
            return CURRENT.get();
        }
    }

    /** Commit Snapshot and delay as one accepted last-known-good transport decision. */
    private Snapshot acceptLoadedSnapshot(
            Snapshot snapshot,
            Integer refreshIntervalSec,
            String fingerprint,
            int evaluationHour) {
        REFRESH_SCHEDULER.acceptPayloadInterval(refreshIntervalSec, true);
        CURRENT_FINGERPRINT.set(fingerprint);
        LAST_EVALUATED_HOUR.set(evaluationHour);
        return snapshot;
    }

    /**
     * Fingerprint skip decision: safe to skip only if payload AND hour are unchanged.
     * The hour check ensures time_based mode re-evaluates on hour boundaries even when
     * config text is identical. Package-private for behavioral testing.
     */
    static boolean shouldSkipLoad(String fingerprint, String previousFingerprint,
                                   int evaluationHour, int lastEvaluatedHour) {
        return SnapshotSkipDecision.shouldSkip(
                fingerprint, previousFingerprint, evaluationHour, lastEvaluatedHour);
    }

    /**
     * Try to (re-)arm the prefs directory observer. Stops any previous observer first.
     * Returns true if newly armed. Called at startup and retried on each heartbeat tick
     * when not armed, so directories that appear after initial arm attempt are recovered.
     * (Review R2, Sol — lifecycle recovery)
     */
    private boolean tryArmObserver(String processName) {
        try {
            if (prefsObserver != null) {
                try { prefsObserver.stopWatching(); } catch (Throwable ignored) {}
            }
            XSharedPreferences probePrefs = new XSharedPreferences(
                    name.caiyao.fakegps.BuildConfig.APPLICATION_ID, PREFS_NAME);
            java.io.File prefsFile = probePrefs.getFile();
            String dirPath = prefsFile.getParent();
            String fileName = prefsFile.getName();

            PrefsDirectoryObserver obs = new PrefsDirectoryObserver(
                    dirPath, fileName, () -> reloadSnapshot(processName));
            if (obs.arm()) {
                prefsObserver = obs;
                XposedBridge.log(RuntimeEvidence.observerArmed(processName, dirPath));
                return true;
            }
        } catch (Throwable t) {
            debug("observer arm attempt failed: " + t.getMessage());
        }
        return false;
    }

    /**
     * Replace the app-classloader sentinel only when this target actually contains it.
     *
     * A module-classloader static cannot prove injection because the app and Xposed may load two
     * copies of the same class. Hooking the Method object from the target classloader makes the
     * probe's own call return true only when LSPosed really installed this module in that process.
     */
    private void installProbeSentinelIfPresent(
            ClassLoader targetClassLoader,
            String processName) {
        Class<?> sentinel = XposedHelpers.findClassIfExists(
                RuntimeHookSentinel.class.getName(),
                targetClassLoader);
        if (sentinel == null) return;
        XposedHelpers.findAndHookMethod(
                sentinel,
                "isHookActive",
                XC_MethodReplacement.returnConstant(true));
        XposedHelpers.findAndHookMethod(
                sentinel,
                "reloadHookSnapshot",
                String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return reloadSnapshotForProbe((String) param.args[0], processName);
                    }
                });
    }

}
