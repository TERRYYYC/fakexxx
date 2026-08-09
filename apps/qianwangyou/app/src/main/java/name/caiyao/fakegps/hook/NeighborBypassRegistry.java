package name.caiyao.fakegps.hook;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Weak identity membership for fabricated neighbor-cell value objects.
 *
 * Telephony value objects derive {@code equals/hashCode} from mutable identity and signal fields.
 * A normal WeakHashMap therefore loses an entry after those fields are populated. This registry
 * fixes the hash at insertion time and compares referents by {@code ==}, while retaining weak-key
 * cleanup so target processes do not leak every CellInfo object they observe.
 */
final class NeighborBypassRegistry {
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final Map<IdentityWeakReference, Boolean> entries = new HashMap<>();

    synchronized void add(Object value) {
        if (value == null) return;
        drainCollected();
        entries.put(new IdentityWeakReference(value, collected), Boolean.TRUE);
    }

    synchronized boolean contains(Object value) {
        if (value == null) return false;
        drainCollected();
        return entries.containsKey(new IdentityWeakReference(value));
    }

    private void drainCollected() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) collected.poll()) != null) {
            entries.remove(reference);
        }
    }

    private static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHash;

        IdentityWeakReference(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            identityHash = System.identityHashCode(referent);
        }

        IdentityWeakReference(Object referent) {
            super(referent);
            identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference)) return false;
            Object left = get();
            Object right = ((IdentityWeakReference) other).get();
            return left != null && left == right;
        }
    }
}
