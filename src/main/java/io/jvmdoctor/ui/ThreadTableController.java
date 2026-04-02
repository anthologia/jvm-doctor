package io.jvmdoctor.ui;

import io.jvmdoctor.model.ThreadInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class ThreadTableController implements Initializable {

    @FXML private TextField filterField;
    @FXML private HBox stateFilterBox;
    @FXML private ToggleButton problemOnlyBtn;
    @FXML private TableView<ThreadInfo> threadTable;
    @FXML private TableColumn<ThreadInfo, String> nameCol;
    @FXML private TableColumn<ThreadInfo, String> stateCol;
    @FXML private TableColumn<ThreadInfo, String> lockCol;
    @FXML private TableColumn<ThreadInfo, Number> depthCol;
    @FXML private TextArea stackDetail;

    private final ObservableList<ThreadInfo> allThreads = FXCollections.observableArrayList();
    private FilteredList<ThreadInfo> filteredThreads;
    private SortedList<ThreadInfo> sortedThreads;

    private final Set<String> selectedStates = new HashSet<>();
    private String frameFilter = null;

    private static final List<String> PROBLEM_STATES = List.of("BLOCKED", "WAITING", "TIMED_WAITING");
    private static final Map<String, Integer> STATE_PRIORITY = Map.of(
            "BLOCKED", 0, "WAITING", 1, "TIMED_WAITING", 2, "RUNNABLE", 3);

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        stateCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().state()));
        lockCol.setCellValueFactory(c -> {
            var lock = c.getValue().waitingOnLock();
            return new javafx.beans.property.SimpleStringProperty(lock != null ? lock.toString() : "");
        });
        depthCol.setCellValueFactory(c -> new javafx.beans.property.SimpleIntegerProperty(c.getValue().stackDepth()));

        // 컬럼 정렬 활성화
        nameCol.setSortable(true);
        lockCol.setSortable(true);
        depthCol.setSortable(true);
        stateCol.setSortable(true);
        // State 컬럼: BLOCKED → WAITING → TIMED_WAITING → RUNNABLE → 기타 우선순위
        stateCol.setComparator((a, b) -> {
            int pa = STATE_PRIORITY.getOrDefault(a, 99);
            int pb = STATE_PRIORITY.getOrDefault(b, 99);
            return pa != pb ? Integer.compare(pa, pb) : a.compareTo(b);
        });

        // State 컬럼 색상
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
        sortedThreads = new SortedList<>(filteredThreads);
        sortedThreads.comparatorProperty().bind(threadTable.comparatorProperty());
        threadTable.setItems(sortedThreads);

        filterField.textProperty().addListener((obs, old, text) -> applyFilter(text));

        // "문제만" 버튼: BLOCKED/WAITING/TIMED_WAITING 칩 일괄 토글
        problemOnlyBtn.setOnAction(e -> {
            boolean on = problemOnlyBtn.isSelected();
            stateFilterBox.getChildren().stream()
                    .filter(n -> n instanceof ToggleButton)
                    .map(n -> (ToggleButton) n)
                    .forEach(tb -> {
                        String state = (String) tb.getUserData();
                        if (state != null && PROBLEM_STATES.contains(state.toUpperCase())) {
                            tb.setSelected(on);
                        }
                    });
        });

        threadTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) { stackDetail.clear(); return; }
            StringBuilder sb = new StringBuilder();
            sb.append("Thread: \"").append(selected.name()).append("\"\n");
            sb.append("State:  ").append(selected.state()).append("\n");
            if (selected.waitingOnLock() != null)
                sb.append("Waiting on: ").append(selected.waitingOnLock()).append("\n");
            if (!selected.heldLocks().isEmpty())
                sb.append("Holding: ").append(
                        selected.heldLocks().stream().map(Object::toString).collect(Collectors.joining(", "))
                ).append("\n");
            sb.append("\n");
            if (selected.stackFrames() != null)
                selected.stackFrames().forEach(f -> sb.append(f).append("\n"));
            stackDetail.setText(sb.toString());
        });
    }

    public void setThreads(List<ThreadInfo> threads) {
        allThreads.setAll(threads);
        selectedStates.clear();
        frameFilter = null;
        problemOnlyBtn.setSelected(false);
        filterField.clear();
        rebuildStateChips(threads);
    }

    /** Top Frames 클릭 시 호출. null이면 프레임 필터 해제. */
    public void filterByFrame(String frameKey) {
        this.frameFilter = frameKey;
        selectedStates.clear();
        problemOnlyBtn.setSelected(false);
        syncChipsToSelectedStates();
        applyFilter(filterField.getText());
    }

    /** 파이차트 슬라이스 클릭 시 호출. null이면 필터 해제. */
    public void filterByState(String state) {
        selectedStates.clear();
        problemOnlyBtn.setSelected(false);
        if (state != null) selectedStates.add(state.toUpperCase());
        syncChipsToSelectedStates();
        applyFilter(filterField.getText());
    }

    private void rebuildStateChips(List<ThreadInfo> threads) {
        stateFilterBox.getChildren().clear();

        threads.stream()
                .map(ThreadInfo::state)
                .distinct()
                .sorted(Comparator.comparingInt(s -> STATE_PRIORITY.getOrDefault(s, 99)))
                .forEach(state -> {
                    ToggleButton chip = new ToggleButton(state);
                    chip.setUserData(state);
                    chip.getStyleClass().add("filter-chip");
                    switch (state.toUpperCase()) {
                        case "BLOCKED"       -> chip.getStyleClass().add("chip-blocked");
                        case "WAITING",
                             "TIMED_WAITING" -> chip.getStyleClass().add("chip-waiting");
                        case "RUNNABLE"      -> chip.getStyleClass().add("chip-runnable");
                    }
                    chip.selectedProperty().addListener((obs, wasOn, isOn) -> {
                        if (isOn) selectedStates.add(state.toUpperCase());
                        else      selectedStates.remove(state.toUpperCase());
                        // "문제만" 버튼은 수동 변경 시 해제
                        if (problemOnlyBtn.isSelected()) problemOnlyBtn.setSelected(false);
                        applyFilter(filterField.getText());
                    });
                    stateFilterBox.getChildren().add(chip);
                });
    }

    private void syncChipsToSelectedStates() {
        stateFilterBox.getChildren().stream()
                .filter(n -> n instanceof ToggleButton)
                .map(n -> (ToggleButton) n)
                .forEach(tb -> {
                    String state = (String) tb.getUserData();
                    if (state != null) tb.setSelected(selectedStates.contains(state.toUpperCase()));
                });
    }

    private void applyFilter(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        filteredThreads.setPredicate(t -> {
            boolean stateMatch = selectedStates.isEmpty()
                    || selectedStates.contains(t.state().toUpperCase());
            boolean textMatch = lower.isEmpty()
                    || t.name().toLowerCase().contains(lower)
                    || t.state().toLowerCase().contains(lower)
                    || (t.waitingOnLock() != null && t.waitingOnLock().toString().toLowerCase().contains(lower));
            boolean frameMatch = frameFilter == null
                    || (t.stackFrames() != null && t.stackFrames().stream()
                            .anyMatch(f -> (f.className() + "." + f.methodName()).equals(frameFilter)));
            return stateMatch && textMatch && frameMatch;
        });
    }
}
