package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.TimelineSnapshot;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.parser.JstackParser;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class TimelineController implements Initializable {

    @FXML private Button addDumpsBtn;
    @FXML private Button clearBtn;
    @FXML private Label statusLabel;
    @FXML private TextField filterField;
    @FXML private ScrollPane heatmapScroll;
    @FXML private GridPane heatmapGrid;

    private final List<TimelineSnapshot> snapshots = new ArrayList<>();
    private final JstackParser parser = new JstackParser();

    private static final int CELL_W = 28;
    private static final int CELL_H = 18;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        filterField.textProperty().addListener((obs, o, n) -> rebuildHeatmap());
        heatmapScroll.setFitToWidth(false);
        heatmapScroll.setFitToHeight(false);
    }

    @FXML
    private void onAddDumps() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Dumps to Timeline (select multiple)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.log", "*.dump"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        List<File> files = chooser.showOpenMultipleDialog(addDumpsBtn.getScene().getWindow());
        if (files == null || files.isEmpty()) return;

        int loaded = 0;
        for (File f : files) {
            try {
                String text = Files.readString(f.toPath());
                ThreadDump dump = parser.parse(text);
                snapshots.add(new TimelineSnapshot(f.getName(), dump));
                loaded++;
            } catch (Exception e) {
                showAlert("Failed to load: " + f.getName() + "\n" + e.getMessage());
            }
        }
        if (loaded > 0) {
            snapshots.sort(Comparator.comparing(TimelineSnapshot::label));
            rebuildHeatmap();
            statusLabel.setText(snapshots.size() + " snapshot(s) loaded.");
        }
    }

    @FXML
    private void onClear() {
        snapshots.clear();
        heatmapGrid.getChildren().clear();
        heatmapGrid.getColumnConstraints().clear();
        heatmapGrid.getRowConstraints().clear();
        statusLabel.setText("Cleared.");
    }

    private void rebuildHeatmap() {
        heatmapGrid.getChildren().clear();
        heatmapGrid.getColumnConstraints().clear();
        heatmapGrid.getRowConstraints().clear();

        if (snapshots.isEmpty()) return;

        // Collect all thread names (union across all snapshots)
        String filter = filterField.getText() == null ? "" : filterField.getText().toLowerCase();
        List<String> threads = snapshots.stream()
                .flatMap(s -> s.dump().threads().stream().map(t -> t.name()))
                .distinct()
                .filter(n -> filter.isEmpty() || n.toLowerCase().contains(filter))
                .sorted()
                .collect(Collectors.toList());

        if (threads.isEmpty()) {
            statusLabel.setText("No threads match the filter.");
            return;
        }

        // Column 0: thread name label
        heatmapGrid.getColumnConstraints().add(colConstraint(200, 200, 400));
        // Columns 1..N: one per snapshot
        for (int s = 0; s < snapshots.size(); s++) {
            heatmapGrid.getColumnConstraints().add(colConstraint(CELL_W, CELL_W, CELL_W));
        }

        // Header row (row 0): snapshot labels
        heatmapGrid.getRowConstraints().add(rowConstraint(20));
        Label corner = new Label("");
        heatmapGrid.add(corner, 0, 0);
        for (int s = 0; s < snapshots.size(); s++) {
            String lbl = shortLabel(snapshots.get(s).label(), s);
            Label header = new Label(lbl);
            header.setFont(Font.font("Monospace", 9));
            header.setTextFill(Color.web("#a6adc8"));
            header.setRotate(-60);
            header.setAlignment(Pos.CENTER);
            header.setPrefWidth(CELL_W);
            Tooltip.install(header, new Tooltip(snapshots.get(s).label()));
            heatmapGrid.add(header, s + 1, 0);
        }

        // Data rows
        for (int row = 0; row < threads.size(); row++) {
            String threadName = threads.get(row);
            heatmapGrid.getRowConstraints().add(rowConstraint(CELL_H));

            Label nameLabel = new Label(threadName);
            nameLabel.setFont(Font.font("Monospace", 11));
            nameLabel.setTextFill(Color.web("#cdd6f4"));
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            nameLabel.setPadding(new javafx.geometry.Insets(0, 4, 0, 4));
            Tooltip.install(nameLabel, new Tooltip(threadName));
            heatmapGrid.add(nameLabel, 0, row + 1);

            for (int col = 0; col < snapshots.size(); col++) {
                Map<String, String> states = snapshots.get(col).states();
                String state = states.getOrDefault(threadName, null);
                Rectangle cell = makeCell(state);
                if (state != null) Tooltip.install(cell, new Tooltip(state));
                heatmapGrid.add(cell, col + 1, row + 1);
            }
        }

        statusLabel.setText(snapshots.size() + " snapshot(s)  ·  " + threads.size() + " threads shown.");
    }

    private Rectangle makeCell(String state) {
        Rectangle r = new Rectangle(CELL_W - 2, CELL_H - 2);
        if (state == null) {
            r.setFill(Color.web("#313244")); // absent
        } else {
            r.setFill(stateColor(state));
        }
        r.setArcWidth(2);
        r.setArcHeight(2);
        return r;
    }

    private Color stateColor(String state) {
        return switch (state.toUpperCase()) {
            case "RUNNABLE"     -> Color.web("#a6e3a1");
            case "BLOCKED"      -> Color.web("#f38ba8");
            case "WAITING"      -> Color.web("#fab387");
            case "TIMED_WAITING"-> Color.web("#f9e2af");
            case "NEW"          -> Color.web("#89b4fa");
            case "TERMINATED"   -> Color.web("#585b70");
            default             -> Color.web("#cba6f7");
        };
    }

    private ColumnConstraints colConstraint(double min, double pref, double max) {
        ColumnConstraints cc = new ColumnConstraints();
        cc.setMinWidth(min); cc.setPrefWidth(pref); cc.setMaxWidth(max);
        return cc;
    }

    private RowConstraints rowConstraint(double height) {
        RowConstraints rc = new RowConstraints();
        rc.setMinHeight(height); rc.setPrefHeight(height); rc.setMaxHeight(height);
        return rc;
    }

    private String shortLabel(String label, int idx) {
        // Use index number if label is too long
        return "#" + (idx + 1);
    }

    private void showAlert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle("Load Warning");
        a.showAndWait();
    }
}
