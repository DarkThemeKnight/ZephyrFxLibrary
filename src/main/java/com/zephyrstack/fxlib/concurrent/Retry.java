package com.zephyrstack.fxlib.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Simple retry helper with exponential backoff. Useful for transient network calls or IO that may
 * succeed after short delays.
 */
public final class Retry {
    private static final long MAX_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private Retry() {}

    public static <T> T withBackoff(Callable<T> call,
                                    int maxAttempts,
                                    Duration initialDelay) throws Exception {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(initialDelay, "initialDelay");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");

        long delayMs = Math.max(0L, initialDelay.toMillis());
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.call();
            } catch (Exception ex) {
                lastException = ex;
                if (attempt == maxAttempts) throw lastException;
                try {
                    sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                delayMs = nextDelay(delayMs);
            }
        }
        throw lastException == null ? new IllegalStateException("Retry failed without exception") : lastException;
    }

    public static void withBackoff(Runnable run,
                                   int maxAttempts,
                                   Duration initialDelay) {
        Objects.requireNonNull(run, "run");
        try {
            withBackoff(() -> {
                run.run();
                return null;
            }, maxAttempts, initialDelay);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(ex);
        }
    }

    private static void sleep(long delayMs) throws InterruptedException {
        if (delayMs <= 0) return;
        Thread.sleep(delayMs);
    }

    private static long nextDelay(long current) {
        if (current <= 0) return 10;
        long doubled = current * 2;
        return Math.min(doubled, MAX_BACKOFF_MILLIS);
    }
}
