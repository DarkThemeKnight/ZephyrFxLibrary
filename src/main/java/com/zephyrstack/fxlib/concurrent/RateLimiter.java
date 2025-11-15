package com.zephyrstack.fxlib.concurrent;

import java.time.Duration;
import java.util.Objects;

/**
 * Token-bucket style rate limiter. Allows up to {@code permits} within the configured window.
 */
public final class RateLimiter {
    private final int maxPermits;
    private final long windowMillis;

    private int availablePermits;
    private long windowResetAt;

    public RateLimiter(int maxPermits, Duration window) {
        if (maxPermits <= 0) throw new IllegalArgumentException("maxPermits must be > 0");
        Objects.requireNonNull(window, "window");
        long millis = window.toMillis();
        if (millis <= 0) throw new IllegalArgumentException("window must be > 0");
        this.maxPermits = maxPermits;
        this.windowMillis = millis;
        this.availablePermits = maxPermits;
        this.windowResetAt = System.currentTimeMillis() + windowMillis;
    }

    /**
     * Returns true if a permit is acquired immediately, false otherwise.
     */
    public synchronized boolean tryAcquire() {
        refillIfNeeded();
        if (availablePermits > 0) {
            availablePermits--;
            return true;
        }
        return false;
    }

    /**
     * Blocks until a permit is available.
     */
    public synchronized void acquire() throws InterruptedException {
        while (!tryAcquire()) {
            long waitFor = windowResetAt - System.currentTimeMillis();
            if (waitFor > 0) {
                wait(waitFor);
            } else {
                // just loop; refill will happen on next iteration
                Thread.yield();
            }
        }
    }

    private void refillIfNeeded() {
        long now = System.currentTimeMillis();
        if (now >= windowResetAt) {
            availablePermits = maxPermits;
            windowResetAt = now + windowMillis;
            notifyAll();
        }
    }
}
