package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.*;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.parser.JstackParser;
import io.jvmdoctor.service.LastDumpSessionStore;
import io.jvmdoctor.service.SingleDumpAnalysisService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;

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
    @FXML private SplitPane singleDumpSummaryPane;
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
    @FXML private SplitPane workspaceSplit;
    @FXML private StackPane snapshotReviewRail;
    @FXML private SnapshotReviewRailController snapshotReviewRailController;

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

    // --- Timeline (injected sub-controller) ---
    @FXML private TimelineController timelineController;
    @FXML private Tab timelineTab;

    // --- Flame Graph (injected sub-controller) ---
    @FXML private FlameGraphController flameGraphController;
    @FXML private Tab flameGraphTab;

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
    private MultiDumpAnalysis multiDumpAnalysis;
    private final ObservableList<TimelineSnapshot> sessionSnapshotItems = FXCollections.observableArrayList();
    private int sessionTargetSnapshotIndex = -1;
    private double workspaceDividerPosition = 0.78;
    private boolean sessionSidebarCollapsedState = false;
    private boolean snapshotReviewBusy = false;
    private String statusBeforeDrag;
    private boolean stateChartRefreshScheduled = false;

    private final JstackParser parser = new JstackParser();
    private final DeadlockAnalyzer deadlockAnalyzer = new DeadlockAnalyzer();
    private final TopFramesAnalyzer topFramesAnalyzer = new TopFramesAnalyzer();
    private final ThreadPoolGrouper poolGrouper = new ThreadPoolGrouper();
    private final MultiDumpAnalyzer multiDumpAnalyzer = new MultiDumpAnalyzer();
    private static final List<String> NAV_ITEMS = List.of(
            "Threads", "Deadlock / Issues", "Top Frames", "Thread Pools", "Flame Graph", "Raw Dump");
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
    private final SingleDumpAnalysisService singleDumpAnalysisService = new SingleDumpAnalysisService(parser, analyzers);
    private final LastDumpSessionStore lastDumpSessionStore = new LastDumpSessionStore();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureFileDrop();
        configureMetricFilters();
        configureSnapshotReviewRail();
        threadTableController.setOnLocateRawRequested(this::locateThreadInRaw);
        threadTableController.setOnStatusMessage(this::updateStatus);
        dumpDiffController.setOnTargetSnapshotChanged(this::handleSessionTargetSnapshotChanged);
        dumpDiffController.clear();
        timelineController.clear();
        updateSessionActionButton();
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
            if (navItem != null) {
                if (!navItem.equals(navList.getSelectionModel().getSelectedItem())) {
                    navList.getSelectionModel().select(navItem);
                }
            } else {
                navList.getSelectionModel().clearSelection();
            }
            updateSummaryModeForTab(selected);
        });
        if (!workspaceSplit.getDividers().isEmpty()) {
            workspaceSplit.getDividers().get(0).positionProperty().addListener((obs, old, updated) -> {
                if (!sessionSidebarCollapsedState && updated.doubleValue() < 0.98) {
                    workspaceDividerPosition = updated.doubleValue();
                }
            });
        }
        contentTabs.getSelectionModel().select(threadsTab);
        navList.getSelectionModel().select("Threads");
        updateSummaryModeForTab(threadsTab);

        updateStatus("Ready. Open, drop, or paste a thread dump to begin. The snapshot review rail on the right stays in sync.");
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

    private void configureSnapshotReviewRail() {
        snapshotReviewRailController.setOnCollapse(() -> setSessionSidebarCollapsed(true));
        snapshotReviewRailController.setOnExpand(() -> setSessionSidebarCollapsed(false));
        snapshotReviewRailController.setOnAddSnapshots(this::onCompareDumps);
        snapshotReviewRailController.setOnOpenTimeline(this::onOpenTimelineTab);
        snapshotReviewRailController.setOnOpenCompare(this::onOpenDumpDiffTab);
        snapshotReviewRailController.setOnReset(this::onClearSession);
        snapshotReviewRailController.setOnMakeBaseline(this::onMakeSelectedBaseline);
        snapshotReviewRailController.setOnUseTarget(this::onUseSelectedSessionTarget);
        snapshotReviewRailController.setOnRemove(this::onRemoveSelectedSessionSnapshot);
        snapshotReviewRailController.setOnSnapshotDoubleClick(index -> {
            if (multiDumpAnalysis == null) {
                onMakeSelectedBaseline();
                return;
            }
            if (index != multiDumpAnalysis.baselineIndex()) {
                onUseSelectedSessionTarget();
            }
        });
        refreshSnapshotReviewRail();
    }

    private Tab tabForNavItem(String navItem) {
        return switch (navItem) {
            case "Threads" -> threadsTab;
            case "Deadlock / Issues" -> deadlockTab;
            case "Top Frames" -> topFramesTab;
            case "Thread Pools" -> threadPoolTab;
            case "Flame Graph" -> flameGraphTab;
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
        if (tab == flameGraphTab) {
            return "Flame Graph";
        }
        if (tab == rawTab) {
            return "Raw Dump";
        }
        return null;
    }

    private void onOpenTimelineTab() {
        if (!hasMultiDumpReview()) {
            return;
        }
        contentTabs.getSelectionModel().select(timelineTab);
    }

    private void onOpenDumpDiffTab() {
        if (!hasMultiDumpReview()) {
            return;
        }
        contentTabs.getSelectionModel().select(dumpDiffTab);
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
                clearMultiDumpSession(false);
                contentTabs.getSelectionModel().select(threadsTab);
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

        String savedPath = lastDumpSessionStore.load();
        if (savedPath == null || savedPath.isBlank()) {
            return;
        }

        File file = new File(savedPath);
        if (!file.isFile()) {
            lastDumpSessionStore.clear();
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
        lastDumpSessionStore.clear();
    }

    private void analyzeCurrentDump() {
        if (rawDumpText == null || rawDumpText.isBlank()) return;
        String dumpText = rawDumpText;
        String sourcePath = currentSourceFilePath;

        updateStatus("Analyzing...");
        snapshotReviewBusy = true;
        updateSessionActionButton();

        // Parse + analyze off-thread to keep UI responsive
        Thread worker = new Thread(() -> {
            try {
                SingleDumpAnalysisService.AnalysisResult result = singleDumpAnalysisService.analyze(dumpText, sourcePath);

                Platform.runLater(() -> {
                    applyCurrentDumpAnalysis(
                            result,
                            "Analysis complete. " + result.dump().threads().size() + " threads parsed.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    snapshotReviewBusy = false;
                    updateSessionActionButton();
                    showError("Parse error: " + e.getMessage());
                });
            }
        }, "jvm-doctor-analyzer");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyCurrentDumpAnalysis(SingleDumpAnalysisService.AnalysisResult result, String statusMessage) {
        currentDump = result.dump();
        currentSourceFilePath = result.sourcePath();
        applyRawDumpText(result.rawDumpText());
        if (result.sourcePath() == null || result.sourcePath().isBlank()) {
            lastDumpSessionStore.clear();
        } else {
            lastDumpSessionStore.save(result.sourcePath());
        }
        snapshotReviewBusy = false;
        refreshSnapshotReviewRail();
        updateSessionActionButton();
        threadTableController.setThreads(result.dump().threads());
        threadTableController.setIssueContext(deadlockAnalyzer.findDeadlockCycles(result.dump()).stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        updateSummary(result.dump(), result.reports());
        deadlockViewController.setReports(result.reports(), result.dump());
        topFramesController.setFrames(topFramesAnalyzer.topFrames(result.dump(), 100));
        threadPoolController.setPools(poolGrouper.group(result.dump()));
        flameGraphController.setDump(result.dump());
        flameGraphController.setOnFrameFocused(topFramesController::highlightFrame);
        flameGraphController.setOnFrameFilterRequested(frameKey -> {
            clearSummarySelectionVisuals();
            topFramesController.highlightFrame(frameKey);
            threadTableController.filterByFrame(frameKey);
            contentTabs.getSelectionModel().select(threadsTab);
            updateStatus("Filtered to threads containing: " + frameKey);
        });
        flameGraphController.setOnRevealInTopFramesRequested(frameKey -> {
            topFramesController.highlightFrame(frameKey);
            contentTabs.getSelectionModel().select(topFramesTab);
            updateStatus("Revealed frame in Top Frames: " + frameKey);
        });
        flameGraphController.setOnThreadSelected(threadName -> {
            contentTabs.getSelectionModel().select(threadsTab);
            threadTableController.selectAndRevealThread(threadName);
            updateStatus("Navigated to thread: " + threadName);
        });
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
        updateStatus(statusMessage);
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

    private void updateSummaryModeForTab(Tab selectedTab) {
        boolean multiDumpMode = selectedTab == dumpDiffTab || selectedTab == timelineTab;
        singleDumpSummaryPane.setVisible(!multiDumpMode);
        singleDumpSummaryPane.setManaged(!multiDumpMode);
        setSessionSidebarCollapsed(sessionSidebarCollapsedState);
        refreshSnapshotReviewRail(selectedTab);
    }

    private TimelineSnapshot currentBaselineSnapshot() {
        return new TimelineSnapshot(currentDumpLabel(), currentSourceFilePath, rawDumpText, currentDump);
    }

    private String currentDumpLabel() {
        if (currentSourceFilePath == null || currentSourceFilePath.isBlank()) {
            return currentDump != null ? "Pasted Dump" : "Current Dump";
        }
        try {
            return Path.of(currentSourceFilePath).getFileName().toString();
        } catch (Exception ignored) {
            return currentSourceFilePath;
        }
    }

    private boolean hasMultiDumpReview() {
        return multiDumpAnalysis != null && multiDumpAnalysis.snapshotCount() >= 2;
    }

    private void setSessionSidebarCollapsed(boolean collapsed) {
        sessionSidebarCollapsedState = collapsed;
        snapshotReviewRailController.setCollapsed(collapsed);
        snapshotReviewRail.setMinWidth(collapsed ? 40 : 240);
        snapshotReviewRail.setPrefWidth(collapsed ? 40 : Math.max(260, snapshotReviewRail.getPrefWidth()));
        snapshotReviewRail.setMaxWidth(collapsed ? 40 : Double.MAX_VALUE);
        if (!workspaceSplit.getDividers().isEmpty()) {
            workspaceSplit.setDividerPositions(collapsed ? 0.975 : workspaceDividerPosition);
        }
    }

    private int resolvePreferredTargetIndex(MultiDumpAnalysis analysis, int preferredTargetSnapshotIndex) {
        if (analysis == null || analysis.snapshotCount() <= 1) {
            return -1;
        }
        if (preferredTargetSnapshotIndex >= 0
                && preferredTargetSnapshotIndex < analysis.snapshotCount()
                && preferredTargetSnapshotIndex != analysis.baselineIndex()) {
            return preferredTargetSnapshotIndex;
        }
        return analysis.defaultComparisonIndex();
    }

    private void refreshSnapshotReviewRail() {
        refreshSnapshotReviewRail(contentTabs == null ? null : contentTabs.getSelectionModel().getSelectedItem());
    }

    private void refreshSnapshotReviewRail(Tab selectedTab) {
        Tab activeTab = selectedTab;
        if (activeTab == null && contentTabs != null) {
            activeTab = contentTabs.getSelectionModel().getSelectedItem();
        }

        List<TimelineSnapshot> snapshots;
        int selectedIndex = -1;
        String statusText;
        if (multiDumpAnalysis == null) {
            if (currentDump != null) {
                snapshots = List.of(currentBaselineSnapshot());
                selectedIndex = 0;
                statusText = "Current dump ready";
            } else {
                snapshots = List.of();
                statusText = "No dump loaded yet.";
            }
            sessionTargetSnapshotIndex = -1;
        } else {
            snapshots = multiDumpAnalysis.snapshots();
            int baselineIndex = multiDumpAnalysis.baselineIndex();
            if (sessionTargetSnapshotIndex < 0
                    || sessionTargetSnapshotIndex >= multiDumpAnalysis.snapshotCount()
                    || sessionTargetSnapshotIndex == baselineIndex) {
                sessionTargetSnapshotIndex = multiDumpAnalysis.defaultComparisonIndex();
            }
            statusText = String.format(
                    "%d snapshot%s loaded",
                    snapshots.size(),
                    snapshots.size() == 1 ? "" : "s");
            selectedIndex = sessionTargetSnapshotIndex >= 0 ? sessionTargetSnapshotIndex : baselineIndex;
        }

        boolean hasBaselineLabel = currentDump != null || hasMultiDumpReview();
        String baselineLabelText = hasBaselineLabel
                ? multiDumpAnalysis != null
                ? "Baseline #" + (multiDumpAnalysis.baselineIndex() + 1) + "  " + multiDumpAnalysis.baselineLabel()
                : "Current Dump  " + currentDumpLabel()
                : "";
        String addTooltip = hasMultiDumpReview()
                ? "Add more snapshots to this review (" + multiDumpAnalysis.snapshotCount() + " currently loaded)."
                : "Add later dumps here. The current analyzed dump becomes the baseline automatically.";

        snapshotReviewRailController.render(new SnapshotReviewRailController.ViewState(
                snapshots,
                hasMultiDumpReview(),
                multiDumpAnalysis == null ? -1 : multiDumpAnalysis.baselineIndex(),
                sessionTargetSnapshotIndex,
                selectedIndex,
                statusText,
                snapshotReviewHint(activeTab),
                baselineLabelText,
                hasBaselineLabel,
                currentDump == null || snapshotReviewBusy,
                "Add Snapshots",
                addTooltip,
                !hasMultiDumpReview() || snapshotReviewBusy,
                !hasMultiDumpReview(),
                activeTab == timelineTab,
                !hasMultiDumpReview(),
                activeTab == dumpDiffTab
        ));
    }

    private String snapshotReviewHint(Tab selectedTab) {
        if (multiDumpAnalysis == null) {
            return currentDump == null
                    ? "Open and analyze a dump first. Snapshot review starts from the current dump."
                    : "Add snapshots here to compare them against the current dump. The current dump becomes snapshot #1 automatically.";
        }
        if (selectedTab == dumpDiffTab) {
            return "Pick any non-baseline snapshot here to compare against the current baseline, or move the baseline if the anchor is wrong.";
        }
        if (selectedTab == timelineTab) {
            return "Timeline shows the full review. Use the list below to move the baseline or jump into a comparison.";
        }
        return "The list below keeps every loaded snapshot visible. Double-click a non-baseline snapshot to open Compare.";
    }

    private void handleSessionTargetSnapshotChanged(int snapshotIndex) {
        sessionTargetSnapshotIndex = snapshotIndex;
        refreshSnapshotReviewRail();
    }

    private int selectedSessionSnapshotIndex() {
        return snapshotReviewRailController == null ? -1 : snapshotReviewRailController.selectedIndex();
    }

    @FXML
    private void onMakeSelectedBaseline() {
        int selectedIndex = selectedSessionSnapshotIndex();
        if (selectedIndex < 0) {
            return;
        }
        if (!hasMultiDumpReview()) {
            onCompareDumps();
            return;
        }
        if (selectedIndex == multiDumpAnalysis.baselineIndex()) {
            return;
        }

        TimelineSnapshot selectedSnapshot = sessionSnapshotItems.get(selectedIndex);
        int preferredTargetIndex = sessionTargetSnapshotIndex;
        if (preferredTargetIndex == selectedIndex) {
            preferredTargetIndex = -1;
        }
        int preferredTargetSnapshotIndex = preferredTargetIndex;
        List<TimelineSnapshot> snapshots = new ArrayList<>(sessionSnapshotItems);

        updateStatus("Re-anchoring snapshot review…");
        snapshotReviewBusy = true;
        updateSessionActionButton();
        Thread worker = new Thread(() -> {
            try {
                List<AnalysisReport> reports = analyzers.stream()
                        .map(a -> a.analyze(selectedSnapshot.dump()))
                        .toList();
                MultiDumpAnalysis analysis = multiDumpAnalyzer.analyze(snapshots, selectedIndex);
                int resolvedTargetIndex = resolvePreferredTargetIndex(analysis, preferredTargetSnapshotIndex);
                Platform.runLater(() -> {
                    applyMultiDumpAnalysis(analysis, resolvedTargetIndex);
                    applyCurrentDumpAnalysis(
                            new SingleDumpAnalysisService.AnalysisResult(
                                    selectedSnapshot.dump(),
                                    selectedSnapshot.rawText(),
                                    selectedSnapshot.sourcePath(),
                                    reports),
                            "Baseline moved to " + selectedSnapshot.label() + ". Current dump switched to that snapshot.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    snapshotReviewBusy = false;
                    updateSessionActionButton();
                    showError(formatThrowableDetails("Snapshot re-anchor error", e));
                });
            }
        }, "jvm-doctor-session-baseline");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onUseSelectedSessionTarget() {
        int selectedIndex = selectedSessionSnapshotIndex();
        if (multiDumpAnalysis == null || selectedIndex < 0 || selectedIndex == multiDumpAnalysis.baselineIndex()) {
            return;
        }
        dumpDiffController.selectTargetSnapshot(selectedIndex);
        contentTabs.getSelectionModel().select(dumpDiffTab);
        updateStatus("Comparison target set to " + sessionSnapshotItems.get(selectedIndex).label() + ".");
    }

    @FXML
    private void onRemoveSelectedSessionSnapshot() {
        int selectedIndex = selectedSessionSnapshotIndex();
        if (!hasMultiDumpReview() || selectedIndex < 0 || selectedIndex >= sessionSnapshotItems.size()
                || selectedIndex == multiDumpAnalysis.baselineIndex()) {
            return;
        }

        List<TimelineSnapshot> remainingSnapshots = new ArrayList<>(sessionSnapshotItems);
        TimelineSnapshot removedSnapshot = remainingSnapshots.remove(selectedIndex);
        if (remainingSnapshots.size() < 2) {
            clearMultiDumpSession(false);
            updateStatus("Removed " + removedSnapshot.label() + ". Snapshot review cleared because only one dump remained.");
            return;
        }

        int baselineIndex = multiDumpAnalysis.baselineIndex();
        if (selectedIndex < baselineIndex) {
            baselineIndex -= 1;
        }
        int preferredTargetIndex = sessionTargetSnapshotIndex;
        if (preferredTargetIndex == selectedIndex) {
            preferredTargetIndex = multiDumpAnalysis.defaultComparisonIndex();
        } else if (selectedIndex < preferredTargetIndex) {
            preferredTargetIndex -= 1;
        }

        rebuildSessionFromSnapshots(
                remainingSnapshots,
                baselineIndex,
                preferredTargetIndex,
                "Refreshing snapshot review…",
                "Removed " + removedSnapshot.label() + " from the snapshot review.");
    }

    @FXML
    private void onClearSession() {
        clearMultiDumpSession(true);
    }

    private void clearMultiDumpSession(boolean announce) {
        multiDumpAnalysis = null;
        sessionSnapshotItems.clear();
        sessionTargetSnapshotIndex = -1;
        dumpDiffController.clear();
        timelineController.clear();
        refreshSnapshotReviewRail();
        updateSessionActionButton();
        if (contentTabs.getSelectionModel().getSelectedItem() == dumpDiffTab
                || contentTabs.getSelectionModel().getSelectedItem() == timelineTab) {
            contentTabs.getSelectionModel().select(threadsTab);
        }
        if (announce) {
            updateStatus("Snapshot review cleared.");
        }
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

        Label stateLabel = new Label(ThreadStateLabels.display(state));
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
        return ThreadStateLabels.display(state) + " (" + count + ")";
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

        String virtualPart = dump.hasVirtualThreads()
                ? dump.virtualThreadCount() + " virtual / " + dump.platformThreadCount() + " platform"
                : "";

        StringBuilder hint = new StringBuilder();
        hint.append(criticalPart).append("  ·  ").append(poolPart).append("  ·  ").append(hotspotPart);
        if (warningFindings > 0) {
            hint.append("  ·  ").append(warningFindings).append(" warning(s)");
        }
        if (!virtualPart.isEmpty()) {
            hint.append("  ·  ").append(virtualPart);
        }
        return hint.toString();
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
        openMultiDumpChooser(hasMultiDumpReview());
    }

    private void openMultiDumpChooser(boolean appendToExisting) {
        if (currentDump == null) {
            showError("Open and analyze a dump first. That dump becomes the baseline.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(appendToExisting ? "Add Snapshots To Review" : "Add Snapshots To Start Review");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.log", "*.dump"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        List<File> files = chooser.showOpenMultipleDialog(rootPane.getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        loadMultiDumpSession(files, appendToExisting);
    }

    private void rebuildSessionFromSnapshots(List<TimelineSnapshot> snapshots, int baselineSnapshotIndex,
                                             int preferredTargetSnapshotIndex,
                                             String buildStatus, String readyStatus) {
        updateStatus(buildStatus);
        snapshotReviewBusy = true;
        updateSessionActionButton();
        Thread worker = new Thread(() -> {
            try {
                MultiDumpAnalysis analysis = multiDumpAnalyzer.analyze(snapshots, baselineSnapshotIndex);
                Platform.runLater(() -> {
                    applyMultiDumpAnalysis(analysis, preferredTargetSnapshotIndex);
                    updateStatus(readyStatus);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    snapshotReviewBusy = false;
                    updateSessionActionButton();
                    showError(formatThrowableDetails("Snapshot review rebuild error", e));
                });
            }
        }, "jvm-doctor-session-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyMultiDumpAnalysis(MultiDumpAnalysis analysis, int preferredTargetSnapshotIndex) {
        multiDumpAnalysis = analysis;
        sessionSnapshotItems.setAll(analysis.snapshots());
        snapshotReviewBusy = false;
        int resolvedTargetIndex = resolvePreferredTargetIndex(analysis, preferredTargetSnapshotIndex);
        sessionTargetSnapshotIndex = resolvedTargetIndex;
        timelineController.setAnalysis(analysis);
        dumpDiffController.setAnalysis(analysis, resolvedTargetIndex);
        updateSessionActionButton();
        refreshSnapshotReviewRail();
    }

    private void loadMultiDumpSession(List<File> files, boolean appendToExisting) {
        updateStatus(appendToExisting ? "Adding snapshots to snapshot review…" : "Building snapshot review…");
        snapshotReviewBusy = true;
        updateSessionActionButton();
        Thread worker = new Thread(() -> {
            try {
                List<TimelineSnapshot> snapshots = new ArrayList<>();
                int baselineIndex;
                if (appendToExisting && multiDumpAnalysis != null) {
                    snapshots.addAll(sessionSnapshotItems);
                    baselineIndex = multiDumpAnalysis.baselineIndex();
                } else {
                    snapshots.add(currentBaselineSnapshot());
                    baselineIndex = 0;
                }

                Set<String> seenPaths = snapshots.stream()
                        .map(TimelineSnapshot::sourcePath)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                List<String> skipped = new ArrayList<>();

                for (File file : files.stream()
                        .sorted(Comparator.comparingLong(File::lastModified).thenComparing(File::getName))
                        .toList()) {
                    String normalizedPath = file.getAbsoluteFile().toPath().normalize().toString();
                    if (seenPaths.contains(normalizedPath)) {
                        skipped.add(file.getName() + " (already loaded)");
                        continue;
                    }
                    try {
                        String text = Files.readString(file.toPath());
                        ThreadDump dump = parser.parse(text);
                        snapshots.add(new TimelineSnapshot(file.getName(), normalizedPath, text, dump));
                        seenPaths.add(normalizedPath);
                    } catch (Exception fileError) {
                        skipped.add(file.getName() + " (" + summarizeException(fileError) + ")");
                    }
                }

                if (snapshots.size() < 2) {
                    Platform.runLater(() -> {
                        snapshotReviewBusy = false;
                        updateSessionActionButton();
                        if (!skipped.isEmpty()) {
                            showWarning("Could not load enough snapshots:\n" + String.join("\n", skipped));
                        }
                        updateStatus("Need at least two distinct dumps to build a snapshot review.");
                    });
                    return;
                }

                MultiDumpAnalysis analysis = multiDumpAnalyzer.analyze(snapshots, baselineIndex);
                Platform.runLater(() -> {
                    applyMultiDumpAnalysis(analysis, analysis.snapshotCount() - 1);
                    contentTabs.getSelectionModel().select(timelineTab);
                    updateStatus("Snapshot review ready: " + analysis.snapshotCount() + " snapshots loaded, "
                            + analysis.suspiciousThreadCount() + " suspicious threads. The rail on the right shows the full review.");
                    if (!skipped.isEmpty()) {
                        showWarning("Skipped some snapshots:\n" + String.join("\n", skipped));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    snapshotReviewBusy = false;
                    updateSessionActionButton();
                    showError(formatThrowableDetails("Snapshot review error", e));
                });
            }
        }, "jvm-doctor-multi-dump");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateStatus(String msg) {
        statusLabel.setText(msg);
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR, "", ButtonType.OK);
        alert.setTitle("Error");
        alert.setHeaderText("Error Details");
        configureCopyableMessageDialog(alert, msg);
        alert.showAndWait();
        updateStatus("Error: " + msg);
    }

    private void showWarning(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, "", ButtonType.OK);
        alert.setTitle("Warning");
        alert.setHeaderText("Warning Details");
        configureCopyableMessageDialog(alert, msg);
        alert.showAndWait();
    }

    private void updateSessionActionButton() {
        refreshSnapshotReviewRail(contentTabs == null ? null : contentTabs.getSelectionModel().getSelectedItem());
    }

    private void configureCopyableMessageDialog(Alert alert, String message) {
        alert.initModality(Modality.WINDOW_MODAL);
        if (rootPane.getScene() != null && rootPane.getScene().getWindow() != null) {
            alert.initOwner(rootPane.getScene().getWindow());
        }
        TextArea textArea = new TextArea(message == null ? "" : message);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefColumnCount(64);
        textArea.setPrefRowCount(8);
        textArea.getStyleClass().add("raw-area");
        alert.getDialogPane().setContent(textArea);
        styleDialog(alert);
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
            SingleDumpAnalysisService.LoadedDump loadedDump = singleDumpAnalysisService.load(file.toPath());
            currentSourceFilePath = loadedDump.normalizedSourcePath();
            lastDumpSessionStore.save(currentSourceFilePath);
            clearMultiDumpSession(false);
            contentTabs.getSelectionModel().select(threadsTab);
            applyRawDumpText(loadedDump.rawDumpText());
            updateStatus("Loaded: " + loadedDump.displayName() + " (" + rawDumpText.length() + " chars)");
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

    private void styleDialog(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        String stylesheet = getClass().getResource("/io/jvmdoctor/style.css").toExternalForm();
        if (!pane.getStylesheets().contains(stylesheet)) {
            pane.getStylesheets().add(stylesheet);
        }
        pane.getStyleClass().add("dialog-pane-dark");
        pane.setMinHeight(Region.USE_PREF_SIZE);
    }

    private String summarizeException(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        String type = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + message;
    }

    private String formatThrowableDetails(String prefix, Throwable throwable) {
        if (throwable == null) {
            return prefix + ": Unknown error";
        }
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return prefix + ": " + summarizeException(throwable) + "\n\n" + sw;
    }
}
