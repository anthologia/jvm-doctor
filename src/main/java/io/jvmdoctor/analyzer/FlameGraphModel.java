package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.util.*;

/**
 * Builds a folded call-tree from a ThreadDump suitable for flame graph rendering.
 * Each node aggregates the number of threads that pass through a given frame,
 * along with their names and states for detailed analysis.
 */
public class FlameGraphModel {

    private final Node root;
    private final boolean reversed;

    private FlameGraphModel(Node root, boolean reversed) {
        this.root = root;
        this.reversed = reversed;
    }

    public Node root() {
        return root;
    }

    /** True if this is a standard (top-down/icicle) graph; false for reverse/bottom-up. */
    public boolean isReversed() {
        return reversed;
    }

    /**
     * Build a standard flame graph (icicle: entry point at top, leaves at bottom).
     */
    public static FlameGraphModel build(ThreadDump dump, boolean hideJdk) {
        return build(dump.threads(), hideJdk, false);
    }

    /**
     * Build from a list of threads. If {@code reverse} is true, stacks are NOT reversed,
     * producing a bottom-up (reverse flame) view rooted at leaf frames.
     */
    public static FlameGraphModel build(List<ThreadInfo> threads, boolean hideJdk) {
        return build(threads, hideJdk, false);
    }

    public static FlameGraphModel build(List<ThreadInfo> threads, boolean hideJdk, boolean reverse) {
        Node root = new Node("all", "", threads.size());
        for (ThreadInfo thread : threads) {
            List<StackFrame> frames = thread.stackFrames();
            if (frames == null || frames.isEmpty()) continue;

            List<StackFrame> ordered;
            if (reverse) {
                // Bottom-up: top of stack (leaf) first — do not reverse
                ordered = new ArrayList<>(frames);
            } else {
                // Standard icicle: bottom of stack (entry point) first
                ordered = new ArrayList<>(frames);
                Collections.reverse(ordered);
            }

            root.addThread(thread.name(), thread.state());
            Node current = root;
            for (StackFrame frame : ordered) {
                if (hideJdk && isJdkFrame(frame)) continue;
                String key = frame.className() + "." + frame.methodName();
                current = current.getOrCreateChild(key, frame.className(), frame.methodName());
                current.addThread(thread.name(), thread.state());
            }
        }
        root.computeSelfCount();
        return new FlameGraphModel(root, reverse);
    }

    static boolean isJdkFrame(StackFrame frame) {
        String cls = frame.className();
        return cls.startsWith("java.") || cls.startsWith("javax.")
                || cls.startsWith("jdk.") || cls.startsWith("sun.")
                || cls.startsWith("com.sun.");
    }

    /**
     * A node in the folded call tree.
     */
    public static class Node {
        private final String label;
        private final String className;
        private final String methodName;
        private int totalCount;
        private int selfCount;
        private final Map<String, Node> children = new LinkedHashMap<>();

        // Thread-level detail for analysis
        private final List<String> threadNames = new ArrayList<>();
        private final Map<String, Integer> stateCounts = new LinkedHashMap<>();

        Node(String label, String methodName, int totalCount) {
            this.label = label;
            this.className = label;
            this.methodName = methodName;
            this.totalCount = totalCount;
        }

        Node(String key, String className, String methodName) {
            this.label = key;
            this.className = className;
            this.methodName = methodName;
            this.totalCount = 0;
        }

        void addThread(String name, String state) {
            threadNames.add(name);
            stateCounts.merge(state, 1, Integer::sum);
        }

        Node getOrCreateChild(String key, String className, String methodName) {
            Node child = children.get(key);
            if (child == null) {
                child = new Node(key, className, methodName);
                children.put(key, child);
            }
            child.totalCount++;
            return child;
        }

        void computeSelfCount() {
            int childSum = 0;
            for (Node child : children.values()) {
                child.computeSelfCount();
                childSum += child.totalCount;
            }
            this.selfCount = Math.max(0, totalCount - childSum);
        }

        public String label() { return label; }
        public String className() { return className; }
        public String methodName() { return methodName; }
        public int totalCount() { return totalCount; }
        public int selfCount() { return selfCount; }
        public Collection<Node> children() { return children.values(); }
        public boolean isLeaf() { return children.isEmpty(); }
        public List<String> threadNames() { return Collections.unmodifiableList(threadNames); }
        public Map<String, Integer> stateCounts() { return Collections.unmodifiableMap(stateCounts); }

        /** Returns the thread state with the highest count at this node. */
        public String dominantState() {
            return stateCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("UNKNOWN");
        }

        /** Returns the ratio of a given state (0.0 – 1.0). */
        public double stateRatio(String state) {
            if (totalCount == 0) return 0;
            return (double) stateCounts.getOrDefault(state, 0) / totalCount;
        }

        public int blockedCount() { return stateCounts.getOrDefault("BLOCKED", 0); }
        public int waitingCount() {
            return stateCounts.getOrDefault("WAITING", 0)
                    + stateCounts.getOrDefault("TIMED_WAITING", 0);
        }
        public int runnableCount() { return stateCounts.getOrDefault("RUNNABLE", 0); }

        public String simpleClassName() {
            int dot = className.lastIndexOf('.');
            return dot >= 0 ? className.substring(dot + 1) : className;
        }

        public String displayLabel() {
            if ("all".equals(label)) return "all (" + totalCount + " threads)";
            return simpleClassName() + "." + methodName;
        }
    }
}
