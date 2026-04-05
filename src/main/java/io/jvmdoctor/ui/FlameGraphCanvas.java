package io.jvmdoctor.ui;

import io.jvmdoctor.analyzer.FlameGraphModel;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * A JavaFX Canvas that renders a flame graph (icicle chart — root at top, leaves at bottom).
 * Supports click-to-zoom, hover tooltips, search highlight, state-based coloring,
 * and right-click context menu.
 */
public class FlameGraphCanvas extends Canvas {

    public enum ColorMode {
        /** Color by frame type (JDK / framework / app). */
        FRAME_TYPE,
        /** Color by dominant thread state (BLOCKED=red, WAITING=yellow, RUNNABLE=blue). */
        THREAD_STATE
    }

    private static final double ROW_HEIGHT = 20;
    private static final double TEXT_PADDING = 4;
    private static final Font LABEL_FONT = Font.font("Monospaced", 11);
    private static final Font LABEL_FONT_BOLD = Font.font("Monospaced", 12);
    private static final Color DIM_OVERLAY = Color.rgb(30, 30, 46, 0.7);

    private FlameGraphModel model;
    private FlameGraphModel.Node zoomRoot;
    private final Deque<FlameGraphModel.Node> zoomHistory = new ArrayDeque<>();
    private final List<RenderedRect> rendered = new ArrayList<>();
    private RenderedRect hoveredRect;
    private FlameGraphModel.Node pinnedNode;
    private Consumer<String> onTooltip;
    private Runnable onZoomChanged;
    private Consumer<String> onShowInThreads;
    private Consumer<FlameGraphModel.Node> onNodeHovered;
    private Consumer<FlameGraphModel.Node> onNodePinned;
    private ColorMode colorMode = ColorMode.FRAME_TYPE;
    private String searchQuery = "";
    private int searchMatchCount;
    private int searchMatchThreads;
    private final ContextMenu contextMenu = new ContextMenu();

    public FlameGraphCanvas() {
        // Allow SplitPane to resize freely — Canvas must not impose a min size
        setManaged(false);
        setOnMouseMoved(this::handleMouseMove);
        setOnMouseClicked(this::handleMouseClick);
        setOnContextMenuRequested(e -> {
            RenderedRect found = findRect(e.getX(), e.getY());
            if (found != null && !"all".equals(found.node.label())) {
                showContextMenu(found.node, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });
    }

    public void setModel(FlameGraphModel model) {
        this.model = model;
        this.zoomRoot = model != null ? model.root() : null;
        this.zoomHistory.clear();
        this.hoveredRect = null;
        this.pinnedNode = null;
        this.searchQuery = "";
        this.searchMatchCount = 0;
        this.searchMatchThreads = 0;
        setCursor(Cursor.DEFAULT);
        repaint();
    }

    public void setOnTooltip(Consumer<String> onTooltip) { this.onTooltip = onTooltip; }
    public void setOnZoomChanged(Runnable onZoomChanged) { this.onZoomChanged = onZoomChanged; }
    public void setOnShowInThreads(Consumer<String> handler) { this.onShowInThreads = handler; }
    public void setOnNodeHovered(Consumer<FlameGraphModel.Node> handler) { this.onNodeHovered = handler; }
    public void setOnNodePinned(Consumer<FlameGraphModel.Node> handler) { this.onNodePinned = handler; }

    public boolean hasPinnedNode() { return pinnedNode != null; }

    public void clearPin() {
        pinnedNode = null;
        repaint();
        if (onNodePinned != null) onNodePinned.accept(null);
    }

    public void setColorMode(ColorMode mode) {
        this.colorMode = mode;
        repaint();
    }

    public ColorMode getColorMode() { return colorMode; }

    public boolean isZoomed() {
        return model != null && zoomRoot != model.root();
    }

    public String zoomContextLabel() {
        if (model == null || zoomRoot == null) {
            return "Whole graph";
        }
        if (zoomRoot == model.root()) {
            return "Whole graph";
        }
        return "Zoomed into: " + zoomRoot.displayLabel();
    }

    public void resetZoom() {
        if (model != null) {
            zoomRoot = model.root();
            zoomHistory.clear();
            repaint();
            if (onZoomChanged != null) onZoomChanged.run();
        }
    }

    public boolean canZoomBack() {
        return !zoomHistory.isEmpty();
    }

    public List<FlameGraphModel.Node> zoomPath() {
        if (model == null || zoomRoot == null) {
            return List.of();
        }
        List<FlameGraphModel.Node> path = new ArrayList<>();
        if (findPath(model.root(), zoomRoot, path)) {
            return List.copyOf(path);
        }
        return List.of(model.root());
    }

    public void zoomBack() {
        if (zoomHistory.isEmpty()) {
            resetZoom();
            return;
        }
        zoomRoot = zoomHistory.pop();
        repaint();
        if (onZoomChanged != null) onZoomChanged.run();
    }

    public void zoomToNode(FlameGraphModel.Node node, boolean rememberHistory) {
        if (model == null || node == null) {
            resetZoom();
            return;
        }
        if (node == zoomRoot) {
            return;
        }
        if (rememberHistory) {
            zoomInto(node);
        } else {
            List<FlameGraphModel.Node> path = new ArrayList<>();
            if (!findPath(model.root(), node, path)) {
                return;
            }
            zoomHistory.clear();
            for (int index = 0; index < path.size() - 1; index++) {
                zoomHistory.push(path.get(index));
            }
            zoomRoot = node;
        }
        repaint();
        if (onZoomChanged != null) onZoomChanged.run();
    }

    /**
     * Set search query. Matching frames are highlighted, others dimmed.
     * Returns a status string like "42 frames (128 threads)".
     */
    public String setSearch(String query) {
        this.searchQuery = query == null ? "" : query.toLowerCase();
        this.searchMatchCount = 0;
        this.searchMatchThreads = 0;
        if (!searchQuery.isEmpty() && zoomRoot != null) {
            countSearchMatches(zoomRoot);
        }
        repaint();
        if (searchQuery.isEmpty()) return "";
        return searchMatchCount + " frames (" + searchMatchThreads + " threads)";
    }

    public int getSearchMatchCount() { return searchMatchCount; }

    // ─── Rendering ───────────────────────────────────────────

    public void repaint() {
        rendered.clear();
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        gc.setFill(Color.web("#1e1e2e"));
        gc.fillRect(0, 0, w, h);

        if (model == null || zoomRoot == null || zoomRoot.totalCount() == 0) {
            gc.setFill(Color.web("#6c7086"));
            gc.setFont(LABEL_FONT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText("No stack data to display", w / 2, h / 2);
            return;
        }

        renderNode(gc, zoomRoot, 0, 0, w);

        // Dim non-matching frames when search is active
        if (!searchQuery.isEmpty()) {
            for (RenderedRect r : rendered) {
                if (!matchesSearch(r.node) && !"all".equals(r.node.label())) {
                    gc.setFill(DIM_OVERLAY);
                    gc.fillRect(r.x + 0.5, r.y + 0.5, Math.max(r.w - 1, 1), ROW_HEIGHT - 1);
                }
            }
            // Re-draw labels for matching frames so they're crisp on top of non-dimmed rects
            for (RenderedRect r : rendered) {
                if (matchesSearch(r.node) && r.w > 30) {
                    drawLabel(gc, r.node, r.x, r.y, r.w, false);
                }
            }
        }
    }

    private void renderNode(GraphicsContext gc, FlameGraphModel.Node node, int depth, double x, double width) {
        if (width < 0.5 || node.totalCount() == 0) return;

        double y = depth * ROW_HEIGHT;
        if (y > getHeight()) return;

        Color fillColor = frameColor(node);
        boolean isHovered = hoveredRect != null && hoveredRect.node == node;
        boolean isPinned = pinnedNode != null && pinnedNode == node;
        if (isHovered || isPinned) {
            fillColor = fillColor.brighter();
        }
        boolean isSearchMatch = !searchQuery.isEmpty() && matchesSearch(node);

        gc.setFill(fillColor);
        gc.fillRect(x + 0.5, y + 0.5, Math.max(width - 1, 1), ROW_HEIGHT - 1);

        if (isPinned && width > 2) {
            gc.setStroke(Color.web("#cba6f7"));
            gc.setLineWidth(2);
            gc.strokeRect(x + 1, y + 1, width - 2, ROW_HEIGHT - 2);
        } else if (isHovered && width > 2) {
            gc.setStroke(Color.web("#dbe7ff"));
            gc.setLineWidth(1.3);
            gc.strokeRect(x + 1, y + 1, width - 2, ROW_HEIGHT - 2);
        } else if (isSearchMatch && width > 2) {
            gc.setStroke(Color.web("#f9e2af"));
            gc.setLineWidth(1.5);
            gc.strokeRect(x + 1, y + 1, width - 2, ROW_HEIGHT - 2);
        }

        rendered.add(new RenderedRect(node, x, y, width, ROW_HEIGHT));

        drawLabel(gc, node, x, y, width, depth == 0);

        // Children
        double childX = x;
        for (FlameGraphModel.Node child : node.children()) {
            double childWidth = width * ((double) child.totalCount() / node.totalCount());
            renderNode(gc, child, depth + 1, childX, childWidth);
            childX += childWidth;
        }
    }

    private void drawLabel(GraphicsContext gc, FlameGraphModel.Node node, double x, double y, double width, boolean bold) {
        if (width <= 30) return;
        gc.setFill(Color.web("#e6e9f4"));
        gc.setFont(bold ? LABEL_FONT_BOLD : LABEL_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        String text = node.displayLabel();
        int maxChars = (int) ((width - TEXT_PADDING * 2) / 7);
        if (maxChars > 0) {
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars - 1) + "…";
            }
            gc.fillText(text, x + TEXT_PADDING, y + ROW_HEIGHT / 2);
        }
    }

    // ─── Color modes ─────────────────────────────────────────

    private Color frameColor(FlameGraphModel.Node node) {
        if ("all".equals(node.label())) {
            return Color.web("#313244");
        }
        return switch (colorMode) {
            case THREAD_STATE -> stateColor(node);
            case FRAME_TYPE -> typeColor(node);
        };
    }

    private Color stateColor(FlameGraphModel.Node node) {
        int total = node.totalCount();
        if (total == 0) return Color.web("#313244");

        double blocked = node.stateRatio("BLOCKED");
        double waiting = node.stateRatio("WAITING") + node.stateRatio("TIMED_WAITING");
        double runnable = node.stateRatio("RUNNABLE");

        // Blend: more blocked → redder, more waiting → yellower, more runnable → bluer
        if (blocked >= waiting && blocked >= runnable) {
            // Red spectrum: intensity by ratio
            return Color.hsb(0, 0.4 + blocked * 0.5, 0.35 + blocked * 0.35);
        } else if (waiting >= runnable) {
            return Color.hsb(40, 0.35 + waiting * 0.45, 0.35 + waiting * 0.3);
        } else {
            return Color.hsb(215, 0.35 + runnable * 0.45, 0.3 + runnable * 0.35);
        }
    }

    private Color typeColor(FlameGraphModel.Node node) {
        String cls = node.className();
        if (cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.")
                || cls.startsWith("sun.") || cls.startsWith("com.sun.")) {
            return Color.web("#3b5e3b");
        }
        if (cls.startsWith("io.netty.") || cls.startsWith("org.apache.tomcat.")
                || cls.startsWith("org.eclipse.jetty.") || cls.startsWith("io.grpc.")
                || cls.startsWith("okhttp3.")) {
            return Color.web("#4a4a2e");
        }
        int hash = Math.abs(node.label().hashCode());
        double hue = 15 + (hash % 30);
        double sat = 0.55 + (hash % 20) / 100.0;
        double bri = 0.55 + (hash % 15) / 100.0;
        return Color.hsb(hue, sat, bri);
    }

    // ─── Search ──────────────────────────────────────────────

    private boolean matchesSearch(FlameGraphModel.Node node) {
        if (searchQuery.isEmpty()) return false;
        return node.label().toLowerCase().contains(searchQuery)
                || node.displayLabel().toLowerCase().contains(searchQuery);
    }

    private void countSearchMatches(FlameGraphModel.Node node) {
        if (!"all".equals(node.label()) && matchesSearch(node)) {
            searchMatchCount++;
            searchMatchThreads += node.totalCount();
        }
        for (FlameGraphModel.Node child : node.children()) {
            countSearchMatches(child);
        }
    }

    // ─── Interaction ─────────────────────────────────────────

    private void handleMouseMove(MouseEvent event) {
        RenderedRect found = findRect(event.getX(), event.getY());
        if (found != hoveredRect) {
            hoveredRect = found;
            setCursor(found != null && !"all".equals(found.node.label()) ? Cursor.HAND : Cursor.DEFAULT);
            repaint();
            if (onTooltip != null) {
                onTooltip.accept(found != null ? buildTooltip(found.node) : "");
            }
            // Only update detail pane on hover when nothing is pinned
            if (pinnedNode == null && onNodeHovered != null) {
                onNodeHovered.accept(found != null ? found.node : null);
            }
        }
    }

    private String buildTooltip(FlameGraphModel.Node node) {
        return node.displayLabel()
                + "  —  " + node.totalCount() + " threads"
                + " (" + String.format("%.1f%%", 100.0 * node.totalCount() / zoomRoot.totalCount()) + ")"
                + (node.selfCount() > 0 ? "  |  self: " + node.selfCount() : "");
    }

    private void handleMouseClick(MouseEvent event) {
        if (event.getButton() == MouseButton.BACK) {
            zoomBack();
            event.consume();
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY) return;
        contextMenu.hide();

        RenderedRect found = findRect(event.getX(), event.getY());
        if (found == null) {
            // Click on empty space → unpin
            if (pinnedNode != null) {
                pinnedNode = null;
                repaint();
                if (onNodePinned != null) onNodePinned.accept(null);
                if (onNodeHovered != null) onNodeHovered.accept(null);
            }
            return;
        }

        if (event.getClickCount() >= 2) {
            // Double-click → zoom
            if (found.node == zoomRoot) {
                if (model != null) zoomRoot = model.root();
                zoomHistory.clear();
                repaint();
                if (onZoomChanged != null) onZoomChanged.run();
            } else {
                zoomToNode(found.node, true);
            }
        } else {
            // Single click → pin/unpin
            if (pinnedNode == found.node) {
                // Unpin
                pinnedNode = null;
                repaint();
                if (onNodePinned != null) onNodePinned.accept(null);
                if (onNodeHovered != null) onNodeHovered.accept(found.node);
            } else {
                pinnedNode = found.node;
                repaint();
                if (onNodePinned != null) onNodePinned.accept(pinnedNode);
            }
        }
    }

    private void showContextMenu(FlameGraphModel.Node node, double screenX, double screenY) {
        contextMenu.getItems().clear();

        // Copy frame name
        MenuItem copyItem = new MenuItem("Copy: " + node.displayLabel());
        copyItem.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(node.label());
            Clipboard.getSystemClipboard().setContent(content);
        });

        // Copy thread names
        MenuItem copyThreads = new MenuItem("Copy thread names (" + node.threadNames().size() + ")");
        copyThreads.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", node.threadNames()));
            Clipboard.getSystemClipboard().setContent(content);
        });

        contextMenu.getItems().addAll(copyItem, copyThreads);

        // Show in Threads tab
        if (onShowInThreads != null) {
            contextMenu.getItems().add(new SeparatorMenuItem());
            MenuItem showThreadsItem = new MenuItem("Show in Threads tab (" + node.totalCount() + " threads)");
            showThreadsItem.setOnAction(e -> onShowInThreads.accept(node.label()));
            contextMenu.getItems().add(showThreadsItem);
        }

        // Zoom into
        contextMenu.getItems().add(new SeparatorMenuItem());
        MenuItem zoomItem = new MenuItem("Zoom into this frame");
        zoomItem.setOnAction(e -> zoomToNode(node, true));
        contextMenu.getItems().add(zoomItem);

        if (canZoomBack()) {
            MenuItem backItem = new MenuItem("Back to previous zoom");
            backItem.setOnAction(e -> zoomBack());
            contextMenu.getItems().add(backItem);
        }

        if (isZoomed()) {
            MenuItem resetItem = new MenuItem("Reset zoom");
            resetItem.setOnAction(e -> resetZoom());
            contextMenu.getItems().add(resetItem);
        }

        contextMenu.show(this, screenX, screenY);
    }

    private RenderedRect findRect(double mx, double my) {
        for (int i = rendered.size() - 1; i >= 0; i--) {
            RenderedRect r = rendered.get(i);
            if (mx >= r.x && mx < r.x + r.w && my >= r.y && my < r.y + r.h) {
                return r;
            }
        }
        return null;
    }

    private void zoomInto(FlameGraphModel.Node node) {
        if (node == null || node == zoomRoot) {
            return;
        }
        if (zoomRoot != null) {
            zoomHistory.push(zoomRoot);
        }
        zoomRoot = node;
    }

    private boolean findPath(FlameGraphModel.Node current, FlameGraphModel.Node target, List<FlameGraphModel.Node> path) {
        path.add(current);
        if (current == target) {
            return true;
        }
        for (FlameGraphModel.Node child : current.children()) {
            if (findPath(child, target, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    private record RenderedRect(FlameGraphModel.Node node, double x, double y, double w, double h) {}
}
