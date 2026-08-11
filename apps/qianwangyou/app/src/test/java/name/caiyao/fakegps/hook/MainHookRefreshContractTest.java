package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Ownership and last-known-good rules for MainHook's per-process refresh loop. */
public class MainHookRefreshContractTest {

    @Test
    public void duplicateLoadPackageCallbacksClaimOnlyOneScheduler() {
        HookRuntimeOwnership ownership = new HookRuntimeOwnership();

        assertTrue(ownership.claimScheduler());
        assertFalse(ownership.claimScheduler());
        assertFalse(ownership.claimScheduler());
    }

    @Test
    public void hookRegistrationIsIdempotentPerTargetClassLoader() {
        HookRuntimeOwnership ownership = new HookRuntimeOwnership();
        ClassLoader first = new ClassLoader() {};
        ClassLoader second = new ClassLoader() {};

        assertTrue(ownership.claimHooks(first));
        assertFalse(ownership.claimHooks(first));
        assertTrue(ownership.claimHooks(second));
    }

    @Test
    public void rejectedPayloadKeepsLastKnownGoodDelay() {
        HookRefreshScheduler scheduler = new HookRefreshScheduler();
        assertEquals(30_000L, scheduler.currentDelayMs());
        assertEquals(60_000L, scheduler.acceptPayloadInterval(60, true));

        assertEquals(60_000L, scheduler.acceptPayloadInterval(5, false));
        assertEquals(60_000L, scheduler.currentDelayMs());
    }

    @Test
    public void acceptedPayloadUpdatesSubsequentTicksAndInitialTickStaysFast() {
        HookRefreshScheduler scheduler = new HookRefreshScheduler();

        assertEquals(3_000L, HookRefreshScheduler.INITIAL_DELAY_MS);
        assertEquals(5_000L, scheduler.acceptPayloadInterval(5, true));
        assertEquals(5_000L, scheduler.currentDelayMs());
    }

    @Test
    public void productionWriterAndHookBothReferenceTheTransportIntervalField() throws Exception {
        String writer = classBytecode("name.caiyao.fakegps.config.ConfigPrefsSync");
        String hook = classBytecode("name.caiyao.fakegps.hook.MainHook");
        String timerCallback = classBytecode("name.caiyao.fakegps.hook.MainHook$1");

        assertTrue(writer.contains("refreshIntervalSec"));
        assertTrue(writer.contains("readRefreshIntervalSec"));
        assertTrue(hook.contains("refreshIntervalSec"));
        assertTrue(hook.contains("claimScheduler"));
        assertTrue(hook.contains("claimHooks"));
        assertTrue(hook.contains("schedulerOwned"));
        assertTrue(hook.contains("intervalChanged"));
        assertFalse(timerCallback.contains("intervalChanged"));
        assertTrue(timerCallback.contains("reloadSnapshot"));
        assertTrue(timerCallback.contains("currentDelayMs"));
    }

    /**
     * Phase B plumbing: MainHook must wire PrefsDirectoryObserver (event-driven primary)
     * and emit all three observer lifecycle evidence events. The fingerprint-based skip
     * must call PublishedConfig.fingerprint() before the JSON parse path.
     */
    @Test
    public void phaseBObserverAndFingerprintWiringPresent() throws Exception {
        String hook = classBytecode("name.caiyao.fakegps.hook.MainHook");

        // Observer lifecycle evidence wiring
        assertTrue("observerArmed evidence missing", hook.contains("observerArmed"));
        assertTrue("observerArmFailed evidence missing", hook.contains("observerArmFailed"));
        assertTrue("timerFallback evidence missing", hook.contains("timerFallback"));

        // Observer class reference
        assertTrue("PrefsDirectoryObserver not referenced",
                hook.contains("PrefsDirectoryObserver"));

        // Fingerprint skip must invoke the fingerprint method
        assertTrue("fingerprint method not referenced", hook.contains("fingerprint"));
    }

    /** PrefsDirectoryObserver must expose arm/isArmed and override onEvent. */
    @Test
    public void prefsDirectoryObserverStructure() throws Exception {
        String observer = classBytecode("name.caiyao.fakegps.hook.PrefsDirectoryObserver");

        assertTrue("arm() missing", observer.contains("arm"));
        assertTrue("isArmed() missing", observer.contains("isArmed"));
        assertTrue("onEvent override missing", observer.contains("onEvent"));
        assertTrue("startWatching call missing", observer.contains("startWatching"));
    }

    // --- SnapshotSkipDecision behavioral tests (replaces bytecode-only fingerprintSkipIncludesHourCheck) ---

    /**
     * Review finding #1 (Sol): fingerprint skip must include hour-of-day so that
     * time_based mode re-evaluates on hour boundaries even when config text is identical.
     *
     * Same fingerprint + same hour = skip (no redundant parse).
     */
    @Test
    public void shouldSkip_sameFingerprint_sameHour_skips() {
        assertTrue(SnapshotSkipDecision.shouldSkip("abc123", "abc123", 14, 14));
    }

    /** Different fingerprint = config changed, must reload regardless of hour. */
    @Test
    public void shouldSkip_differentFingerprint_doesNotSkip() {
        assertFalse(SnapshotSkipDecision.shouldSkip("abc123", "def456", 14, 14));
    }

    /** Same fingerprint but different hour = hour boundary crossed, must re-evaluate. */
    @Test
    public void shouldSkip_sameFingerprint_differentHour_doesNotSkip() {
        assertFalse(SnapshotSkipDecision.shouldSkip("abc123", "abc123", 15, 14));
    }

    /** Null fingerprint (first load or parse failure) must never skip. */
    @Test
    public void shouldSkip_nullFingerprint_doesNotSkip() {
        assertFalse(SnapshotSkipDecision.shouldSkip(null, "abc123", 14, 14));
    }

    /** No previous fingerprint (cold start) must not skip. */
    @Test
    public void shouldSkip_nullPreviousFingerprint_doesNotSkip() {
        assertFalse(SnapshotSkipDecision.shouldSkip("abc123", null, 14, 14));
    }

    /** Both null = cold start with null payload, must not skip. */
    @Test
    public void shouldSkip_bothNull_doesNotSkip() {
        assertFalse(SnapshotSkipDecision.shouldSkip(null, null, 14, 14));
    }

    /**
     * Review finding #2 (Sol): arm() must verify directory existence before claiming
     * success. FileObserver.startWatching() silently succeeds on non-existent directories
     * but never delivers events, producing a false observer_armed evidence log.
     */
    @Test
    public void observerArmVerifiesDirectoryExists() throws Exception {
        String observer = classBytecode("name.caiyao.fakegps.hook.PrefsDirectoryObserver");
        assertTrue("isDirectory check missing — arm() must verify target dir exists",
                observer.contains("isDirectory"));
    }

    /**
     * Review finding (Sol R2): heartbeat handler must attempt observer re-arm when
     * observer is null or not armed. Without this, an initial arm failure permanently
     * degrades the process to timer-only mode with no recovery path.
     *
     * Verified via bytecode: the timer callback (MainHook$1) must reference both
     * isArmed (check) and tryArmObserver (retry).
     */
    @Test
    public void heartbeatHandlerRetriesObserverArm() throws Exception {
        String timerCallback = classBytecode("name.caiyao.fakegps.hook.MainHook$1");
        assertTrue("heartbeat must check isArmed for lazy retry",
                timerCallback.contains("isArmed"));
        assertTrue("heartbeat must call tryArmObserver for recovery",
                timerCallback.contains("tryArmObserver"));
    }

    /**
     * Review finding (Sol R3): when the watched prefs directory is deleted/moved, the
     * kernel delivers IN_IGNORED and drops the watch. onEvent must disarm in that case —
     * otherwise isArmed() stays true forever, the heartbeat lazy-retry never fires, and
     * the event-driven path is silently dead until process restart even after the app
     * recreates the directory.
     */
    @Test
    public void observerDisarmsOnKernelWatchLoss() throws Exception {
        String observer = classBytecode("name.caiyao.fakegps.hook.PrefsDirectoryObserver");
        assertTrue("onEvent must disarm on IN_IGNORED (kernel watch loss)",
                observer.contains("disarm"));
        assertTrue("watch-loss evidence log missing", observer.contains("watch lost"));
    }

    /**
     * Sol's frozen fused design (2026-08-04): the fused path must resolve clients via the
     * public LocationServices factory + runtime capability, and replace results only through
     * public APIs. Internal impl class names and private GMS field mutation are prohibited
     * in bytecode.
     */
    @Test
    public void fusedPathUsesPublicApisNoPrivateFieldFallbacks() throws Exception {
        String hook = classBytecode("name.caiyao.fakegps.hook.HookUtils");

        for (String field : new String[]{"mLocations", "mIsLocationAvailable",
                "mResult", "mComplete", "mResultSet"}) {
            assertFalse("private GMS field fallback must be removed: " + field,
                    hook.contains(field));
        }
        assertFalse("internal impl name guessing must be removed",
                hook.contains("FusedLocationProviderClientImpl"));
        assertFalse("internal impl name guessing must be removed", hook.contains("zzbp"));

        assertTrue("public factory hook expected", hook.contains("getFusedLocationProviderClient"));

        // Sol R5 #1: the exact Maps APK has NO Tasks/Task descriptors under their public
        // names — looking the utility class up by name silently returns real-location Tasks.
        assertFalse("Tasks utility name lookup prohibited (R8-renamed away on exact Maps)",
                hook.contains("com.google.android.gms.tasks.Tasks"));
    }

    /**
     * Sol R9 #1: no production site may discard the install transaction's terminal result.
     * Structurally, HookUtils may call the registry directly only inside the single
     * observed-install wrapper and the Task delivery aggregator; every eager/callback/
     * listener install must go through installObserved, which consumes the result and
     * emits bounded failure evidence.
     */
    @Test
    public void everyRegistryResultIsConsumedThroughObservedInstall() throws Exception {
        String source = readSource("name/caiyao/fakegps/hook/HookUtils.java");

        int directRegistryCalls = countOccurrences(source, "FUSED_HOOK_REGISTRY.claimAndInstall(");
        assertEquals("only installObserved + the delivery aggregator may call the registry",
                2, directRegistryCalls);

        int observedCalls = countOccurrences(source, "installObserved(");
        assertTrue("eager value-object, callback and listener installs must be observed"
                + " (1 definition + 7 sites), found " + observedCalls,
                observedCalls >= 8);
    }

    private static String readSource(String relative) throws Exception {
        String[] roots = {"app/src/main/java/", "src/main/java/"};
        for (String root : roots) {
            java.io.File f = new java.io.File(root + relative);
            if (f.isFile()) {
                return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.FileNotFoundException(relative);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    private static String classBytecode(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = MainHookRefreshContractTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}
