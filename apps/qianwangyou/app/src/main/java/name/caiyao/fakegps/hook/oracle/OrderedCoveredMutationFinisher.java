package name.caiyao.fakegps.hook.oracle;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;

/**
 * FIFO worker for endpoint samples that must run after platform callback locks are released.
 *
 * QWY Binder finishes use {@link #awaitDrained()} as a causal barrier: every covered completion
 * enqueued before the Binder call must publish before that call may report a stable even cursor.
 */
final class OrderedCoveredMutationFinisher implements AutoCloseable {
    private static final long DEFAULT_BARRIER_TIMEOUT_MILLIS = 5_000L;

    private final AtomicReference<Thread> workerThread = new AtomicReference<>();
    private final ExecutorService executor;
    private final Set<TrackedAction> acceptedActions = ConcurrentHashMap.newKeySet();
    private final long barrierTimeout;
    private final TimeUnit barrierTimeoutUnit;

    OrderedCoveredMutationFinisher(String threadName) {
        this(threadName, DEFAULT_BARRIER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    OrderedCoveredMutationFinisher(
            String threadName,
            long barrierTimeout,
            TimeUnit barrierTimeoutUnit) {
        if (barrierTimeout <= 0L) {
            throw new IllegalArgumentException("positive barrier timeout is required");
        }
        if (barrierTimeoutUnit == null) {
            throw new IllegalArgumentException("barrier timeout unit is required");
        }
        this.barrierTimeout = barrierTimeout;
        this.barrierTimeoutUnit = barrierTimeoutUnit;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            workerThread.compareAndSet(null, thread);
            return thread;
        });
    }

    void execute(Runnable action) {
        execute(action, () -> { });
    }

    /** Every accepted covered token supplies an idempotent retirement callback. */
    void execute(Runnable action, Runnable onDiscard) {
        final TrackedAction tracked = new TrackedAction(action, onDiscard);
        acceptedActions.add(tracked);
        try {
            executor.execute(tracked);
        } catch (RejectedExecutionException failure) {
            acceptedActions.remove(tracked);
            tracked.discard();
            throw failure;
        }
    }

    /** Waits only from a non-worker Binder thread and never while an oracle lock is held. */
    void awaitDrained() {
        if (Thread.currentThread() == workerThread.get()) {
            throw new IllegalStateException("covered-mutation finisher cannot await itself");
        }
        final Future<?> barrier;
        try {
            barrier = executor.submit(() -> { });
        } catch (RejectedExecutionException failure) {
            throw new IllegalStateException("covered-mutation finisher rejected its barrier", failure);
        }
        try {
            barrier.get(barrierTimeout, barrierTimeoutUnit);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("covered-mutation finisher barrier interrupted", failure);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("covered-mutation finisher barrier failed", failure.getCause());
        } catch (TimeoutException failure) {
            // The stable cursor cannot wait forever for an endpoint sample whose callback has
            // already returned. Retire the worker so later callbacks are rejected/abandoned and
            // let the Binder caller poison the oracle rather than holding QWY locks indefinitely.
            barrier.cancel(false);
            executor.shutdownNow();
            // shutdownNow returns queued Runnables but relying on that list misses a running
            // sampler and races an accepted enqueue. The process-owned set covers both; each
            // callback retires its token at most once even if a late worker unwinds afterward.
            for (TrackedAction accepted : acceptedActions.toArray(new TrackedAction[0])) {
                accepted.discard();
            }
            throw new IllegalStateException("covered-mutation finisher barrier timed out", failure);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        for (TrackedAction accepted : acceptedActions.toArray(new TrackedAction[0])) {
            accepted.discard();
        }
    }

    private final class TrackedAction implements Runnable {
        private final Runnable action;
        private final Runnable onDiscard;
        private final AtomicBoolean discarded = new AtomicBoolean();

        TrackedAction(Runnable action, Runnable onDiscard) {
            if (action == null || onDiscard == null) {
                throw new IllegalArgumentException("covered action and discard callback are required");
            }
            this.action = action;
            this.onDiscard = onDiscard;
        }

        @Override
        public void run() {
            try {
                if (!discarded.get()) action.run();
            } finally {
                acceptedActions.remove(this);
            }
        }

        void discard() {
            if (discarded.compareAndSet(false, true)) onDiscard.run();
        }
    }
}
