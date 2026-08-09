package name.caiyao.fakegps.hook;

import java.util.concurrent.atomic.AtomicLong;
import name.caiyao.fakegps.config.PublishPropagation;

/** Last-known-good refresh delay owned by one hook process. */
final class HookRefreshScheduler {

    static final long INITIAL_DELAY_MS = 3_000L;

    private final AtomicLong currentDelayMs =
            new AtomicLong(PublishPropagation.HOOK_REFRESH_INTERVAL_MS);

    long currentDelayMs() {
        return currentDelayMs.get();
    }

    /**
     * Accept a candidate only when the containing payload passed all structural checks.
     * A rejected/malformed reload keeps the previous delay together with the previous Snapshot.
     */
    long acceptPayloadInterval(Integer rawSeconds, boolean payloadAccepted) {
        if (payloadAccepted) {
            currentDelayMs.set(PublishPropagation.runtimeIntervalMs(rawSeconds));
        }
        return currentDelayMs.get();
    }
}
