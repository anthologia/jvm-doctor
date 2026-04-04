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
import javafx.stage.Modality;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.Comparator;

public class MainController implements Initializable {

    private static final PseudoClass DRAG_OVER = PseudoClass.getPseudoClass("drag-over");
    private static final String DROP_HINT_STATUS = "Drop a thread dump file to load and analyze.";
    private static final Path SESSION_DIR = Path.of(System.getProperty("user.home"), ".jvm-doctor");
    private static final Path LAST_DUMP_PATH_FILE = SESSION_DIR.resolve("last-dump-path.txt");
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
    @FXML private Label criticalLabel;
    @FXML private Label deadlockedLabel;
    @FXML private Label hotLockLabel;
    @FXML private Label poolIssueLabel;
    @FXML private Label summaryHintLabel;

    // --- Content tabs ---
    @FXML private TabPane contentTabs;
    @FXML private Tab threadsTab;
    @FXML private Tab deadlockTab;
    @FXML private Tab rawTab;

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
    private String currentSourceFilePath;
    private final Set<String> activeStateFilters = new LinkedHashSet<>();
    private String activeMetricFilterKey;
    private Set<String> deadlockedThreadNames = Set.of();
    private Set<String> criticalThreadNames = Set.of();
    private HotLockFocus hotLockFocus = HotLockFocus.empty();
    private PoolIssueFocus poolIssueFocus = PoolIssueFocus.empty();
    private String statusBeforeDrag;
    private boolean stateChartRefreshScheduled = false;

    private final JstackParser parser = new JstackParser();
    private final DeadlockAnalyzer deadlockAnalyzer = new DeadlockAnalyzer();
    private final TopFramesAnalyzer topFramesAnalyzer = new TopFramesAnalyzer();
    private final ThreadPoolGrouper poolGrouper = new ThreadPoolGrouper();
    private final DumpDiffer dumpDiffer = new DumpDiffer();
    private static final List<String> NAV_ITEMS = List.of(
            "Threads", "Deadlock / Issues", "Top Frames", "Thread Pools", "Dump Diff", "Timeline", "Raw Dump");
    private record HotLockFocus(String lockLabel, long waiterCount, Set<String> affectedThreadNames, String ownerThreadName) {
        static HotLockFocus empty() {
            return new HotLockFocus("", 0, Set.of(), null);
        }

        boolean present() {
            return waiterCount > 0;
        }
    }

    private record PoolIssueFocus(long poolCount, Set<String> affectedThreadNames, Set<String> criticalThreadNames) {
        static PoolIssueFocus empty() {
            return new PoolIssueFocus(0, Set.of(), Set.of());
        }

        boolean present() {
            return poolCount > 0 && !affectedThreadNames.isEmpty();
        }
    }
    private final List<Analyzer> analyzers = List.of(
            deadlockAnalyzer,
            new ThreadStateAnalyzer(),
            new LockContentionAnalyzer(),
            new ThreadPoolHealthAnalyzer(),
            new EventLoopBlockingAnalyzer(),
            topFramesAnalyzer
    );

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureFileDrop();
        configureMetricFilters();
        threadTableController.setOnLocateRawRequested(this::locateThreadInRaw);
        threadTableController.setOnStatusMessage(this::updateStatus);
        navList.setItems(FXCollections.observableArrayList(NAV_ITEMS));
        navList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) return;
            Tab target = tabForNavItem(selected);
            if (target != null && contentTabs.getSelectionModel().getSelectedItem() != target) {
                contentTabs.getSelectionModel().select(target);
            }
        });
        contentTabs.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            String navItem = navItemForTab(selected);
            if (navItem != null && !navItem.equals(navList.getSelectionModel().getSelectedItem())) {
                navList.getSelectionModel().select(navItem);
            }
        });
        contentTabs.getSelectionModel().select(threadsTab);
        navList.getSelectionModel().select("Threads");

        updateStatus("Ready. Open, drop, or paste a thread dump to begin.");
    }

    private void configureMetricFilters() {
        configureMetricLabel(totalLabel, () -> {
            clearSummaryThreadFilter();
            updateStatus("Showing all threads.");
        });
        configureMetricLabel(criticalLabel, () -> toggleMetricThreadFilter(
                "critical",
                criticalThreadNames,
                "No critical issue threads in the current dump.",
                "Filtered to threads implicated in critical issues.",
                "Critical issue thread filter cleared."));
        configureMetricLabel(deadlockedLabel, () -> toggleMetricThreadFilter(
                "deadlocked",
                deadlockedThreadNames,
                "No deadlocked threads in the current dump.",
                "Filtered to deadlocked threads.",
                "Deadlocked thread filter cleared."));
        configureMetricLabel(hotLockLabel, this::toggleHotLockMetricFilter);
        configureMetricLabel(poolIssueLabel, this::togglePoolIssueMetricFilter);
    }

    private void configureMetricLabel(Label label, Runnable action) {
        label.getStyleClass().add("metric-number-clickable");
        if (label.getParent() instanceof Region region) {
            region.getStyleClass().add("metric-tile-clickable");
            region.setOnMouseClicked(e -> {
                if (currentDump == null) {
                    return;
                }
                action.run();
            });
            return;
        }
        label.setOnMouseClicked(e -> {
            if (currentDump == null) {
                return;
            }
            action.run();
        });
    }

    private Tab tabForNavItem(String navItem) {
        return switch (navItem) {
            case "Threads" -> threadsTab;
            case "Deadlock / Issues" -> deadlockTab;
            case "Top Frames" -> topFramesTab;
            case "Thread Pools" -> threadPoolTab;
            case "Dump Diff" -> dumpDiffTab;
            case "Timeline" -> timelineTab;
            case "Raw Dump" -> rawTab;
            default -> null;
        };
    }

    private String navItemForTab(Tab tab) {
        if (tab == null) {
            return null;
        }
        if (tab == threadsTab) {
            return "Threads";
        }
        if (tab == deadlockTab) {
            return "Deadlock / Issues";
        }
        if (tab == topFramesTab) {
            return "Top Frames";
        }
        if (tab == threadPoolTab) {
            return "Thread Pools";
        }
        if (tab == dumpDiffTab) {
            return "Dump Diff";
        }
        if (tab == timelineTab) {
            return "Timeline";
        }
        if (tab == rawTab) {
            return "Raw Dump";
        }
        return null;
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
        styleDialog(dialog);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) return ta.getText();
            return null;
        });

        dialog.showAndWait().ifPresent(text -> {
            if (!text.isBlank()) {
                currentSourceFilePath = null;
                applyRawDumpText(text);
                analyzeCurrentDump();
            }
        });
    }

    public void openDumpFile(File file) {
        loadDumpFile(file);
    }

    public boolean hasLoadedDump() {
        return rawDumpText != null && !rawDumpText.isBlank();
    }

    public void promptRestoreLastSessionIfAvailable() {
        if (hasLoadedDump()) {
            return;
        }

        String savedPath = readLastSessionPath();
        if (savedPath == null || savedPath.isBlank()) {
            return;
        }

        File file = new File(savedPath);
        if (!file.isFile()) {
            clearLastSessionPath();
            return;
        }

        ButtonType reopenButton = new ButtonType("Reopen", ButtonBar.ButtonData.OK_DONE);
        ButtonType skipButton = new ButtonType("No", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, file.getAbsolutePath(), reopenButton, skipButton);
        alert.setTitle("Restore Previous Dump");
        alert.setHeaderText("Reopen the last dump file?");
        alert.initModality(Modality.WINDOW_MODAL);
        if (rootPane.getScene() != null && rootPane.getScene().getWindow() != null) {
            alert.initOwner(rootPane.getScene().getWindow());
        }
        styleDialog(alert);
        ButtonType result = alert.showAndWait().orElse(skipButton);
        if (result == reopenButton) {
            openDumpFile(file);
            return;
        }
        clearLastSessionPath();
    }

    private void analyzeCurrentDump() {
        if (rawDumpText == null || rawDumpText.isBlank()) return;
        String dumpText = rawDumpText;

        updateStatus("Analyzing...");

        // Parse + analyze off-thread to keep UI responsive
        Thread worker = new Thread(() -> {
            try {
                ThreadDump dump = parser.parse(dumpText);
                List<AnalysisReport> reports = analyzers.stream()
                        .map(a -> a.analyze(dump))
                        .toList();

                Platform.runLater(() -> {
                    currentDump = dump;
                    threadTableController.setThreads(dump.threads());
                    threadTableController.setIssueContext(deadlockAnalyzer.findDeadlockCycles(dump).stream()
                            .flatMap(List::stream)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
                    updateSummary(dump, reports);
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
        activeStateFilters.clear();
        activeMetricFilterKey = null;
        deadlockedThreadNames = deadlockAnalyzer.findDeadlockCycles(dump).stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        hotLockFocus = findHotLockFocus(dump);
        poolIssueFocus = findPoolIssueFocus(dump);
        criticalThreadNames = findCriticalThreadNames(dump, deadlockedThreadNames, hotLockFocus, poolIssueFocus);

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
                data.getNode().setOnMouseClicked(e -> toggleStateMetricFilter(Set.of(state),
                        "Filtered by state: " + state + "  (click again to clear)"));
            }
            scheduleStateChartRefresh();
        });

        // Metrics
        totalLabel.setText(String.valueOf(dump.threads().size()));
        criticalLabel.setText(String.valueOf(criticalThreadNames.size()));
        deadlockedLabel.setText(String.valueOf(deadlockedThreadNames.size()));
        hotLockLabel.setText(String.valueOf(hotLockFocus.waiterCount()));
        poolIssueLabel.setText(String.valueOf(poolIssueFocus.poolCount()));
        summaryHintLabel.setText(buildSummaryHint(dump, reports));
    }

    private void toggleStateMetricFilter(Set<String> states, String statusMessage) {
        if (activeMetricFilterKey == null && activeStateFilters.equals(states)) {
            clearSummaryThreadFilter();
            updateStatus("State filter cleared.");
            return;
        }

        activeStateFilters.clear();
        activeStateFilters.addAll(states);
        activeMetricFilterKey = null;
        threadTableController.filterByStates(states);
        contentTabs.getSelectionModel().select(threadsTab);
        updateStateChartHighlight();
        updateStatus(statusMessage);
    }

    private void toggleMetricThreadFilter(String key, Set<String> threadNames, String emptyStatus,
                                          String applyStatus, String clearStatus) {
        if (threadNames == null || threadNames.isEmpty()) {
            updateStatus(emptyStatus);
            return;
        }
        if (key.equals(activeMetricFilterKey)) {
            clearSummaryThreadFilter();
            updateStatus(clearStatus);
            return;
        }

        activeStateFilters.clear();
        activeMetricFilterKey = key;
        threadTableController.filterByThreadNames(threadNames);
        contentTabs.getSelectionModel().select(threadsTab);
        updateStateChartHighlight();
        updateStatus(applyStatus);
    }

    private void toggleHotLockMetricFilter() {
        if (!hotLockFocus.present()) {
            updateStatus("No lock hotspot detected in the current dump.");
            return;
        }
        String detail = hotLockFocus.lockLabel() + " with " + hotLockFocus.waiterCount() + " waiter(s)";
        if (hotLockFocus.ownerThreadName() != null && !hotLockFocus.ownerThreadName().isBlank()) {
            detail += " and owner " + hotLockFocus.ownerThreadName();
        }
        toggleMetricThreadFilter(
                "hot-lock",
                hotLockFocus.affectedThreadNames(),
                "No lock hotspot detected in the current dump.",
                "Filtered to hottest lock: " + detail + ".",
                "Hot lock thread filter cleared.");
    }

    private void togglePoolIssueMetricFilter() {
        if (!poolIssueFocus.present()) {
            updateStatus("No unhealthy thread pools detected in the current dump.");
            return;
        }
        toggleMetricThreadFilter(
                "pool-issues",
                poolIssueFocus.affectedThreadNames(),
                "No unhealthy thread pools detected in the current dump.",
                "Filtered to threads in " + poolIssueFocus.poolCount() + " unhealthy pool(s).",
                "Pool issue thread filter cleared.");
    }

    private void clearSummaryThreadFilter() {
        clearSummarySelectionVisuals();
        threadTableController.clearQuickFilters();
        contentTabs.getSelectionModel().select(threadsTab);
    }

    private void clearSummarySelectionVisuals() {
        activeStateFilters.clear();
        activeMetricFilterKey = null;
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
            boolean active = activeStateFilters.isEmpty()
                    || activeStateFilters.contains(extractStateName(data.getName()));
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

    private String buildSummaryHint(ThreadDump dump, List<AnalysisReport> reports) {
        long warningFindings = reports.stream()
                .flatMap(r -> r.findings().stream())
                .filter(f -> f.severity() == AnalysisReport.Severity.WARNING)
                .count();

        String criticalPart = criticalThreadNames.isEmpty()
                ? "No critical threads"
                : criticalThreadNames.size() + " critical thread(s)";
        String hotspotPart = hotLockFocus.present()
                ? hotLockFocus.lockLabel() + " hottest lock (" + hotLockFocus.waiterCount() + " waiter(s))"
                : "No hot lock";
        String poolPart = poolIssueFocus.present()
                ? poolIssueFocus.poolCount() + " unhealthy pool(s)"
                : "No unhealthy pools";

        if (warningFindings > 0) {
            return criticalPart + "  ·  " + poolPart + "  ·  " + hotspotPart + "  ·  " + warningFindings + " warning(s)";
        }
        return criticalPart + "  ·  " + poolPart + "  ·  " + hotspotPart;
    }

    private HotLockFocus findHotLockFocus(ThreadDump dump) {
        Map<String, Long> waitersPerLock = dump.threads().stream()
                .filter(t -> t.waitingOnLock() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        t -> t.waitingOnLock().lockId(),
                        java.util.stream.Collectors.counting()));
        if (waitersPerLock.isEmpty()) {
            return HotLockFocus.empty();
        }

        Map<String, String> lockLabelById = dump.threads().stream()
                .filter(t -> t.waitingOnLock() != null)
                .collect(java.util.stream.Collectors.toMap(
                        t -> t.waitingOnLock().lockId(),
                        t -> {
                            String className = t.waitingOnLock().lockClassName();
                            return className == null || className.isBlank() ? t.waitingOnLock().lockId() : className;
                        },
                        (a, b) -> a));
        Map<String, String> ownerByLockId = new java.util.HashMap<>();
        for (var thread : dump.threads()) {
            if (thread.heldLocks() == null) {
                continue;
            }
            for (var lock : thread.heldLocks()) {
                ownerByLockId.put(lock.lockId(), thread.name());
            }
        }

        var hottest = waitersPerLock.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .orElse(null);
        if (hottest == null) {
            return HotLockFocus.empty();
        }

        String lockId = hottest.getKey();
        Set<String> affectedThreads = dump.threads().stream()
                .filter(t -> t.waitingOnLock() != null && lockId.equals(t.waitingOnLock().lockId()))
                .map(t -> t.name())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String ownerThread = ownerByLockId.get(lockId);
        if (ownerThread != null) {
            affectedThreads.add(ownerThread);
        }

        String lockLabel = lockLabelById.getOrDefault(lockId, lockId);
        return new HotLockFocus(lockLabel, hottest.getValue(), Set.copyOf(affectedThreads), ownerThread);
    }

    private PoolIssueFocus findPoolIssueFocus(ThreadDump dump) {
        Set<String> affectedThreads = new LinkedHashSet<>();
        Set<String> criticalPoolThreads = new LinkedHashSet<>();

        List<io.jvmdoctor.analyzer.ThreadPool> issuePools = poolGrouper.group(dump).stream()
                .filter(this::isIssuePool)
                .toList();

        for (var pool : issuePools) {
            pool.threads().stream()
                    .map(t -> t.name())
                    .forEach(affectedThreads::add);
            if (isCriticalPool(pool)) {
                pool.threads().stream()
                        .map(t -> t.name())
                        .forEach(criticalPoolThreads::add);
            }
        }

        return new PoolIssueFocus(issuePools.size(), Set.copyOf(affectedThreads), Set.copyOf(criticalPoolThreads));
    }

    private Set<String> findCriticalThreadNames(ThreadDump dump, Set<String> deadlockedThreads,
                                                HotLockFocus hotLockFocus, PoolIssueFocus poolIssueFocus) {
        Set<String> criticalThreads = new LinkedHashSet<>(deadlockedThreads);
        if (hotLockFocus.waiterCount() >= 5) {
            criticalThreads.addAll(hotLockFocus.affectedThreadNames());
        }
        criticalThreads.addAll(poolIssueFocus.criticalThreadNames());
        dump.threads().stream()
                .filter(this::isBlockedInfrastructureThread)
                .map(t -> t.name())
                .forEach(criticalThreads::add);
        return Set.copyOf(criticalThreads);
    }

    private boolean isIssuePool(io.jvmdoctor.analyzer.ThreadPool pool) {
        return pool.total() >= 3 && ("Contended".equals(pool.health()) || "Starved".equals(pool.health()));
    }

    private boolean isCriticalPool(io.jvmdoctor.analyzer.ThreadPool pool) {
        return (pool.waiting() >= Math.max(3, Math.round(pool.total() * 0.8)) && pool.runnable() == 0 && pool.total() >= 8)
                || pool.blocked() >= 4;
    }

    private boolean isBlockedInfrastructureThread(io.jvmdoctor.model.ThreadInfo thread) {
        String name = thread.name();
        return "BLOCKED".equalsIgnoreCase(thread.state())
                && (name.matches("^nioEventLoopGroup-\\d+-\\d+$")
                || name.matches("^qtp\\d+-\\d+$")
                || name.matches("^((?:http|https|ajp)-nio(?:-\\d+)?)-exec-\\d+$")
                || name.matches("^OkHttp\\s+.*")
                || name.matches("^grpc-.*"));
    }

    private void locateThreadInRaw(io.jvmdoctor.model.ThreadInfo thread) {
        if (thread == null || rawDumpText == null || rawDumpText.isBlank()) {
            return;
        }

        String quotedName = "\"" + thread.name() + "\"";
        int idx = rawDumpText.indexOf(quotedName);
        if (idx < 0) {
            updateStatus("Could not locate " + thread.name() + " in the raw dump.");
            return;
        }

        int lineStart = rawDumpText.lastIndexOf('\n', idx);
        int lineEnd = rawDumpText.indexOf('\n', idx);
        int start = lineStart >= 0 ? lineStart + 1 : idx;
        int end = lineEnd >= 0 ? lineEnd : rawDumpText.length();

        contentTabs.getSelectionModel().select(rawTab);
        rawTextArea.requestFocus();
        rawTextArea.selectRange(start, end);
        rawTextArea.positionCaret(start);
        updateStatus("Located thread in Raw Dump: " + thread.name());
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
        alert.initModality(Modality.WINDOW_MODAL);
        if (rootPane.getScene() != null && rootPane.getScene().getWindow() != null) {
            alert.initOwner(rootPane.getScene().getWindow());
        }
        styleDialog(alert);
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
            currentSourceFilePath = file.getAbsoluteFile().toPath().normalize().toString();
            persistLastSessionPath(currentSourceFilePath);
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

    private void persistLastSessionPath(String sourceFilePath) {
        if (sourceFilePath == null || sourceFilePath.isBlank()) {
            clearLastSessionPath();
            return;
        }
        try {
            Files.createDirectories(SESSION_DIR);
            Files.writeString(LAST_DUMP_PATH_FILE, sourceFilePath);
        } catch (IOException ignored) {
        }
    }

    private void clearLastSessionPath() {
        try {
            Files.deleteIfExists(LAST_DUMP_PATH_FILE);
        } catch (IOException ignored) {
        }
    }

    private String readLastSessionPath() {
        try {
            if (!Files.isRegularFile(LAST_DUMP_PATH_FILE)) {
                return null;
            }
            String savedPath = Files.readString(LAST_DUMP_PATH_FILE).trim();
            return savedPath.isBlank() ? null : savedPath;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void styleDialog(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        String stylesheet = getClass().getResource("/io/jvmdoctor/style.css").toExternalForm();
        if (!pane.getStylesheets().contains(stylesheet)) {
            pane.getStylesheets().add(stylesheet);
        }
        pane.getStyleClass().add("dialog-pane-dark");
        pane.setMinHeight(Region.USE_PREF_SIZE);
    }
}
