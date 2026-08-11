package name.caiyao.fakegps.hook;

import android.os.FileObserver;

import de.robv.android.xposed.XposedBridge;

/**
 * Watches the DIRECTORY containing the hook prefs file for write-completion events.
 *
 * <h3>Why directory, not file</h3>
 * {@code SharedPreferences.commit()} replaces the inode (Phase A measured: 54164 &rarr; 49560).
 * A {@link FileObserver} constructed on the <em>file path</em> holds a watch on the now-unlinked
 * inode: it fires once, then never again, and the failure is invisible because the timer masks it.
 * Watching the directory sees the {@code MOVED_TO} event from the atomic rename.
 *
 * <h3>Event mask</h3>
 * {@code MOVED_TO | CLOSE_WRITE | CREATE} covers every write strategy across Android versions:
 * <ul>
 *   <li>Most versions: write temp file, atomic rename &rarr; {@code MOVED_TO}</li>
 *   <li>Some versions: direct write &rarr; {@code CLOSE_WRITE}</li>
 *   <li>Edge case: delete then create &rarr; {@code CREATE}</li>
 * </ul>
 * Multiple events for a single commit are harmless because the fingerprint check in
 * {@code MainHook.loadSnapshot()} makes the redundant reload a no-op.
 *
 * <h3>Thread safety</h3>
 * {@code onEvent} runs on the static {@code FileObserver.ObserverThread}. The callback acquires
 * {@code MainHook.SNAPSHOT_LOCK} via {@code reloadSnapshot()}, which serializes against timer
 * ticks and probe reloads. At steady state the thread sits in {@code epoll_wait} (zero CPU).
 */
final class PrefsDirectoryObserver extends FileObserver {

    private static final String TAG = "FakeGPS";
    private static final int MASK = MOVED_TO | CLOSE_WRITE | CREATE;
    /** Kernel inotify watch-loss signal. Not exposed in the FileObserver public API. */
    private static final int IN_IGNORED = 0x8000;

    private final String watchedDirPath;
    private final String targetFilename;
    private final Runnable onChanged;
    private volatile boolean armed;

    /**
     * @param dirPath        absolute path of the directory to watch
     * @param targetFilename the prefs file name (not a path) to filter events for
     * @param onChanged      callback to invoke when the target file changes; runs on the
     *                       FileObserver thread, so it must be thread-safe
     */
    @SuppressWarnings("deprecation") // FileObserver(String, int) is the only API-24-safe ctor
    PrefsDirectoryObserver(String dirPath, String targetFilename, Runnable onChanged) {
        super(dirPath, MASK);
        this.watchedDirPath = dirPath;
        this.targetFilename = targetFilename;
        this.onChanged = onChanged;
    }

    /**
     * Try to start the inotify watch. Returns {@code true} if the observer is now armed.
     * On failure, returns {@code false} and the caller should fall back to the timer.
     */
    boolean arm() {
        try {
            // Pre-flight: verify target directory exists. FileObserver.startWatching()
            // silently succeeds on non-existent directories but never delivers events,
            // producing a false observer_armed evidence log. (Review finding #2, Sol)
            if (!new java.io.File(watchedDirPath).isDirectory()) {
                XposedBridge.log(TAG + ": observer dir does not exist: " + watchedDirPath);
                armed = false;
                return false;
            }
            startWatching();
            armed = true;
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FileObserver.startWatching failed: " + t.getMessage());
            armed = false;
            return false;
        }
    }

    boolean isArmed() {
        return armed;
    }

    @Override
    public void onEvent(int event, String path) {
        // Kernel dropped the watch (directory deleted/moved/unmounted): IN_IGNORED is
        // terminal — the watch is gone, but without disarming, isArmed() would stay true
        // forever. The heartbeat lazy-retry would then never fire and the event-driven
        // path would stay silently dead until process restart, even after the app
        // recreates the directory. (Review finding P1 #2, Sol R3)
        if ((event & IN_IGNORED) != 0) {
            disarm();
            return;
        }
        if (!targetFilename.equals(path)) return;
        try {
            onChanged.run();
        } catch (Throwable t) {
            // Swallow: a reload failure must not kill the FileObserver thread.
            // A dead observer thread permanently stops ALL inotify delivery for the process.
            XposedBridge.log(TAG + ": observer reload failed: " + t.getMessage());
        }
    }

    /** Mark the watch as lost so the MainHook heartbeat re-arms on its next tick. */
    private void disarm() {
        armed = false;
        XposedBridge.log(TAG + ": observer watch lost (IN_IGNORED) — heartbeat will re-arm");
    }
}
