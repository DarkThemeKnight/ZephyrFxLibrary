package com.zephyrstack.fxlib.concurrent;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Delegating {@link ExecutorService} that keeps track of submitted/running/completed tasks and notifies listeners.
 */
public final class ObservableExecutorService extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final CopyOnWriteArrayList<Consumer<Stats>> listeners = new CopyOnWriteArrayList<>();

    public ObservableExecutorService(ExecutorService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        submitted.incrementAndGet();
        delegate.execute(track(command));
        notifyListeners();
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        submitted.incrementAndGet();
        Future<T> future = delegate.submit(track(task));
        notifyListeners();
        return future;
    }

    @Override
    public Future<?> submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        submitted.incrementAndGet();
        Future<?> future = delegate.submit(track(task));
        notifyListeners();
        return future;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        Objects.requireNonNull(task, "task");
        submitted.incrementAndGet();
        Future<T> future = delegate.submit(track(task), result);
        notifyListeners();
        return future;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrap(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout,
                                         TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrap(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrap(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout,
                           TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrap(tasks), timeout, unit);
    }

    public Stats stats() {
        return new Stats(
                submitted.get(),
                running.get(),
                completed.get()
        );
    }

    public AutoCloseable addListener(Consumer<Stats> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        listener.accept(stats());
        return () -> listeners.remove(listener);
    }

    private Runnable track(Runnable runnable) {
        return () -> {
            running.incrementAndGet();
            notifyListeners();
            try {
                runnable.run();
            } finally {
                running.decrementAndGet();
                completed.incrementAndGet();
                notifyListeners();
            }
        };
    }

    private <T> Callable<T> track(Callable<T> callable) {
        return () -> {
            running.incrementAndGet();
            notifyListeners();
            try {
                return callable.call();
            } finally {
                running.decrementAndGet();
                completed.incrementAndGet();
                notifyListeners();
            }
        };
    }

    private <T> Collection<? extends Callable<T>> wrap(Collection<? extends Callable<T>> tasks) {
        return tasks.stream()
                .map(this::track)
                .toList();
    }

    private void notifyListeners() {
        Stats stats = stats();
        for (Consumer<Stats> listener : listeners) {
            listener.accept(stats);
        }
    }

    public record Stats(long submitted, long running, long completed) {
        public long pending() {
            long pending = submitted - completed - running;
            return Math.max(pending, 0);
        }
    }
}
