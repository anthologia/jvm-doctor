package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.util.*;
import java.util.stream.Collectors;

public class DumpDiffer {

    /**
     * Compare baseline (dump A) against current (dump B).
     * Threads are matched by name.
     */
    public DumpDiff diff(ThreadDump baseline, ThreadDump current) {
        Map<String, ThreadInfo> before = index(baseline);
        Map<String, ThreadInfo> after  = index(current);

        List<DumpDiff.ThreadDelta> deltas = new ArrayList<>();

        // Threads in baseline
        for (Map.Entry<String, ThreadInfo> e : before.entrySet()) {
            String name = e.getKey();
            ThreadInfo b = e.getValue();
            ThreadInfo a = after.get(name);
            if (a == null) {
                deltas.add(new DumpDiff.ThreadDelta(name, DumpDiff.ChangeType.REMOVED,
                        normalizedState(b), null, b, null));
            } else if (!normalizedState(b).equalsIgnoreCase(normalizedState(a))) {
                deltas.add(new DumpDiff.ThreadDelta(name, DumpDiff.ChangeType.STATE_CHANGED,
                        normalizedState(b), normalizedState(a), b, a));
            } else {
                deltas.add(new DumpDiff.ThreadDelta(name, DumpDiff.ChangeType.UNCHANGED,
                        normalizedState(b), normalizedState(a), b, a));
            }
        }

        // Threads only in current (added)
        for (Map.Entry<String, ThreadInfo> e : after.entrySet()) {
            if (!before.containsKey(e.getKey())) {
                deltas.add(new DumpDiff.ThreadDelta(e.getKey(), DumpDiff.ChangeType.ADDED,
                        null, normalizedState(e.getValue()), null, e.getValue()));
            }
        }

        // Sort: REMOVED first, then ADDED, then STATE_CHANGED, then UNCHANGED; alpha within
        deltas.sort(Comparator
                .comparingInt((DumpDiff.ThreadDelta d) -> changeOrder(d.change()))
                .thenComparing(DumpDiff.ThreadDelta::threadName));

        return new DumpDiff(List.copyOf(deltas));
    }

    private Map<String, ThreadInfo> index(ThreadDump dump) {
        return dump.threads().stream()
                .collect(Collectors.toMap(
                        t -> t.name() == null || t.name().isBlank() ? "<unnamed-thread>" : t.name(),
                        t -> t,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    private int changeOrder(DumpDiff.ChangeType c) {
        return switch (c) {
            case REMOVED       -> 0;
            case ADDED         -> 1;
            case STATE_CHANGED -> 2;
            case UNCHANGED     -> 3;
        };
    }

    private String normalizedState(ThreadInfo thread) {
        if (thread == null || thread.state() == null || thread.state().isBlank()) {
            return "UNKNOWN";
        }
        return thread.state();
    }
}
