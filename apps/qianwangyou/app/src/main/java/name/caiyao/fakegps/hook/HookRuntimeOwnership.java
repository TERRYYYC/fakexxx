package name.caiyao.fakegps.hook;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-module-classloader ownership gates for hooks and the process refresh scheduler. */
final class HookRuntimeOwnership {

    private final AtomicBoolean schedulerClaimed = new AtomicBoolean(false);
    private final Set<ClassLoader> hookedClassLoaders = ConcurrentHashMap.newKeySet();

    /** Exactly one load-package callback may create the process timer. */
    boolean claimScheduler() {
        return schedulerClaimed.compareAndSet(false, true);
    }

    /** A target classloader receives the hook registry at most once. */
    boolean claimHooks(ClassLoader classLoader) {
        if (classLoader == null) {
            throw new IllegalArgumentException("classLoader == null");
        }
        return hookedClassLoaders.add(classLoader);
    }
}
