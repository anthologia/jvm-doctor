package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.DumpDiff;
import io.jvmdoctor.analyzer.DumpDiff.ChangeType;
import io.jvmdoctor.analyzer.DumpDiff.ThreadDelta;
import io.jvmdoctor.analyzer.DumpDiffer;
import io.jvmdoctor.analyzer.MultiDumpAnalysis;
import io.jvmdoctor.analyzer.MultiDumpAnalysis.ThreadSeries;
import io.jvmdoctor.model.ThreadInfo;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.net.URL;
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
    @FXML private TableColumn<ThreadDelta, String> beforeCol;
    @FXML private TableColumn<ThreadDelta, String> afterCol;
    @FXML private TextArea detailArea;

    private final ObservableList<ThreadDelta> allDeltas = FXCollections.observableArrayList();
    private FilteredList<ThreadDelta> filtered;
    private Map<String, ThreadSeries> seriesByThreadName = Map.of();
    private MultiDumpAnalysis analysis;
    private int snapshotCount;
    private List<Integer> targetSnapshotIndexes = List.of();
    private final DumpDiffer dumpDiffer = new DumpDiffer();
    private IntConsumer onTargetSnapshotChanged = ignored -> { };

    @Override
    public void initialize(URL location, ResourceBundle resources) {
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
        beforeCol.setCellValueFactory(c -> new SimpleStringProperty(seenLabel(c.getValue())));
        afterCol.setCellValueFactory(c -> new SimpleStringProperty(flipLabel(c.getValue())));

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
        diffTable.setItems(filtered);

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
        targetSnapshotIndexes = java.util.stream.IntStream.range(1, analysis.snapshotCount())
                .boxed()
                .toList();
        List<String> targetLabels = targetSnapshotIndexes.stream()
                .map(index -> analysis.snapshots().get(index).label())
                .toList();
        toSnapshotBox.setItems(FXCollections.observableArrayList(targetLabels));
        toSnapshotBox.setDisable(targetLabels.isEmpty());
        latestTargetBtn.setDisable(targetLabels.isEmpty());
        if (!targetLabels.isEmpty()) {
            int initialTargetIndex = targetSnapshotIndexes.indexOf(preferredTargetSnapshotIndex);
            if (initialTargetIndex < 0) {
                initialTargetIndex = targetLabels.size() - 1;
            }
            toSnapshotBox.getSelectionModel().select(initialTargetIndex);
            updateSelectedPair();
        } else {
            allDeltas.clear();
            sessionLabel.setText("Need at least two snapshots in the session to use Pair Diff.");
            summaryLabel.setText("Load more snapshots to compare the anchor against a later target.");
            onTargetSnapshotChanged.accept(-1);
        }
    }

    public void clear() {
        analysis = null;
        allDeltas.clear();
        seriesByThreadName = Map.of();
        snapshotCount = 0;
        targetSnapshotIndexes = List.of();
        anchorSnapshotLabel.setText("Session anchor");
        toSnapshotBox.getItems().clear();
        toSnapshotBox.setDisable(true);
        latestTargetBtn.setDisable(true);
        sessionLabel.setText("Pair Diff compares the fixed anchor against one later loaded dump.");
        summaryLabel.setText("Start a session, then choose a later dump from the target picker or the session rail.");
        diffTable.getSelectionModel().clearSelection();
        detailArea.clear();
        onTargetSnapshotChanged.accept(-1);
    }

    public void selectTargetSnapshot(int snapshotIndex) {
        if (snapshotIndex <= 0 || targetSnapshotIndexes.isEmpty()) {
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
        int fromIndex = 0;
        int toIndex = targetSnapshotIndexes.get(targetSelectionIndex);

        DumpDiff diff = dumpDiffer.diff(
                analysis.snapshots().get(fromIndex).dump(),
                analysis.snapshots().get(toIndex).dump());
        allDeltas.setAll(diff.deltas());
        sessionLabel.setText(String.format(
                "Anchor Pair Diff  ·  %s  vs  %s  ·  %d-snapshot session",
                analysis.snapshots().get(fromIndex).label(),
                analysis.snapshots().get(toIndex).label(),
                analysis.snapshotCount()));
        summaryLabel.setText(String.format(
                "+%d added   -%d removed   ~%d changed   =%d same   !%d new blocked   ->%d resolved   !%d stuck",
                diff.addedCount(),
                diff.removedCount(),
                diff.changedCount(),
                diff.unchangedCount(),
                diff.newlyBlockedCount(),
                diff.resolvedBlockedCount(),
                diff.stuckCount()));
        applyFilter();
        diffTable.getSelectionModel().clearSelection();
        detailArea.clear();
        onTargetSnapshotChanged.accept(toIndex);
    }

    private void selectLatestTarget() {
        if (analysis == null || targetSnapshotIndexes.isEmpty()) {
            return;
        }
        toSnapshotBox.getSelectionModel().select(targetSnapshotIndexes.size() - 1);
        updateSelectedPair();
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
        if (series == null) {
            return delta.signals();
        }
        String seriesSignals = series.signalLabel();
        if (seriesSignals.isBlank()) {
            return delta.signals();
        }
        if (delta.signals().isBlank()) {
            return seriesSignals;
        }
        if (seriesSignals.contains(delta.signals())) {
            return seriesSignals;
        }
        return seriesSignals + " · " + delta.signals();
    }
}
