package com.zephyrstack.fxlib.core;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Higher-level wrapper around {@link java.util.concurrent.CompletableFuture} that tracks status and
 * marshals callbacks back onto the FX thread.
 */
public final class FxTaskRunner {
    private final ZephyrFxApplicationContext ctx;

    public FxTaskRunner() {
        this(ZephyrFxApplicationContext.getInstance());
    }

    public FxTaskRunner(ZephyrFxApplicationContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    public <T> FxTask<T> submit(String name, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return new FxTask<>(name, work);
    }

    public <T> FxTask<T> submit(Supplier<T> work) {
        return submit("fx-task", work);
    }

    public final class FxTask<T> {
        private final String name;
        private final ObjectProperty<TaskStatus> status = new SimpleObjectProperty<>(TaskStatus.PENDING);
        private final CompletableFuture<T> future;

        private FxTask(String name, Supplier<T> work) {
            this.name = name == null ? "fx-task" : name;
            status.set(TaskStatus.RUNNING);
            future = CompletableFuture.supplyAsync(work, ctx.getBackgroundExecutor());
            future.whenComplete((result, throwable) -> {
                if (throwable == null) {
                    updateStatus(TaskStatus.SUCCEEDED);
                } else if (throwable instanceof CancellationException) {
                    updateStatus(TaskStatus.CANCELLED);
                } else {
                    updateStatus(TaskStatus.FAILED);
                }
            });
        }

        public String name() {
            return name;
        }

        public ReadOnlyObjectProperty<TaskStatus> statusProperty() {
            return status;
        }

        public TaskStatus getStatus() {
            return status.get();
        }

        public CompletableFuture<T> future() {
            return future;
        }

        public FxTask<T> onSuccess(Consumer<T> consumer) {
            future.thenAcceptAsync(result -> {
                if (consumer != null) consumer.accept(result);
            }, ZephyrFxApplicationContext.fxExecutor());
            return this;
        }

        public FxTask<T> onFailure(Consumer<Throwable> consumer) {
            future.whenCompleteAsync((result, throwable) -> {
                if (throwable != null && !(throwable instanceof CancellationException)) {
                    if (consumer != null) consumer.accept(unwrap(throwable));
                }
            }, ZephyrFxApplicationContext.fxExecutor());
            return this;
        }

        public FxTask<T> onFinished(Runnable runnable) {
            future.whenCompleteAsync((result, throwable) -> {
                if (runnable != null) runnable.run();
            }, ZephyrFxApplicationContext.fxExecutor());
            return this;
        }

        public boolean cancel() {
            boolean cancelled = future.cancel(true);
            if (cancelled) {
                updateStatus(TaskStatus.CANCELLED);
            }
            return cancelled;
        }

        private void updateStatus(TaskStatus newStatus) {
            ctx.runOnFxThread(() -> status.set(newStatus));
        }

        private Throwable unwrap(Throwable throwable) {
            if (throwable.getCause() != null) return throwable.getCause();
            return throwable;
        }
    }
}
