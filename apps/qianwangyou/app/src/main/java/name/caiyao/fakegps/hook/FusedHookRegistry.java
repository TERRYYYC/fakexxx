package name.caiyao.fakegps.hook;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Per-Method hook install transaction (Sol R5 #3, R6 #3, R7 #1, R8 #1).
 *
 * Every Method installation goes through {@link #claimAndInstall} — a terminal-state
 * transaction backed by a per-Method {@link CompletableFuture}:
 *
 * <ul>
 *   <li>The FIRST caller becomes the installer: install, complete the future, report
 *       {@link InstallResult#INSTALLED}; on failure remove the entry, complete the future
 *       exceptionally, and report {@link InstallResult#FAILED}.</li>
 *   <li>CONCURRENT callers block on the future — they never mistake an in-flight install
 *       for an installed method (R8 #1: that window leaked both fake-success evidence and
 *       one real-location call). When the peer succeeded they report
 *       {@link InstallResult#ALREADY_INSTALLED}; when it failed they loop and retry as the
 *       new installer.</li>
 *   <li>Failure reasons are retained (bounded) for delivery evidence (R8 #2).</li>
 * </ul>
 *
 * Deadlock note: an installer must never synchronously re-enter {@code claimAndInstall}
 * for the SAME method on the same thread. Production paths only ever install other
 * methods from inside a hook callback.
 */
final class FusedHookRegistry {

    /** Terminal outcome of an install transaction. */
    enum InstallResult {
        /** This call installed the hook. */
        INSTALLED,
        /** The hook was already installed (by this or a concurrent caller). */
        ALREADY_INSTALLED,
        /** The install failed; the method is free for a later retry. */
        FAILED,
    }

    /** Runs the actual hook installation; any throwable marks the install as failed. */
    interface Installer {
        void install(Method method);
    }

    private final ConcurrentHashMap<Method, CompletableFuture<Boolean>> states =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Method, String> lastFailures =
            new ConcurrentHashMap<>();

    /**
     * The ONLY way production code installs a hooked Method: terminal-state transaction
     * with wait-for-outcome semantics for concurrent callers (Sol R8 #1).
     */
    InstallResult claimAndInstall(Method method, Installer installer) {
        boolean interrupted = false;
        try {
            for (;;) {
                CompletableFuture<Boolean> fresh = new CompletableFuture<>();
                CompletableFuture<Boolean> race = states.putIfAbsent(method, fresh);
                if (race == null) {
                    try {
                        installer.install(method);
                        fresh.complete(true);
                        return InstallResult.INSTALLED;
                    } catch (Throwable t) {
                        states.remove(method, fresh);
                        fresh.completeExceptionally(t);
                        lastFailures.put(method, t.getClass().getSimpleName());
                        return InstallResult.FAILED;
                    }
                }
                try {
                    Boolean ok = null;
                    // R9 #2: an interrupted waiter must NOT escape before the terminal state —
                    // returning early lets the application call a not-yet-hooked fused method
                    // (real-location window). Keep waiting; restore the flag on every exit,
                    // including peer-failure takeover.
                    for (;;) {
                        try {
                            ok = race.get();
                            break;
                        } catch (InterruptedException ie) {
                            interrupted = true;
                        }
                    }
                    return Boolean.TRUE.equals(ok)
                            ? InstallResult.ALREADY_INSTALLED
                            : InstallResult.FAILED;
                } catch (ExecutionException peerFailure) {
                    // The peer's install failed and removed its entry — loop and try to
                    // become the installer ourselves.
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Bounded diagnostic for the last failed install of a method, or null. */
    String lastFailure(Method method) {
        return lastFailures.get(method);
    }
}
