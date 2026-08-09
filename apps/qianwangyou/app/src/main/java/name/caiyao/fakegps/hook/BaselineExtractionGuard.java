package name.caiyao.fakegps.hook;

import java.util.function.Supplier;

/**
 * Marks the narrow call stack that extracts a real cellular baseline.
 *
 * Getter hooks execute synchronously on the same thread as the original getter. Without this
 * guard, baseline extraction calls getTac()/getRsrp()/isRegistered() and reads back this module's
 * own spoofed results instead of the framework values it is supposed to preserve.
 */
public final class BaselineExtractionGuard {
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    private BaselineExtractionGuard() {}

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static <T> T call(Supplier<T> supplier) {
        DEPTH.set(DEPTH.get() + 1);
        try {
            return supplier.get();
        } finally {
            int remaining = DEPTH.get() - 1;
            if (remaining == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(remaining);
            }
        }
    }
}
