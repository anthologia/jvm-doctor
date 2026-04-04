package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.ThreadPoolGrouper;
import io.jvmdoctor.model.LockInfo;
import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadInfo;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ThreadTableController implements Initializable {

    @FXML private TextField filterField;
    @FXML private FlowPane stateFilterBox;
    @FXML private ToggleButton problemOnlyBtn;
    @FXML private HBox scopeSummaryBox;
    @FXML private Label scopeLabel;
    @FXML private HBox selectionIndicatorBox;
    @FXML private Button ownerBtn;
    @FXML private Button samePoolBtn;
    @FXML private Button sameFrameBtn;
    @FXML private Button showRawBtn;
    @FXML private Button clearFiltersBtn;
    @FXML private Button revealSelectedBtn;
    @FXML private Label statsLabel;
    @FXML private Label selectedThreadTitle;
    @FXML private TextField selectedThreadMeta;
    @FXML private HBox selectionActionBox;
    @FXML private TableView<ThreadInfo> threadTable;
    @FXML private TableColumn<ThreadInfo, String> nameCol;
    @FXML private TableColumn<ThreadInfo, String> stateCol;
    @FXML private TableColumn<ThreadInfo, String> issuesCol;
    @FXML private TableColumn<ThreadInfo, String> contextCol;
    @FXML private TableColumn<ThreadInfo, String> topFrameCol;
    @FXML private TextArea stackDetail;

    private final ObservableList<ThreadInfo> allThreads = FXCollections.observableArrayList();
    private FilteredList<ThreadInfo> filteredThreads;
    private SortedList<ThreadInfo> sortedThreads;

    private final Set<String> selectedStates = new HashSet<>();
    private Set<String> threadNameFilter = null;
    private String frameFilter = null;
    private String poolFilter = null;
    private Set<String> deadlockedThreadNames = Set.of();

    private final Map<String, String> poolByThread = new HashMap<>();
    private final Map<String, ThreadInfo> threadByName = new HashMap<>();
    private final Map<String, ThreadInfo> lockOwnerById = new HashMap<>();
    private final Map<String, Long> waitersByLockId = new HashMap<>();

    private Consumer<ThreadInfo> onLocateRawRequested;
    private Consumer<String> onStatusMessage;

    private final ThreadPoolGrouper poolGrouper = new ThreadPoolGrouper();
    private boolean syncingStateControls = false;

    private static final List<String> PROBLEM_STATES = List.of("BLOCKED", "WAITING", "TIMED_WAITING");
    private static final Map<String, Integer> STATE_PRIORITY = Map.of(
            "BLOCKED", 0, "WAITING", 1, "TIMED_WAITING", 2, "RUNNABLE", 3);

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        threadTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        threadTable.setTableMenuButtonVisible(true);
        threadTable.setFixedCellSize(42);

        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        stateCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().state()));
        issuesCol.setCellValueFactory(c -> new SimpleStringProperty(buildSignals(c.getValue())));
        contextCol.setCellValueFactory(c -> new SimpleStringProperty(buildContextSummary(c.getValue())));
        topFrameCol.setCellValueFactory(c -> new SimpleStringProperty(topFrameLabel(c.getValue())));

        nameCol.setSortable(true);
        stateCol.setSortable(true);
        contextCol.setSortable(true);
        topFrameCol.setSortable(true);
        issuesCol.setSortable(true);
        nameCol.setResizable(true);
        stateCol.setResizable(true);
        contextCol.setResizable(true);
        topFrameCol.setResizable(true);
        issuesCol.setResizable(true);

        stateCol.setComparator((a, b) -> {
            int pa = STATE_PRIORITY.getOrDefault(a, 99);
            int pb = STATE_PRIORITY.getOrDefault(b, 99);
            return pa != pb ? Integer.compare(pa, pb) : a.compareTo(b);
        });

        stateCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String state, boolean empty) {
                super.updateItem(state, empty);
                getStyleClass().removeAll("state-blocked", "state-waiting", "state-timed-waiting", "state-runnable");
                if (empty || state == null) {
                    setText(null);
                } else {
                    setText(state);
                    switch (state.toUpperCase()) {
                        case "BLOCKED"       -> getStyleClass().add("state-blocked");
                        case "WAITING"       -> getStyleClass().add("state-waiting");
                        case "TIMED_WAITING" -> getStyleClass().add("state-timed-waiting");
                        case "RUNNABLE"      -> getStyleClass().add("state-runnable");
                    }
                }
            }
        });

        nameCol.setCellFactory(col -> new TableCell<>() {
            private final Label primary = new Label();
            private final Label secondary = new Label();
            private final VBox box = new VBox(2, primary, secondary);
            {
                configureCompactCellLabel(primary);
                configureCompactCellLabel(secondary);
                primary.getStyleClass().add("thread-cell-primary");
                secondary.getStyleClass().add("thread-cell-secondary");
                box.getStyleClass().add("thread-cell-box");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                ThreadInfo thread = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (thread == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }

                primary.setText(thread.name());
                secondary.setText("#" + thread.threadId() + "  ·  depth " + thread.stackDepth());
                setText(null);
                setGraphic(box);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setTooltip(new Tooltip(thread.name()));
            }
        });

        issuesCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("state-blocked", "state-waiting", "state-runnable");
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                } else {
                    setText(item);
                    if (item.contains("DEADLOCK") || item.contains("OWNER")) {
                        getStyleClass().add("state-blocked");
                    } else if (item.contains("HOT_LOCK")) {
                        getStyleClass().add("state-waiting");
                    }
                }
            }
        });

        contextCol.setCellFactory(col -> new TableCell<>() {
            private final Label primary = new Label();
            private final Label secondary = new Label();
            private final VBox box = new VBox(2, primary, secondary);
            {
                configureCompactCellLabel(primary);
                configureCompactCellLabel(secondary);
                primary.getStyleClass().add("thread-cell-primary");
                secondary.getStyleClass().add("thread-cell-secondary");
                box.getStyleClass().add("thread-cell-box");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                ThreadInfo thread = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (thread == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }

                primary.setText(displayPoolName(thread));
                secondary.setText(buildContextSecondary(thread));
                setText(null);
                setGraphic(box);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setTooltip(new Tooltip(buildContextTooltip(thread)));
            }
        });

        topFrameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    ThreadInfo thread = getTableRow() == null ? null : getTableRow().getItem();
                    String frameKey = topFrameKey(thread);
                    setTooltip(frameKey == null ? null : new Tooltip(frameKey));
                }
            }
        });

        filteredThreads = new FilteredList<>(allThreads, t -> true);
        sortedThreads = new SortedList<>(filteredThreads);
        sortedThreads.comparatorProperty().bind(threadTable.comparatorProperty());
        threadTable.setItems(sortedThreads);
        threadTable.setRowFactory(table -> {
            TableRow<ThreadInfo> row = new TableRow<>();
            row.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (!row.isEmpty()
                        && event.getClickCount() == 1
                        && row.isSelected()) {
                    threadTable.getSelectionModel().clearSelection();
                    event.consume();
                }
            });
            return row;
        });

        filterField.textProperty().addListener((obs, old, text) -> applyFilter(text));

        problemOnlyBtn.setOnAction(e -> {
            boolean on = problemOnlyBtn.isSelected();
            syncingStateControls = true;
            stateFilterBox.getChildren().stream()
                    .filter(n -> n instanceof ToggleButton)
                    .map(n -> (ToggleButton) n)
                    .forEach(tb -> {
                        String state = (String) tb.getUserData();
                        if (state != null && PROBLEM_STATES.contains(state.toUpperCase())) {
                            tb.setSelected(on);
                        }
                    });
            syncingStateControls = false;
            if (!on) {
                selectedStates.removeAll(PROBLEM_STATES);
            }
            applyFilter(filterField.getText());
        });

        threadTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            updateDetail(selected);
            updateActionButtons(selected);
        });

        updateActionButtons(null);
        updateStats();
        updateScopeSummary();
    }

    public void setThreads(List<ThreadInfo> threads) {
        allThreads.setAll(threads);
        rebuildThreadContext(threads);
        deadlockedThreadNames = Set.of();
        selectedStates.clear();
        threadNameFilter = null;
        frameFilter = null;
        poolFilter = null;
        problemOnlyBtn.setSelected(false);
        filterField.clear();
        rebuildStateChips(threads);
        threadTable.getSelectionModel().clearSelection();
        stackDetail.clear();
        selectedThreadTitle.setText("Select a thread");
        selectedThreadMeta.setText("Use search, state chips, or summary metrics to narrow the list.");
        updateActionButtons(null);
        updateStats();
        updateScopeSummary();
    }

    public void setIssueContext(Set<String> deadlockedThreadNames) {
        this.deadlockedThreadNames = deadlockedThreadNames == null ? Set.of() : new LinkedHashSet<>(deadlockedThreadNames);
        threadTable.refresh();
        updateDetail(threadTable.getSelectionModel().getSelectedItem());
        updateStats();
    }

    public void setOnLocateRawRequested(Consumer<ThreadInfo> handler) {
        this.onLocateRawRequested = handler;
    }

    public void setOnStatusMessage(Consumer<String> handler) {
        this.onStatusMessage = handler;
    }

    public void filterByFrame(String frameKey) {
        poolFilter = null;
        threadNameFilter = null;
        this.frameFilter = frameKey;
        selectedStates.clear();
        problemOnlyBtn.setSelected(false);
        syncChipsToSelectedStates();
        applyFilter(filterField.getText());
    }

    public void filterByState(String state) {
        filterByStates(state == null ? Set.of() : Set.of(state));
    }

    public void filterByStates(Set<String> states) {
        threadNameFilter = null;
        frameFilter = null;
        poolFilter = null;
        selectedStates.clear();
        problemOnlyBtn.setSelected(false);
        if (states != null) {
            selectedStates.addAll(states.stream().map(String::toUpperCase).collect(Collectors.toSet()));
        }
        syncChipsToSelectedStates();
        applyFilter(filterField.getText());
    }

    public void filterByThreadNames(Set<String> threadNames) {
        threadNameFilter = (threadNames == null || threadNames.isEmpty()) ? null : new HashSet<>(threadNames);
        frameFilter = null;
        poolFilter = null;
        selectedStates.clear();
        problemOnlyBtn.setSelected(false);
        syncChipsToSelectedStates();
        applyFilter(filterField.getText());
    }

    public void filterByPool(String poolName) {
        if (poolName == null || poolName.isBlank()) {
            clearQuickFilters();
            return;
        }
        poolFilter = poolName;
        threadNameFilter = null;
        frameFilter = null;
        selectedStates.clear();
        problemOnlyBtn.setSelected(false);
        syncChipsToSelectedStates();
        applyFilter(filterField.getText());
    }

    public void clearQuickFilters() {
        threadNameFilter = null;
        frameFilter = null;
        poolFilter = null;
        selectedStates.clear();
        problemOnlyBtn.setSelected(false);
        if (!filterField.getText().isBlank()) {
            filterField.clear();
        }
        syncChipsToSelectedStates();
        applyFilter(filterField.getText());
    }

    @FXML
    private void onGoOwner() {
        ThreadInfo selected = threadTable.getSelectionModel().getSelectedItem();
        if (selected == null || selected.waitingOnLock() == null) {
            publishStatus("Selected thread is not waiting on a lock.");
            return;
        }

        ThreadInfo owner = lockOwnerById.get(selected.waitingOnLock().lockId());
        if (owner == null) {
            publishStatus("No owner thread found for " + selected.waitingOnLock() + ".");
            return;
        }

        clearQuickFilters();
        selectThread(owner.name());
        publishStatus("Moved to lock owner: " + owner.name());
    }

    @FXML
    private void onSamePool() {
        ThreadInfo selected = threadTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        String pool = poolByThread.get(selected.name());
        if (pool == null || pool.isBlank() || pool.equals(selected.name())) {
            publishStatus("No pool detected for the selected thread.");
            return;
        }
        filterByPool(pool);
        publishStatus("Filtered to pool: " + pool);
    }

    @FXML
    private void onSameFrame() {
        ThreadInfo selected = threadTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        String frameKey = topFrameKey(selected);
        if (frameKey == null) {
            publishStatus("No stack frame available for the selected thread.");
            return;
        }
        filterByFrame(frameKey);
        publishStatus("Filtered to top frame: " + frameKey);
    }

    @FXML
    private void onShowRaw() {
        ThreadInfo selected = threadTable.getSelectionModel().getSelectedItem();
        if (selected == null || onLocateRawRequested == null) {
            return;
        }
        onLocateRawRequested.accept(selected);
    }

    @FXML
    private void onClearQuickFilters() {
        clearQuickFilters();
        publishStatus("Thread quick filters cleared.");
    }

    @FXML
    private void onRevealSelected() {
        ThreadInfo selected = threadTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        threadTable.scrollTo(selected);
        threadTable.requestFocus();
        publishStatus("Revealed selected thread: " + selected.name());
    }

    private void rebuildThreadContext(List<ThreadInfo> threads) {
        threadByName.clear();
        lockOwnerById.clear();
        waitersByLockId.clear();
        poolByThread.clear();

        for (ThreadInfo thread : threads) {
            threadByName.put(thread.name(), thread);
            poolByThread.put(thread.name(), poolGrouper.detectPoolName(thread.name()));
            if (thread.heldLocks() != null) {
                for (LockInfo lock : thread.heldLocks()) {
                    lockOwnerById.put(lock.lockId(), thread);
                }
            }
        }

        waitersByLockId.putAll(threads.stream()
                .filter(t -> t.waitingOnLock() != null)
                .collect(Collectors.groupingBy(
                        t -> t.waitingOnLock().lockId(),
                        Collectors.counting())));
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
                        case "WAITING"       -> chip.getStyleClass().add("chip-waiting");
                        case "TIMED_WAITING" -> chip.getStyleClass().add("chip-timed-waiting");
                        case "RUNNABLE"      -> chip.getStyleClass().add("chip-runnable");
                    }
                    chip.selectedProperty().addListener((obs, wasOn, isOn) -> {
                        if (isOn) selectedStates.add(state.toUpperCase());
                        else      selectedStates.remove(state.toUpperCase());
                        if (!syncingStateControls && problemOnlyBtn.isSelected()) problemOnlyBtn.setSelected(false);
                        applyFilter(filterField.getText());
                    });
                    stateFilterBox.getChildren().add(chip);
                });
    }

    private void syncChipsToSelectedStates() {
        syncingStateControls = true;
        stateFilterBox.getChildren().stream()
                .filter(n -> n instanceof ToggleButton)
                .map(n -> (ToggleButton) n)
                .forEach(tb -> {
                    String state = (String) tb.getUserData();
                    if (state != null) tb.setSelected(selectedStates.contains(state.toUpperCase()));
                });
        syncingStateControls = false;
    }

    private void applyFilter(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        filteredThreads.setPredicate(t -> {
            boolean stateMatch = selectedStates.isEmpty() || selectedStates.contains(t.state().toUpperCase());
            boolean textMatch = lower.isEmpty()
                    || t.name().toLowerCase().contains(lower)
                    || t.state().toLowerCase().contains(lower)
                    || String.valueOf(t.threadId()).contains(lower)
                    || buildSignals(t).toLowerCase().contains(lower)
                    || poolByThread.getOrDefault(t.name(), "").toLowerCase().contains(lower)
                    || topFrameLabel(t).toLowerCase().contains(lower)
                    || buildContextSecondary(t).toLowerCase().contains(lower)
                    || (t.waitingOnLock() != null && t.waitingOnLock().toString().toLowerCase().contains(lower));
            boolean frameMatch = frameFilter == null
                    || (t.stackFrames() != null && t.stackFrames().stream()
                    .anyMatch(f -> (f.className() + "." + f.methodName()).equals(frameFilter)));
            boolean nameMatch = threadNameFilter == null || threadNameFilter.contains(t.name());
            boolean poolMatch = poolFilter == null || poolFilter.equals(poolByThread.get(t.name()));
            return stateMatch && textMatch && frameMatch && nameMatch && poolMatch;
        });
        updateStats();
        updateScopeSummary();
    }

    private void updateDetail(ThreadInfo selected) {
        if (selected == null) {
            stackDetail.clear();
            selectedThreadTitle.setText("Select a thread");
            selectedThreadMeta.setText("Use search, state chips, or summary metrics to narrow the list.");
            return;
        }

        selectedThreadTitle.setText(selected.name());
        selectedThreadMeta.setText(buildSelectionMeta(selected));

        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(selected.name()).append("\"").append("\n");
        sb.append("#").append(selected.threadId()).append("  ").append(selected.state()).append("\n");
        sb.append("Pool: ").append(displayPoolName(selected)).append("\n");

        String signals = buildSignals(selected);
        if (!signals.isBlank()) {
            sb.append("Signals: ").append(signals).append("\n");
        }

        if (selected.waitingOnLock() != null) {
            sb.append("Waiting on: ").append(selected.waitingOnLock()).append("\n");
            ThreadInfo owner = lockOwnerById.get(selected.waitingOnLock().lockId());
            if (owner != null) {
                sb.append("Lock owner: ").append(owner.name()).append("\n");
            }
            long waiters = waitersByLockId.getOrDefault(selected.waitingOnLock().lockId(), 0L);
            if (waiters > 1) {
                sb.append("Waiters on same lock: ").append(waiters).append("\n");
            }
        }

        if (!selected.heldLocks().isEmpty()) {
            sb.append("Holding: ").append(
                    selected.heldLocks().stream().map(Object::toString).collect(Collectors.joining(", "))
            ).append("\n");
            long ownedWaiters = ownedLockWaiters(selected);
            if (ownedWaiters > 0) {
                sb.append("Threads waiting on held locks: ").append(ownedWaiters).append("\n");
            }
        }

        String topFrame = topFrameKey(selected);
        if (topFrame != null) {
            sb.append("Top frame: ").append(topFrame).append("\n");
        }

        sb.append("\n");
        if (selected.stackFrames() != null) {
            selected.stackFrames().forEach(f -> sb.append(f).append("\n"));
        }
        stackDetail.setText(sb.toString());
    }

    private void updateActionButtons(ThreadInfo selected) {
        boolean hasSelection = selected != null;
        selectionActionBox.setVisible(hasSelection);
        selectionActionBox.setManaged(hasSelection);
        selectionIndicatorBox.setVisible(hasSelection);
        selectionIndicatorBox.setManaged(hasSelection);
        ownerBtn.setDisable(!hasSelection || selected.waitingOnLock() == null || lockOwnerById.get(selected.waitingOnLock().lockId()) == null);
        samePoolBtn.setDisable(!hasSelection || !hasDetectedPool(selected));
        sameFrameBtn.setDisable(!hasSelection || topFrameKey(selected) == null);
        showRawBtn.setDisable(!hasSelection || onLocateRawRequested == null);
        revealSelectedBtn.setDisable(!hasSelection);
        clearFiltersBtn.setDisable(!hasQuickFilters());
    }

    private boolean hasQuickFilters() {
        return threadNameFilter != null
                || frameFilter != null
                || poolFilter != null
                || !selectedStates.isEmpty()
                || (filterField.getText() != null && !filterField.getText().isBlank());
    }

    private void updateStats() {
        statsLabel.setText(filteredThreads.size() + " of " + allThreads.size() + " threads");
        updateActionButtons(threadTable.getSelectionModel().getSelectedItem());
    }

    private void updateScopeSummary() {
        List<String> scopes = new ArrayList<>();
        if (filterField.getText() != null && !filterField.getText().isBlank()) {
            scopes.add("search \"" + filterField.getText().trim() + "\"");
        }
        if (!selectedStates.isEmpty()) {
            scopes.add("states " + selectedStates.stream().sorted(Comparator.comparingInt(s -> STATE_PRIORITY.getOrDefault(s, 99)))
                    .collect(Collectors.joining(", ")));
        }
        if (poolFilter != null) {
            scopes.add("pool " + poolFilter);
        }
        if (frameFilter != null) {
            scopes.add("top frame " + frameFilter);
        }
        if (threadNameFilter != null) {
            scopes.add(threadNameFilter.size() == 1
                    ? "thread " + threadNameFilter.iterator().next()
                    : threadNameFilter.size() + " selected threads");
        }

        boolean hasScope = !scopes.isEmpty();
        scopeSummaryBox.setVisible(hasScope);
        scopeSummaryBox.setManaged(hasScope);
        scopeLabel.setText(hasScope ? "Active filters: " + String.join("  •  ", scopes) : "");
        clearFiltersBtn.setDisable(!hasScope);
    }

    private void selectThread(String threadName) {
        for (ThreadInfo thread : sortedThreads) {
            if (thread.name().equals(threadName)) {
                threadTable.getSelectionModel().select(thread);
                threadTable.scrollTo(thread);
                return;
            }
        }
    }

    private void publishStatus(String message) {
        if (onStatusMessage != null) {
            onStatusMessage.accept(message);
        }
    }

    private void configureCompactCellLabel(Label label) {
        label.setWrapText(false);
        label.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        label.setMaxWidth(Double.MAX_VALUE);
    }

    private String buildContextSummary(ThreadInfo thread) {
        return displayPoolName(thread) + " " + buildContextSecondary(thread);
    }

    private String buildContextSecondary(ThreadInfo thread) {
        if (thread.waitingOnLock() != null) {
            ThreadInfo owner = lockOwnerById.get(thread.waitingOnLock().lockId());
            if (owner != null) {
                return "waiting on " + thread.waitingOnLock() + "  ·  owner " + owner.name();
            }
            return "waiting on " + thread.waitingOnLock();
        }
        if (thread.heldLocks() != null && !thread.heldLocks().isEmpty()) {
            long ownedWaiters = ownedLockWaiters(thread);
            if (ownedWaiters > 0) {
                return "holding " + thread.heldLocks().size() + " lock(s)  ·  " + ownedWaiters + " waiter(s)";
            }
            return "holding " + thread.heldLocks().size() + " lock(s)";
        }
        return "no active lock context";
    }

    private String buildContextTooltip(ThreadInfo thread) {
        List<String> parts = new ArrayList<>();
        parts.add("Pool: " + displayPoolName(thread));
        parts.add("Context: " + buildContextSecondary(thread));
        String frameKey = topFrameKey(thread);
        if (frameKey != null) {
            parts.add("Top frame: " + frameKey);
        }
        return String.join("\n", parts);
    }

    private String buildSelectionMeta(ThreadInfo selected) {
        List<String> parts = new ArrayList<>();
        parts.add("#" + selected.threadId());
        parts.add(selected.state());

        String pool = poolByThread.get(selected.name());
        if (pool != null && !pool.isBlank() && !pool.equals(selected.name())) {
            parts.add(pool);
        }

        String signals = buildSignals(selected);
        if (!signals.isBlank()) {
            parts.add(signals);
        }

        if (selected.waitingOnLock() != null) {
            ThreadInfo owner = lockOwnerById.get(selected.waitingOnLock().lockId());
            parts.add("waiting on " + selected.waitingOnLock());
            if (owner != null) {
                parts.add("owner " + owner.name());
            }
        } else if (selected.heldLocks() != null && !selected.heldLocks().isEmpty()) {
            parts.add("holding " + selected.heldLocks().size() + " lock(s)");
        }

        String topFrame = topFrameKey(selected);
        if (topFrame != null) {
            parts.add(topFrame);
        }

        return String.join("  •  ", parts);
    }

    private boolean hasDetectedPool(ThreadInfo thread) {
        if (thread == null) {
            return false;
        }
        String pool = poolByThread.get(thread.name());
        return pool != null && !pool.isBlank() && !pool.equals(thread.name());
    }

    private String displayPoolName(ThreadInfo thread) {
        String pool = thread == null ? null : poolByThread.get(thread.name());
        if (pool == null || pool.isBlank() || pool.equals(thread.name())) {
            return "Standalone thread";
        }
        return pool;
    }

    private String topFrameLabel(ThreadInfo thread) {
        String frameKey = topFrameKey(thread);
        if (frameKey == null) {
            return "";
        }
        int dot = frameKey.lastIndexOf('.');
        return dot > 0 ? frameKey.substring(dot + 1) : frameKey;
    }

    private String topFrameKey(ThreadInfo thread) {
        if (thread == null || thread.stackFrames() == null || thread.stackFrames().isEmpty()) {
            return null;
        }
        StackFrame preferred = thread.stackFrames().stream()
                .filter(f -> !isJdkFrame(f))
                .findFirst()
                .orElse(thread.stackFrames().get(0));
        return preferred.className() + "." + preferred.methodName();
    }

    private boolean isJdkFrame(StackFrame frame) {
        String className = frame.className();
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.");
    }

    private String buildSignals(ThreadInfo thread) {
        List<String> signals = new ArrayList<>();
        if (deadlockedThreadNames.contains(thread.name())) {
            signals.add("DEADLOCK");
        }
        if (thread.waitingOnLock() != null) {
            long waiters = waitersByLockId.getOrDefault(thread.waitingOnLock().lockId(), 0L);
            if (waiters > 1) {
                signals.add("HOT_LOCK(" + waiters + ")");
            }
        }
        long ownedWaiters = ownedLockWaiters(thread);
        if (ownedWaiters > 0) {
            signals.add("OWNER(" + ownedWaiters + ")");
        }
        String pool = poolByThread.get(thread.name());
        if (pool != null && !pool.equals(thread.name()) && thread.isWaiting()) {
            signals.add("POOL_WAIT");
        }
        return String.join(" · ", signals);
    }

    private long ownedLockWaiters(ThreadInfo thread) {
        if (thread.heldLocks() == null || thread.heldLocks().isEmpty()) {
            return 0;
        }
        return thread.heldLocks().stream()
                .mapToLong(lock -> waitersByLockId.getOrDefault(lock.lockId(), 0L))
                .sum();
    }
}
