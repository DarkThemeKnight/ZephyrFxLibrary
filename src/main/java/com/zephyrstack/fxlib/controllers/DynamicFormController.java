package com.zephyrstack.fxlib.controllers;

import com.zephyrstack.fxlib.concurrent.FxExecutors;
import com.zephyrstack.fxlib.concurrent.FxFutures;
import com.zephyrstack.fxlib.concurrent.FxScheduler;
import com.zephyrstack.fxlib.core.TaskStatus;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Labeled;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Small utility controller that manages a collection of form fields, their async validation tasks,
 * and per-field error presentation. Call {@link #configureField(String, Supplier)} to register fields,
 * attach one or more {@link FieldTask}s, and trigger validations programmatically or via auto listeners.
 */
public final class DynamicFormController {
    private final Map<String, FieldHandle<?>> fields = new LinkedHashMap<>();
    private final ReadOnlyBooleanWrapper formValid = new ReadOnlyBooleanWrapper(this, "formValid", true);
    private final ReadOnlyObjectWrapper<FormResult> lastResult =
            new ReadOnlyObjectWrapper<>(this, "lastResult", FormResult.empty());

    /**
     * Begin configuring a field definition.
     */
    public <T> FormFieldBuilder<T> configureField(String fieldId, Supplier<T> valueSupplier) {
        Objects.requireNonNull(fieldId, "fieldId");
        Objects.requireNonNull(valueSupplier, "valueSupplier");
        return new FormFieldBuilder<>(fieldId, valueSupplier);
    }

    /**
     * Look up a registered field.
     */
    public Optional<FieldHandle<?>> findField(String fieldId) {
        Objects.requireNonNull(fieldId, "fieldId");
        return Optional.ofNullable(fields.get(fieldId));
    }

    /**
     * Remove a registered field and detach any listeners.
     */
    public boolean removeField(String fieldId) {
        Objects.requireNonNull(fieldId, "fieldId");
        FieldHandle<?> removed = fields.remove(fieldId);
        if (removed != null) {

            removed.dispose();
            recomputeFormValidity();
            updateResultSnapshot();
            return true;
        }
        return false;
    }

    /**
     * Validate a specific field. Returns the async result for further chaining.
     */
    public CompletableFuture<FieldResult<?>> validateField(String fieldId) {
        Objects.requireNonNull(fieldId, "fieldId");
        FieldHandle<?> handle = fields.get(fieldId);
        if (handle == null) {
            throw new IllegalArgumentException("Unknown field: " + fieldId);
        }
        return unsafeCast(handle.validate());
    }

    /**
     * Validate all registered fields concurrently.
     */
    public CompletableFuture<FormResult> validateAll() {
        if (fields.isEmpty()) {
            FormResult empty = FormResult.empty();
            lastResult.set(empty);
            formValid.set(true);
            return CompletableFuture.completedFuture(empty);
        }
        List<CompletableFuture<FieldResult<?>>> futures = fields.values().stream()
                .map(handle -> unsafeCast(handle.validate()))
                .toList();
        return FxFutures.all(futures).thenApply(results -> {
            LinkedHashMap<String, FieldResult<?>> snapshot = new LinkedHashMap<>();
            results.forEach(result -> snapshot.put(result.fieldId(), result));
            FormResult aggregate = new FormResult(snapshot);
            FxExecutors.fx().execute(() -> lastResult.set(aggregate));
            return aggregate;
        });
    }

    /**
     * Returns {@code true} when every registered field has completed validation and no task reported errors.
     */
    public boolean isFormValid() {
        return formValid.get();
    }

    /**
     * Read-only property that mirrors {@link #isFormValid()} for binding into UI controls.
     */
    public ReadOnlyBooleanProperty formValidProperty() {
        return formValid.getReadOnlyProperty();
    }

    /**
     * Snapshot of the latest aggregate validation result produced by {@link #validateAll()} or any field validation.
     */
    public FormResult getLastResult() {
        return lastResult.get();
    }

    /**
     * Property wrapper around {@link #getLastResult()} for observation in the UI.
     */
    public ReadOnlyObjectProperty<FormResult> lastResultProperty() {
        return lastResult.getReadOnlyProperty();
    }

    private <T> FieldHandle<T> registerField(FieldHandle<T> handle) {
        FieldHandle<?> existing = fields.putIfAbsent(handle.getId(), handle);
        if (existing != null) {
            handle.dispose();
            throw new IllegalArgumentException("Field already registered: " + handle.getId());
        }
        recomputeFormValidity();
        updateResultSnapshot();
        return handle;
    }

    private void recomputeFormValidity() {
        FxExecutors.fx().execute(() -> {
            if (fields.isEmpty()) {
                formValid.set(true);
                return;
            }
            boolean allValidated = fields.values().stream().allMatch(f -> f.getLastResult() != null);
            boolean allValid = allValidated && fields.values().stream()
                    .map(FieldHandle::getLastResult)
                    .filter(Objects::nonNull)
                    .allMatch(FieldResult::isValid);
            formValid.set(allValid);
        });
    }

    private void updateResultSnapshot() {
        FxExecutors.fx().execute(() -> {
            if (fields.isEmpty()) {
                lastResult.set(FormResult.empty());
                return;
            }
            LinkedHashMap<String, FieldResult<?>> snapshot = new LinkedHashMap<>();
            fields.forEach((id, handle) -> {
                FieldResult<?> result = handle.getLastResult();
                if (result != null) {
                    snapshot.put(id, result);
                }
            });
            lastResult.set(snapshot.isEmpty() ? FormResult.empty() : new FormResult(snapshot));
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletableFuture<FieldResult<?>> unsafeCast(CompletableFuture<FieldResult<T>> future) {
        return (CompletableFuture<FieldResult<?>>) (CompletableFuture<?>) future;
    }

    // ---------- Builder ----------

    /**
     * Fluent builder that wires a single form field into the controller, including the tasks that validate it
     * and any presentation hooks. Instances are short lived: configure, then call {@link #register()}.
     */
    public final class FormFieldBuilder<T> {
        private final String fieldId;
        private final Supplier<T> valueSupplier;
        private final List<FieldTask<T>> tasks = new ArrayList<>();
        private FieldErrorPresenter errorPresenter = FieldErrorPresenter.noop();
        private ObservableValue<?> autoTriggerSource;
        private long debounceMillis = 300;
        private boolean autoValidate;
        private boolean validateOnAttach;
        private Consumer<FieldResult<T>> resultListener;

        private FormFieldBuilder(String fieldId, Supplier<T> valueSupplier) {
            this.fieldId = fieldId;
            this.valueSupplier = valueSupplier;
        }

        /**
         * Adds a validation task that runs after previously declared tasks for this field.
         */
        public FormFieldBuilder<T> withTask(FieldTask<T> task) {
            Objects.requireNonNull(task, "task");
            tasks.add(task);
            return this;
        }

        /**
         * Adds multiple validation tasks in the order provided by the collection.
         */
        public FormFieldBuilder<T> withTasks(Collection<? extends FieldTask<T>> taskCollection) {
            Objects.requireNonNull(taskCollection, "taskCollection");
            taskCollection.forEach(this::withTask);
            return this;
        }

        /**
         * Sets the presenter responsible for surfacing validation errors to the UI (labels, popovers, etc).
         */
        public FormFieldBuilder<T> displayErrorsWith(FieldErrorPresenter presenter) {
            Objects.requireNonNull(presenter, "presenter");
            this.errorPresenter = presenter;
            return this;
        }

        /**
         * Registers a listener invoked with each validation result, regardless of outcome.
         */
        public FormFieldBuilder<T> onResult(Consumer<FieldResult<T>> listener) {
            this.resultListener = listener;
            return this;
        }

        /**
         * Enables automatic validation triggered by the supplied observable, using a default debounce window.
         */
        public FormFieldBuilder<T> autoValidateOn(ObservableValue<?> triggerSource) {
            return autoValidateOn(triggerSource, Duration.ofMillis(300));
        }

        /**
         * Enables automatic validation triggered by {@code triggerSource} using the supplied debounce duration.
         */
        public FormFieldBuilder<T> autoValidateOn(ObservableValue<?> triggerSource, Duration debounce) {
            Objects.requireNonNull(triggerSource, "triggerSource");
            Objects.requireNonNull(debounce, "debounce");
            this.autoTriggerSource = triggerSource;
            this.debounceMillis = Math.max(0, debounce.toMillis());
            this.autoValidate = true;
            return this;
        }

        /**
         * Performs an initial validation immediately after {@link #register()} completes.
         */
        public FormFieldBuilder<T> validateOnAttach() {
            this.validateOnAttach = true;
            return this;
        }

        /**
         * Finalizes the configuration, wires the field into the controller, and optionally triggers validation.
         */
        public FieldHandle<T> register() {
            FieldHandle<T> handle = new FieldHandle<>(
                    fieldId,
                    valueSupplier,
                    tasks.isEmpty() ? List.of() : List.copyOf(tasks),
                    errorPresenter == null ? FieldErrorPresenter.noop() : errorPresenter,
                    resultListener,
                    autoValidate ? autoTriggerSource : null,
                    autoValidate ? debounceMillis : 0
            );
            registerField(handle);
            if (validateOnAttach) {
                handle.validate();
            }
            return handle;
        }
    }

    // ---------- Field handle ----------

    /**
     * Runtime handle for an individual form field. Exposes imperative validation methods and observable state
     * (result, status, validity) that can be bound directly to UI components.
     */
    public final class FieldHandle<T> {
        private final String id;
        private final Supplier<T> valueSupplier;
        private final List<FieldTask<T>> tasks;
        private final FieldErrorPresenter errorPresenter;
        private final Consumer<FieldResult<T>> resultListener;
        private final ObservableValue<?> autoTriggerSource;
        private final long debounceMillis;
        private final AtomicLong requestSequence = new AtomicLong();
        private final AtomicLong appliedSequence = new AtomicLong();
        private final ReadOnlyObjectWrapper<FieldResult<T>> lastResult =
                new ReadOnlyObjectWrapper<>(this, "lastResult", null);
        private final ReadOnlyObjectWrapper<TaskStatus> status =
                new ReadOnlyObjectWrapper<>(this, "status", TaskStatus.PENDING);
        private final ReadOnlyBooleanWrapper valid =
                new ReadOnlyBooleanWrapper(this, "valid", false);
        private final ChangeListener<Object> autoListener;

        private FieldHandle(String id,
                            Supplier<T> valueSupplier,
                            List<FieldTask<T>> tasks,
                            FieldErrorPresenter errorPresenter,
                            Consumer<FieldResult<T>> resultListener,
                            ObservableValue<?> autoTriggerSource,
                            long debounceMillis) {
            this.id = id;
            this.valueSupplier = valueSupplier;
            this.tasks = tasks;
            this.errorPresenter = errorPresenter;
            this.resultListener = resultListener;
            this.autoTriggerSource = autoTriggerSource;
            this.debounceMillis = debounceMillis;
            this.autoListener = setupAutoValidation(autoTriggerSource, debounceMillis);
        }

        public String getId() {
            return id;
        }

        /**
         * Executes the configured validation task chain asynchronously and updates observable state on completion.
         */
        public CompletableFuture<FieldResult<T>> validate() {
            long ticket = requestSequence.incrementAndGet();
            updateStatus(TaskStatus.RUNNING);
            T snapshot = valueSupplier.get();
            FieldTaskContext<T> context = new FieldTaskContext<>(id, snapshot);
            CompletableFuture<List<FieldTaskResult>> chain =
                    CompletableFuture.completedFuture(new ArrayList<>());

            for (FieldTask<T> task : tasks) {
                chain = chain.thenCompose(results ->
                        executeTask(task, context).thenApply(result -> {
                            results.add(result);
                            return results;
                        }));
            }

            CompletableFuture<FieldResult<T>> future = chain.thenApply(results ->
                    new FieldResult<>(id, snapshot, results));

            return future.handle((result, throwable) -> {
                FieldResult<T> finalResult = throwable == null
                        ? result
                        : FieldResult.failure(id, snapshot, throwable);
                applyResult(ticket, finalResult);
                return finalResult;
            });
        }

        /**
         * Returns the last completed validation result or {@code null} if the field never validated.
         */
        public FieldResult<T> getLastResult() {
            return lastResult.get();
        }

        /**
         * Property accessor for observing {@link #getLastResult()} changes.
         */
        public ReadOnlyObjectProperty<FieldResult<T>> lastResultProperty() {
            return lastResult.getReadOnlyProperty();
        }

        /**
         * Current execution status of the validation pipeline.
         */
        public TaskStatus getStatus() {
            return status.get();
        }

        /**
         * Observable wrapper around {@link #getStatus()}.
         */
        public ReadOnlyObjectProperty<TaskStatus> statusProperty() {
            return status.getReadOnlyProperty();
        }

        /**
         * Convenience flag that mirrors {@link FieldResult#isValid()} for the last result.
         */
        public boolean isValid() {
            return valid.get();
        }

        /**
         * Property view of {@link #isValid()} to support binding expressions.
         */
        public ReadOnlyBooleanProperty validProperty() {
            return valid.getReadOnlyProperty();
        }

        private void updateStatus(TaskStatus newStatus) {
            FxExecutors.fx().execute(() -> status.set(newStatus));
        }

        private CompletableFuture<FieldTaskResult> executeTask(FieldTask<T> task, FieldTaskContext<T> context) {
            try {
                CompletionStage<FieldTaskResult> stage = task.run(context);
                if (stage == null) {
                    return CompletableFuture.completedFuture(
                            FieldTaskResult.error(task.name(), "Task returned null CompletionStage"));
                }
                return stage.handle((result, throwable) -> {
                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        return FieldTaskResult.error(task.name(), messageFrom(cause));
                    }
                    FieldTaskResult normalized = result == null
                            ? FieldTaskResult.error(task.name(), "Task returned null result")
                            : result.ensureTaskName(task.name());
                    return normalized;
                }).toCompletableFuture();
            } catch (Throwable ex) {
                Throwable cause = unwrap(ex);
                return CompletableFuture.completedFuture(
                        FieldTaskResult.error(task.name(), messageFrom(cause)));
            }
        }

        private void applyResult(long ticket, FieldResult<T> result) {
            for (; ; ) {
                long current = appliedSequence.get();
                if (ticket < current) {
                    return;
                }
                if (appliedSequence.compareAndSet(current, ticket)) {
                    break;
                }
            }

            FxExecutors.fx().execute(() -> {
                lastResult.set(result);
                valid.set(result.isValid());
                TaskStatus newStatus = result.isValid() ? TaskStatus.SUCCEEDED : TaskStatus.FAILED;
                status.set(newStatus);
                errorPresenter.present(result);
                if (resultListener != null) {
                    resultListener.accept(result);
                }
                recomputeFormValidity();
                updateResultSnapshot();
            });
        }

        private ChangeListener<Object> setupAutoValidation(ObservableValue<?> triggerSource,
                                                           long debounce) {
            if (triggerSource == null) {
                return null;
            }
            Runnable runner = debounce > 0
                    ? FxScheduler.debounce(debounce, this::triggerAutoValidation)
                    : this::triggerAutoValidation;
            ChangeListener<Object> listener = (obs, old, value) -> runner.run();
            FxExecutors.fx().execute(() -> triggerSource.addListener(listener));
            return listener;
        }

        private void triggerAutoValidation() {
            validate();
        }

        /**
         * Detaches listeners and frees FX resources. Safe to call multiple times.
         */
        public void dispose() {
            if (autoTriggerSource != null && autoListener != null) {
                FxExecutors.fx().execute(() -> autoTriggerSource.removeListener(autoListener));
            }
        }
    }

    // ---------- Results ----------

    /**
     * Immutable snapshot describing the outcome of validating a single field, including the evaluated value
     * and every task result in the order executed.
     */
    public static final class FieldResult<T> {
        private final String fieldId;
        private final T valueSnapshot;
        private final List<FieldTaskResult> outcomes;

        private FieldResult(String fieldId, T valueSnapshot, List<FieldTaskResult> outcomes) {
            this.fieldId = fieldId;
            this.valueSnapshot = valueSnapshot;
            this.outcomes = List.copyOf(outcomes);
        }

        /**
         * Logical identifier supplied when the field was registered.
         */
        public String fieldId() {
            return fieldId;
        }

        /**
         * Value captured from the supplier at the time validation started.
         */
        public T value() {
            return valueSnapshot;
        }

        /**
         * Ordered list of task-level outcomes; never {@code null}.
         */
        public List<FieldTaskResult> outcomes() {
            return outcomes;
        }

        /**
         * Convenience check that returns {@code true} when no task produced an error.
         */
        public boolean isValid() {
            return outcomes.stream().noneMatch(FieldTaskResult::isError);
        }

        /**
         * Inverse of {@link #isValid()} expressed as a reader-friendly predicate.
         */
        public boolean hasErrors() {
            return outcomes.stream().anyMatch(FieldTaskResult::isError);
        }

        /**
         * Shortcut to the first erroneous task result, if any.
         */
        public Optional<FieldTaskResult> firstError() {
            return outcomes.stream().filter(FieldTaskResult::isError).findFirst();
        }

        /**
         * Extracts the message component from {@link #firstError()}.
         */
        public Optional<String> firstErrorMessage() {
            return firstError().map(FieldTaskResult::message);
        }

        @Override
        public String toString() {
            return "FieldResult{" +
                    "fieldId='" + fieldId + '\'' +
                    ", value=" + valueSnapshot +
                    ", outcomes=" + outcomes +
                    '}';
        }

        static <T> FieldResult<T> failure(String fieldId, T snapshot, Throwable throwable) {
            Throwable cause = unwrap(throwable);
            String message = messageFrom(cause);
            return new FieldResult<>(fieldId, snapshot,
                    List.of(FieldTaskResult.error("field-task", message)));
        }
    }

    /**
     * Aggregate view of the most recent validation results for all registered fields.
     */
    public static final class FormResult {
        private final Map<String, FieldResult<?>> fieldResults;

        private FormResult(Map<String, FieldResult<?>> fieldResults) {
            this.fieldResults = Map.copyOf(fieldResults);
        }

        /**
         * Returns a map keyed by field id containing the most recent {@link FieldResult} for each registered field.
         */
        public Map<String, FieldResult<?>> fieldResults() {
            return fieldResults;
        }

        /**
         * Indicates that every field result in the snapshot is valid (or no results exist).
         */
        public boolean isValid() {
            return fieldResults.isEmpty() || fieldResults.values().stream().allMatch(FieldResult::isValid);
        }

        /**
         * Returns the first field that reported an error, respecting the registration order preserved by the map.
         */
        public Optional<FieldResult<?>> firstError() {
            return fieldResults.values().stream()
                    .filter(FieldResult::hasErrors)
                    .findFirst();
        }

        /**
         * Returns the canonical empty result (no registered fields or no validations yet).
         */
        public static FormResult empty() {
            return new FormResult(Map.of());
        }

        @Override
        public String toString() {
            return "FormResult{" +
                    "fieldResults=" + fieldResults +
                    '}';
        }
    }

    // ---------- Tasks ----------

    /**
     * Building block that validates a field value and returns an async {@link FieldTaskResult}. Tasks can represent
     * simple synchronous checks or asynchronous work such as HTTP calls.
     */
    @FunctionalInterface
    public interface FieldTask<T> {
        /**
         * Executes the task logic against the supplied context and produces a {@link FieldTaskResult}.
         */
        CompletionStage<FieldTaskResult> run(FieldTaskContext<T> context);

        /**
         * Optional human-readable name used to label the result; defaults to {@code field-task}.
         */
        default String name() {
            return "field-task";
        }

        /**
         * Convenience helper for synchronous validations that immediately resolve to a {@link FieldTaskResult}.
         */
        static <T> FieldTask<T> sync(String name, Function<T, FieldTaskResult> validator) {
            Objects.requireNonNull(validator, "validator");
            String taskName = name == null ? "field-task" : name;
            return new FieldTask<>() {
                @Override
                public CompletionStage<FieldTaskResult> run(FieldTaskContext<T> context) {
                    FieldTaskResult result = validator.apply(context.value());
                    FieldTaskResult normalized = result == null
                            ? FieldTaskResult.error(taskName, "Validator returned null result")
                            : result.ensureTaskName(taskName);
                    return CompletableFuture.completedFuture(normalized);
                }

                @Override
                public String name() {
                    return taskName;
                }
            };
        }

        /**
         * Convenience helper for asynchronous validations that may perform I/O or long-running work.
         */
        static <T> FieldTask<T> async(String name,
                                      Function<T, CompletionStage<FieldTaskResult>> validator) {
            Objects.requireNonNull(validator, "validator");
            String taskName = name == null ? "field-task" : name;
            return new FieldTask<>() {
                @Override
                public CompletionStage<FieldTaskResult> run(FieldTaskContext<T> context) {
                    CompletionStage<FieldTaskResult> stage = validator.apply(context.value());
                    if (stage == null) {
                        return CompletableFuture.completedFuture(
                                FieldTaskResult.error(taskName, "Async validator returned null CompletionStage"));
                    }
                    return stage.thenApply(result -> result == null
                            ? FieldTaskResult.error(taskName, "Async validator returned null result")
                            : result.ensureTaskName(taskName));
                }

                @Override
                public String name() {
                    return taskName;
                }
            };
        }

        /**
         * Declares a predicate-based rule that emits an error message when {@code predicate} fails.
         */
        static <T> FieldTask<T> require(String name, Predicate<T> predicate, String errorMessage) {
            Objects.requireNonNull(predicate, "predicate");
            String message = errorMessage == null || errorMessage.isBlank()
                    ? "Value is required"
                    : errorMessage;
            String taskName = name == null ? "required" : name;
            return sync(taskName, value -> predicate.test(value)
                    ? FieldTaskResult.ok(taskName)
                    : FieldTaskResult.error(taskName, message));
        }


        /**
         * Common helper for free-form text inputs that must contain non-blank content.
         */
        static FieldTask<String> requireNonBlank(String errorMessage) {
            return require("required", value -> value != null && !value.isBlank(), errorMessage);
        }
    }

    /**
     * Lightweight wrapper that passes the field id and captured value into a {@link FieldTask}.
     */
    public record FieldTaskContext<T>(String fieldId, T value) {
        public FieldTaskContext {
            Objects.requireNonNull(fieldId, "fieldId");
        }
    }

    /**
     * Immutable outcome for a single {@link FieldTask}, including severity and a user-facing message.
     */
    public record FieldTaskResult(String taskName, Severity severity, String message) {
        public enum Severity {
            OK,
            INFO,
            WARNING,
            ERROR
        }

        public FieldTaskResult {
            if (severity == null) {
                severity = Severity.OK;
            }
        }

        /**
         * Creates a successful result without a message payload.
         */
        public static FieldTaskResult ok(String taskName) {
            return new FieldTaskResult(taskName, Severity.OK, "");
        }

        /**
         * Creates an informational result. Useful when surfacing hints instead of blocking submission.
         */
        public static FieldTaskResult info(String taskName, String message) {
            return new FieldTaskResult(taskName, Severity.INFO, normalize(message));
        }

        /**
         * Creates a warning-level result.
         */
        public static FieldTaskResult warning(String taskName, String message) {
            return new FieldTaskResult(taskName, Severity.WARNING, normalize(message));
        }

        /**
         * Creates an error-level result which marks the parent field as invalid.
         */
        public static FieldTaskResult error(String taskName, String message) {
            return new FieldTaskResult(taskName, Severity.ERROR, normalize(message));
        }

        /**
         * Utility flag that reports whether this result represents an error.
         */
        public boolean isError() {
            return severity == Severity.ERROR;
        }

        private FieldTaskResult ensureTaskName(String fallback) {
            if (taskName != null && !taskName.isBlank()) {
                return this;
            }
            return new FieldTaskResult(fallback, severity, message);
        }

        private static String normalize(String message) {
            return message == null ? "" : message;
        }

        @Override
        public String toString() {
            return "FieldTaskResult{" +
                    "taskName='" + taskName + '\'' +
                    ", severity=" + severity +
                    ", message='" + message + '\'' +
                    '}';
        }
    }

    // ---------- Error presentation ----------

    /**
     * Hook for surfacing validation outcomes to the UI (labels, decorators, notifications, etc).
     */
    @FunctionalInterface
    public interface FieldErrorPresenter {
        /**
         * Process the supplied {@link FieldResult}, typically by toggling visual indicators on a control.
         */
        void present(FieldResult<?> result);

        /**
         * Returns a no-op presenter that ignores all results.
         */
        static FieldErrorPresenter noop() {
            return result -> {
            };
        }

        /**
         * Convenience presenter that writes the first error message into a {@link Labeled} control
         * and toggles its managed/visible state.
         */
        static FieldErrorPresenter forLabel(Labeled labeled) {
            Objects.requireNonNull(labeled, "labeled");
            return result -> {
                boolean hasError = result.hasErrors();
                String message = result.firstErrorMessage().orElse("");
                labeled.setText(message);
                labeled.setManaged(hasError);
                labeled.setVisible(hasError);
            };
        }

        /**
         * Composes two presenters so both receive the same {@link FieldResult} events.
         */
        default FieldErrorPresenter andThen(FieldErrorPresenter other) {
            Objects.requireNonNull(other, "other");
            return result -> {
                present(result);
                other.present(result);
            };
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException
                && completionException.getCause() != null) {
            return completionException.getCause();
        }
        if (throwable instanceof CancellationException cancellationException
                && cancellationException.getCause() != null) {
            return cancellationException.getCause();
        }
        return throwable;
    }

    private static String messageFrom(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getMessage();
        return (message == null || message.isBlank())
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
