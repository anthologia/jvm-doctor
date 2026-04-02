package io.jvmdoctor.analyzer;

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
    }

    public long addedCount()   { return deltas.stream().filter(d -> d.change() == ChangeType.ADDED).count(); }
    public long removedCount() { return deltas.stream().filter(d -> d.change() == ChangeType.REMOVED).count(); }
    public long changedCount() { return deltas.stream().filter(d -> d.change() == ChangeType.STATE_CHANGED).count(); }
    public long unchangedCount() { return deltas.stream().filter(d -> d.change() == ChangeType.UNCHANGED).count(); }
}
