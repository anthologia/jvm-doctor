package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.FrameStat;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Consumer;

public class TopFramesController implements Initializable {

    @FXML private TextField filterField;
    @FXML private CheckBox hideJdkFramesCheck;
    @FXML private Label statsLabel;
    @FXML private TableView<FrameStat> framesTable;
    @FXML private TableColumn<FrameStat, String>  methodCol;
    @FXML private TableColumn<FrameStat, String>  packageCol;
    @FXML private TableColumn<FrameStat, Number>  countCol;
    @FXML private TableColumn<FrameStat, String>  pctCol;
    @FXML private TableColumn<FrameStat, Double>  barCol;

    private final ObservableList<FrameStat> allFrames = FXCollections.observableArrayList();
    private FilteredList<FrameStat> filteredFrames;
    private SortedList<FrameStat> sortedFrames;
    private Consumer<String> onFrameClicked;  // null = clear filter
    private String selectedFrameKey = null;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        framesTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        framesTable.setTableMenuButtonVisible(true);

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
        countCol.setSortable(true);
        countCol.setResizable(true);

        pctCol.setCellValueFactory(c ->
                new SimpleStringProperty(String.format("%.1f%%", c.getValue().percentage())));
        pctCol.setStyle("-fx-alignment: CENTER_RIGHT;");
        pctCol.setComparator((left, right) -> Double.compare(parsePercent(left), parsePercent(right)));
        pctCol.setSortable(true);
        pctCol.setResizable(true);

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
        barCol.setSortable(true);
        barCol.setResizable(true);
        methodCol.setSortable(true);
        packageCol.setSortable(true);
        methodCol.setResizable(true);
        packageCol.setResizable(true);

        filteredFrames = new FilteredList<>(allFrames, f -> true);
        sortedFrames = new SortedList<>(filteredFrames);
        sortedFrames.comparatorProperty().bind(framesTable.comparatorProperty());
        framesTable.setItems(sortedFrames);
        countCol.setSortType(TableColumn.SortType.DESCENDING);
        framesTable.getSortOrder().setAll(countCol);

        filterField.textProperty().addListener((obs, old, text) -> applyFilter());
        hideJdkFramesCheck.selectedProperty().addListener((obs, old, selected) -> applyFilter());

        // Double-click a row to toggle the linked thread filter.
        framesTable.setRowFactory(tv -> {
            TableRow<FrameStat> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
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
        hideJdkFramesCheck.setSelected(true);
        framesTable.getSelectionModel().clearSelection();
        applyFilter();
    }

    public void setOnFrameClicked(Consumer<String> handler) {
        this.onFrameClicked = handler;
    }

    private void applyFilter() {
        String lower = filterField.getText() == null ? "" : filterField.getText().toLowerCase();
        boolean hideJdk = hideJdkFramesCheck.isSelected();
        filteredFrames.setPredicate(f -> {
            boolean textMatch = lower.isEmpty()
                    || f.className().toLowerCase().contains(lower)
                    || f.methodName().toLowerCase().contains(lower);
            boolean frameMatch = !hideJdk || !isJdkFrame(f.className());
            return textMatch && frameMatch;
        });
        statsLabel.setText(filteredFrames.size() + " / " + allFrames.size() + " unique frames");
    }

    private double parsePercent(String text) {
        if (text == null || text.isBlank()) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(text.replace("%", ""));
        } catch (NumberFormatException ignored) {
            return Double.NEGATIVE_INFINITY;
        }
    }

    private boolean isJdkFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.");
    }
}
