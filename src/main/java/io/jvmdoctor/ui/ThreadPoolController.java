package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.ThreadPool;
import io.jvmdoctor.model.ThreadInfo;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

public class ThreadPoolController implements Initializable {

    @FXML private TextField filterField;
    @FXML private Label statsLabel;
    @FXML private TableView<ThreadPool> poolTable;
    @FXML private TableColumn<ThreadPool, String> poolNameCol;
    @FXML private TableColumn<ThreadPool, String> kindCol;
    @FXML private TableColumn<ThreadPool, String> healthCol;
    @FXML private TableColumn<ThreadPool, Number> totalCol;
    @FXML private TableColumn<ThreadPool, Number> runnableCol;
    @FXML private TableColumn<ThreadPool, Number> waitingCol;
    @FXML private TableColumn<ThreadPool, Number> blockedCol;
    @FXML private TableColumn<ThreadPool, String> dominantCol;
    @FXML private TableColumn<ThreadPool, String> topFrameCol;

    @FXML private VBox threadDetailBox;
    @FXML private Label selectedPoolLabel;
    @FXML private Label selectedPoolHintLabel;
    @FXML private ListView<String> threadListView;

    private final ObservableList<ThreadPool> allPools = FXCollections.observableArrayList();
    private FilteredList<ThreadPool> filteredPools;
    private SortedList<ThreadPool> sortedPools;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        poolTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        poolTable.setTableMenuButtonVisible(true);

        poolNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        kindCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().kind()));
        healthCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().health()));
        totalCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().total()));
        runnableCol.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().runnable()));
        waitingCol.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().waiting()));
        blockedCol.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().blocked()));
        dominantCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dominantState()));
        topFrameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dominantTopFrame()));
        poolNameCol.setSortable(true);
        kindCol.setSortable(true);
        healthCol.setSortable(true);
        totalCol.setSortable(true);
        runnableCol.setSortable(true);
        waitingCol.setSortable(true);
        blockedCol.setSortable(true);
        dominantCol.setSortable(true);
        topFrameCol.setSortable(true);
        poolNameCol.setResizable(true);
        kindCol.setResizable(true);
        healthCol.setResizable(true);
        totalCol.setResizable(true);
        runnableCol.setResizable(true);
        waitingCol.setResizable(true);
        blockedCol.setResizable(true);
        dominantCol.setResizable(true);
        topFrameCol.setResizable(true);

        healthCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String state, boolean empty) {
                super.updateItem(state, empty);
                getStyleClass().removeAll("state-blocked", "state-waiting", "state-runnable");
                if (empty || state == null) { setText(null); return; }
                setText(state);
                switch (state) {
                    case "Contended", "Starved" -> getStyleClass().add("state-blocked");
                    case "Busy", "Active" -> getStyleClass().add("state-runnable");
                }
            }
        });

        // Colour the dominant state cell
        dominantCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String state, boolean empty) {
                super.updateItem(state, empty);
                getStyleClass().removeAll("state-blocked", "state-waiting", "state-runnable");
                if (empty || state == null) { setText(null); return; }
                setText(state);
                switch (state) {
                    case "BLOCKED"       -> getStyleClass().add("state-blocked");
                    case "WAITING",
                         "TIMED_WAITING" -> getStyleClass().add("state-waiting");
                    case "RUNNABLE"      -> getStyleClass().add("state-runnable");
                }
            }
        });

        topFrameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(shorten(item));
                setTooltip(new Tooltip(item));
            }
        });

        // Colour blocked count red when > 0
        blockedCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number val, boolean empty) {
                super.updateItem(val, empty);
                getStyleClass().remove("state-blocked");
                if (empty || val == null) { setText(null); return; }
                setText(val.toString());
                if (val.longValue() > 0) getStyleClass().add("state-blocked");
            }
        });

        filteredPools = new FilteredList<>(allPools, p -> true);
        sortedPools = new SortedList<>(filteredPools);
        sortedPools.comparatorProperty().bind(poolTable.comparatorProperty());
        poolTable.setItems(sortedPools);
        totalCol.setSortType(TableColumn.SortType.DESCENDING);
        poolTable.getSortOrder().setAll(totalCol);

        filterField.textProperty().addListener((obs, old, text) -> applyFilter(text));

        poolTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null) {
                threadDetailBox.setVisible(false);
                return;
            }
            threadDetailBox.setVisible(true);
            selectedPoolLabel.setText(sel.name() + "  (" + sel.total() + " threads)");
            selectedPoolHintLabel.setText(sel.kind() + "  ·  " + sel.health() + "  ·  " + sel.dominantTopFrame());
            List<String> lines = sel.threads().stream()
                    .sorted((a, b) -> {
                        int pa = statePriority(a.state());
                        int pb = statePriority(b.state());
                        return pa != pb ? Integer.compare(pa, pb) : a.name().compareTo(b.name());
                    })
                    .map(t -> formatThread(t))
                    .collect(Collectors.toList());
            threadListView.setItems(FXCollections.observableArrayList(lines));
        });

        threadDetailBox.setVisible(false);
    }

    public void setPools(List<ThreadPool> pools) {
        allPools.setAll(pools);
        filterField.clear();
        threadDetailBox.setVisible(false);
        updateStatsLabel();
    }

    private void applyFilter(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        filteredPools.setPredicate(p ->
                lower.isEmpty() || p.name().toLowerCase().contains(lower));
        updateStatsLabel();
    }

    private void updateStatsLabel() {
        long singles = filteredPools.stream().filter(p -> p.total() == 1).count();
        long unhealthy = filteredPools.stream()
                .filter(p -> !"Healthy".equals(p.health()))
                .count();
        statsLabel.setText(filteredPools.size() + " groups  ·  " + singles + " singletons  ·  " + unhealthy + " flagged");
    }

    private String formatThread(ThreadInfo t) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(t.state()).append("]  ").append(t.name());
        if (t.waitingOnLock() != null)
            sb.append("  ← ").append(t.waitingOnLock());
        if (t.stackFrames() != null && !t.stackFrames().isEmpty()) {
            sb.append("  ·  ").append(shorten(t.stackFrames().get(0).className() + "." + t.stackFrames().get(0).methodName()));
        }
        return sb.toString();
    }

    private int statePriority(String state) {
        return switch (state.toUpperCase()) {
            case "BLOCKED" -> 0;
            case "WAITING" -> 1;
            case "TIMED_WAITING" -> 2;
            default -> 3;
        };
    }

    private String shorten(String text) {
        return text.length() <= 42 ? text : text.substring(0, 39) + "...";
    }
}
