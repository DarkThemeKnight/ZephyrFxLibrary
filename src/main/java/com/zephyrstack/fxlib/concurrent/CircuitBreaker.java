package com.zephyrstack.fxlib.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Basic circuit breaker: when failures exceed a threshold it opens for a cooldown period,
 * then transitions to HALF_OPEN allowing a limited number of trial calls.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final int halfOpenTrialCount;

    private final AtomicInteger failureCounter = new AtomicInteger();
    private final AtomicInteger halfOpenSuccessCounter = new AtomicInteger();
    private volatile State state = State.CLOSED;
    private volatile long openSince = -1;

    public CircuitBreaker(int failureThreshold,
                          Duration openDuration,
                          int halfOpenTrialCount) {
        if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold must be > 0");
        Objects.requireNonNull(openDuration, "openDuration");
        if (openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration must be > 0");
        }
        if (halfOpenTrialCount <= 0) throw new IllegalArgumentException("halfOpenTrialCount must be > 0");
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.halfOpenTrialCount = halfOpenTrialCount;
    }

    public <T> T execute(Callable<T> callable) throws Exception {
        Objects.requireNonNull(callable, "callable");
        State current = state;
        if (current == State.OPEN) {
            if (System.currentTimeMillis() - openSince >= openDuration.toMillis()) {
                transitionTo(State.HALF_OPEN);
            } else {
                throw new CircuitBreakerOpenException("Circuit breaker is open; retries after " + openDuration);
            }
        }
        if (state == State.HALF_OPEN && halfOpenSuccessCounter.get() >= halfOpenTrialCount) {
            // Enough successful trials => close
            transitionTo(State.CLOSED);
        }

        try {
            T result = callable.call();
            onSuccess();
            return result;
        } catch (Exception ex) {
            onFailure();
            throw ex;
        }
    }

    public State state() {
        return state;
    }

    private synchronized void transitionTo(State newState) {
        state = newState;
        switch (newState) {
            case CLOSED -> {
                failureCounter.set(0);
                halfOpenSuccessCounter.set(0);
                openSince = -1;
            }
            case OPEN -> {
                openSince = System.currentTimeMillis();
            }
            case HALF_OPEN -> {
                halfOpenSuccessCounter.set(0);
            }
        }
    }

    private void onSuccess() {
        if (state == State.HALF_OPEN) {
            if (halfOpenSuccessCounter.incrementAndGet() >= halfOpenTrialCount) {
                transitionTo(State.CLOSED);
            }
        } else if (state == State.CLOSED) {
            failureCounter.set(0);
        }
    }

    private void onFailure() {
        if (state == State.HALF_OPEN) {
            transitionTo(State.OPEN);
        } else if (state == State.CLOSED) {
            if (failureCounter.incrementAndGet() >= failureThreshold) {
                transitionTo(State.OPEN);
            }
        }
    }
}
