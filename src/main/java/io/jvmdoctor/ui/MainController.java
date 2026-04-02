package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.*;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.parser.JstackParser;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.PieChart;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

public class MainController implements Initializable {

    // --- Toolbar ---
    @FXML private Button openFileBtn;
    @FXML private Button pasteDumpBtn;
    @FXML private Button analyzeBtn;

    // --- Left nav ---
    @FXML private ListView<String> navList;

    // --- Summary panel ---
    @FXML private PieChart stateChart;
    @FXML private Label totalLabel;
    @FXML private Label blockedLabel;
    @FXML private Label deadlockLabel;

    // --- Content tabs ---
    @FXML private TabPane contentTabs;
    @FXML private Tab threadsTab;
    @FXML private Tab deadlockTab;
    @FXML private Tab rawTab;

    // --- Thread table (injected sub-controller) ---
    @FXML private ThreadTableController threadTableController;

    // --- Deadlock view (injected sub-controller) ---
    @FXML private DeadlockViewController deadlockViewController;

    // --- Raw text area ---
    @FXML private TextArea rawTextArea;

    // --- Status bar ---
    @FXML private Label statusLabel;

    private ThreadDump currentDump;
    private String rawDumpText;
    private String activeStateFilter = null;

    private final JstackParser parser = new JstackParser();
    private final List<Analyzer> analyzers = List.of(
            new DeadlockAnalyzer(),
            new ThreadStateAnalyzer(),
            new LockContentionAnalyzer()
    );

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        navList.setItems(FXCollections.observableArrayList("Summary", "Deadlock", "Threads", "Locks"));
        navList.getSelectionModel().select(0);
        navList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) return;
            switch (selected) {
                case "Deadlock" -> contentTabs.getSelectionModel().select(deadlockTab);
                case "Threads"  -> contentTabs.getSelectionModel().select(threadsTab);
                default         -> contentTabs.getSelectionModel().select(0);
            }
        });

        analyzeBtn.setDisable(true);
        updateStatus("Ready. Open a thread dump file or paste a dump to begin.");
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

        try {
            rawDumpText = Files.readString(file.toPath());
            rawTextArea.setText(rawDumpText);
            analyzeBtn.setDisable(false);
            updateStatus("Loaded: " + file.getName() + " (" + rawDumpText.length() + " chars)");
            onAnalyze();
        } catch (IOException e) {
            showError("Failed to read file: " + e.getMessage());
        }
    }

    @FXML
    private void onPasteDump() {
        Dialog<String> dialog = new TextInputDialog();
        dialog.setTitle("Paste Thread Dump");
        dialog.setHeaderText("Paste your jstack / thread dump output below:");
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
                rawDumpText = text;
                rawTextArea.setText(rawDumpText);
                analyzeBtn.setDisable(false);
                updateStatus("Dump pasted (" + rawDumpText.length() + " chars). Click Analyze.");
            }
        });
    }

    @FXML
    private void onAnalyze() {
        if (rawDumpText == null || rawDumpText.isBlank()) return;

        analyzeBtn.setDisable(true);
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
                    analyzeBtn.setDisable(false);
                    updateStatus("Analysis complete. " + dump.threads().size() + " threads parsed.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    analyzeBtn.setDisable(false);
                    showError("Parse error: " + e.getMessage());
                });
            }
        }, "jvm-doctor-analyzer");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateSummary(ThreadDump dump, List<AnalysisReport> reports) {
        // Pie chart
        activeStateFilter = null;
        Map<String, Long> dist = dump.stateDistribution();
        var chartData = FXCollections.<PieChart.Data>observableArrayList();
        dist.forEach((state, count) -> chartData.add(new PieChart.Data(state + " (" + count + ")", count)));
        stateChart.setData(chartData);
        stateChart.setLabelsVisible(true);
        stateChart.setLegendVisible(false);

        // 슬라이스 클릭 → 상태 필터 (재클릭 시 해제)
        Platform.runLater(() -> {
            for (PieChart.Data data : stateChart.getData()) {
                String state = data.getName().replaceAll("\\s*\\(\\d+\\)$", ""); // "RUNNABLE (5)" → "RUNNABLE"
                data.getNode().setStyle("-fx-cursor: hand;");
                data.getNode().setOnMouseClicked(e -> {
                    if (state.equals(activeStateFilter)) {
                        // 토글 해제
                        activeStateFilter = null;
                        threadTableController.filterByState(null);
                        stateChart.getData().forEach(d -> d.getNode().setOpacity(1.0));
                        updateStatus("State filter cleared.");
                    } else {
                        // 해당 상태로 필터
                        activeStateFilter = state;
                        threadTableController.filterByState(state);
                        contentTabs.getSelectionModel().select(threadsTab);
                        stateChart.getData().forEach(d ->
                                d.getNode().setOpacity(d == data ? 1.0 : 0.35));
                        updateStatus("Filtered by state: " + state + "  (click again to clear)");
                    }
                });
            }
        });

        // Metrics
        totalLabel.setText("Total: " + dump.threads().size());
        blockedLabel.setText("Blocked: " + dump.blockedCount());

        long deadlocks = reports.stream()
                .filter(r -> r.analyzerName().equals("Deadlock Analyzer"))
                .flatMap(r -> r.findings().stream())
                .filter(f -> f.severity() == AnalysisReport.Severity.CRITICAL)
                .count();
        deadlockLabel.setText("Deadlocks: " + deadlocks);
        if (deadlocks > 0) {
            deadlockLabel.getStyleClass().add("metric-critical");
        }
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
}
