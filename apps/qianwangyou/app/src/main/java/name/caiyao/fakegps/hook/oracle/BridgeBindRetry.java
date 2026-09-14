package name.caiyao.fakegps.hook.oracle;

import java.util.Objects;

/**
 * #194: retry budget for the phase-600 oracle bridge registration inside system_server.
 *
 * Every registration failure used to be terminal and silent: a rejected bindService, a null
 * binding, a failed registerOracle call, or a QWY process that died before the service ever
 * connected left the oracle unregistered until the next manual reinstall. This budget turns
 * each failure path into a bounded backoff rebind — 1s, 5s, 30s, 30s on top of the initial
 * attempt — and, once the budget is spent, emits a single give-up log line so the failure is
 * visible instead of silent. A completed registration resets the budget, and so does every
 * framework-driven onBindingDied rebind (#195 review: that rebind starts a fresh generation
 * and must be able to retry and log its own failures even after a give-up).
 *
 * Pure policy: the installer supplies the rebind scheduler and the log sink, so host JVM tests
 * execute this exact budget. NOT thread-safe — every caller is confined to system_server's main
 * thread (boot-phase callback, ServiceConnection callbacks, Handler runnables).
 */
final class BridgeBindRetry {
    /** Backoff before retry attempt N (0-based), on top of the initial bind. */
    static final long[] RETRY_DELAYS_MS = {1_000L, 5_000L, 30_000L, 30_000L};

    /** How long a bound-but-never-connected generation may stay silent before it counts as failed. */
    static final long CONNECT_WATCHDOG_MS = 30_000L;

    interface Environment {
        /** Schedules the rebind action to run on the main thread after {@code delayMs}. */
        void scheduleRebind(long delayMs);

        /** Operator-visible log sink ({@code XposedBridge.log} inside system_server). */
        void log(String message);
    }

    private final Environment environment;
    private int retriesUsed;
    private boolean gaveUpLogged;

    BridgeBindRetry(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    /**
     * A registration attempt failed for any reason. Schedules the next backoff rebind, or — once
     * the budget is spent — emits the final give-up log exactly once and stays silent until the
     * budget is reset (a later framework onBindingDied, reboot, or successful registration).
     */
    void onRegistrationFailed(String reason) {
        if (retriesUsed >= RETRY_DELAYS_MS.length) {
            if (!gaveUpLogged) {
                gaveUpLogged = true;
                environment.log("bridge registration gave up after " + retriesUsed
                        + " retries (last failure: " + reason
                        + "); oracle stays unregistered until the next binding death or reboot (#194)");
            }
            return;
        }
        long delayMs = RETRY_DELAYS_MS[retriesUsed];
        retriesUsed++;
        environment.log("bridge registration failed (" + retriesUsed + "/"
                + RETRY_DELAYS_MS.length + "): " + reason + "; rebinding in " + delayMs + "ms");
        environment.scheduleRebind(delayMs);
    }

    /**
     * Fresh budget for a brand-new binding generation (#195 review). Called when the framework's
     * onBindingDied rebind starts a new bind: that generation must be able to retry and log its
     * own failures even if the previous budget was spent and its give-up line already logged —
     * otherwise a post-give-up binding death would rebind and fail with zero logs and zero
     * retries. Deliberately touches no generation state: the installer's generation guards stay
     * the sole protection that keeps a stale scheduled rebind from tearing down a healthy binding.
     */
    void resetBudget() {
        retriesUsed = 0;
        gaveUpLogged = false;
    }

    /** A completed registration resets the budget and the give-up marker for later generations. */
    void onRegistrationSucceeded() {
        resetBudget();
    }

    int retriesUsed() {
        return retriesUsed;
    }
}
