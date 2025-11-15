package com.zephyrstack.fxlib.concurrent;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small scheduling helpers built on top of {@link FxExecutors}:
 * - {@link #debounce(long, Runnable)} delays execution until input quiets down.
 * - {@link #throttle(long, Runnable)} ensures execution occurs at most once per window.
 *
 * Returned {@link Runnable} instances should be invoked whenever the caller wants to trigger the action.
 */
public final class FxScheduler {
    private FxScheduler() {
    }

    /**
     * Returns a trigger runnable that debounces calls: the wrapped action is invoked only after there
     * have been no new trigger calls for {@code ms} milliseconds. Execution is marshaled onto the FX thread.
     */
    public static Runnable debounce(long ms, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
        ScheduledExecutorService scheduler = FxExecutors.scheduler();
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        return () -> {
            ScheduledFuture<?> existing = futureRef.getAndSet(null);
            if (existing != null) existing.cancel(false);

            ScheduledFuture<?> scheduled = scheduler.schedule(() -> FxExecutors.fx().execute(action),
                    ms, TimeUnit.MILLISECONDS);
            futureRef.set(scheduled);
        };
    }

    /**
     * Returns a trigger runnable that throttles calls: the wrapped action executes immediately the first
     * time, then subsequent triggers within {@code ms} milliseconds are ignored. After the window elapses
     * the action may be executed again. Execution is marshaled onto the FX thread.
     */
    public static Runnable throttle(long ms, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (ms <= 0) throw new IllegalArgumentException("ms must be > 0");
        AtomicLong lastRunAt = new AtomicLong(0);

        return () -> {
            long now = System.currentTimeMillis();
            long last = lastRunAt.get();
            if (now - last >= ms && lastRunAt.compareAndSet(last, now)) {
                FxExecutors.fx().execute(action);
            }
        };
    }
}
