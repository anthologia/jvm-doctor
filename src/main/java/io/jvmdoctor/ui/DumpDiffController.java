package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.DumpDiff;
import io.jvmdoctor.analyzer.DumpDiff.ChangeType;
import io.jvmdoctor.analyzer.DumpDiff.ThreadDelta;
import io.jvmdoctor.model.ThreadInfo;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

public class DumpDiffController implements Initializable {

    @FXML private Label summaryLabel;
    @FXML private HBox filterChips;
    @FXML private ToggleButton showAdded;
    @FXML private ToggleButton showRemoved;
    @FXML private ToggleButton showChanged;
    @FXML private ToggleButton showUnchanged;
    @FXML private TextField filterField;
    @FXML private TableView<ThreadDelta> diffTable;
    @FXML private TableColumn<ThreadDelta, String> changeCol;
    @FXML private TableColumn<ThreadDelta, String> nameCol;
    @FXML private TableColumn<ThreadDelta, String> beforeCol;
    @FXML private TableColumn<ThreadDelta, String> afterCol;
    @FXML private TextArea detailArea;

    private final ObservableList<ThreadDelta> allDeltas = FXCollections.observableArrayList();
    private FilteredList<ThreadDelta> filtered;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        changeCol.setCellValueFactory(c -> new SimpleStringProperty(changeLabel(c.getValue().change())));
        changeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                getStyleClass().removeAll("diff-added", "diff-removed", "diff-changed", "diff-unchanged");
                if (empty || val == null) { setText(null); return; }
                setText(val);
                ThreadDelta d = getTableRow() == null ? null : (ThreadDelta) getTableRow().getItem();
                if (d != null) getStyleClass().add(changeCss(d.change()));
            }
        });

        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().threadName()));
        beforeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().stateBefore() == null ? "—" : c.getValue().stateBefore()));
        afterCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().stateAfter() == null ? "—" : c.getValue().stateAfter()));

        // Row styling
        diffTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(ThreadDelta item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("diff-row-added", "diff-row-removed", "diff-row-changed");
                if (!empty && item != null) {
                    switch (item.change()) {
                        case ADDED         -> getStyleClass().add("diff-row-added");
                        case REMOVED       -> getStyleClass().add("diff-row-removed");
                        case STATE_CHANGED -> getStyleClass().add("diff-row-changed");
                    }
                }
            }
        });

        filtered = new FilteredList<>(allDeltas, d -> true);
        diffTable.setItems(filtered);

        // chip toggles
        showAdded.setSelected(true);
        showRemoved.setSelected(true);
        showChanged.setSelected(true);
        showUnchanged.setSelected(false);

        showAdded.setOnAction(e -> applyFilter());
        showRemoved.setOnAction(e -> applyFilter());
        showChanged.setOnAction(e -> applyFilter());
        showUnchanged.setOnAction(e -> applyFilter());
        filterField.textProperty().addListener((obs, o, n) -> applyFilter());

        diffTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null) { detailArea.clear(); return; }
            detailArea.setText(buildDetail(sel));
        });
    }

    public void setDiff(DumpDiff diff) {
        allDeltas.setAll(diff.deltas());
        summaryLabel.setText(String.format(
                "+%d added   -%d removed   ~%d state changed   =%d unchanged",
                diff.addedCount(), diff.removedCount(), diff.changedCount(), diff.unchangedCount()));
        applyFilter();
        detailArea.clear();
    }

    public void clear() {
        allDeltas.clear();
        summaryLabel.setText("Load two dumps to compare.");
        detailArea.clear();
    }

    private void applyFilter() {
        String text = filterField.getText() == null ? "" : filterField.getText().toLowerCase();
        filtered.setPredicate(d -> {
            boolean typeOk = switch (d.change()) {
                case ADDED         -> showAdded.isSelected();
                case REMOVED       -> showRemoved.isSelected();
                case STATE_CHANGED -> showChanged.isSelected();
                case UNCHANGED     -> showUnchanged.isSelected();
            };
            boolean nameOk = text.isEmpty() || d.threadName().toLowerCase().contains(text);
            return typeOk && nameOk;
        });
    }

    private String changeLabel(ChangeType c) {
        return switch (c) {
            case ADDED         -> "+ ADDED";
            case REMOVED       -> "- REMOVED";
            case STATE_CHANGED -> "~ CHANGED";
            case UNCHANGED     -> "= SAME";
        };
    }

    private String changeCss(ChangeType c) {
        return switch (c) {
            case ADDED         -> "diff-added";
            case REMOVED       -> "diff-removed";
            case STATE_CHANGED -> "diff-changed";
            case UNCHANGED     -> "diff-unchanged";
        };
    }

    private String buildDetail(ThreadDelta d) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thread: \"").append(d.threadName()).append("\"\n");
        sb.append("Change: ").append(d.change()).append("\n");
        if (d.stateBefore() != null) sb.append("Before: ").append(d.stateBefore()).append("\n");
        if (d.stateAfter()  != null) sb.append("After:  ").append(d.stateAfter()).append("\n");

        ThreadInfo ref = d.threadAfter() != null ? d.threadAfter() : d.threadBefore();
        if (ref != null) {
            sb.append("\n");
            if (ref.waitingOnLock() != null)
                sb.append("Waiting on: ").append(ref.waitingOnLock()).append("\n");
            if (!ref.heldLocks().isEmpty())
                sb.append("Holding:    ").append(
                        ref.heldLocks().stream().map(Object::toString).collect(Collectors.joining(", "))
                ).append("\n");
            if (ref.stackFrames() != null && !ref.stackFrames().isEmpty()) {
                sb.append("\nStack:\n");
                ref.stackFrames().forEach(f -> sb.append("  ").append(f).append("\n"));
            }
        }
        return sb.toString();
    }
}
