package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.FrameStat;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Consumer;

public class TopFramesController implements Initializable {

    @FXML private TextField filterField;
    @FXML private Label statsLabel;
    @FXML private TableView<FrameStat> framesTable;
    @FXML private TableColumn<FrameStat, String>  methodCol;
    @FXML private TableColumn<FrameStat, String>  packageCol;
    @FXML private TableColumn<FrameStat, Number>  countCol;
    @FXML private TableColumn<FrameStat, String>  pctCol;
    @FXML private TableColumn<FrameStat, Double>  barCol;

    private final ObservableList<FrameStat> allFrames = FXCollections.observableArrayList();
    private FilteredList<FrameStat> filteredFrames;
    private Consumer<String> onFrameClicked;  // null = clear filter
    private String selectedFrameKey = null;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        methodCol.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().simpleClassName() + "." + c.getValue().methodName()));

        // Tooltip shows full class name
        methodCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setTooltip(null); }
                else {
                    setText(item);
                    FrameStat fs = getTableRow().getItem();
                    if (fs != null) setTooltip(new Tooltip(fs.className() + "." + fs.methodName()));
                }
            }
        });

        packageCol.setCellValueFactory(c -> {
            String cls = c.getValue().className();
            int dot = cls.lastIndexOf('.');
            return new SimpleStringProperty(dot > 0 ? cls.substring(0, dot) : "");
        });

        countCol.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().threadCount()));

        pctCol.setCellValueFactory(c ->
                new SimpleStringProperty(String.format("%.1f%%", c.getValue().percentage())));
        pctCol.setStyle("-fx-alignment: CENTER_RIGHT;");

        barCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().percentage()));
        barCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            { bar.setMaxWidth(Double.MAX_VALUE); bar.getStyleClass().add("frame-progress"); }

            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) { setGraphic(null); }
                else { bar.setProgress(val / 100.0); setGraphic(bar); }
            }
        });

        filteredFrames = new FilteredList<>(allFrames, f -> true);
        framesTable.setItems(filteredFrames);

        filterField.textProperty().addListener((obs, old, text) -> {
            String lower = text == null ? "" : text.toLowerCase();
            filteredFrames.setPredicate(f ->
                    lower.isEmpty()
                    || f.className().toLowerCase().contains(lower)
                    || f.methodName().toLowerCase().contains(lower));
        });

        // Row click: toggle frame filter
        framesTable.setRowFactory(tv -> {
            TableRow<FrameStat> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 1 && !row.isEmpty()) {
                    String key = row.getItem().frameKey();
                    if (key.equals(selectedFrameKey)) {
                        selectedFrameKey = null;
                        tv.getSelectionModel().clearSelection();
                        if (onFrameClicked != null) onFrameClicked.accept(null);
                    } else {
                        selectedFrameKey = key;
                        if (onFrameClicked != null) onFrameClicked.accept(key);
                    }
                }
            });
            return row;
        });
    }

    public void setFrames(List<FrameStat> frames) {
        allFrames.setAll(frames);
        selectedFrameKey = null;
        filterField.clear();
        framesTable.getSelectionModel().clearSelection();
        statsLabel.setText(frames.size() + " unique frames");
    }

    public void setOnFrameClicked(Consumer<String> handler) {
        this.onFrameClicked = handler;
    }
}
