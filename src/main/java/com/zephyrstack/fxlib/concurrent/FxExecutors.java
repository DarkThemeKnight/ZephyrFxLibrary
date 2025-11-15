package com.zephyrstack.fxlib.concurrent;

import javafx.application.Platform;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executors for JavaFX applications:
 * - {@link #fx()} ensures work runs on the FX Application Thread.
 * - {@link #scheduler()} exposes a daemon {@link ScheduledExecutorService} for repeated/delayed tasks.
 */
public final class FxExecutors {
    private static final Executor FX = command -> {
        Objects.requireNonNull(command, "command");
        if (Platform.isFxApplicationThread()) {
            command.run();
        } else {
            Platform.runLater(command);
        }
    };

    private static final ScheduledExecutorService SCHEDULER;

    static {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "fx-scheduler-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        SCHEDULER = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                factory
        );
        if (SCHEDULER instanceof ScheduledThreadPoolExecutor executor) {
            executor.setRemoveOnCancelPolicy(true);
        }
    }

    private FxExecutors() {
    }

    /**
     * Returns an executor that dispatches work onto the FX Application Thread.
     */
    public static Executor fx() {
        return FX;
    }

    /**
     * Returns a daemon scheduled executor suitable for timers, polling, etc.
     */
    public static ScheduledExecutorService scheduler() {
        return SCHEDULER;
    }

    /**
     * Optional: allow graceful shutdown if the application wants to tear down pools explicitly.
     */
    public static void shutdownScheduler() {
        SCHEDULER.shutdownNow();
    }
}
