package io.jvmdoctor.ui;

import io.jvmdoctor.model.ThreadInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

public class ThreadTableController implements Initializable {

    @FXML private TextField filterField;
    @FXML private TableView<ThreadInfo> threadTable;
    @FXML private TableColumn<ThreadInfo, String> nameCol;
    @FXML private TableColumn<ThreadInfo, String> stateCol;
    @FXML private TableColumn<ThreadInfo, String> lockCol;
    @FXML private TableColumn<ThreadInfo, Number> depthCol;
    @FXML private TextArea stackDetail;

    private final ObservableList<ThreadInfo> allThreads = FXCollections.observableArrayList();
    private FilteredList<ThreadInfo> filteredThreads;
    private String stateFilter = null;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        stateCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().state()));
        lockCol.setCellValueFactory(c -> {
            var lock = c.getValue().waitingOnLock();
            return new javafx.beans.property.SimpleStringProperty(lock != null ? lock.toString() : "");
        });
        depthCol.setCellValueFactory(c -> new javafx.beans.property.SimpleIntegerProperty(c.getValue().stackDepth()));

        // Color-code state column
        stateCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String state, boolean empty) {
                super.updateItem(state, empty);
                getStyleClass().removeAll("state-blocked", "state-waiting", "state-runnable");
                if (empty || state == null) {
                    setText(null);
                } else {
                    setText(state);
                    switch (state.toUpperCase()) {
                        case "BLOCKED"       -> getStyleClass().add("state-blocked");
                        case "WAITING",
                             "TIMED_WAITING" -> getStyleClass().add("state-waiting");
                        case "RUNNABLE"      -> getStyleClass().add("state-runnable");
                    }
                }
            }
        });

        filteredThreads = new FilteredList<>(allThreads, t -> true);
        threadTable.setItems(filteredThreads);

        filterField.textProperty().addListener((obs, old, text) -> applyFilter(text));

        threadTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                stackDetail.clear();
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Thread: \"").append(selected.name()).append("\"\n");
            sb.append("State:  ").append(selected.state()).append("\n");
            if (selected.waitingOnLock() != null) {
                sb.append("Waiting on: ").append(selected.waitingOnLock()).append("\n");
            }
            if (!selected.heldLocks().isEmpty()) {
                sb.append("Holding: ").append(
                        selected.heldLocks().stream().map(Object::toString).collect(Collectors.joining(", "))
                ).append("\n");
            }
            sb.append("\n");
            if (selected.stackFrames() != null) {
                selected.stackFrames().forEach(f -> sb.append(f).append("\n"));
            }
            stackDetail.setText(sb.toString());
        });
    }

    public void setThreads(List<ThreadInfo> threads) {
        allThreads.setAll(threads);
        stateFilter = null;
        filterField.clear();
    }

    /** 파이차트 슬라이스 클릭 시 호출. null이면 상태 필터 해제. */
    public void filterByState(String state) {
        this.stateFilter = state;
        applyFilter(filterField.getText());
    }

    private void applyFilter(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        filteredThreads.setPredicate(t -> {
            boolean stateMatch = stateFilter == null || t.state().equalsIgnoreCase(stateFilter);
            boolean textMatch = lower.isEmpty()
                    || t.name().toLowerCase().contains(lower)
                    || t.state().toLowerCase().contains(lower)
                    || (t.waitingOnLock() != null && t.waitingOnLock().toString().toLowerCase().contains(lower));
            return stateMatch && textMatch;
        });
    }
}
