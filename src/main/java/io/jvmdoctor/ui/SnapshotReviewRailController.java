package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.TimelineSnapshot;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.IntConsumer;

public class SnapshotReviewRailController implements Initializable {

    @FXML private StackPane root;
    @FXML private VBox sessionSidebar;
    @FXML private VBox sessionSidebarCollapsed;
    @FXML private Label sessionSidebarStatusLabel;
    @FXML private Label baselineDumpLabel;
    @FXML private Label sessionSidebarHintLabel;
    @FXML private Button compareDumpsBtn;
    @FXML private Button sessionClearWorkspaceBtn;
    @FXML private ToggleButton sessionTimelineNavBtn;
    @FXML private ToggleButton pairDiffNavBtn;
    @FXML private ListView<TimelineSnapshot> sessionSnapshotList;
    @FXML private Label sessionSelectionLabel;
    @FXML private TextField sessionSelectionField;
    @FXML private Button sessionSidebarCollapseBtn;
    @FXML private Button sessionSidebarExpandBtn;
    @FXML private Button sessionMakeBaselineBtn;
    @FXML private Button sessionUseTargetBtn;
    @FXML private Button sessionRemoveBtn;

    private final ObservableList<TimelineSnapshot> snapshotItems = FXCollections.observableArrayList();

    private Runnable onCollapse = () -> { };
    private Runnable onExpand = () -> { };
    private Runnable onAddSnapshots = () -> { };
    private Runnable onOpenTimeline = () -> { };
    private Runnable onOpenCompare = () -> { };
    private Runnable onReset = () -> { };
    private Runnable onMakeBaseline = () -> { };
    private Runnable onUseTarget = () -> { };
    private Runnable onRemove = () -> { };
    private IntConsumer onSelectionChanged = ignored -> { };
    private IntConsumer onSnapshotDoubleClick = ignored -> { };

    private ViewState viewState = ViewState.empty();
    private boolean suppressSelectionEvents;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        Label placeholder = new Label("Open a dump to start building a snapshot review.");
        placeholder.getStyleClass().add("status-label");
        sessionSnapshotList.setPlaceholder(placeholder);
        sessionSnapshotList.setItems(snapshotItems);
        sessionSnapshotList.setCellFactory(list -> new ListCell<>() {
            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() != 2 || isEmpty()) {
                        return;
                    }
                    sessionSnapshotList.getSelectionModel().select(getIndex());
                    onSnapshotDoubleClick.accept(getIndex());
                });
            }

            @Override
            protected void updateItem(TimelineSnapshot snapshot, boolean empty) {
                super.updateItem(snapshot, empty);
                if (empty || snapshot == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }

                int snapshotIndex = getIndex();

                Label indexLabel = new Label("#" + (snapshotIndex + 1));
                indexLabel.getStyleClass().add("session-snapshot-index");

                Label titleLabel = new Label(snapshot.label());
                titleLabel.getStyleClass().add("session-snapshot-title");
                titleLabel.setMaxWidth(Double.MAX_VALUE);
                titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
                HBox.setHgrow(titleLabel, Priority.ALWAYS);

                HBox titleRow = new HBox(8, indexLabel, titleLabel);
                titleRow.setFillHeight(false);

                FlowPane badgeRow = new FlowPane(6, 6);
                badgeRow.getStyleClass().add("session-badge-row");
                if (!viewState.reviewActive()) {
                    badgeRow.getChildren().add(sessionBadge("Current", "session-badge-current"));
                } else if (snapshotIndex == viewState.baselineIndex()) {
                    badgeRow.getChildren().add(sessionBadge("Baseline", "session-badge-anchor"));
                }
                if (viewState.reviewActive()
                        && snapshotIndex == viewState.targetIndex()
                        && snapshotIndex != viewState.baselineIndex()) {
                    badgeRow.getChildren().add(sessionBadge("Compare", "session-badge-target"));
                }
                if (viewState.reviewActive() && snapshotIndex == snapshotItems.size() - 1) {
                    badgeRow.getChildren().add(sessionBadge("Latest", "session-badge-latest"));
                }

                Label metaLabel = new Label(sourceDescription(snapshot));
                metaLabel.getStyleClass().add("session-snapshot-meta");
                metaLabel.setWrapText(true);

                VBox body = new VBox(4, titleRow);
                if (!badgeRow.getChildren().isEmpty()) {
                    body.getChildren().add(badgeRow);
                }
                body.getChildren().add(metaLabel);

                setText(null);
                setGraphic(body);
                setTooltip(new Tooltip(snapshot.label() + "\n" + sourceDescription(snapshot)));
            }
        });
        sessionSnapshotList.getSelectionModel().selectedIndexProperty().addListener((obs, old, selected) -> {
            updateSelectionControls();
            sessionSnapshotList.refresh();
            if (!suppressSelectionEvents) {
                onSelectionChanged.accept(selected == null ? -1 : selected.intValue());
            }
        });

        sessionSidebarCollapseBtn.setTooltip(new Tooltip("Collapse snapshot review"));
        sessionSidebarExpandBtn.setTooltip(new Tooltip("Expand snapshot review"));
        render(ViewState.empty());
        setCollapsed(false);
    }

    public void render(ViewState state) {
        viewState = state == null ? ViewState.empty() : state;
        sessionSidebarStatusLabel.setText(viewState.statusText());
        sessionSidebarHintLabel.setText(viewState.hintText());
        baselineDumpLabel.setText(viewState.baselineLabelText());
        baselineDumpLabel.setVisible(viewState.showBaselineLabel());
        baselineDumpLabel.setManaged(viewState.showBaselineLabel());

        compareDumpsBtn.setDisable(viewState.addDisabled());
        compareDumpsBtn.setText(viewState.addButtonText());
        Tooltip addTooltip = compareDumpsBtn.getTooltip();
        if (addTooltip == null) {
            compareDumpsBtn.setTooltip(new Tooltip(viewState.addButtonTooltip()));
        } else {
            addTooltip.setText(viewState.addButtonTooltip());
        }
        sessionClearWorkspaceBtn.setDisable(viewState.resetDisabled());
        sessionTimelineNavBtn.setDisable(viewState.timelineDisabled());
        sessionTimelineNavBtn.setSelected(viewState.timelineSelected());
        pairDiffNavBtn.setDisable(viewState.compareDisabled());
        pairDiffNavBtn.setSelected(viewState.compareSelected());

        suppressSelectionEvents = true;
        snapshotItems.setAll(viewState.snapshots());
        if (viewState.selectedIndex() >= 0 && viewState.selectedIndex() < snapshotItems.size()) {
            sessionSnapshotList.getSelectionModel().select(viewState.selectedIndex());
            sessionSnapshotList.scrollTo(viewState.selectedIndex());
        } else {
            sessionSnapshotList.getSelectionModel().clearSelection();
        }
        suppressSelectionEvents = false;

        updateSelectionControls();
        sessionSnapshotList.refresh();
    }

    public void setCollapsed(boolean collapsed) {
        sessionSidebar.setVisible(!collapsed);
        sessionSidebar.setManaged(!collapsed);
        sessionSidebarCollapsed.setVisible(collapsed);
        sessionSidebarCollapsed.setManaged(collapsed);
    }

    public int selectedIndex() {
        return sessionSnapshotList.getSelectionModel().getSelectedIndex();
    }

    public void setOnCollapse(Runnable callback) {
        onCollapse = callback == null ? () -> { } : callback;
    }

    public void setOnExpand(Runnable callback) {
        onExpand = callback == null ? () -> { } : callback;
    }

    public void setOnAddSnapshots(Runnable callback) {
        onAddSnapshots = callback == null ? () -> { } : callback;
    }

    public void setOnOpenTimeline(Runnable callback) {
        onOpenTimeline = callback == null ? () -> { } : callback;
    }

    public void setOnOpenCompare(Runnable callback) {
        onOpenCompare = callback == null ? () -> { } : callback;
    }

    public void setOnReset(Runnable callback) {
        onReset = callback == null ? () -> { } : callback;
    }

    public void setOnMakeBaseline(Runnable callback) {
        onMakeBaseline = callback == null ? () -> { } : callback;
    }

    public void setOnUseTarget(Runnable callback) {
        onUseTarget = callback == null ? () -> { } : callback;
    }

    public void setOnRemove(Runnable callback) {
        onRemove = callback == null ? () -> { } : callback;
    }

    public void setOnSelectionChanged(IntConsumer callback) {
        onSelectionChanged = callback == null ? ignored -> { } : callback;
    }

    public void setOnSnapshotDoubleClick(IntConsumer callback) {
        onSnapshotDoubleClick = callback == null ? ignored -> { } : callback;
    }

    @FXML
    private void onCollapseSidebar() {
        onCollapse.run();
    }

    @FXML
    private void onExpandSidebar() {
        onExpand.run();
    }

    @FXML
    private void onAddSnapshots() {
        onAddSnapshots.run();
    }

    @FXML
    private void onOpenTimeline() {
        onOpenTimeline.run();
    }

    @FXML
    private void onOpenCompare() {
        onOpenCompare.run();
    }

    @FXML
    private void onReset() {
        onReset.run();
    }

    @FXML
    private void onMakeBaseline() {
        onMakeBaseline.run();
    }

    @FXML
    private void onUseTarget() {
        onUseTarget.run();
    }

    @FXML
    private void onRemove() {
        onRemove.run();
    }

    private void updateSelectionControls() {
        int selectedIndex = selectedIndex();
        TimelineSnapshot selectedSnapshot = sessionSnapshotList.getSelectionModel().getSelectedItem();
        boolean hasReview = viewState.reviewActive();
        boolean alreadyBaseline = hasReview && selectedSnapshot != null && selectedIndex == viewState.baselineIndex();
        boolean canMakeBaseline = hasReview && selectedSnapshot != null && selectedIndex >= 0 && !alreadyBaseline;
        boolean canUseAsTarget = hasReview && selectedSnapshot != null && selectedIndex >= 0
                && selectedIndex != viewState.baselineIndex();
        boolean alreadyTarget = canUseAsTarget && selectedIndex == viewState.targetIndex();

        sessionMakeBaselineBtn.setText(hasReview
                ? alreadyBaseline ? "Baseline" : "Make Baseline"
                : "Pending Baseline");
        sessionMakeBaselineBtn.setDisable(!canMakeBaseline);
        sessionUseTargetBtn.setText(alreadyTarget ? "Already Comparing" : "Compare This Snapshot");
        sessionUseTargetBtn.setDisable(!canUseAsTarget || alreadyTarget);
        sessionRemoveBtn.setDisable(!hasReview || selectedSnapshot == null || selectedIndex == viewState.baselineIndex());

        if (selectedSnapshot == null) {
            sessionSelectionLabel.setText("Selected Snapshot");
            sessionSelectionField.clear();
            return;
        }

        sessionSelectionLabel.setText(!hasReview
                ? "Current Dump"
                : selectedIndex == viewState.baselineIndex()
                ? "Baseline"
                : selectedIndex == viewState.targetIndex() ? "Compare Target" : "Selected Snapshot");
        sessionSelectionField.setText(sourceDescription(selectedSnapshot));
    }

    private Label sessionBadge(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("session-badge", styleClass);
        return label;
    }

    private String sourceDescription(TimelineSnapshot snapshot) {
        if (snapshot.sourcePath() == null || snapshot.sourcePath().isBlank()) {
            return "Current analyzed dump";
        }
        return snapshot.sourcePath();
    }

    public record ViewState(
            List<TimelineSnapshot> snapshots,
            boolean reviewActive,
            int baselineIndex,
            int targetIndex,
            int selectedIndex,
            String statusText,
            String hintText,
            String baselineLabelText,
            boolean showBaselineLabel,
            boolean addDisabled,
            String addButtonText,
            String addButtonTooltip,
            boolean resetDisabled,
            boolean timelineDisabled,
            boolean timelineSelected,
            boolean compareDisabled,
            boolean compareSelected
    ) {
        public ViewState {
            snapshots = List.copyOf(Objects.requireNonNullElse(snapshots, List.of()));
            statusText = Objects.requireNonNullElse(statusText, "");
            hintText = Objects.requireNonNullElse(hintText, "");
            baselineLabelText = Objects.requireNonNullElse(baselineLabelText, "");
            addButtonText = Objects.requireNonNullElse(addButtonText, "Add Snapshots");
            addButtonTooltip = Objects.requireNonNullElse(addButtonTooltip, "");
        }

        public static ViewState empty() {
            return new ViewState(
                    List.of(),
                    false,
                    -1,
                    -1,
                    -1,
                    "No dump loaded yet.",
                    "Open and analyze a dump first. Snapshot review starts from the current dump.",
                    "",
                    false,
                    true,
                    "Add Snapshots",
                    "Open and analyze a dump first. That dump becomes the baseline.",
                    true,
                    true,
                    false,
                    true,
                    false
            );
        }
    }
}
