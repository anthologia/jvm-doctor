package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.*;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.parser.JstackParser;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.chart.PieChart;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.Comparator;

public class MainController implements Initializable {

    private static final PseudoClass DRAG_OVER = PseudoClass.getPseudoClass("drag-over");
    private static final String DROP_HINT_STATUS = "Drop a thread dump file to load and analyze.";
    private static final Map<String, String> STATE_COLORS = Map.of(
            "BLOCKED", "#c73a4f",
            "WAITING", "#b99132",
            "TIMED_WAITING", "#8c6a45",
            "RUNNABLE", "#3677e0",
            "NEW", "#628c7b",
            "TERMINATED", "#70798a",
            "UNKNOWN", "#7c8898"
    );

    // --- Toolbar ---
    @FXML private BorderPane rootPane;
    @FXML private Button openFileBtn;
    @FXML private Button pasteDumpBtn;

    // --- Left nav ---
    @FXML private ListView<String> navList;

    // --- Summary panel ---
    @FXML private PieChart stateChart;
    @FXML private FlowPane stateLegend;
    @FXML private Label totalLabel;
    @FXML private Label blockedLabel;
    @FXML private Label deadlockLabel;

    // --- Content tabs ---
    @FXML private TabPane contentTabs;
    @FXML private Tab threadsTab;
    @FXML private Tab deadlockTab;

    // --- Thread table (injected sub-controller) ---
    @FXML private ThreadTableController threadTableController;

    // --- Deadlock view (injected sub-controller) ---
    @FXML private DeadlockViewController deadlockViewController;

    // --- Top Frames (injected sub-controller) ---
    @FXML private TopFramesController topFramesController;
    @FXML private Tab topFramesTab;

    // --- Thread Pool (injected sub-controller) ---
    @FXML private ThreadPoolController threadPoolController;
    @FXML private Tab threadPoolTab;

    // --- Dump Diff (injected sub-controller) ---
    @FXML private DumpDiffController dumpDiffController;
    @FXML private Tab dumpDiffTab;
    @FXML private Button compareDumpsBtn;

    // --- Timeline (injected sub-controller) ---
    @FXML private TimelineController timelineController;
    @FXML private Tab timelineTab;

    // --- Raw tab ---
    @FXML private TextArea rawTextArea;
    @FXML private TextField rawSearchField;
    @FXML private Label rawMatchLabel;
    private int rawSearchFrom = 0;

    // --- Status bar ---
    @FXML private Label statusLabel;

    private ThreadDump currentDump;
    private String rawDumpText;
    private String activeStateFilter = null;
    private boolean deadlockMetricFilterActive = false;
    private Set<String> deadlockedThreadNames = Set.of();
    private String statusBeforeDrag;
    private boolean stateChartRefreshScheduled = false;

    private final JstackParser parser = new JstackParser();
    private final DeadlockAnalyzer deadlockAnalyzer = new DeadlockAnalyzer();
    private final TopFramesAnalyzer topFramesAnalyzer = new TopFramesAnalyzer();
    private final ThreadPoolGrouper poolGrouper = new ThreadPoolGrouper();
    private final DumpDiffer dumpDiffer = new DumpDiffer();
    private final List<Analyzer> analyzers = List.of(
            deadlockAnalyzer,
            new ThreadStateAnalyzer(),
            new LockContentionAnalyzer(),
            topFramesAnalyzer
    );

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureFileDrop();
        configureMetricFilters();
        navList.setItems(FXCollections.observableArrayList("Summary", "Deadlock", "Threads", "Top Frames", "Thread Pools", "Dump Diff", "Timeline", "Locks"));
        navList.getSelectionModel().select(0);
        navList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) return;
            switch (selected) {
                case "Deadlock"      -> contentTabs.getSelectionModel().select(deadlockTab);
                case "Threads"       -> contentTabs.getSelectionModel().select(threadsTab);
                case "Top Frames"    -> contentTabs.getSelectionModel().select(topFramesTab);
                case "Thread Pools"  -> contentTabs.getSelectionModel().select(threadPoolTab);
                case "Dump Diff"     -> contentTabs.getSelectionModel().select(dumpDiffTab);
                case "Timeline"      -> contentTabs.getSelectionModel().select(timelineTab);
                default              -> contentTabs.getSelectionModel().select(0);
            }
        });

        updateStatus("Ready. Open, drop, or paste a thread dump to begin.");
    }

    private void configureMetricFilters() {
        configureMetricLabel(totalLabel, () -> {
            clearSummaryThreadFilter();
            updateStatus("Showing all threads.");
        });
        configureMetricLabel(blockedLabel, () -> toggleStateMetricFilter("BLOCKED"));
        configureMetricLabel(deadlockLabel, this::toggleDeadlockMetricFilter);
    }

    private void configureMetricLabel(Label label, Runnable action) {
        label.getStyleClass().add("metric-number-clickable");
        label.setOnMouseClicked(e -> {
            if (currentDump == null) {
                return;
            }
            action.run();
        });
    }

    @FXML
    private void onOpenFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Thread Dump");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.log", "*.dump"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = chooser.showOpenDialog(openFileBtn.getScene().getWindow());
        if (file == null) return;
        loadDumpFile(file);
    }

    @FXML
    private void onPasteDump() {
        Dialog<String> dialog = new TextInputDialog();
        dialog.setTitle("Paste Thread Dump");
        dialog.setHeaderText("Paste your jstack / thread dump output below:");
        dialog.setGraphic(null);
        dialog.setContentText(null);

        // Replace the default editor with a TextArea
        TextArea ta = new TextArea();
        ta.setWrapText(false);
        ta.setPrefRowCount(20);
        ta.setPrefColumnCount(80);
        ((TextInputDialog) dialog).getEditor().setVisible(false);
        ((TextInputDialog) dialog).getEditor().setManaged(false);
        dialog.getDialogPane().setContent(ta);
        dialog.getDialogPane().setPrefWidth(700);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) return ta.getText();
            return null;
        });

        dialog.showAndWait().ifPresent(text -> {
            if (!text.isBlank()) {
                applyRawDumpText(text);
                analyzeCurrentDump();
            }
        });
    }

    public void openDumpFile(File file) {
        loadDumpFile(file);
    }

    private void analyzeCurrentDump() {
        if (rawDumpText == null || rawDumpText.isBlank()) return;

        updateStatus("Analyzing...");

        // Parse + analyze off-thread to keep UI responsive
        Thread worker = new Thread(() -> {
            try {
                ThreadDump dump = parser.parse(rawDumpText);
                List<AnalysisReport> reports = analyzers.stream()
                        .map(a -> a.analyze(dump))
                        .toList();

                Platform.runLater(() -> {
                    currentDump = dump;
                    updateSummary(dump, reports);
                    threadTableController.setThreads(dump.threads());
                    deadlockViewController.setReports(reports, dump);
                    topFramesController.setFrames(topFramesAnalyzer.topFrames(dump, 100));
                    threadPoolController.setPools(poolGrouper.group(dump));
                    topFramesController.setOnFrameClicked(frameKey -> {
                        clearSummarySelectionVisuals();
                        threadTableController.filterByFrame(frameKey);
                        if (frameKey != null) {
                            contentTabs.getSelectionModel().select(threadsTab);
                            updateStatus("Filtered to threads containing: " + frameKey + "  (double-click again to clear)");
                        } else {
                            updateStatus("Frame filter cleared.");
                        }
                    });
                    updateStatus("Analysis complete. " + dump.threads().size() + " threads parsed.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Parse error: " + e.getMessage());
                });
            }
        }, "jvm-doctor-analyzer");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateSummary(ThreadDump dump, List<AnalysisReport> reports) {
        activeStateFilter = null;
        deadlockMetricFilterActive = false;
        deadlockedThreadNames = deadlockAnalyzer.findDeadlockCycles(dump).stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        // Pie chart
        Map<String, Long> dist = dump.stateDistribution();
        var chartData = FXCollections.<PieChart.Data>observableArrayList(
                dist.entrySet().stream()
                        .sorted(Comparator
                                .comparingInt((Map.Entry<String, Long> entry) -> statePriority(entry.getKey()))
                                .thenComparing(Map.Entry::getKey))
                        .map(entry -> new PieChart.Data(formatStateLabel(entry.getKey(), entry.getValue()), entry.getValue()))
                        .toList()
        );
        stateChart.setData(chartData);
        stateChart.setLabelsVisible(true);
        stateChart.setLegendVisible(false);

        Platform.runLater(() -> {
            for (PieChart.Data data : stateChart.getData()) {
                String state = extractStateName(data.getName());
                data.getNode().setStyle("-fx-cursor: hand;");
                data.getNode().setOnMouseClicked(e -> toggleStateMetricFilter(state));
            }
            scheduleStateChartRefresh();
        });

        // Metrics
        totalLabel.setText(String.valueOf(dump.threads().size()));
        blockedLabel.setText(String.valueOf(dump.blockedCount()));

        long deadlocks = reports.stream()
                .filter(r -> r.analyzerName().equals("Deadlock Analyzer"))
                .flatMap(r -> r.findings().stream())
                .filter(f -> f.severity() == AnalysisReport.Severity.CRITICAL)
                .count();
        deadlockLabel.setText(String.valueOf(deadlocks));
        if (deadlocks > 0) {
            deadlockLabel.getStyleClass().add("metric-number-deadlock-active");
        } else {
            deadlockLabel.getStyleClass().remove("metric-number-deadlock-active");
        }
    }

    private void toggleStateMetricFilter(String state) {
        if (state.equals(activeStateFilter) && !deadlockMetricFilterActive) {
            clearSummaryThreadFilter();
            updateStatus("State filter cleared.");
            return;
        }

        activeStateFilter = state;
        deadlockMetricFilterActive = false;
        threadTableController.filterByState(state);
        contentTabs.getSelectionModel().select(threadsTab);
        updateStateChartHighlight();
        updateStatus("Filtered by state: " + state + "  (click again to clear)");
    }

    private void toggleDeadlockMetricFilter() {
        if (deadlockedThreadNames.isEmpty()) {
            updateStatus("No deadlocked threads in the current dump.");
            return;
        }
        if (deadlockMetricFilterActive) {
            clearSummaryThreadFilter();
            updateStatus("Deadlock thread filter cleared.");
            return;
        }

        activeStateFilter = null;
        deadlockMetricFilterActive = true;
        threadTableController.filterByThreadNames(deadlockedThreadNames);
        contentTabs.getSelectionModel().select(threadsTab);
        updateStateChartHighlight();
        updateStatus("Filtered to threads involved in deadlocks.");
    }

    private void clearSummaryThreadFilter() {
        clearSummarySelectionVisuals();
        threadTableController.clearQuickFilters();
        contentTabs.getSelectionModel().select(threadsTab);
    }

    private void clearSummarySelectionVisuals() {
        activeStateFilter = null;
        deadlockMetricFilterActive = false;
        updateStateChartHighlight();
    }

    private void scheduleStateChartRefresh() {
        if (stateChartRefreshScheduled) {
            return;
        }
        stateChartRefreshScheduled = true;
        Platform.runLater(() -> {
            stateChartRefreshScheduled = false;
            refreshStateChartDecorations();
        });
    }

    private void refreshStateChartDecorations() {
        if (stateChart.getData() == null) {
            return;
        }

        stateLegend.getChildren().clear();
        boolean needsRetry = false;

        for (PieChart.Data data : stateChart.getData()) {
            createLegendItem(data);
            Node slice = data.getNode();
            if (slice == null) {
                needsRetry = true;
                continue;
            }
            slice.setStyle("-fx-pie-color: " + colorForState(extractStateName(data.getName())) + "; -fx-cursor: hand;");
        }

        updateStateChartHighlight();
        if (needsRetry) {
            scheduleStateChartRefresh();
        }
    }

    private void updateStateChartHighlight() {
        if (stateChart.getData() == null) {
            return;
        }
        stateChart.getData().forEach(data -> {
            if (data.getNode() == null) {
                return;
            }
            boolean active = activeStateFilter == null || extractStateName(data.getName()).equals(activeStateFilter);
            data.getNode().setOpacity(active ? 1.0 : 0.35);
        });
    }

    private void createLegendItem(PieChart.Data data) {
        String state = extractStateName(data.getName());
        Region swatch = new Region();
        swatch.getStyleClass().add("state-legend-swatch");
        swatch.setStyle("-fx-background-color: " + colorForState(state) + ";");

        Label stateLabel = new Label(state);
        stateLabel.getStyleClass().add("state-legend-label");

        Label countLabel = new Label(String.valueOf((long) data.getPieValue()));
        countLabel.getStyleClass().add("state-legend-count");

        HBox item = new HBox(6, swatch, stateLabel, countLabel);
        item.getStyleClass().add("state-legend-item");
        stateLegend.getChildren().add(item);
    }

    private String colorForState(String state) {
        return STATE_COLORS.getOrDefault(state.toUpperCase(), STATE_COLORS.get("UNKNOWN"));
    }

    private String formatStateLabel(String state, long count) {
        return state + " (" + count + ")";
    }

    private String extractStateName(String label) {
        return label.replaceFirst("\\s*\\(\\d+\\)$", "");
    }

    private int statePriority(String state) {
        return switch (state.toUpperCase()) {
            case "BLOCKED" -> 0;
            case "WAITING" -> 1;
            case "TIMED_WAITING" -> 2;
            case "RUNNABLE" -> 3;
            case "NEW" -> 4;
            case "TERMINATED" -> 5;
            default -> 99;
        };
    }

    @FXML
    private void onRawSearch() {
        String query = rawSearchField.getText();
        if (query.isEmpty()) { rawMatchLabel.setText(""); return; }

        String content = rawTextArea.getText();
        if (content.isEmpty()) { rawMatchLabel.setText("No content"); return; }

        // 전체 매치 수 계산
        int total = 0;
        int pos = 0;
        while ((pos = content.indexOf(query, pos)) >= 0) { total++; pos++; }
        if (total == 0) {
            rawMatchLabel.setText("Not found");
            rawSearchFrom = 0;
            return;
        }

        // 현재 위치에서 다음 매치 탐색 (끝에 도달하면 wrap)
        int idx = content.indexOf(query, rawSearchFrom);
        if (idx < 0) {
            idx = content.indexOf(query, 0);
            rawSearchFrom = 0;
        }
        rawTextArea.selectRange(idx, idx + query.length());
        rawTextArea.requestFocus();
        rawSearchFrom = idx + 1;

        // 현재 몇 번째인지 표시
        int current = 0;
        pos = 0;
        while ((pos = content.indexOf(query, pos)) >= 0) {
            current++;
            if (pos >= idx) break;
            pos++;
        }
        rawMatchLabel.setText(current + " / " + total);
    }

    @FXML
    private void onCompareDumps() {
        if (currentDump == null) {
            showError("Open and analyze a dump first (this will be the baseline).");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Second Dump (current)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.log", "*.dump"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = chooser.showOpenDialog(compareDumpsBtn.getScene().getWindow());
        if (file == null) return;

        updateStatus("Comparing dumps…");
        ThreadDump baseline = currentDump;
        Thread worker = new Thread(() -> {
            try {
                String text = Files.readString(file.toPath());
                ThreadDump current = parser.parse(text);
                DumpDiff diff = dumpDiffer.diff(baseline, current);
                Platform.runLater(() -> {
                    dumpDiffController.setDiff(diff);
                    contentTabs.getSelectionModel().select(dumpDiffTab);
                    updateStatus("Diff complete: +" + diff.addedCount() + " added, -"
                            + diff.removedCount() + " removed, ~" + diff.changedCount() + " changed.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("Diff error: " + e.getMessage()));
            }
        }, "jvm-doctor-differ");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateStatus(String msg) {
        statusLabel.setText(msg);
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        alert.setTitle("Error");
        alert.showAndWait();
        updateStatus("Error: " + msg);
    }

    private void configureFileDrop() {
        rootPane.addEventFilter(DragEvent.DRAG_OVER, this::handleDragOver);
        rootPane.addEventFilter(DragEvent.DRAG_ENTERED_TARGET, this::handleDragEntered);
        rootPane.addEventFilter(DragEvent.DRAG_EXITED_TARGET, this::handleDragExited);
        rootPane.addEventFilter(DragEvent.DRAG_DROPPED, this::handleDragDropped);
    }

    private void handleDragOver(DragEvent event) {
        if (firstDroppedFile(event.getDragboard()) != null) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void handleDragEntered(DragEvent event) {
        if (firstDroppedFile(event.getDragboard()) == null) {
            return;
        }
        if (!rootPane.getPseudoClassStates().contains(DRAG_OVER)) {
            statusBeforeDrag = statusLabel.getText();
        }
        rootPane.pseudoClassStateChanged(DRAG_OVER, true);
        updateStatus(DROP_HINT_STATUS);
        event.consume();
    }

    private void handleDragExited(DragEvent event) {
        clearDragState();
        event.consume();
    }

    private void handleDragDropped(DragEvent event) {
        File file = firstDroppedFile(event.getDragboard());
        boolean success = file != null && loadDumpFile(file);
        clearDragState();
        event.setDropCompleted(success);
        event.consume();
    }

    private void clearDragState() {
        rootPane.pseudoClassStateChanged(DRAG_OVER, false);
        if (DROP_HINT_STATUS.equals(statusLabel.getText()) && statusBeforeDrag != null) {
            updateStatus(statusBeforeDrag);
        }
        statusBeforeDrag = null;
    }

    private File firstDroppedFile(Dragboard dragboard) {
        if (dragboard == null || !dragboard.hasFiles()) {
            return null;
        }
        return dragboard.getFiles().stream()
                .filter(File::isFile)
                .findFirst()
                .orElse(null);
    }

    private boolean loadDumpFile(File file) {
        if (file == null) {
            return false;
        }
        if (!file.isFile()) {
            showError("Not a file: " + file.getAbsolutePath());
            return false;
        }

        try {
            String dumpText = Files.readString(file.toPath());
            applyRawDumpText(dumpText);
            updateStatus("Loaded: " + file.getName() + " (" + rawDumpText.length() + " chars)");
            analyzeCurrentDump();
            return true;
        } catch (IOException e) {
            showError("Failed to read file: " + e.getMessage());
            return false;
        }
    }

    private void applyRawDumpText(String dumpText) {
        rawDumpText = dumpText;
        rawTextArea.setText(rawDumpText);
        rawSearchField.clear();
        rawMatchLabel.setText("");
        rawSearchFrom = 0;
    }
}
