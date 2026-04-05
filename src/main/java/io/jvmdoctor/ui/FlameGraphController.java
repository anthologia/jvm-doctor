package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.FlameGraphModel;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Consumer;

public class FlameGraphController implements Initializable {
    private static final List<String> PROBLEM_STATES = List.of("BLOCKED", "WAITING", "TIMED_WAITING");
    private static final Map<String, String> STATE_COLORS = Map.of(
            "BLOCKED", "#c73a4f",
            "WAITING", "#b99132",
            "TIMED_WAITING", "#8c6a45",
            "RUNNABLE", "#3677e0",
            "NEW", "#628c7b",
            "TERMINATED", "#70798a"
    );

    @FXML private ToggleButton hideJdkToggle;
    @FXML private ToggleButton reverseToggle;
    @FXML private ToggleButton colorByStateToggle;
    @FXML private Button resetZoomBtn;
    @FXML private TextField searchField;
    @FXML private Label searchResultLabel;
    @FXML private Label tooltipLabel;
    @FXML private ToggleButton problemOnlyToggle;
    @FXML private ToggleButton blockedToggle;
    @FXML private ToggleButton waitingToggle;
    @FXML private ToggleButton timedWaitingToggle;
    @FXML private ToggleButton runnableToggle;
    @FXML private ToggleButton virtualOnlyToggle;
    @FXML private ToggleButton platformOnlyToggle;
    @FXML private Label scopeStatsLabel;
    @FXML private HBox zoomBreadcrumbBar;
    @FXML private HBox zoomBreadcrumbBox;
    @FXML private SplitPane mainSplit;
    @FXML private StackPane canvasContainer;

    @FXML private VBox detailPane;
    @FXML private Label detailFrameLabel;
    @FXML private Label detailCountLabel;
    @FXML private Label detailSelfLabel;
    @FXML private Button showInTopFramesBtn;
    @FXML private Button showInThreadsBtn;
    @FXML private Button collapseDetailBtn;
    @FXML private HBox stateBar;
    @FXML private FlowPane stateLegendPane;
    @FXML private FlowPane threadListPane;

    private final FlameGraphCanvas canvas = new FlameGraphCanvas();
    private final Set<String> selectedStates = new LinkedHashSet<>();

    private ThreadDump currentDump;
    private Map<String, String> threadStateByName = Map.of();
    private Consumer<String> onFrameFilterRequested;
    private Consumer<String> onRevealInTopFramesRequested;
    private Consumer<String> onFrameFocused;
    private Consumer<String> onThreadSelected;
    private Boolean virtualFilter = null;
    private boolean syncingFilterControls = false;
    private FlameGraphModel.Node activeDetailNode;
    private double detailDividerPosition = 0.72;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        canvasContainer.getChildren().add(canvas);

        canvasContainer.widthProperty().addListener((obs, oldValue, newValue) -> {
            canvas.setWidth(newValue.doubleValue());
            canvas.repaint();
        });
        canvasContainer.heightProperty().addListener((obs, oldValue, newValue) -> {
            canvas.setHeight(newValue.doubleValue());
            canvas.repaint();
        });

        canvas.setOnTooltip(this::setHoverInfo);
        canvas.setOnZoomChanged(this::updateZoomUi);
        canvas.setOnNodeHovered(this::updateDetailPane);
        canvas.setOnNodePinned(node -> {
            if (node != null) {
                ensureDetailVisible();
            }
            updateDetailPane(node);
            updatePinIndicator();
            publishFrameFocus(node);
        });

        resetZoomBtn.setTooltip(new Tooltip("Return to the whole flame graph"));
        collapseDetailBtn.setTooltip(new Tooltip("Hide details"));
        searchField.textProperty().addListener((obs, oldValue, newValue) ->
                searchResultLabel.setText(canvas.setSearch(newValue)));

        clearDetailPane();
        setDetailVisible(false);
        updateZoomUi();
        updateScopeStats(0, 0);
        Platform.runLater(() -> {
            if (!mainSplit.getDividers().isEmpty()) {
                detailDividerPosition = mainSplit.getDividers().get(0).getPosition();
            }
        });
    }

    public void setDump(ThreadDump dump) {
        currentDump = dump;
        searchField.clear();
        searchResultLabel.setText("");

        if (dump != null) {
            Map<String, String> lookup = new HashMap<>();
            for (ThreadInfo thread : dump.threads()) {
                lookup.put(thread.name(), thread.state());
            }
            threadStateByName = lookup;
        } else {
            threadStateByName = Map.of();
        }

        boolean hasVirtualThreads = dump != null && dump.hasVirtualThreads();
        virtualOnlyToggle.setDisable(!hasVirtualThreads);
        platformOnlyToggle.setDisable(!hasVirtualThreads);
        if (!hasVirtualThreads) {
            virtualOnlyToggle.setSelected(false);
            platformOnlyToggle.setSelected(false);
            virtualFilter = null;
        }

        rebuildModel();
        clearDetailPane();
        updateZoomUi();
    }

    public void clear() {
        currentDump = null;
        threadStateByName = Map.of();
        canvas.setModel(null);
        setHoverInfo(null);
        searchField.clear();
        searchResultLabel.setText("");
        clearDetailPane();
        updateZoomUi();
        updateScopeStats(0, 0);
    }

    public void setOnFrameFilterRequested(Consumer<String> handler) {
        onFrameFilterRequested = handler;
        canvas.setOnShowInThreads(frameKey -> {
            if (onFrameFilterRequested != null) {
                onFrameFilterRequested.accept(frameKey);
            }
        });
    }

    public void setOnRevealInTopFramesRequested(Consumer<String> handler) {
        onRevealInTopFramesRequested = handler;
    }

    public void setOnFrameFocused(Consumer<String> handler) {
        onFrameFocused = handler;
    }

    public void setOnThreadSelected(Consumer<String> handler) {
        onThreadSelected = handler;
    }

    @FXML
    private void onToggleHideJdk() {
        rebuildModel();
    }

    @FXML
    private void onToggleReverse() {
        rebuildModel();
    }

    @FXML
    private void onToggleProblemOnly() {
        syncingFilterControls = true;
        boolean selected = problemOnlyToggle.isSelected();
        blockedToggle.setSelected(selected);
        waitingToggle.setSelected(selected);
        timedWaitingToggle.setSelected(selected);
        if (selected) {
            runnableToggle.setSelected(false);
        }
        syncingFilterControls = false;
        syncStateFiltersFromControls();
        rebuildModel();
    }

    @FXML
    private void onStateScopeChanged() {
        if (!syncingFilterControls && problemOnlyToggle.isSelected()) {
            problemOnlyToggle.setSelected(false);
        }
        syncStateFiltersFromControls();
        rebuildModel();
    }

    @FXML
    private void onToggleVirtualOnly() {
        if (virtualOnlyToggle.isSelected()) {
            platformOnlyToggle.setSelected(false);
            virtualFilter = Boolean.TRUE;
        } else if (!platformOnlyToggle.isSelected()) {
            virtualFilter = null;
        }
        rebuildModel();
    }

    @FXML
    private void onTogglePlatformOnly() {
        if (platformOnlyToggle.isSelected()) {
            virtualOnlyToggle.setSelected(false);
            virtualFilter = Boolean.FALSE;
        } else if (!virtualOnlyToggle.isSelected()) {
            virtualFilter = null;
        }
        rebuildModel();
    }

    @FXML
    private void onToggleColorMode() {
        canvas.setColorMode(colorByStateToggle.isSelected()
                ? FlameGraphCanvas.ColorMode.THREAD_STATE
                : FlameGraphCanvas.ColorMode.FRAME_TYPE);
    }

    @FXML
    private void onResetZoom() {
        canvas.resetZoom();
    }

    @FXML
    private void onHideDetails() {
        setDetailVisible(false);
    }

    @FXML
    private void onSearch() {
        searchResultLabel.setText(canvas.setSearch(searchField.getText()));
    }

    @FXML
    private void onShowCurrentFrameInThreads() {
        if (activeDetailNode == null || "all".equals(activeDetailNode.label()) || onFrameFilterRequested == null) {
            return;
        }
        onFrameFilterRequested.accept(activeDetailNode.label());
    }

    @FXML
    private void onShowCurrentFrameInTopFrames() {
        if (activeDetailNode == null || "all".equals(activeDetailNode.label()) || onRevealInTopFramesRequested == null) {
            return;
        }
        onRevealInTopFramesRequested.accept(activeDetailNode.label());
    }

    private void rebuildModel() {
        if (currentDump == null || currentDump.threads().isEmpty()) {
            canvas.setModel(null);
            clearDetailPane();
            publishFrameFocus(null);
            updateScopeStats(0, 0);
            updateZoomUi();
            return;
        }

        List<ThreadInfo> visibleThreads = currentDump.threads().stream()
                .filter(this::matchesScope)
                .toList();
        boolean hideJdk = hideJdkToggle.isSelected();
        boolean reverse = reverseToggle.isSelected();

        canvas.setModel(FlameGraphModel.build(visibleThreads, hideJdk, reverse));
        updateScopeStats(visibleThreads.size(), currentDump.threads().size());
        updateZoomUi();

        if (activeDetailNode != null) {
            boolean stillVisible = visibleThreads.stream()
                    .anyMatch(thread -> activeDetailNode.threadNames().contains(thread.name()));
            if (!stillVisible) {
                clearDetailPane();
                publishFrameFocus(null);
            }
        }
    }

    private void updateZoomUi() {
        resetZoomBtn.setDisable(!canvas.isZoomed());
        rebuildZoomBreadcrumbs();
    }

    private void clearDetailPane() {
        activeDetailNode = null;
        detailFrameLabel.setText("Hover a frame to inspect — click to pin");
        detailFrameLabel.getStyleClass().removeAll("flame-detail-title-active");
        detailCountLabel.setText("");
        detailSelfLabel.setText("");
        stateBar.getChildren().clear();
        stateLegendPane.getChildren().clear();
        threadListPane.getChildren().clear();
        showInThreadsBtn.setDisable(true);
        showInTopFramesBtn.setDisable(true);
    }

    private void updatePinIndicator() {
        if (canvas.hasPinnedNode()) {
            detailSelfLabel.setText(detailSelfLabel.getText()
                    + (detailSelfLabel.getText().isEmpty() ? "" : "  |  ") + "PINNED (click to unpin)");
        }
    }

    private void updateDetailPane(FlameGraphModel.Node node) {
        if (node == null || "all".equals(node.label())) {
            clearDetailPane();
            return;
        }
        activeDetailNode = node;

        detailFrameLabel.setText(node.label());
        if (!detailFrameLabel.getStyleClass().contains("flame-detail-title-active")) {
            detailFrameLabel.getStyleClass().add("flame-detail-title-active");
        }
        detailCountLabel.setText(node.totalCount() + " threads");
        detailSelfLabel.setText(node.selfCount() > 0 ? "self: " + node.selfCount() : "");
        showInThreadsBtn.setDisable(onFrameFilterRequested == null);
        showInTopFramesBtn.setDisable(onRevealInTopFramesRequested == null);

        stateBar.getChildren().clear();
        Map<String, Integer> states = node.stateCounts();
        int total = node.totalCount();
        if (total > 0) {
            List<String> orderedStates = orderedStates(states.keySet());
            for (String state : orderedStates) {
                int count = states.get(state);
                double ratio = (double) count / total;
                Region segment = new Region();
                segment.setMinHeight(14);
                segment.setPrefHeight(14);
                segment.setMaxHeight(14);
                segment.setStyle("-fx-background-color: " + STATE_COLORS.getOrDefault(state, "#7c8898") + ";");
                HBox.setHgrow(segment, Priority.ALWAYS);
                segment.prefWidthProperty().bind(stateBar.widthProperty().multiply(ratio));
                segment.setMinWidth(ratio > 0 ? 2 : 0);
                stateBar.getChildren().add(segment);
            }
        }

        stateLegendPane.getChildren().clear();
        for (String state : orderedStates(states.keySet())) {
            int count = states.get(state);
            double pct = 100.0 * count / total;

            Region swatch = new Region();
            swatch.setMinSize(10, 10);
            swatch.setPrefSize(10, 10);
            swatch.setMaxSize(10, 10);
            swatch.setStyle("-fx-background-color: " + STATE_COLORS.getOrDefault(state, "#7c8898")
                    + "; -fx-background-radius: 2;");

            Label label = new Label(ThreadStateLabels.display(state) + ": " + count + " (" + String.format("%.0f%%", pct) + ")");
            label.setStyle("-fx-text-fill: #cdd6f4; -fx-font-size: 11px;");

            HBox item = new HBox(4, swatch, label);
            item.setAlignment(Pos.CENTER_LEFT);
            stateLegendPane.getChildren().add(item);
        }

        threadListPane.getChildren().clear();
        List<String> names = node.threadNames();
        int maxShow = 200;
        int shown = Math.min(names.size(), maxShow);
        for (int index = 0; index < shown; index++) {
            String name = names.get(index);
            String state = threadStateByName.getOrDefault(name, "UNKNOWN");
            String color = STATE_COLORS.getOrDefault(state, "#7c8898");

            Region dot = new Region();
            dot.setMinSize(8, 8);
            dot.setPrefSize(8, 8);
            dot.setMaxSize(8, 8);
            dot.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 4;");

            Label nameLabel = new Label(name);
            nameLabel.setStyle("-fx-text-fill: #cdd6f4; -fx-font-size: 11px;");
            nameLabel.setMaxWidth(250);
            nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

            HBox chip = new HBox(4, dot, nameLabel);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setStyle("-fx-background-color: #24273a; -fx-background-radius: 4; "
                    + "-fx-border-color: #313244; -fx-border-radius: 4; -fx-padding: 2 6 2 6; -fx-cursor: hand;");
            chip.setOnMouseEntered(event -> chip.setStyle("-fx-background-color: #313244; -fx-background-radius: 4; "
                    + "-fx-border-color: #cba6f7; -fx-border-radius: 4; -fx-padding: 2 6 2 6; -fx-cursor: hand;"));
            chip.setOnMouseExited(event -> chip.setStyle("-fx-background-color: #24273a; -fx-background-radius: 4; "
                    + "-fx-border-color: #313244; -fx-border-radius: 4; -fx-padding: 2 6 2 6; -fx-cursor: hand;"));
            chip.setOnMouseClicked(event -> {
                if (onThreadSelected != null) {
                    onThreadSelected.accept(name);
                }
            });
            threadListPane.getChildren().add(chip);
        }
        if (names.size() > maxShow) {
            Label more = new Label("+" + (names.size() - maxShow) + " more");
            more.setStyle("-fx-text-fill: #6c7086; -fx-font-size: 11px; -fx-padding: 2 6 2 6;");
            threadListPane.getChildren().add(more);
        }
    }

    private void syncStateFiltersFromControls() {
        selectedStates.clear();
        if (blockedToggle.isSelected()) {
            selectedStates.add("BLOCKED");
        }
        if (waitingToggle.isSelected()) {
            selectedStates.add("WAITING");
        }
        if (timedWaitingToggle.isSelected()) {
            selectedStates.add("TIMED_WAITING");
        }
        if (runnableToggle.isSelected()) {
            selectedStates.add("RUNNABLE");
        }
    }

    private boolean matchesScope(ThreadInfo thread) {
        boolean stateMatch = selectedStates.isEmpty() || selectedStates.contains(thread.state().toUpperCase());
        boolean typeMatch = virtualFilter == null || thread.isVirtual() == virtualFilter;
        return stateMatch && typeMatch;
    }

    private void updateScopeStats(int shownThreads, int totalThreads) {
        if (totalThreads <= 0) {
            scopeStatsLabel.setText("");
            return;
        }
        List<String> parts = new ArrayList<>();
        parts.add(shownThreads + " / " + totalThreads + " threads");
        if (problemOnlyToggle.isSelected()) {
            parts.add("problem states");
        } else if (!selectedStates.isEmpty()) {
            parts.add(ThreadStateLabels.displayList(selectedStates));
        }
        if (virtualFilter != null) {
            parts.add(virtualFilter ? "virtual only" : "platform only");
        }
        scopeStatsLabel.setText(String.join("  ·  ", parts));
    }

    private void publishFrameFocus(FlameGraphModel.Node node) {
        if (onFrameFocused == null) {
            return;
        }
        onFrameFocused.accept(node == null || "all".equals(node.label()) ? null : node.label());
    }

    private List<String> orderedStates(Set<String> states) {
        List<String> ordered = new ArrayList<>();
        for (String state : List.of("BLOCKED", "WAITING", "TIMED_WAITING", "RUNNABLE")) {
            if (states.contains(state)) {
                ordered.add(state);
            }
        }
        for (String state : states) {
            if (!ordered.contains(state)) {
                ordered.add(state);
            }
        }
        return ordered;
    }

    private void ensureDetailVisible() {
        if (!isDetailVisible()) {
            setDetailVisible(true);
        }
    }

    private void setDetailVisible(boolean visible) {
        if (!visible && mainSplit.getItems().contains(detailPane) && !mainSplit.getDividers().isEmpty()) {
            detailDividerPosition = mainSplit.getDividers().get(0).getPosition();
        }

        detailPane.setManaged(visible);
        detailPane.setVisible(visible);

        if (visible) {
            if (!mainSplit.getItems().contains(detailPane)) {
                mainSplit.getItems().add(detailPane);
            }
            Platform.runLater(() -> {
                if (!mainSplit.getDividers().isEmpty()) {
                    mainSplit.setDividerPositions(detailDividerPosition);
                }
            });
        } else {
            mainSplit.getItems().remove(detailPane);
        }
    }

    private boolean isDetailVisible() {
        return mainSplit.getItems().contains(detailPane);
    }

    private void setHoverInfo(String text) {
        tooltipLabel.setText(text == null || text.isBlank()
                ? "Hover a frame to inspect"
                : text);
    }

    private void rebuildZoomBreadcrumbs() {
        if (zoomBreadcrumbBar == null || zoomBreadcrumbBox == null) {
            return;
        }

        List<FlameGraphModel.Node> path = canvas.zoomPath();
        boolean visible = path.size() > 1;
        zoomBreadcrumbBar.setManaged(visible);
        zoomBreadcrumbBar.setVisible(visible);
        zoomBreadcrumbBox.getChildren().clear();
        if (!visible) {
            return;
        }

        for (int index = 0; index < path.size(); index++) {
            FlameGraphModel.Node node = path.get(index);
            boolean current = index == path.size() - 1;

            if (index > 0) {
                Label separator = new Label("›");
                separator.getStyleClass().add("flame-breadcrumb-separator");
                zoomBreadcrumbBox.getChildren().add(separator);
            }

            if (current) {
                Label label = new Label(breadcrumbLabel(node));
                label.getStyleClass().add("flame-breadcrumb-current");
                zoomBreadcrumbBox.getChildren().add(label);
            } else {
                Button crumb = new Button(breadcrumbLabel(node));
                crumb.getStyleClass().addAll("toolbar-btn", "toolbar-btn-subtle", "compact-action-btn", "flame-breadcrumb-btn");
                crumb.setOnAction(event -> canvas.zoomToNode(node, false));
                zoomBreadcrumbBox.getChildren().add(crumb);
            }
        }
    }

    private String breadcrumbLabel(FlameGraphModel.Node node) {
        if (node == null || "all".equals(node.label())) {
            return "Whole Graph";
        }
        return node.displayLabel();
    }
}
