package com.zephyrstack.fxlib.controllers;

import com.zephyrstack.fxlib.control.controls.table.PaginatedTableView;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base controller for the reusable PaginatedTable.fxml layout.
 * Subclasses add columns and set a data provider; this class handles paging, async loading, and UX.
 * <p>
 * New: header action button, button bar, search with debounce, extra filter dropdown, per-page selector, totals label.
 */
public abstract class AbstractPaginatedTableController<T> {
    // ---------- New header/filters/footer fields ----------
    @FXML
    private Button tableAction;
    @FXML
    private FontIcon tableHeaderIcon;
    @FXML
    private ButtonBar buttonFilterBar;
    @FXML
    private Button filterOption;
    @FXML
    private TextField tableSearch;
    @FXML
    private ComboBox<?> filterDropDown;
    @FXML
    private Label displayTotals;
    @FXML
    private ComboBox<Integer> perPageFilter;

    // ---------- Existing FXML ----------
    @FXML
    protected BorderPane tableRoot;
    @FXML
    protected HBox headerHbox;
    @FXML
    protected Label tableTitle;
    @FXML
    protected TableView<T> tableView;
    @FXML
    protected HBox footerHbox;
    @FXML
    protected Pagination paginationControl;

    // ---------- Model/state ----------
    private final ObservableList<T> currentItems = FXCollections.observableArrayList();
    private final BooleanProperty loading = new SimpleBooleanProperty(this, "loading", false);
    private final IntegerProperty pageSize = new SimpleIntegerProperty(this, "pageSize", 25);
    private final LongProperty totalItems = new SimpleLongProperty(this, "totalItems", 0);
    private final StringProperty query = new SimpleStringProperty(this, "query", "");
    private final ObjectProperty<PaginatedTableView.DataProvider<T>> dataProvider =
            new SimpleObjectProperty<>(this, "dataProvider");
    private final BooleanProperty autoRefreshOnInitialize =
            new SimpleBooleanProperty(this, "autoRefreshOnInitialize", true);
    private final StringProperty title = new SimpleStringProperty(this, "tableTitle", "Table");

    private final ListProperty<Integer> pageSizeOptions =
            new SimpleListProperty<>(FXCollections.observableArrayList(10, 25, 50, 100));
    private final IntegerProperty searchDebounceMillis =
            new SimpleIntegerProperty(this, "searchDebounceMillis", 350);

    private final Label placeholderLabel = new Label("No data");
    private final AtomicLong requestCounter = new AtomicLong();
    private volatile long newestRequestId = 0;

    private PauseTransition searchDebounce; // created in initialize()

    // ---------- Lifecycle ----------
    @FXML
    private void initialize() {
        configureTitleBinding();
        setupTableView();
        setupPagination();
        setupHeaderAndFilters();
        setupFooterControls();

        // data provider readiness
        dataProvider.addListener((obs, old, provider) -> {
            if (provider != null && isAutoRefreshOnInitialize()) {
                resetToFirstPage();
            } else if (provider == null) {
                currentItems.clear();
                placeholderLabel.setText("Data provider not configured.");
            }
        });
        PaginatedTableView.DataProvider<T> existingProvider = dataProvider.get();
        if (existingProvider == null) {
            placeholderLabel.setText("Data provider not configured.");
        } else if (isAutoRefreshOnInitialize()) {
            resetToFirstPage();
        }

        onTableReady();
    }

    // ---------- Wiring ----------

    private void configureTitleBinding() {
        if (tableTitle == null) return;
        if (tableTitle.getText() != null && !tableTitle.getText().isBlank()) {
            title.set(tableTitle.getText());
        }
        tableTitle.textProperty().bind(title);
    }


    private void setupTableView() {
        Objects.requireNonNull(tableView, "tableView was not injected");
        tableView.setItems(currentItems);
        tableView.setPlaceholder(placeholderLabel);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        configureTable(tableView);
    }

    private void setupPagination() {
        Objects.requireNonNull(paginationControl, "paginationControl was not injected");
        paginationControl.setPageCount(1);
        paginationControl.setCurrentPageIndex(0);
        paginationControl.setMaxPageIndicatorCount(7);
        paginationControl.setPageFactory(this::handlePageRequest);

        pageSize.addListener((obs, old, size) -> {
            if (size == null || size.intValue() <= 0) {
                pageSize.set(25);
                return;
            }
            if (paginationControl.getCurrentPageIndex() == 0) {
                refresh();
            } else {
                paginationControl.setCurrentPageIndex(0);
            }
        });
    }

    private void setupHeaderAndFilters() {
        // Action button
        if (tableAction != null) {
            tableAction.setOnAction(e -> onActionButton());
        }

        // Search + debounce
        if (tableSearch != null) {
            if (tableSearch.getPromptText() == null || tableSearch.getPromptText().isBlank()) {
                tableSearch.setPromptText("Search");
            }
            searchDebounce = new PauseTransition(Duration.millis(searchDebounceMillis.get()));
            searchDebounceMillis.addListener((o, old, v) -> {
                if (searchDebounce != null) searchDebounce.setDuration(Duration.millis(Math.max(0, v.intValue())));
            });

            tableSearch.textProperty().addListener((o, old, text) -> {
                if (searchDebounce == null) return;
                searchDebounce.stop();
                searchDebounce.setOnFinished(ev -> setQuery(composeQuery(text)));
                searchDebounce.playFromStart();
            });
        }

        // Extra filter dropdown (subclasses can read its selection in composeQuery(...))
        if (filterDropDown != null) {
            filterDropDown.valueProperty().addListener((o, old, v) -> {
                // Any filter change triggers a new composed query and resets to first page
                String base = tableSearch != null ? tableSearch.getText() : query.get();
                setQuery(composeQuery(base));
            });
        }

        // ButtonBar exists for optional chips/buttons; no default wiring needed
        if (buttonFilterBar != null && filterOption != null) {
            filterOption.setOnAction(e -> onFilterBarButton(filterOption));
        }
    }

    private void setupFooterControls() {
        // Per-page
        if (perPageFilter != null) {
            perPageFilter.itemsProperty().bind(pageSizeOptions);
            if (!pageSizeOptions.isEmpty()) {
                int def = pageSize.get();
                if (!pageSizeOptions.contains(def)) pageSizeOptions.add(def);
                perPageFilter.getSelectionModel().select(Integer.valueOf(def));
            }
            perPageFilter.getSelectionModel().selectedItemProperty().addListener((o, old, v) -> {
                if (v != null) setPageSize(v);
            });
        }

        // Totals label is updated after each load; also react if totalItems changes externally
        totalItems.addListener((o, old, v) -> updateTotalsLabel(
                paginationControl.getCurrentPageIndex(),
                getPageSize(),
                currentItems.size(),
                v == null ? 0 : v.longValue()
        ));
    }

    // ---------- Paging / Async ----------

    private Node handlePageRequest(int pageIndex) {
        requestPage(pageIndex);
        Region spacer = new Region();
        spacer.setMinSize(0, 0);
        spacer.setPrefSize(0, 0);
        spacer.setMaxSize(0, 0);
        spacer.setManaged(false);
        return spacer;
    }

    private void requestPage(int pageIndex) {
        PaginatedTableView.DataProvider<T> provider = dataProvider.get();
        if (provider == null) {
            placeholderLabel.setText("Data provider not configured.");
            currentItems.clear();
            return;
        }
        int size = Math.max(1, pageSize.get());
        String q = query.get() == null ? "" : query.get().trim();

        loading.set(true);
        placeholderLabel.setText("Loading…");
        long requestId = requestCounter.incrementAndGet();
        newestRequestId = requestId;

        CompletableFuture<PaginatedTableView.PagedResult<T>> future;
        try {
            future = Objects.requireNonNull(provider.fetch(pageIndex, size, q),
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
                handleSuccess(result, pageIndex, size);
            }
        }));
    }

    private void handleFailure(long requestId, Throwable throwable) {
        if (requestId != newestRequestId) return;
        loading.set(false);
        currentItems.clear();
        placeholderLabel.setText("Failed to load data: " + shortMessage(throwable));
        updateTotalsLabel(0, getPageSize(), 0, 0);
        onLoadFailed(throwable);
    }

    private void handleSuccess(PaginatedTableView.PagedResult<T> result, int pageIndex, int size) {
        List<T> items = result == null ? List.of() : result.items();
        long total = result == null ? items.size() : Math.max(result.total(), items.size());
        if (items.isEmpty() && total > 0 && pageIndex > 0) {
            int lastPage = (int) Math.max(0, (total - 1) / size);
            if (lastPage != pageIndex) {
                paginationControl.setCurrentPageIndex(lastPage);
                return;
            }
        }

        loading.set(false);
        totalItems.set(total);
        placeholderLabel.setText(items.isEmpty() ? "No data" : "");
        currentItems.setAll(items);

        int pageCount = total == 0 ? 1 : (int) Math.ceil(total / (double) size);
        paginationControl.setPageCount(Math.max(pageCount, 1));
        paginationControl.setDisable(pageCount <= 1);

        updateTotalsLabel(pageIndex, size, items.size(), total);
        onPageLoaded(items, total, pageIndex);
    }

    private void updateTotalsLabel(int pageIndex, int size, int countOnPage, long total) {
        if (displayTotals == null) return;
        if (total <= 0 || countOnPage <= 0) {
            displayTotals.setText("0 items");
            return;
        }
        long start = (long) pageIndex * size + 1;
        long end = start + countOnPage - 1;
        displayTotals.setText(start + "–" + end + " of " + total);
    }

    private String shortMessage(Throwable throwable) {
        Throwable root = (throwable instanceof CompletionException && throwable.getCause() != null)
                ? throwable.getCause()
                : throwable;
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    // ---------- Hooks for subclasses ----------

    /**
     * Called when the controller is ready (after FXML wiring).
     */
    protected void onTableReady() {
    }

    /**
     * Configure table columns/cell factories.
     */
    protected void configureTable(TableView<T> table) {
    }

    /**
     * Called after a page has been loaded successfully.
     */
    protected void onPageLoaded(List<T> pageItems, long total, int pageIndex) {
    }

    /**
     * Called when the data provider fails.
     */
    protected void onLoadFailed(Throwable error) {
    }

    /**
     * Called when the header action button is clicked.
     */
    protected void onActionButton() {
    }

    /**
     * Called when a button inside the filter bar is clicked (single example button is exposed).
     */
    protected void onFilterBarButton(Button which) { /* no-op */ }

    /**
     * Compose the query sent to the DataProvider.
     * Default: trimmed search text. Subclasses can append filter selections.
     */
    protected String composeQuery(String rawSearchText) {
        return rawSearchText == null ? "" : rawSearchText.trim();
    }

    // ---------- Public API / accessors ----------

    public final TableView<T> getTableView() {
        return tableView;
    }

    public final Pagination getPaginationControl() {
        return paginationControl;
    }

    public final BorderPane getTableRoot() {
        return tableRoot;
    }

    public final ObservableList<T> displayedItems() {
        return FXCollections.unmodifiableObservableList(currentItems);
    }

    public final BooleanProperty loadingProperty() {
        return loading;
    }

    public final boolean isLoading() {
        return loading.get();
    }

    public final LongProperty totalItemsProperty() {
        return totalItems;
    }

    public final long getTotalItems() {
        return totalItems.get();
    }

    public final IntegerProperty pageSizeProperty() {
        return pageSize;
    }

    public final void setPageSize(int size) {
        pageSize.set(Math.max(1, size));
    }

    public final int getPageSize() {
        return pageSize.get();
    }

    public final StringProperty queryProperty() {
        return query;
    }

    public final void setQuery(String text) {
        String sanitized = text == null ? "" : text.trim();
        if (Objects.equals(query.get(), sanitized)) return;
        query.set(sanitized);
        resetToFirstPage();
    }

    public final String getQuery() {
        return query.get();
    }

    public final void setDataProvider(PaginatedTableView.DataProvider<T> provider) {
        dataProvider.set(provider);
    }

    public final PaginatedTableView.DataProvider<T> getDataProvider() {
        return dataProvider.get();
    }

    public final ObjectProperty<PaginatedTableView.DataProvider<T>> dataProviderProperty() {
        return dataProvider;
    }

    public final boolean isAutoRefreshOnInitialize() {
        return autoRefreshOnInitialize.get();
    }

    public final void setAutoRefreshOnInitialize(boolean auto) {
        autoRefreshOnInitialize.set(auto);
    }

    public final BooleanProperty autoRefreshOnInitializeProperty() {
        return autoRefreshOnInitialize;
    }

    public final void refresh() {
        if (paginationControl == null) {
            requestPage(0);
        } else {
            requestPage(paginationControl.getCurrentPageIndex());
        }
    }

    public final void resetToFirstPage() {
        if (paginationControl == null) {
            requestPage(0);
            return;
        }
        if (paginationControl.getCurrentPageIndex() == 0) {
            refresh();
        } else {
            paginationControl.setCurrentPageIndex(0);
        }
    }

    public final void setTableTitle(String text) {
        title.set(text == null ? "" : text);
    }

    public final String getTableTitle() {
        return title.get();
    }

    public final StringProperty tableTitleProperty() {
        return title;
    }

    // New helpers
    public final void setSearchPrompt(String prompt) {
        if (tableSearch != null) tableSearch.setPromptText(prompt);
    }

    public final IntegerProperty searchDebounceMillisProperty() {
        return searchDebounceMillis;
    }

    public final void setSearchDebounceMillis(int ms) {
        searchDebounceMillis.set(Math.max(0, ms));
    }

    public final ListProperty<Integer> pageSizeOptionsProperty() {
        return pageSizeOptions;
    }

    public final void setPageSizeOptions(List<Integer> opts) {
        pageSizeOptions.set(FXCollections.observableArrayList(opts));
        if (perPageFilter != null) perPageFilter.getSelectionModel().select(Integer.valueOf(getPageSize()));
    }

    public final TextField getTableSearchField() {
        return tableSearch;
    }

    public final ComboBox<?> getFilterDropDown() {
        return filterDropDown;
    }

    public final ButtonBar getButtonFilterBar() {
        return buttonFilterBar;
    }

    public final Button getTableActionButton() {
        return tableAction;
    }

    public final Label getDisplayTotalsLabel() {
        return displayTotals;
    }

    public final ComboBox<Integer> getPerPageFilter() {
        return perPageFilter;
    }
}
