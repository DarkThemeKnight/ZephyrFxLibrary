package com.zephyrstack.fxlib.concurrent;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Helper around {@link java.util.concurrent.CompletableFuture} to standardize FX-thread delivery,
 * combination, and timeout handling.
 */
public final class FxFutures {
    private FxFutures() {}

    public static <T> CompletableFuture<T> supply(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return CompletableFuture.supplyAsync(work);
    }

    public static <T> CompletableFuture<T> supply(Supplier<T> work, Executor executor) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.supplyAsync(work, executor);
    }

    /**
     * Run background work and deliver results/errors on the FX thread.
     */
    public static <T> CompletableFuture<T> run(Supplier<T> work,
                                               Consumer<T> onFxSuccess,
                                               Consumer<Throwable> onFxError) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> future = supply(work);
        deliver(future, onFxSuccess, onFxError);
        return future;
    }

    /**
     * Ensure success/error callbacks happen on the FX thread.
     */
    public static <T> void deliver(CompletableFuture<T> future,
                                   Consumer<T> onFxSuccess,
                                   Consumer<Throwable> onFxError) {
        future.thenAcceptAsync(result -> {
            if (onFxSuccess != null) onFxSuccess.accept(result);
        }, FxExecutors.fx());
        future.exceptionally(ex -> {
            if (onFxError != null) {
                FxExecutors.fx().execute(() -> onFxError.accept(unwrap(ex)));
            }
            return null;
        });
    }

    /**
     * Attach a timeout to an existing future. On timeout the future completes exceptionally.
     */
    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, Duration timeout) {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(timeout, "timeout");
        long millis = timeout.toMillis();
        if (millis <= 0) return future;

        CompletableFuture<T> timeoutFuture = new CompletableFuture<>();
        ScheduledFuture<?> scheduled = FxExecutors.scheduler().schedule(() -> {
            timeoutFuture.completeExceptionally(new TimeoutException("Operation timed out after " + timeout));
        }, millis, TimeUnit.MILLISECONDS);

        future.whenComplete((result, throwable) -> scheduled.cancel(false));
        return future.applyToEither(timeoutFuture, Function.identity());
    }

    /**
     * Convert collection of futures into one that completes when all succeed.
     */
    public static <T> CompletableFuture<List<T>> all(Collection<? extends CompletableFuture<? extends T>> futures) {
        Objects.requireNonNull(futures, "futures");
        CompletableFuture<?>[] arr = futures.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(arr)
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .map(value -> (T) value)
                        .toList());
    }

    /**
     * Shortcut for chaining additional async work on the FX thread without blocking.
     */
    public static <T, R> CompletableFuture<R> thenOnFx(CompletableFuture<T> future,
                                                       Function<T, R> fxMapper) {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(fxMapper, "fxMapper");
        return future.thenApplyAsync(fxMapper, FxExecutors.fx());
    }

    private static Throwable unwrap(Throwable ex) {
        if (ex instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return ex;
    }
}
