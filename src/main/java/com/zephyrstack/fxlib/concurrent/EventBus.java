package com.zephyrstack.fxlib.concurrent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal synchronous event bus keyed by event type. Subscribers are invoked immediately on the calling thread.
 */
public final class EventBus {
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    /**
     * Subscribes to a concrete event type. Returns an {@link AutoCloseable} that removes the handler when closed.
     */
    public <T> AutoCloseable subscribe(Class<T> eventType, Consumer<T> handler) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");
        CopyOnWriteArrayList<Consumer<?>> list = listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>());
        list.add(handler);
        return () -> list.remove(handler);
    }

    /**
     * Publishes an event to all handlers registered for its concrete class. Supertype dispatch is not performed.
     */
    public void publish(Object event) {
        if (event == null) return;
        Class<?> type = event.getClass();
        List<Consumer<?>> handlers = listeners.get(type);
        if (handlers == null || handlers.isEmpty()) return;
        for (Consumer<?> handler : handlers) {
            @SuppressWarnings("unchecked")
            Consumer<Object> consumer = (Consumer<Object>) handler;
            consumer.accept(event);
        }
    }

    /**
     * Removes all registered listeners.
     */
    public void clear() {
        listeners.clear();
    }
}
