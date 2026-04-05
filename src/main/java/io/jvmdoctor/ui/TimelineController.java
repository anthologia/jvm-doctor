package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.MultiDumpAnalysis;
import io.jvmdoctor.analyzer.MultiDumpAnalysis.ThreadSeries;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;

import java.net.URL;
import java.util.Comparator;
import java.util.ResourceBundle;

public class TimelineController implements Initializable {

    @FXML private Label statusLabel;
    @FXML private Label sessionLabel;
    @FXML private Label summaryLabel;
    @FXML private ToggleButton suspiciousToggle;
    @FXML private ToggleButton stuckToggle;
    @FXML private ToggleButton flappingToggle;
    @FXML private ToggleButton blockedToggle;
    @FXML private ToggleButton persistentToggle;
    @FXML private TextField filterField;
    @FXML private ScrollPane heatmapScroll;
    @FXML private GridPane heatmapGrid;

    private static final int CELL_W = 28;
    private static final int CELL_H = 18;

    private MultiDumpAnalysis analysis;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        filterField.textProperty().addListener((obs, oldValue, newValue) -> rebuildHeatmap());
        suspiciousToggle.setOnAction(e -> rebuildHeatmap());
        stuckToggle.setOnAction(e -> rebuildHeatmap());
        flappingToggle.setOnAction(e -> rebuildHeatmap());
        blockedToggle.setOnAction(e -> rebuildHeatmap());
        persistentToggle.setOnAction(e -> rebuildHeatmap());
        heatmapScroll.setFitToWidth(false);
        heatmapScroll.setFitToHeight(false);
        clear();
    }

    public void setAnalysis(MultiDumpAnalysis analysis) {
        this.analysis = analysis;
        sessionLabel.setText(String.format(
                "Tracking %d loaded snapshots  ·  baseline %s  ·  latest %s",
                analysis.snapshotCount(),
                analysis.baselineLabel(),
                analysis.latestLabel()));
        rebuildHeatmap();
    }

    public void clear() {
        analysis = null;
        heatmapGrid.getChildren().clear();
        heatmapGrid.getColumnConstraints().clear();
        heatmapGrid.getRowConstraints().clear();
        sessionLabel.setText("Use a baseline and add snapshots to track recurring threads over time.");
        statusLabel.setText("No snapshot review loaded.");
        summaryLabel.setText("The snapshot rail on the right keeps every loaded dump visible, can re-anchor the baseline, and can open Compare To Baseline.");
    }

    private void rebuildHeatmap() {
        heatmapGrid.getChildren().clear();
        heatmapGrid.getColumnConstraints().clear();
        heatmapGrid.getRowConstraints().clear();

        if (analysis == null || analysis.snapshotCount() == 0) {
            return;
        }

        var visibleSeries = analysis.threadSeries().stream()
                .filter(this::matchesFilter)
                .sorted(Comparator
                        .comparingInt(ThreadSeries::suspicionScore).reversed()
                        .thenComparingInt(ThreadSeries::blockedSnapshots).reversed()
                        .thenComparingInt(ThreadSeries::appearances).reversed()
                        .thenComparing(ThreadSeries::threadName))
                .toList();

        if (visibleSeries.isEmpty()) {
            statusLabel.setText("No threads match the current timeline filters.");
            summaryLabel.setText("Relax the scope chips or search to see more threads.");
            return;
        }

        heatmapGrid.getColumnConstraints().add(colConstraint(56, 56, 56));
        heatmapGrid.getColumnConstraints().add(colConstraint(180, 180, 260));
        heatmapGrid.getColumnConstraints().add(colConstraint(250, 300, 420));
        for (int index = 0; index < analysis.snapshotCount(); index++) {
            heatmapGrid.getColumnConstraints().add(colConstraint(CELL_W, CELL_W, CELL_W));
        }

        heatmapGrid.getRowConstraints().add(rowConstraint(22));
        heatmapGrid.add(headerLabel("Seen"), 0, 0);
        heatmapGrid.add(headerLabel("Signals"), 1, 0);
        heatmapGrid.add(headerLabel("Thread"), 2, 0);
        for (int index = 0; index < analysis.snapshotCount(); index++) {
            Label header = headerLabel("#" + (index + 1));
            String headerTooltip = snapshotTooltip(index);
            Tooltip.install(header, new Tooltip(headerTooltip));
            heatmapGrid.add(header, index + 3, 0);
        }

        for (int row = 0; row < visibleSeries.size(); row++) {
            ThreadSeries series = visibleSeries.get(row);
            heatmapGrid.getRowConstraints().add(rowConstraint(CELL_H + 6));

            Label seenLabel = metaLabel(series.seenLabel(analysis.snapshotCount()), "timeline-seen-label");
            Tooltip.install(seenLabel, new Tooltip(series.appearances() + " observed snapshot(s)"));
            heatmapGrid.add(seenLabel, 0, row + 1);

            Label signalsLabel = metaLabel(signalText(series), "timeline-signal-label");
            signalsLabel.getStyleClass().add(signalStyleClass(series));
            Tooltip.install(signalsLabel, new Tooltip(signalTooltip(series)));
            heatmapGrid.add(signalsLabel, 1, row + 1);

            Label nameLabel = metaLabel(series.threadName(), "timeline-thread-label");
            if (series.suspicious()) {
                nameLabel.getStyleClass().add("timeline-thread-suspicious");
            }
            nameLabel.setTooltip(new Tooltip(threadTooltip(series)));
            heatmapGrid.add(nameLabel, 2, row + 1);

            for (int col = 0; col < analysis.snapshotCount(); col++) {
                String state = series.stateAt(col);
                Rectangle cell = makeCell(state);
                String topFrame = series.topFrameAt(col);
                String tooltip = snapshotTooltip(col)
                        + "\nState: " + (state == null ? "absent" : ThreadStateLabels.display(state))
                        + "\nTop frame: " + (topFrame == null || topFrame.isBlank() ? "—" : topFrame);
                Tooltip.install(cell, new Tooltip(tooltip));
                heatmapGrid.add(cell, col + 3, row + 1);
            }
        }

        statusLabel.setText(String.format(
                "%d snapshots  ·  %d threads shown  ·  %d suspicious",
                analysis.snapshotCount(),
                visibleSeries.size(),
                visibleSeries.stream().filter(ThreadSeries::suspicious).count()));
        summaryLabel.setText(buildSummary(visibleSeries));
    }

    private boolean matchesFilter(ThreadSeries series) {
        String text = filterField.getText() == null ? "" : filterField.getText().toLowerCase();
        boolean textOk = text.isEmpty()
                || series.threadName().toLowerCase().contains(text)
                || series.signalLabel().toLowerCase().contains(text)
                || series.displayTopFrame().toLowerCase().contains(text)
                || series.dominantState().toLowerCase().contains(text);
        if (!textOk) {
            return false;
        }
        if (suspiciousToggle.isSelected() && !series.suspicious()) {
            return false;
        }
        if (stuckToggle.isSelected() && !series.stuckCandidate()) {
            return false;
        }
        if (flappingToggle.isSelected() && !series.flapping()) {
            return false;
        }
        if (blockedToggle.isSelected() && !series.repeatedlyBlocked()) {
            return false;
        }
        return !persistentToggle.isSelected() || series.presentInAllSnapshots();
    }

    private Label headerLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("timeline-header-label");
        return label;
    }

    private Label metaLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setFont(Font.font("Monospace", 11));
        label.setPadding(new Insets(0, 4, 0, 4));
        label.setAlignment(Pos.CENTER_LEFT);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private Rectangle makeCell(String state) {
        Rectangle rectangle = new Rectangle(CELL_W - 2, CELL_H - 2);
        rectangle.setFill(state == null ? Color.web("#313244") : stateColor(state));
        rectangle.setArcWidth(3);
        rectangle.setArcHeight(3);
        return rectangle;
    }

    private Color stateColor(String state) {
        return switch (state.toUpperCase()) {
            case "RUNNABLE" -> Color.web("#3677e0");
            case "BLOCKED" -> Color.web("#c73a4f");
            case "WAITING" -> Color.web("#b99132");
            case "TIMED_WAITING" -> Color.web("#8c6a45");
            case "NEW" -> Color.web("#628c7b");
            case "TERMINATED" -> Color.web("#585b70");
            default -> Color.web("#7c8898");
        };
    }

    private ColumnConstraints colConstraint(double min, double pref, double max) {
        ColumnConstraints constraints = new ColumnConstraints();
        constraints.setMinWidth(min);
        constraints.setPrefWidth(pref);
        constraints.setMaxWidth(max);
        return constraints;
    }

    private RowConstraints rowConstraint(double height) {
        RowConstraints constraints = new RowConstraints();
        constraints.setMinHeight(height);
        constraints.setPrefHeight(height);
        constraints.setMaxHeight(height);
        return constraints;
    }

    private String signalText(ThreadSeries series) {
        if (!series.signalLabel().isBlank()) {
            return series.signalLabel();
        }
        return series.presentInAllSnapshots() ? "PERSISTENT" : "OBSERVED";
    }

    private String signalStyleClass(ThreadSeries series) {
        if (series.stuckCandidate() || series.repeatedlyBlocked()) {
            return "timeline-signal-critical";
        }
        if (series.flapping() || series.newlyBlocked()) {
            return "timeline-signal-warning";
        }
        if (series.presentInAllSnapshots()) {
            return "timeline-signal-persistent";
        }
        return "timeline-signal-muted";
    }

    private String signalTooltip(ThreadSeries series) {
        return "Signals: " + signalText(series)
                + "\nTransitions: " + series.transitions()
                + "\nBlocked snapshots: " + series.blockedSnapshots();
    }

    private String threadTooltip(ThreadSeries series) {
        return "Dominant state: " + ThreadStateLabels.display(series.dominantState())
                + "\nSeen: " + series.seenLabel(analysis.snapshotCount())
                + "\nTop frame: " + series.displayTopFrame();
    }

    private String buildSummary(java.util.List<ThreadSeries> visibleSeries) {
        long stuck = visibleSeries.stream().filter(ThreadSeries::stuckCandidate).count();
        long flapping = visibleSeries.stream().filter(ThreadSeries::flapping).count();
        long repeatBlocked = visibleSeries.stream().filter(ThreadSeries::repeatedlyBlocked).count();
        long persistent = visibleSeries.stream().filter(ThreadSeries::presentInAllSnapshots).count();
        return String.format(
                "%d stuck  ·  %d flapping  ·  %d repeat blocked  ·  %d persistent",
                stuck,
                flapping,
                repeatBlocked,
                persistent);
    }

    private String snapshotTooltip(int index) {
        if (analysis == null || index < 0 || index >= analysis.snapshotCount()) {
            return "Unknown snapshot";
        }
        var snapshot = analysis.snapshots().get(index);
        StringBuilder tooltip = new StringBuilder();
        tooltip.append("Snapshot ").append(index + 1).append(": ").append(snapshot.label() == null ? "Unnamed dump" : snapshot.label());
        if (snapshot.sourcePath() != null && !snapshot.sourcePath().isBlank()) {
            tooltip.append("\n").append(snapshot.sourcePath());
        }
        if (index == analysis.baselineIndex()) {
            tooltip.append("\nBaseline snapshot");
        }
        return tooltip.toString();
    }
}
