package name.caiyao.fakegps.hook;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Identity gate for fused-returned Task instances (Sol R6 #1), with TRUE weak-reference
 * identity semantics (Sol R7 #3).
 *
 * Hooking a listener-registration method on the shared runtime Task class would otherwise
 * wrap listeners for EVERY GMS Task in the process, not just the ones our fused APIs
 * returned. This tracker marks the actual instances handed out by hooked fused methods,
 * matched by reference identity — never by {@code equals()} — and held weakly so completed
 * tasks are never leaked. Hash codes are captured at insertion, so referents with mutable
 * {@code hashCode()} stay findable.
 */
final class FusedTaskTracker {

    /** Weak key whose equality is REFERENT identity; stale keys never match. */
    private static final class IdentityWeakRef extends WeakReference<Object> {
        private final int hash;

        IdentityWeakRef(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakRef)) return false;
            Object mine = get();
            return mine != null && mine == ((IdentityWeakRef) other).get();
        }
    }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final Set<IdentityWeakRef> tracked = new HashSet<>();

    synchronized void mark(Object task) {
        if (task == null) return;
        expunge();
        tracked.add(new IdentityWeakRef(task, queue));
    }

    synchronized boolean isTracked(Object task) {
        if (task == null) return false;
        expunge();
        return tracked.contains(new IdentityWeakRef(task, queue));
    }

    private void expunge() {
        for (Object ref = queue.poll(); ref != null; ref = queue.poll()) {
            tracked.remove(ref);
        }
        // Bounded hygiene: drop already-cleared keys even without queue delivery.
        Iterator<IdentityWeakRef> it = tracked.iterator();
        while (it.hasNext()) {
            if (it.next().get() == null) {
                it.remove();
            }
        }
    }
}
