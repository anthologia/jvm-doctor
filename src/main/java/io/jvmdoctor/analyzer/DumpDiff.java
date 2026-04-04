package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadInfo;

import java.util.List;

/**
 * Result of comparing two thread dumps (baseline vs current).
 */
public record DumpDiff(
        List<ThreadDelta> deltas
) {

    public enum ChangeType { ADDED, REMOVED, STATE_CHANGED, UNCHANGED }

    public record ThreadDelta(
            String threadName,
            ChangeType change,
            String stateBefore,   // null for ADDED
            String stateAfter,    // null for REMOVED
            ThreadInfo threadBefore,
            ThreadInfo threadAfter
    ) {
        public boolean isSignificant() {
            return change != ChangeType.UNCHANGED;
        }

        public String transitionLabel() {
            String before = stateBefore == null ? "—" : stateBefore;
            String after = stateAfter == null ? "—" : stateAfter;
            return before + " -> " + after;
        }

        public boolean becameBlocked() {
            return !"BLOCKED".equalsIgnoreCase(stateBefore) && "BLOCKED".equalsIgnoreCase(stateAfter);
        }

        public boolean resolvedBlocked() {
            return "BLOCKED".equalsIgnoreCase(stateBefore) && !"BLOCKED".equalsIgnoreCase(stateAfter);
        }

        public boolean stuckCandidate() {
            if (threadBefore == null || threadAfter == null) {
                return false;
            }
            String beforeFrame = topFrame(threadBefore);
            String afterFrame = topFrame(threadAfter);
            return !beforeFrame.isBlank()
                    && beforeFrame.equals(afterFrame)
                    && stateBefore != null
                    && stateAfter != null
                    && stateBefore.equalsIgnoreCase(stateAfter)
                    && ("BLOCKED".equalsIgnoreCase(stateAfter)
                    || "WAITING".equalsIgnoreCase(stateAfter)
                    || "TIMED_WAITING".equalsIgnoreCase(stateAfter));
        }

        public String signals() {
            StringBuilder sb = new StringBuilder();
            if (becameBlocked()) sb.append("NEW_BLOCKED");
            if (resolvedBlocked()) appendSignal(sb, "BLOCK_RESOLVED");
            if (stuckCandidate()) appendSignal(sb, "STUCK");
            return sb.toString();
        }

        public String topFrameBefore() {
            return topFrame(threadBefore);
        }

        public String topFrameAfter() {
            return topFrame(threadAfter);
        }

        private static void appendSignal(StringBuilder sb, String signal) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(signal);
        }

        private static String topFrame(ThreadInfo thread) {
            if (thread == null || thread.stackFrames() == null || thread.stackFrames().isEmpty()) {
                return "";
            }
            StackFrame frame = thread.stackFrames().stream()
                    .filter(f -> !isJdkFrame(f))
                    .findFirst()
                    .orElse(thread.stackFrames().get(0));
            return frame.className() + "." + frame.methodName();
        }

        private static boolean isJdkFrame(StackFrame frame) {
            String className = frame.className();
            return className.startsWith("java.")
                    || className.startsWith("javax.")
                    || className.startsWith("jdk.")
                    || className.startsWith("sun.");
        }
    }

    public long addedCount()   { return deltas.stream().filter(d -> d.change() == ChangeType.ADDED).count(); }
    public long removedCount() { return deltas.stream().filter(d -> d.change() == ChangeType.REMOVED).count(); }
    public long changedCount() { return deltas.stream().filter(d -> d.change() == ChangeType.STATE_CHANGED).count(); }
    public long unchangedCount() { return deltas.stream().filter(d -> d.change() == ChangeType.UNCHANGED).count(); }
    public long newlyBlockedCount() { return deltas.stream().filter(ThreadDelta::becameBlocked).count(); }
    public long resolvedBlockedCount() { return deltas.stream().filter(ThreadDelta::resolvedBlocked).count(); }
    public long stuckCount() { return deltas.stream().filter(ThreadDelta::stuckCandidate).count(); }
}
