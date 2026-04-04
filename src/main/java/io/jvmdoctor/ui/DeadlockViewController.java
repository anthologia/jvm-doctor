package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.AnalysisReport;
import io.jvmdoctor.model.ThreadDump;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

public class DeadlockViewController implements Initializable {

    @FXML private VBox deadlockContainer;
    @FXML private Label deadlockStatusLabel;
    @FXML private Label findingCountLabel;
    @FXML private Label criticalCountLabel;
    @FXML private Label warningCountLabel;
    @FXML private ListView<String> findingsList;
    @FXML private TextArea findingDetail;

    private List<FindingRow> rows = List.of();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        findingsList.getSelectionModel().selectedIndexProperty().addListener((obs, old, idx) -> {
            int i = idx.intValue();
            if (i >= 0 && i < rows.size()) {
                FindingRow row = rows.get(i);
                AnalysisReport.Finding finding = row.finding();
                findingDetail.setText("Analyzer: " + row.analyzerName() + "\n"
                        + "Severity: " + finding.severity() + "\n\n"
                        + finding.title() + "\n\n" + finding.detail());
            } else {
                findingDetail.clear();
            }
        });
    }

    public void setReports(List<AnalysisReport> reports, ThreadDump dump) {
        findingsList.getItems().clear();
        findingDetail.clear();

        boolean hasCritical = reports.stream().anyMatch(AnalysisReport::hasCritical);
        if (hasCritical) {
            deadlockStatusLabel.setText("CRITICAL ISSUES DETECTED");
            deadlockStatusLabel.getStyleClass().removeAll("status-ok");
            deadlockStatusLabel.getStyleClass().add("status-critical");
        } else {
            deadlockStatusLabel.setText("No critical issues detected");
            deadlockStatusLabel.getStyleClass().removeAll("status-critical");
            deadlockStatusLabel.getStyleClass().add("status-ok");
        }

        rows = reports.stream()
                .flatMap(report -> report.findings().stream()
                        .map(finding -> new FindingRow(report.analyzerName(), finding)))
                .toList();

        long critical = rows.stream().filter(row -> row.finding().severity() == AnalysisReport.Severity.CRITICAL).count();
        long warnings = rows.stream().filter(row -> row.finding().severity() == AnalysisReport.Severity.WARNING).count();

        findingCountLabel.setText(rows.size() + " findings");
        criticalCountLabel.setText(critical + " critical");
        warningCountLabel.setText(warnings + " warnings");

        for (FindingRow row : rows) {
            String icon = switch (row.finding().severity()) {
                case CRITICAL -> "🔴 ";
                case WARNING  -> "⚠️ ";
                case INFO     -> "ℹ️ ";
            };
            findingsList.getItems().add(icon + "[" + row.finding().severity() + "] "
                    + row.analyzerName() + " · " + row.finding().title());
        }

        if (!findingsList.getItems().isEmpty()) {
            findingsList.getSelectionModel().select(0);
        }
    }

    private record FindingRow(String analyzerName, AnalysisReport.Finding finding) {}
}
