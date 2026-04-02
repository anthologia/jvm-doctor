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
    @FXML private ListView<String> findingsList;
    @FXML private TextArea findingDetail;

    private List<AnalysisReport> reports;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        findingsList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            // Detail is stored as the item's full text after separator
            // We store index mapping via userData trick below
        });
    }

    public void setReports(List<AnalysisReport> reports, ThreadDump dump) {
        this.reports = reports;
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

        List<AnalysisReport.Finding> allFindings = reports.stream()
                .flatMap(r -> r.findings().stream())
                .toList();

        for (AnalysisReport.Finding f : allFindings) {
            String icon = switch (f.severity()) {
                case CRITICAL -> "🔴 ";
                case WARNING  -> "⚠️ ";
                case INFO     -> "ℹ️ ";
            };
            findingsList.getItems().add(icon + "[" + f.severity() + "] " + f.title());
        }

        findingsList.getSelectionModel().selectedIndexProperty().addListener((obs, old, idx) -> {
            int i = idx.intValue();
            if (i >= 0 && i < allFindings.size()) {
                AnalysisReport.Finding f = allFindings.get(i);
                findingDetail.setText(f.title() + "\n\n" + f.detail());
            }
        });

        if (!findingsList.getItems().isEmpty()) {
            findingsList.getSelectionModel().select(0);
        }
    }
}
