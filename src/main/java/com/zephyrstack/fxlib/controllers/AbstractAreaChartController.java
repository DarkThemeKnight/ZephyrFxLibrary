package com.zephyrstack.fxlib.controllers;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Base controller that wires {@code charts/areaChart/Areachart.fxml} into a reusable, data-driven component.
 * Subclasses plug in a data provider, optionally override hooks, and focus on domain specifics.
 */
public abstract class AbstractAreaChartController {
    private static final String LOADING_STYLE = "loading";

    // ---------- FXML wiring ----------
    @FXML private VBox rootContainer;
    @FXML private HBox titleBar;
    @FXML private VBox titleBox;
    @FXML private Label lblTitle;
    @FXML private Label lblSubtitle;
    @FXML private HBox titleStats;
    @FXML private Label lblTotal;
    @FXML private Label lblPeak;

    @FXML private HBox filtersBar;
    @FXML private ComboBox<Object> cbPresetRange;
    @FXML private DatePicker dpFrom;
    @FXML private DatePicker dpTo;
    @FXML private TextField tfSearch;
    @FXML private ToggleButton tgStacked;
    @FXML private ToggleButton tgLogScale;
    @FXML private Button btnApply;
    @FXML private Button btnReset;

    @FXML private HBox legendBar;
    @FXML private HBox legendSeriesA;
    @FXML private Circle dotSeriesA;
    @FXML private Label lblSeriesA;
    @FXML private HBox legendSeriesB;
    @FXML private Circle dotSeriesB;
    @FXML private Label lblSeriesB;
    @FXML private HBox legendSeriesC;
    @FXML private Circle dotSeriesC;
    @FXML private Label lblSeriesC;
    @FXML private Label lblUnit;

    @FXML private AnchorPane chartContainer;
    @FXML private AreaChart<Number, Number> areaChart;
    @FXML private NumberAxis xAxis;
    @FXML private NumberAxis yAxis;

    // ---------- State ----------
    private final ObservableList<XYChart.Series<Number, Number>> displayedSeries =
            FXCollections.observableArrayList();

    private final BooleanProperty loading = new SimpleBooleanProperty(this, "loading", false);
    private final BooleanProperty stacked = new SimpleBooleanProperty(this, "stacked", false);
    private final BooleanProperty logScale = new SimpleBooleanProperty(this, "logScale", false);
    private final BooleanProperty autoApplyFilters = new SimpleBooleanProperty(this, "autoApplyFilters", true);
    private final BooleanProperty autoRefreshOnInitialize =
            new SimpleBooleanProperty(this, "autoRefreshOnInitialize", true);

    private final ObjectProperty<Object> presetSelection = new SimpleObjectProperty<>(this, "presetSelection");
    private final ObjectProperty<LocalDate> fromDate = new SimpleObjectProperty<>(this, "fromDate");
    private final ObjectProperty<LocalDate> toDate = new SimpleObjectProperty<>(this, "toDate");
    private final StringProperty searchText = new SimpleStringProperty(this, "searchText", "");

    private final StringProperty title = new SimpleStringProperty(this, "title", "Area Chart");
    private final StringProperty subtitle = new SimpleStringProperty(this, "subtitle", "");
    private final StringProperty unitLabel = new SimpleStringProperty(this, "unitLabel", "Unit");
    private final LongProperty totalValue = new SimpleLongProperty(this, "totalValue", 0);
    private final DoubleProperty peakValue = new SimpleDoubleProperty(this, "peakValue", 0);

    private final ObjectProperty<AreaChartDataProvider> dataProvider =
            new SimpleObjectProperty<>(this, "dataProvider");
    private final ReadOnlyObjectWrapper<ChartSnapshot> lastSnapshot =
            new ReadOnlyObjectWrapper<>(this, "lastSnapshot", ChartSnapshot.empty());
    private final ReadOnlyObjectWrapper<ChartQuery> lastQuery =
            new ReadOnlyObjectWrapper<>(this, "lastQuery");

    private final IntegerProperty searchDebounceMillis =
            new SimpleIntegerProperty(this, "searchDebounceMillis", 350);

    private final AtomicLong requestCounter = new AtomicLong();
    private volatile long newestRequestId;

    private PauseTransition searchDebounce;
    private List<LegendSlot> legendSlots = List.of();
    private final Locale numberLocale = Locale.getDefault(Locale.Category.FORMAT);
    private boolean suppressFilterNotifications;
    private boolean pendingFilterNotification;

    // ---------- Lifecycle ----------
    @FXML
    private void initialize() {
        Objects.requireNonNull(areaChart, "areaChart was not injected");
        Objects.requireNonNull(xAxis, "xAxis was not injected");
        Objects.requireNonNull(yAxis, "yAxis was not injected");

        configureTitleSection();
        configureStatsSection();
        configureLegend();
        configureFilters();
        configureChart();
        configureButtons();

        dataProvider.addListener((obs, old, provider) -> {
            if (provider != null && isAutoRefreshOnInitialize()) {
                refresh();
            } else if (provider == null) {
                clearChart();
            }
        });

        if (dataProvider.get() != null && isAutoRefreshOnInitialize()) {
            refresh();
        }

        onChartReady();
    }

    // ---------- Configure UI ----------

    private void configureTitleSection() {
        if (lblTitle != null) {
            if (lblTitle.getText() != null && !lblTitle.getText().isBlank()) {
                title.set(lblTitle.getText());
            }
            lblTitle.textProperty().bind(title);
        }
        if (lblSubtitle != null) {
            if (lblSubtitle.getText() != null && !lblSubtitle.getText().isBlank()) {
                subtitle.set(lblSubtitle.getText());
            }
            lblSubtitle.textProperty().bind(subtitle);
        }
    }

    private void configureStatsSection() {
        if (lblUnit != null) {
            if (lblUnit.getText() != null && !lblUnit.getText().isBlank()) {
                unitLabel.set(lblUnit.getText());
            }
            lblUnit.textProperty().bind(unitLabel);
        }
        if (lblTotal != null) {
            lblTotal.textProperty().bind(Bindings.createStringBinding(
                    () -> "Total: " + formatLong(totalValue.get()),
                    totalValue));
        }
        if (lblPeak != null) {
            lblPeak.textProperty().bind(Bindings.createStringBinding(
                    () -> "Peak: " + formatDouble(peakValue.get()),
                    peakValue));
        }
    }

    private void configureLegend() {
        legendSlots = Stream.of(
                        new LegendSlot(legendSeriesA, dotSeriesA, lblSeriesA),
                        new LegendSlot(legendSeriesB, dotSeriesB, lblSeriesB),
                        new LegendSlot(legendSeriesC, dotSeriesC, lblSeriesC))
                .filter(LegendSlot::isPresent)
                .toList();
        legendSlots.forEach(LegendSlot::hide);
    }

    private void configureFilters() {
        if (cbPresetRange != null) {
            cbPresetRange.valueProperty().addListener((obs, old, value) -> setPresetSelection(value));
        }
        presetSelection.addListener((obs, old, value) -> {
            if (cbPresetRange != null && !Objects.equals(cbPresetRange.getValue(), value)) {
                cbPresetRange.setValue(value);
            }
        });

        if (dpFrom != null) {
            dpFrom.valueProperty().addListener((obs, old, value) -> setFromDate(value));
        }
        fromDate.addListener((obs, old, value) -> {
            if (dpFrom != null && !Objects.equals(dpFrom.getValue(), value)) {
                dpFrom.setValue(value);
            }
        });

        if (dpTo != null) {
            dpTo.valueProperty().addListener((obs, old, value) -> setToDate(value));
        }
        toDate.addListener((obs, old, value) -> {
            if (dpTo != null && !Objects.equals(dpTo.getValue(), value)) {
                dpTo.setValue(value);
            }
        });

        setupSearchField();
        if (tgStacked != null) {
            tgStacked.selectedProperty().addListener((obs, old, selected) ->
                    setStacked(Boolean.TRUE.equals(selected)));
            stacked.addListener((obs, old, value) -> {
                if (tgStacked.isSelected() != value) {
                    tgStacked.setSelected(value);
                }
            });
            tgStacked.setSelected(stacked.get());
        }

        if (tgLogScale != null) {
            tgLogScale.selectedProperty().addListener((obs, old, selected) ->
                    setLogScale(Boolean.TRUE.equals(selected)));
            logScale.addListener((obs, old, value) -> {
                if (tgLogScale.isSelected() != value) {
                    tgLogScale.setSelected(value);
                }
            });
            tgLogScale.setSelected(logScale.get());
        }
    }

    private void setupSearchField() {
        if (tfSearch == null) return;
        if (tfSearch.getPromptText() == null || tfSearch.getPromptText().isBlank()) {
            tfSearch.setPromptText("Search...");
        }
        searchDebounce = new PauseTransition(Duration.millis(searchDebounceMillis.get()));
        searchDebounceMillis.addListener((obs, old, value) -> {
            if (searchDebounce != null && value != null) {
                searchDebounce.setDuration(Duration.millis(Math.max(0, value.intValue())));
            }
        });
        tfSearch.textProperty().addListener((obs, old, text) -> {
            if (searchDebounce == null) {
                setSearchText(text);
                return;
            }
            searchDebounce.stop();
            searchDebounce.setOnFinished(e -> setSearchText(text));
            searchDebounce.playFromStart();
        });
        searchText.addListener((obs, old, value) -> {
            if (tfSearch != null && !Objects.equals(tfSearch.getText(), value)) {
                tfSearch.setText(value);
            }
        });
    }

    private void configureChart() {
        Bindings.bindContent(areaChart.getData(), displayedSeries);
        areaChart.setLegendVisible(false);
        configureAreaChart(areaChart, xAxis, yAxis);
    }

    private void configureButtons() {
        if (btnApply != null) {
            btnApply.setOnAction(e -> refresh());
        }
        if (btnReset != null) {
            btnReset.setOnAction(e -> resetFilters());
        }
    }

    // ---------- Data flow ----------

    public final void refresh() {
        refresh(buildChartQuery());
    }

    private void refresh(ChartQuery query) {
        AreaChartDataProvider provider = dataProvider.get();
        if (provider == null) {
            clearChart();
            return;
        }
        Objects.requireNonNull(query, "query");
        lastQuery.set(query);

        loading.set(true);
        updateLoadingStyle(true);

        long requestId = requestCounter.incrementAndGet();
        newestRequestId = requestId;

        CompletableFuture<ChartSnapshot> future;
        try {
            future = Objects.requireNonNull(provider.fetch(query), "DataProvider returned null future");
        } catch (Exception ex) {
            handleFailure(requestId, ex);
            return;
        }

        future.whenComplete((snapshot, throwable) ->
                Platform.runLater(() -> {
                    if (requestId != newestRequestId) return;
                    if (throwable != null) {
                        handleFailure(requestId, throwable);
                    } else {
                        handleSuccess(snapshot == null ? ChartSnapshot.empty() : snapshot);
                    }
                }));
    }

    private void handleFailure(long requestId, Throwable throwable) {
        if (requestId != newestRequestId) return;
        loading.set(false);
        updateLoadingStyle(false);
        onLoadFailed(throwable);
    }

    private void handleSuccess(ChartSnapshot snapshot) {
        loading.set(false);
        updateLoadingStyle(false);

        if (snapshot.unitLabel() != null && !snapshot.unitLabel().isBlank()) {
            setUnitLabel(snapshot.unitLabel());
        }
        if (snapshot.statusText() != null && !snapshot.statusText().isBlank()) {
            setSubtitle(snapshot.statusText());
        }

        totalValue.set(Math.max(0, snapshot.total()));
        peakValue.set(Math.max(0, snapshot.peak()));

        applySeries(snapshot.series());
        lastSnapshot.set(snapshot);
        onSnapshotApplied(snapshot);
    }

    private void applySeries(List<SeriesData> seriesData) {
        List<SeriesData> safeSeries = seriesData == null ? List.of() : seriesData;
        List<XYChart.Series<Number, Number>> fxSeries = safeSeries.stream()
                .map(this::toFxSeries)
                .toList();
        displayedSeries.setAll(fxSeries);
        updateLegend(safeSeries);
    }

    private XYChart.Series<Number, Number> toFxSeries(SeriesData data) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(data.name());
        data.points().forEach(point -> series.getData().add(new XYChart.Data<>(point.x(), point.y())));
        return series;
    }

    private void updateLegend(List<SeriesData> seriesData) {
        if (legendSlots.isEmpty()) return;
        for (int i = 0; i < legendSlots.size(); i++) {
            LegendSlot slot = legendSlots.get(i);
            if (i < seriesData.size()) {
                slot.apply(seriesData.get(i), getUnitLabel());
            } else {
                slot.hide();
            }
        }
    }

    private void clearChart() {
        displayedSeries.clear();
        lastSnapshot.set(ChartSnapshot.empty());
        totalValue.set(0);
        peakValue.set(0);
        updateLegend(List.of());
    }

    // ---------- Filters / setters ----------

    private void filtersChanged() {
        if (suppressFilterNotifications) {
            pendingFilterNotification = true;
            return;
        }
        pendingFilterNotification = false;
        ChartQuery query = buildChartQuery();
        onFiltersChanged(query);
        if (isAutoApplyFilters()) {
            refresh(query);
        }
    }

    private void flushFilterNotificationsIfNeeded() {
        if (suppressFilterNotifications || !pendingFilterNotification) {
            return;
        }
        filtersChanged();
    }

    private void withoutFilterNotifications(Runnable action) {
        boolean previous = suppressFilterNotifications;
        suppressFilterNotifications = true;
        try {
            action.run();
        } finally {
            suppressFilterNotifications = previous;
        }
    }

    public final void resetFilters() {
        withoutFilterNotifications(() -> {
            setPresetSelection(null);
            setFromDate(null);
            setToDate(null);
            setSearchText("");
            setStacked(false);
            setLogScale(false);
            if (cbPresetRange != null) {
                cbPresetRange.getSelectionModel().clearSelection();
            }
        });
        onResetFilters();
        flushFilterNotificationsIfNeeded();
    }

    public final void setPresetSelection(Object selection) {
        if (Objects.equals(presetSelection.get(), selection)) return;
        presetSelection.set(selection);
        if (cbPresetRange != null && !Objects.equals(cbPresetRange.getValue(), selection)) {
            cbPresetRange.setValue(selection);
        }
        filtersChanged();
    }
    public final Object getPresetSelection() { return presetSelection.get(); }
    public final ObjectProperty<Object> presetSelectionProperty() { return presetSelection; }

    public final void setFromDate(LocalDate date) {
        if (Objects.equals(fromDate.get(), date)) return;
        fromDate.set(date);
        if (dpFrom != null && !Objects.equals(dpFrom.getValue(), date)) {
            dpFrom.setValue(date);
        }
        filtersChanged();
    }
    public final LocalDate getFromDate() { return fromDate.get(); }
    public final ObjectProperty<LocalDate> fromDateProperty() { return fromDate; }

    public final void setToDate(LocalDate date) {
        if (Objects.equals(toDate.get(), date)) return;
        toDate.set(date);
        if (dpTo != null && !Objects.equals(dpTo.getValue(), date)) {
            dpTo.setValue(date);
        }
        filtersChanged();
    }
    public final LocalDate getToDate() { return toDate.get(); }
    public final ObjectProperty<LocalDate> toDateProperty() { return toDate; }

    public final void setSearchText(String text) {
        String sanitized = text == null ? "" : text.trim();
        if (Objects.equals(searchText.get(), sanitized)) return;
        searchText.set(sanitized);
        if (tfSearch != null && !Objects.equals(tfSearch.getText(), sanitized)) {
            tfSearch.setText(sanitized);
        }
        filtersChanged();
    }
    public final String getSearchText() { return searchText.get(); }
    public final StringProperty searchTextProperty() { return searchText; }

    public final void setStacked(boolean value) {
        if (stacked.get() == value) return;
        stacked.set(value);
        if (tgStacked != null && tgStacked.isSelected() != value) {
            tgStacked.setSelected(value);
        }
        updateStackedVisualState(value);
        filtersChanged();
    }
    public final boolean isStacked() { return stacked.get(); }
    public final BooleanProperty stackedProperty() { return stacked; }

    public final void setLogScale(boolean value) {
        if (logScale.get() == value) return;
        logScale.set(value);
        if (tgLogScale != null && tgLogScale.isSelected() != value) {
            tgLogScale.setSelected(value);
        }
        updateLogScaleVisualState(value);
        filtersChanged();
    }
    public final boolean isLogScale() { return logScale.get(); }
    public final BooleanProperty logScaleProperty() { return logScale; }

    public final void setTitle(String text) { title.set(text == null ? "" : text); }
    public final String getTitle() { return title.get(); }
    public final StringProperty titleProperty() { return title; }

    public final void setSubtitle(String text) { subtitle.set(text == null ? "" : text); }
    public final String getSubtitle() { return subtitle.get(); }
    public final StringProperty subtitleProperty() { return subtitle; }

    public final void setUnitLabel(String text) { unitLabel.set(text == null ? "" : text); }
    public final String getUnitLabel() { return unitLabel.get(); }
    public final StringProperty unitLabelProperty() { return unitLabel; }

    public final void setDataProvider(AreaChartDataProvider provider) { dataProvider.set(provider); }
    public final AreaChartDataProvider getDataProvider() { return dataProvider.get(); }
    public final ObjectProperty<AreaChartDataProvider> dataProviderProperty() { return dataProvider; }

    public final boolean isAutoApplyFilters() { return autoApplyFilters.get(); }
    public final void setAutoApplyFilters(boolean auto) { autoApplyFilters.set(auto); }
    public final BooleanProperty autoApplyFiltersProperty() { return autoApplyFilters; }

    public final boolean isAutoRefreshOnInitialize() { return autoRefreshOnInitialize.get(); }
    public final void setAutoRefreshOnInitialize(boolean auto) { autoRefreshOnInitialize.set(auto); }
    public final BooleanProperty autoRefreshOnInitializeProperty() { return autoRefreshOnInitialize; }

    public final BooleanProperty loadingProperty() { return loading; }
    public final boolean isLoading() { return loading.get(); }

    public final IntegerProperty searchDebounceMillisProperty() { return searchDebounceMillis; }
    public final void setSearchDebounceMillis(int millis) {
        searchDebounceMillis.set(Math.max(0, millis));
    }

    public final ReadOnlyObjectProperty<ChartSnapshot> lastSnapshotProperty() { return lastSnapshot.getReadOnlyProperty(); }
    public final ChartSnapshot getLastSnapshot() { return lastSnapshot.get(); }

    public final ReadOnlyObjectProperty<ChartQuery> lastQueryProperty() { return lastQuery.getReadOnlyProperty(); }
    public final ChartQuery getLastQuery() { return lastQuery.get(); }

    public final ObservableList<XYChart.Series<Number, Number>> displayedSeries() {
        return FXCollections.unmodifiableObservableList(displayedSeries);
    }

    public final AreaChart<Number, Number> getAreaChart() { return areaChart; }
    public final NumberAxis getxAxis() { return xAxis; }
    public final NumberAxis getyAxis() { return yAxis; }
    public final VBox getRootContainer() { return rootContainer; }

    // ---------- Hooks ----------

    /** Called after FXML wiring is completed. */
    protected void onChartReady() {}

    /** Called before/after the chart is populated - subclasses can tweak axes, tooltip, etc. */
    protected void configureAreaChart(AreaChart<Number, Number> chart, NumberAxis xAxis, NumberAxis yAxis) {}

    /** Called right before executing a refresh when filters change or apply button is pressed. */
    protected void onFiltersChanged(ChartQuery pendingQuery) {}

    /** Called after {@link #resetFilters()} completes. */
    protected void onResetFilters() {}

    /** Called after data has successfully loaded and been applied to the chart. */
    protected void onSnapshotApplied(ChartSnapshot snapshot) {}

    /** Called when the data provider fails. */
    protected void onLoadFailed(Throwable error) {}

    /** Override to append custom filter payload to the query. */
    protected Map<String, Object> collectExtraFilters() { return Map.of(); }

    /** Allow subclasses to update visuals when stacked mode toggles. */
    protected void onStackedModeChanged(boolean stacked) {}

    /** Allow subclasses to update visuals when log scale toggles. */
    protected void onLogScaleChanged(boolean logScale) {}

    // ---------- Helpers ----------

    private void updateStackedVisualState(boolean value) {
        toggleStyleClass(areaChart, "stacked", value);
        onStackedModeChanged(value);
    }

    private void updateLogScaleVisualState(boolean value) {
        toggleStyleClass(areaChart, "log-scale", value);
        onLogScaleChanged(value);
    }

    private void toggleStyleClass(AreaChart<?, ?> chart, String style, boolean enable) {
        if (chart == null) return;
        List<String> classes = chart.getStyleClass();
        if (enable) {
            if (!classes.contains(style)) classes.add(style);
        } else {
            classes.remove(style);
        }
    }

    private String formatLong(long value) {
        NumberFormat format = NumberFormat.getIntegerInstance(numberLocale);
        return format.format(value);
    }

    private String formatDouble(double value) {
        if (!Double.isFinite(value)) return "0";
        NumberFormat format = NumberFormat.getNumberInstance(numberLocale);
        format.setMaximumFractionDigits(2);
        format.setMinimumFractionDigits(0);
        return format.format(value);
    }

    private void updateLoadingStyle(boolean isLoadingNow) {
        if (rootContainer == null) return;
        List<String> classes = rootContainer.getStyleClass();
        if (isLoadingNow) {
            if (!classes.contains(LOADING_STYLE)) {
                classes.add(LOADING_STYLE);
            }
        } else {
            classes.remove(LOADING_STYLE);
        }
    }

    private ChartQuery buildChartQuery() {
        return new ChartQuery(
                getPresetSelection(),
                getFromDate(),
                getToDate(),
                getSearchText(),
                isStacked(),
                isLogScale(),
                collectExtraFilters());
    }

    // ---------- Records / DTOs ----------

    public interface AreaChartDataProvider {
        /**
         * Fetch chart data for the given query. Implementations may run asynchronously.
         */
        CompletableFuture<ChartSnapshot> fetch(ChartQuery query);
    }

    public record ChartQuery(
            Object presetSelection,
            LocalDate fromDate,
            LocalDate toDate,
            String searchText,
            boolean stacked,
            boolean logScale,
            Map<String, Object> extraFilters) {
        public ChartQuery {
            extraFilters = (extraFilters == null)
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(extraFilters));
        }
    }

    public record ChartSnapshot(
            List<SeriesData> series,
            long total,
            double peak,
            String unitLabel,
            String statusText) {
        public ChartSnapshot {
            series = series == null ? List.of() : List.copyOf(series);
        }

        public static ChartSnapshot empty() {
            return new ChartSnapshot(List.of(), 0, 0, null, null);
        }
    }

    public record SeriesData(
            String name,
            List<DataPoint> points,
            String legendValue,
            Paint legendColor) {
        public SeriesData {
            Objects.requireNonNull(name, "name");
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record DataPoint(Number x, Number y) {
        public DataPoint {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
        }
    }

    // ---------- Legend slot helper ----------

    private static final class LegendSlot {
        private final HBox container;
        private final Circle dot;
        private final Label label;

        private LegendSlot(HBox container, Circle dot, Label label) {
            this.container = container;
            this.dot = dot;
            this.label = label;
        }

        private boolean isPresent() {
            return container != null || dot != null || label != null;
        }

        private void apply(SeriesData data, String unitLabel) {
            if (container != null) {
                container.setManaged(true);
                container.setVisible(true);
            }
            if (label != null) {
                StringBuilder text = new StringBuilder(data.name());
                if (data.legendValue() != null && !data.legendValue().isBlank()) {
                    text.append(" - ").append(data.legendValue());
                    if (unitLabel != null && !unitLabel.isBlank()) {
                        text.append(" ").append(unitLabel);
                    }
                }
                label.setText(text.toString());
            }
            if (dot != null && data.legendColor() != null) {
                dot.setFill(data.legendColor());
            }
        }

        private void hide() {
            if (container != null) {
                container.setVisible(false);
                container.setManaged(false);
            }
            if (label != null) {
                label.setText("");
            }
        }
    }
}
