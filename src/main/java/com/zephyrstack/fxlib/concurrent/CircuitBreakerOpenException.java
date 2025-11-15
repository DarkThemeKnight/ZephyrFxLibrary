package com.zephyrstack.fxlib.concurrent;

/**
 * Thrown when a {@link CircuitBreaker} refuses to execute due to being OPEN.
 */
public final class CircuitBreakerOpenException extends RuntimeException {
    public CircuitBreakerOpenException(String message) {
        super(message);
    }
}
