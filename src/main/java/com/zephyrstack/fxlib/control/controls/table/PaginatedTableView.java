package com.zephyrstack.fxlib.control.controls.table;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Table wrapper with built-in pagination, search query, and async data loading hooks.
 *
 * @param <T> row type
 */
public final class PaginatedTableView<T> extends BorderPane {
    @FunctionalInterface
    public interface DataProvider<T> {
        CompletableFuture<PagedResult<T>> fetch(int page, int size, String query);
    }

    public record PagedResult<R>(List<R> items, long total) {
        public PagedResult(List<R> items, long total) {
            this.items = items == null ? List.of() : List.copyOf(items);
            this.total = Math.max(0, total);
        }
    }

    private static final List<Integer> DEFAULT_PAGE_SIZES = List.of(10, 25, 50);

    private final TableView<T> tableView = new TableView<>();
    private final ComboBox<Integer> pageSizeBox = new ComboBox<>();
    private final Button prevButton = new Button("Previous");
    private final Button nextButton = new Button("Next");
    private final Label summaryLabel = new Label("Ready");
    private final TextField queryField = new TextField();
    private final Label placeholderLabel = new Label("No data");
    private final ProgressIndicator loadingIndicator = new ProgressIndicator();
    private final StackPane tableContainer = new StackPane();
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty hasNextPage = new SimpleBooleanProperty(false);

    private final StringProperty query = new SimpleStringProperty(this, "query", "");
    private final IntegerProperty pageIndex = new SimpleIntegerProperty(this, "pageIndex", 0);
    private final IntegerProperty pageSize = new SimpleIntegerProperty(this, "pageSize", DEFAULT_PAGE_SIZES.get(0));

    private final PauseTransition queryDebounce = new PauseTransition(Duration.millis(350));
    private final AtomicLong requestCounter = new AtomicLong();
    private volatile long newestRequestId = 0;
    private DataProvider<T> dataProvider;

    public PaginatedTableView() {
        initializeLayout();
        initializeBehavior();
    }

    private void initializeLayout() {
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tableView.setPlaceholder(placeholderLabel);
        tableView.setFocusTraversable(false);

        loadingIndicator.setMaxSize(42, 42);
        loadingIndicator.visibleProperty().bind(loading);
        loadingIndicator.managedProperty().bind(loadingIndicator.visibleProperty());
        loadingIndicator.setMouseTransparent(true);
        StackPane.setAlignment(loadingIndicator, Pos.CENTER);

        tableContainer.getChildren().addAll(tableView, loadingIndicator);
        setCenter(tableContainer);

        setTop(buildTopBar());
        setBottom(buildBottomBar());

        setPadding(new Insets(8));
        setPageSizeOptions(DEFAULT_PAGE_SIZES.stream().mapToInt(Integer::intValue).toArray());
    }

    private HBox buildTopBar() {
        Label searchLabel = new Label("Search");
        searchLabel.getStyleClass().add("paginated-table-search-label");

        queryField.setPromptText("Search…");
        queryField.textProperty().bindBidirectional(query);
        queryField.setOnKeyPressed(evt -> {
            if (evt.getCode() == KeyCode.ENTER) {
                triggerQueryRefresh();
            }
        });

        Button searchButton = new Button("Search");
        searchButton.setOnAction(e -> triggerQueryRefresh());

        HBox top = new HBox(8, searchLabel, queryField, searchButton);
        HBox.setHgrow(queryField, Priority.ALWAYS);
        top.setPadding(new Insets(8));
        top.setAlignment(Pos.CENTER_LEFT);
        return top;
    }

    private HBox buildBottomBar() {
        prevButton.setOnAction(e -> goToPage(pageIndex.get() - 1));
        nextButton.setOnAction(e -> goToPage(pageIndex.get() + 1));

        pageSizeBox.setPrefWidth(90);
        pageSizeBox.valueProperty().addListener((obs, old, value) -> {
            if (value != null && value > 0) {
                if (pageSize.get() != value) {
                    pageSize.set(value);
                } else {
                    triggerPageReset();
                }
            }
        });

        Label pageSizeLabel = new Label("Rows per page");
        HBox controls = new HBox(12,
                prevButton,
                nextButton,
                new Separator(),
                pageSizeLabel,
                pageSizeBox,
                new Separator(),
                summaryLabel);
        controls.setPadding(new Insets(8));
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(summaryLabel, Priority.ALWAYS);
        summaryLabel.setMaxWidth(Double.MAX_VALUE);
        return controls;
    }

    private void initializeBehavior() {
        queryDebounce.setOnFinished(e -> triggerPageReset());

        query.addListener((obs, old, value) -> {
            if (value == null && old == null) return;
            queryDebounce.playFromStart();
        });

        pageIndex.addListener((obs, old, value) -> {
            if (Objects.equals(old, value)) return;
            refresh();
        });

        pageSize.addListener((obs, old, value) -> {
            if (Objects.equals(old, value) || value == null) return;
            triggerPageReset();
        });

        prevButton.disableProperty().bind(loading.or(pageIndex.lessThanOrEqualTo(0)));
        nextButton.disableProperty().bind(loading.or(hasNextPage.not()));
    }

    private void triggerPageReset() {
        if (pageIndex.get() == 0) {
            refresh();
        } else {
            pageIndex.set(0);
        }
    }

    private void goToPage(int nextPage) {
        if (nextPage < 0) {
            return;
        }
        pageIndex.set(nextPage);
    }

    private void triggerQueryRefresh() {
        queryDebounce.stop();
        triggerPageReset();
    }

    public TableView<T> table() {
        return tableView;
    }

    public void setDataProvider(DataProvider<T> provider) {
        this.dataProvider = provider;
        triggerPageReset();
    }

    public void setPageSizeOptions(int... sizes) {
        int[] sanitized = (sizes == null || sizes.length == 0)
                ? DEFAULT_PAGE_SIZES.stream().mapToInt(Integer::intValue).toArray()
                : Arrays.stream(sizes).filter(v -> v > 0).distinct().sorted().toArray();
        if (sanitized.length == 0) {
            sanitized = DEFAULT_PAGE_SIZES.stream().mapToInt(Integer::intValue).toArray();
        }
        ObservableList<Integer> options = FXCollections.observableArrayList(Arrays.stream(sanitized).boxed().collect(Collectors.toList()));
        pageSizeBox.setItems(options);
        int desired = pageSize.get();
        if (!options.contains(desired)) {
            desired = options.get(0);
            pageSize.set(desired);
        }
        pageSizeBox.getSelectionModel().select(Integer.valueOf(desired));
    }

    public void setQueryPrompt(String text) {
        queryField.setPromptText(text);
    }

    public void refresh() {
        if (dataProvider == null) {
            summaryLabel.setText("Data provider not configured.");
            return;
        }
        final int size = Math.max(1, pageSize.get());
        final int page = Math.max(0, pageIndex.get());
        final String q = query.get() == null ? "" : query.get().trim();

        loading.set(true);
        placeholderLabel.setText("Loading…");

        long requestId = requestCounter.incrementAndGet();
        newestRequestId = requestId;

        CompletableFuture<PagedResult<T>> future;
        try {
            future = Objects.requireNonNull(dataProvider.fetch(page, size, q),
                    "DataProvider returned null future");
        } catch (Exception ex) {
            handleFailure(requestId, ex);
            return;
        }

        future.whenComplete((result, throwable) -> Platform.runLater(() -> {
            if (requestId != newestRequestId) return;
            if (throwable != null) {
                handleFailure(requestId, throwable);
            } else {
                handleSuccess(result, page, size);
            }
        }));
    }

    private void handleFailure(long requestId, Throwable throwable) {
        if (requestId != newestRequestId) return;
        loading.set(false);
        tableView.getItems().clear();
        placeholderLabel.setText("Failed to load data: " + shortMessage(throwable));
        summaryLabel.setText("Unable to load data.");
        hasNextPage.set(false);
    }

    private void handleSuccess(PagedResult<T> result, int page, int size) {
        List<T> items = result == null ? List.of() : result.items;
        long total = result == null ? items.size() : Math.max(result.total, items.size());
        if (items.isEmpty() && total > 0 && page > 0) {
            int lastPage = (int) Math.max(0, (total - 1) / size);
            if (lastPage != page) {
                pageIndex.set(lastPage);
                return;
            }
        }

        tableView.getItems().setAll(items);

        loading.set(false);
        placeholderLabel.setText("No data");

        long start = items.isEmpty() ? 0 : (page * (long) size) + 1;
        long end = items.isEmpty() ? 0 : start + items.size() - 1;
        if (total == 0) {
            summaryLabel.setText("No results");
        } else {
            summaryLabel.setText("Showing %d–%d of %d".formatted(start, end, total));
        }

        boolean more = ((long) (page + 1) * size) < total;
        hasNextPage.set(more);
    }

    private String shortMessage(Throwable throwable) {
        Throwable root = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    public StringProperty queryProperty() {
        return query;
    }

    public IntegerProperty pageIndexProperty() {
        return pageIndex;
    }
}
