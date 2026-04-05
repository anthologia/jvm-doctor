package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.DumpDiff;
import io.jvmdoctor.analyzer.DumpDiff.ChangeType;
import io.jvmdoctor.analyzer.DumpDiff.ThreadDelta;
import io.jvmdoctor.analyzer.DumpDiffer;
import io.jvmdoctor.analyzer.MultiDumpAnalysis;
import io.jvmdoctor.analyzer.MultiDumpAnalysis.ThreadSeries;
import io.jvmdoctor.analyzer.ThreadRuntimeAnalyzer;
import io.jvmdoctor.model.ThreadInfo;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

public class DumpDiffController implements Initializable {

    @FXML private Label sessionLabel;
    @FXML private Label summaryLabel;
    @FXML private Label anchorSnapshotLabel;
    @FXML private ComboBox<String> toSnapshotBox;
    @FXML private Button latestTargetBtn;
    @FXML private HBox filterChips;
    @FXML private ToggleButton showAdded;
    @FXML private ToggleButton showRemoved;
    @FXML private ToggleButton showChanged;
    @FXML private ToggleButton showUnchanged;
    @FXML private TextField filterField;
    @FXML private TableView<ThreadDelta> diffTable;
    @FXML private TableColumn<ThreadDelta, String> changeCol;
    @FXML private TableColumn<ThreadDelta, String> nameCol;
    @FXML private TableColumn<ThreadDelta, String> transitionCol;
    @FXML private TableColumn<ThreadDelta, String> signalsCol;
    @FXML private TableColumn<ThreadDelta, Number> deltaCpuCol;
    @FXML private TableColumn<ThreadDelta, Number> loadCol;
    @FXML private TableColumn<ThreadDelta, String> beforeCol;
    @FXML private TableColumn<ThreadDelta, String> afterCol;
    @FXML private TextArea detailArea;

    private final ObservableList<ThreadDelta> allDeltas = FXCollections.observableArrayList();
    private FilteredList<ThreadDelta> filtered;
    private SortedList<ThreadDelta> sorted;
    private Map<String, ThreadSeries> seriesByThreadName = Map.of();
    private MultiDumpAnalysis analysis;
    private int snapshotCount;
    private List<Integer> targetSnapshotIndexes = List.of();
    private final DumpDiffer dumpDiffer = new DumpDiffer();
    private IntConsumer onTargetSnapshotChanged = ignored -> { };
    private double pairIntervalMillis = Double.NaN;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        diffTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        diffTable.setTableMenuButtonVisible(true);

        changeCol.setCellValueFactory(c -> new SimpleStringProperty(changeLabel(c.getValue().change())));
        changeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("diff-added", "diff-removed", "diff-changed", "diff-unchanged");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                ThreadDelta delta = getTableRow() == null ? null : (ThreadDelta) getTableRow().getItem();
                if (delta != null) {
                    getStyleClass().add(changeCss(delta.change()));
                }
            }
        });

        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().threadName()));
        transitionCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().transitionLabel()));
        signalsCol.setCellValueFactory(c -> new SimpleStringProperty(seriesSignals(c.getValue())));
        deltaCpuCol.setCellValueFactory(c -> new SimpleDoubleProperty(cpuDeltaValue(c.getValue())));
        loadCol.setCellValueFactory(c -> new SimpleDoubleProperty(intervalLoadValue(c.getValue())));
        beforeCol.setCellValueFactory(c -> new SimpleStringProperty(seenLabel(c.getValue())));
        afterCol.setCellValueFactory(c -> new SimpleStringProperty(flipLabel(c.getValue())));
        changeCol.setComparator((left, right) -> Integer.compare(changePriority(left), changePriority(right)));
        deltaCpuCol.setComparator((left, right) -> Double.compare(left.doubleValue(), right.doubleValue()));
        loadCol.setComparator((left, right) -> Double.compare(left.doubleValue(), right.doubleValue()));
        beforeCol.setComparator((left, right) -> Integer.compare(parseSeen(left), parseSeen(right)));
        afterCol.setComparator((left, right) -> Integer.compare(parseIntLabel(left), parseIntLabel(right)));
        changeCol.setResizable(true);
        nameCol.setResizable(true);
        transitionCol.setResizable(true);
        signalsCol.setResizable(true);
        deltaCpuCol.setResizable(true);
        loadCol.setResizable(true);
        beforeCol.setResizable(true);
        afterCol.setResizable(true);
        changeCol.setSortable(true);
        nameCol.setSortable(true);
        transitionCol.setSortable(true);
        signalsCol.setSortable(true);
        deltaCpuCol.setSortable(true);
        loadCol.setSortable(true);
        beforeCol.setSortable(true);
        afterCol.setSortable(true);

        signalsCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("state-blocked", "state-waiting", "state-runnable");
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    return;
                }
                setText(item);
                if (item.contains("STUCK") || item.contains("NEW_BLOCKED") || item.contains("REPEAT_BLOCKED")) {
                    getStyleClass().add("state-blocked");
                } else if (item.contains("FLAPPING")) {
                    getStyleClass().add("state-runnable");
                } else if (item.contains("BLOCK_RESOLVED") || item.contains("PERSISTENT")) {
                    getStyleClass().add("state-waiting");
                }
            }
        });
        deltaCpuCol.setStyle("-fx-alignment: CENTER_RIGHT;");
        deltaCpuCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("state-runnable", "state-blocked");
                if (empty || value == null || value.doubleValue() < 0) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                ThreadDelta delta = getTableRow() == null ? null : getTableRow().getItem();
                setText(ThreadRuntimeAnalyzer.formatCpuMillis(value.doubleValue()));
                if (delta != null) {
                    String tooltip = "ΔCPU: " + ThreadRuntimeAnalyzer.formatCpuMillis(value.doubleValue());
                    double load = delta.intervalLoad(pairIntervalMillis);
                    if (!Double.isNaN(load)) {
                        tooltip += "\nInterval load: " + ThreadRuntimeAnalyzer.formatCpuLoad(load);
                    }
                    setTooltip(new Tooltip(tooltip));
                    if (isSpinRise(delta)) {
                        getStyleClass().add("state-blocked");
                    } else if (isCpuSpike(delta)) {
                        getStyleClass().add("state-runnable");
                    }
                } else {
                    setTooltip(null);
                }
            }
        });
        loadCol.setStyle("-fx-alignment: CENTER_RIGHT;");
        loadCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("state-runnable", "state-blocked");
                if (empty || value == null || value.doubleValue() < 0) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                ThreadDelta delta = getTableRow() == null ? null : getTableRow().getItem();
                setText(ThreadRuntimeAnalyzer.formatCpuLoad(value.doubleValue()));
                if (delta != null) {
                    String tooltip = "Interval load: " + ThreadRuntimeAnalyzer.formatCpuLoad(value.doubleValue());
                    if (!Double.isNaN(pairIntervalMillis)) {
                        tooltip += "\nWindow: " + formatInterval(pairIntervalMillis);
                    }
                    setTooltip(new Tooltip(tooltip));
                    if (isSpinRise(delta)) {
                        getStyleClass().add("state-blocked");
                    } else if (isCpuSpike(delta)) {
                        getStyleClass().add("state-runnable");
                    }
                } else {
                    setTooltip(null);
                }
            }
        });

        diffTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(ThreadDelta item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("diff-row-added", "diff-row-removed", "diff-row-changed");
                if (!empty && item != null) {
                    switch (item.change()) {
                        case ADDED -> getStyleClass().add("diff-row-added");
                        case REMOVED -> getStyleClass().add("diff-row-removed");
                        case STATE_CHANGED -> getStyleClass().add("diff-row-changed");
                        case UNCHANGED -> {
                        }
                    }
                }
            }
        });

        filtered = new FilteredList<>(allDeltas, delta -> true);
        sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(diffTable.comparatorProperty());
        diffTable.setItems(sorted);
        deltaCpuCol.setSortType(TableColumn.SortType.DESCENDING);
        diffTable.getSortOrder().setAll(deltaCpuCol, afterCol);

        showAdded.setSelected(true);
        showRemoved.setSelected(true);
        showChanged.setSelected(true);
        showUnchanged.setSelected(false);

        showAdded.setOnAction(e -> applyFilter());
        showRemoved.setOnAction(e -> applyFilter());
        showChanged.setOnAction(e -> applyFilter());
        showUnchanged.setOnAction(e -> applyFilter());
        filterField.textProperty().addListener((obs, oldValue, newValue) -> applyFilter());
        toSnapshotBox.getSelectionModel().selectedIndexProperty().addListener((obs, oldValue, newValue) -> updateSelectedPair());
        latestTargetBtn.setOnAction(e -> selectLatestTarget());

        diffTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                detailArea.clear();
                return;
            }
            detailArea.setText(buildDetail(selected));
        });

        clear();
    }

    public void setOnTargetSnapshotChanged(IntConsumer callback) {
        onTargetSnapshotChanged = callback == null ? ignored -> { } : callback;
    }

    public void setAnalysis(MultiDumpAnalysis analysis) {
        setAnalysis(analysis, analysis.snapshotCount() - 1);
    }

    public void setAnalysis(MultiDumpAnalysis analysis, int preferredTargetSnapshotIndex) {
        this.analysis = analysis;
        snapshotCount = analysis.snapshotCount();
        seriesByThreadName = analysis.seriesByThreadName();
        anchorSnapshotLabel.setText(analysis.baselineLabel());
        targetSnapshotIndexes = java.util.stream.IntStream.range(0, analysis.snapshotCount())
                .filter(index -> index != analysis.baselineIndex())
                .boxed()
                .toList();
        List<String> targetLabels = targetSnapshotIndexes.stream()
                .map(index -> targetLabel(analysis, index))
                .toList();
        toSnapshotBox.setItems(FXCollections.observableArrayList(targetLabels));
        toSnapshotBox.setDisable(targetLabels.isEmpty());
        latestTargetBtn.setDisable(targetLabels.isEmpty());
        if (!targetLabels.isEmpty()) {
            int initialTargetIndex = targetSnapshotIndexes.indexOf(preferredTargetSnapshotIndex);
            if (initialTargetIndex < 0) {
                int defaultTargetIndex = targetSnapshotIndexes.indexOf(analysis.defaultComparisonIndex());
                initialTargetIndex = defaultTargetIndex >= 0 ? defaultTargetIndex : targetLabels.size() - 1;
            }
            toSnapshotBox.getSelectionModel().select(initialTargetIndex);
            updateSelectedPair();
        } else {
            allDeltas.clear();
            sessionLabel.setText("Need at least one other snapshot to compare against the baseline.");
            summaryLabel.setText("Add more snapshots or move the baseline so another snapshot can be compared.");
            onTargetSnapshotChanged.accept(-1);
        }
    }

    public void clear() {
        analysis = null;
        allDeltas.clear();
        seriesByThreadName = Map.of();
        snapshotCount = 0;
        targetSnapshotIndexes = List.of();
        pairIntervalMillis = Double.NaN;
        anchorSnapshotLabel.setText("Baseline dump");
        toSnapshotBox.getItems().clear();
        toSnapshotBox.setDisable(true);
        latestTargetBtn.setDisable(true);
        sessionLabel.setText("Compare To Baseline shows how one snapshot drifted from the baseline dump.");
        summaryLabel.setText("Use a baseline, add snapshots, then choose any non-baseline snapshot from the selector or rail.");
        diffTable.getSelectionModel().clearSelection();
        detailArea.clear();
        onTargetSnapshotChanged.accept(-1);
    }

    public void selectTargetSnapshot(int snapshotIndex) {
        if (analysis == null || snapshotIndex < 0 || snapshotIndex == analysis.baselineIndex() || targetSnapshotIndexes.isEmpty()) {
            return;
        }
        int targetSelectionIndex = targetSnapshotIndexes.indexOf(snapshotIndex);
        if (targetSelectionIndex < 0) {
            return;
        }
        if (toSnapshotBox.getSelectionModel().getSelectedIndex() != targetSelectionIndex) {
            toSnapshotBox.getSelectionModel().select(targetSelectionIndex);
        }
        updateSelectedPair();
    }

    public int selectedTargetSnapshotIndex() {
        int selectionIndex = toSnapshotBox.getSelectionModel().getSelectedIndex();
        if (selectionIndex < 0 || selectionIndex >= targetSnapshotIndexes.size()) {
            return -1;
        }
        return targetSnapshotIndexes.get(selectionIndex);
    }

    private void updateSelectedPair() {
        if (analysis == null || targetSnapshotIndexes.isEmpty()) {
            return;
        }
        int targetSelectionIndex = toSnapshotBox.getSelectionModel().getSelectedIndex();
        if (targetSelectionIndex < 0 || targetSelectionIndex >= targetSnapshotIndexes.size()) {
            return;
        }
        int fromIndex = analysis.baselineIndex();
        int toIndex = targetSnapshotIndexes.get(targetSelectionIndex);
        pairIntervalMillis = computeIntervalMillis(fromIndex, toIndex);

        DumpDiff diff = dumpDiffer.diff(
                analysis.snapshots().get(fromIndex).dump(),
                analysis.snapshots().get(toIndex).dump());
        allDeltas.setAll(diff.deltas());
        sessionLabel.setText(String.format(
                "Baseline Comparison  ·  %s  vs  %s  ·  %s window  ·  %d snapshots loaded",
                analysis.snapshots().get(fromIndex).label(),
                analysis.snapshots().get(toIndex).label(),
                formatInterval(pairIntervalMillis),
                analysis.snapshotCount()));
        summaryLabel.setText(String.format(
                "+%d added   -%d removed   ~%d changed   =%d same   !%d new blocked   ->%d resolved   !%d stuck   CPU spikes %d",
                diff.addedCount(),
                diff.removedCount(),
                diff.changedCount(),
                diff.unchangedCount(),
                diff.newlyBlockedCount(),
                diff.resolvedBlockedCount(),
                diff.stuckCount(),
                diff.deltas().stream().filter(this::isCpuSpike).count()));
        applyFilter();
        if (diff.deltas().stream().anyMatch(ThreadDelta::hasCpuDelta)) {
            deltaCpuCol.setSortType(TableColumn.SortType.DESCENDING);
            diffTable.getSortOrder().setAll(deltaCpuCol, loadCol, afterCol);
        } else {
            afterCol.setSortType(TableColumn.SortType.DESCENDING);
            diffTable.getSortOrder().setAll(afterCol);
        }
        diffTable.refresh();
        diffTable.getSelectionModel().clearSelection();
        detailArea.clear();
        onTargetSnapshotChanged.accept(toIndex);
    }

    private void selectLatestTarget() {
        if (analysis == null || targetSnapshotIndexes.isEmpty()) {
            return;
        }
        int latestSnapshotIndex = analysis.snapshotCount() - 1;
        int selectionIndex = targetSnapshotIndexes.indexOf(latestSnapshotIndex);
        toSnapshotBox.getSelectionModel().select(selectionIndex >= 0 ? selectionIndex : targetSnapshotIndexes.size() - 1);
        updateSelectedPair();
    }

    private String targetLabel(MultiDumpAnalysis analysis, int snapshotIndex) {
        String relation = snapshotIndex < analysis.baselineIndex() ? "Earlier" : "Later";
        return "#" + (snapshotIndex + 1) + "  " + analysis.snapshots().get(snapshotIndex).label() + "  ·  " + relation;
    }

    private void applyFilter() {
        String text = filterField.getText() == null ? "" : filterField.getText().toLowerCase();
        filtered.setPredicate(delta -> {
            boolean typeOk = switch (delta.change()) {
                case ADDED -> showAdded.isSelected();
                case REMOVED -> showRemoved.isSelected();
                case STATE_CHANGED -> showChanged.isSelected();
                case UNCHANGED -> showUnchanged.isSelected();
            };
            boolean textOk = text.isEmpty()
                    || delta.threadName().toLowerCase().contains(text)
                    || delta.transitionLabel().toLowerCase().contains(text)
                    || seriesSignals(delta).toLowerCase().contains(text)
                    || formatDeltaCpu(delta).toLowerCase().contains(text)
                    || formatIntervalLoad(delta).toLowerCase().contains(text)
                    || delta.topFrameBefore().toLowerCase().contains(text)
                    || delta.topFrameAfter().toLowerCase().contains(text);
            return typeOk && textOk;
        });
    }

    private String changeLabel(ChangeType changeType) {
        return switch (changeType) {
            case ADDED -> "+ ADDED";
            case REMOVED -> "- REMOVED";
            case STATE_CHANGED -> "~ CHANGED";
            case UNCHANGED -> "= SAME";
        };
    }

    private String changeCss(ChangeType changeType) {
        return switch (changeType) {
            case ADDED -> "diff-added";
            case REMOVED -> "diff-removed";
            case STATE_CHANGED -> "diff-changed";
            case UNCHANGED -> "diff-unchanged";
        };
    }

    private String buildDetail(ThreadDelta delta) {
        StringBuilder sb = new StringBuilder();
        ThreadSeries series = seriesByThreadName.get(delta.threadName());

        sb.append("Thread: \"").append(delta.threadName()).append("\"\n");
        sb.append("Boundary change: ").append(delta.change()).append("\n");
        if (delta.stateBefore() != null) {
            sb.append("Before: ").append(delta.stateBefore()).append("\n");
        }
        if (delta.stateAfter() != null) {
            sb.append("After:  ").append(delta.stateAfter()).append("\n");
        }
        if (delta.threadBefore() != null && delta.threadBefore().hasCpuTime()) {
            sb.append("CPU before: ").append(ThreadRuntimeAnalyzer.formatCpuMillis(delta.threadBefore().cpuMillis())).append("\n");
        }
        if (delta.threadAfter() != null && delta.threadAfter().hasCpuTime()) {
            sb.append("CPU after:  ").append(ThreadRuntimeAnalyzer.formatCpuMillis(delta.threadAfter().cpuMillis())).append("\n");
        }
        if (delta.hasCpuDelta()) {
            sb.append("ΔCPU: ").append(ThreadRuntimeAnalyzer.formatCpuMillis(delta.cpuDeltaMillis())).append("\n");
            sb.append("Interval load: ").append(ThreadRuntimeAnalyzer.formatCpuLoad(delta.intervalLoad(pairIntervalMillis)));
            if (!Double.isNaN(pairIntervalMillis)) {
                sb.append(" over ").append(formatInterval(pairIntervalMillis));
            }
            sb.append("\n");
        }
        if (series != null) {
            sb.append("Seen: ").append(series.seenLabel(snapshotCount)).append("\n");
            sb.append("Transitions: ").append(series.transitions()).append("\n");
            if (!series.signalLabel().isBlank()) {
                sb.append("Signals: ").append(series.signalLabel()).append("\n");
            }
            if (!series.displayTopFrame().isBlank()) {
                sb.append("Dominant top frame: ").append(series.displayTopFrame()).append("\n");
            }
        } else if (!delta.signals().isBlank()) {
            sb.append("Signals: ").append(delta.signals()).append("\n");
        }
        if (!delta.topFrameBefore().isBlank()) {
            sb.append("Top before: ").append(delta.topFrameBefore()).append("\n");
        }
        if (!delta.topFrameAfter().isBlank()) {
            sb.append("Top after:  ").append(delta.topFrameAfter()).append("\n");
        }

        ThreadInfo reference = delta.threadAfter() != null ? delta.threadAfter() : delta.threadBefore();
        if (reference != null) {
            sb.append("\n");
            if (reference.waitingOnLock() != null) {
                sb.append("Waiting on: ").append(reference.waitingOnLock()).append("\n");
            }
            if (!reference.heldLocks().isEmpty()) {
                sb.append("Holding:    ")
                        .append(reference.heldLocks().stream().map(Object::toString).collect(Collectors.joining(", ")))
                        .append("\n");
            }
            if (reference.stackFrames() != null && !reference.stackFrames().isEmpty()) {
                sb.append("\nStack:\n");
                reference.stackFrames().forEach(frame -> sb.append("  ").append(frame).append("\n"));
            }
        }
        return sb.toString();
    }

    private String seenLabel(ThreadDelta delta) {
        ThreadSeries series = seriesByThreadName.get(delta.threadName());
        if (series == null || snapshotCount == 0) {
            return "—";
        }
        return series.seenLabel(snapshotCount);
    }

    private String flipLabel(ThreadDelta delta) {
        ThreadSeries series = seriesByThreadName.get(delta.threadName());
        if (series == null) {
            return "—";
        }
        return String.valueOf(series.transitions());
    }

    private String seriesSignals(ThreadDelta delta) {
        ThreadSeries series = seriesByThreadName.get(delta.threadName());
        String cpuSignals = cpuSignals(delta);
        if (series == null) {
            return joinSignals(delta.signals(), cpuSignals);
        }
        String seriesSignals = series.signalLabel();
        return joinSignals(joinSignals(seriesSignals, delta.signals()), cpuSignals);
    }

    private int parseSeen(String text) {
        if (text == null || text.isBlank() || "—".equals(text)) {
            return Integer.MIN_VALUE;
        }
        int slash = text.indexOf('/');
        String numerator = slash >= 0 ? text.substring(0, slash) : text;
        return parseIntLabel(numerator);
    }

    private int parseIntLabel(String text) {
        if (text == null || text.isBlank() || "—".equals(text)) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private int changePriority(String label) {
        if (label == null || label.isBlank()) {
            return 99;
        }
        return switch (label) {
            case "+ ADDED" -> 0;
            case "~ CHANGED" -> 1;
            case "- REMOVED" -> 2;
            case "= SAME" -> 3;
            default -> 99;
        };
    }

    private double computeIntervalMillis(int fromIndex, int toIndex) {
        if (analysis == null || fromIndex < 0 || toIndex < 0
                || fromIndex >= analysis.snapshotCount() || toIndex >= analysis.snapshotCount()) {
            return Double.NaN;
        }
        try {
            long millis = Math.abs(Duration.between(
                    analysis.snapshots().get(fromIndex).dump().timestamp(),
                    analysis.snapshots().get(toIndex).dump().timestamp()).toMillis());
            return millis > 0 ? millis : Double.NaN;
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private double cpuDeltaValue(ThreadDelta delta) {
        double value = delta == null ? Double.NaN : delta.cpuDeltaMillis();
        return Double.isNaN(value) ? -1.0 : value;
    }

    private double intervalLoadValue(ThreadDelta delta) {
        double value = delta == null ? Double.NaN : delta.intervalLoad(pairIntervalMillis);
        return Double.isNaN(value) ? -1.0 : value;
    }

    private String formatDeltaCpu(ThreadDelta delta) {
        return delta == null ? "—" : ThreadRuntimeAnalyzer.formatCpuMillis(delta.cpuDeltaMillis());
    }

    private String formatIntervalLoad(ThreadDelta delta) {
        return delta == null ? "—" : ThreadRuntimeAnalyzer.formatCpuLoad(delta.intervalLoad(pairIntervalMillis));
    }

    private String formatInterval(double intervalMillis) {
        if (Double.isNaN(intervalMillis) || intervalMillis <= 0) {
            return "unknown interval";
        }
        return ThreadRuntimeAnalyzer.formatElapsedSeconds(intervalMillis / 1000.0);
    }

    private boolean isCpuSpike(ThreadDelta delta) {
        if (delta == null || !delta.hasCpuDelta()) {
            return false;
        }
        double load = delta.intervalLoad(pairIntervalMillis);
        return (!Double.isNaN(load) && load >= 0.50 && delta.cpuDeltaMillis() >= 100.0)
                || delta.cpuDeltaMillis() >= 2_000.0;
    }

    private boolean isSpinRise(ThreadDelta delta) {
        if (delta == null || !isCpuSpike(delta)) {
            return false;
        }
        if (!"RUNNABLE".equalsIgnoreCase(delta.stateBefore()) || !"RUNNABLE".equalsIgnoreCase(delta.stateAfter())) {
            return false;
        }
        String before = delta.topFrameBefore();
        String after = delta.topFrameAfter();
        double load = delta.intervalLoad(pairIntervalMillis);
        return before != null && !before.isBlank() && before.equals(after)
                && !Double.isNaN(load) && load >= 0.70;
    }

    private String cpuSignals(ThreadDelta delta) {
        if (delta == null) {
            return "";
        }
        if (isSpinRise(delta)) {
            return "SPIN_RISE";
        }
        if (isCpuSpike(delta)) {
            return "CPU_SPIKE";
        }
        return "";
    }

    private String joinSignals(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        if (left.contains(right)) {
            return left;
        }
        return left + " · " + right;
    }
}
