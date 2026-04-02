package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.LockInfo;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.util.*;

/**
 * Detects deadlock cycles by building a wait-for graph.
 * Edge: thread A → lock L → thread B (B holds L, A waits for L).
 */
public class DeadlockAnalyzer implements Analyzer {

    @Override
    public String name() { return "Deadlock Analyzer"; }

    @Override
    public AnalysisReport analyze(ThreadDump dump) {
        AnalysisReport report = new AnalysisReport(name());

        // Map: lockId -> thread that holds it
        Map<String, ThreadInfo> lockHolder = new HashMap<>();
        for (ThreadInfo t : dump.threads()) {
            if (t.heldLocks() != null) {
                for (LockInfo li : t.heldLocks()) {
                    lockHolder.put(li.lockId(), t);
                }
            }
        }

        // Build wait-for graph: threadName -> threadName it's waiting for
        Map<String, String> waitFor = new HashMap<>();
        Map<String, LockInfo> waitForLock = new HashMap<>();
        for (ThreadInfo t : dump.threads()) {
            if (t.waitingOnLock() != null) {
                ThreadInfo holder = lockHolder.get(t.waitingOnLock().lockId());
                if (holder != null && !holder.name().equals(t.name())) {
                    waitFor.put(t.name(), holder.name());
                    waitForLock.put(t.name(), t.waitingOnLock());
                }
            }
        }

        // Detect cycles using DFS
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<List<String>> cycles = new ArrayList<>();

        for (String node : waitFor.keySet()) {
            if (!visited.contains(node)) {
                List<String> path = new ArrayList<>();
                detectCycle(node, waitFor, visited, inStack, path, cycles);
            }
        }

        if (cycles.isEmpty()) {
            report.addFinding(AnalysisReport.Severity.INFO, "No deadlocks detected", "The thread dump contains no deadlock cycles.");
        } else {
            for (List<String> cycle : cycles) {
                StringBuilder detail = new StringBuilder("Deadlock cycle:\n");
                for (int i = 0; i < cycle.size(); i++) {
                    String t = cycle.get(i);
                    String next = cycle.get((i + 1) % cycle.size());
                    LockInfo lock = waitForLock.get(t);
                    detail.append("  \"").append(t).append("\" waits for lock ")
                          .append(lock != null ? lock : "?")
                          .append(" held by \"").append(next).append("\"\n");
                }
                report.addFinding(AnalysisReport.Severity.CRITICAL,
                        "Deadlock detected (" + cycle.size() + " threads)", detail.toString());
            }
        }

        return report;
    }

    private void detectCycle(String node, Map<String, String> waitFor,
                              Set<String> visited, Set<String> inStack,
                              List<String> path, List<List<String>> cycles) {
        visited.add(node);
        inStack.add(node);
        path.add(node);

        String next = waitFor.get(node);
        if (next != null) {
            if (inStack.contains(next)) {
                // Found cycle — extract it
                int start = path.indexOf(next);
                if (start >= 0) {
                    List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
                    // Avoid duplicate cycles
                    String normalized = normalize(cycle);
                    boolean duplicate = cycles.stream()
                            .anyMatch(c -> normalize(c).equals(normalized));
                    if (!duplicate) cycles.add(cycle);
                }
            } else if (!visited.contains(next)) {
                detectCycle(next, waitFor, visited, inStack, path, cycles);
            }
        }

        path.remove(path.size() - 1);
        inStack.remove(node);
    }

    private String normalize(List<String> cycle) {
        if (cycle.isEmpty()) return "";
        String min = Collections.min(cycle);
        int idx = cycle.indexOf(min);
        List<String> rotated = new ArrayList<>(cycle.subList(idx, cycle.size()));
        rotated.addAll(cycle.subList(0, idx));
        return String.join("->", rotated);
    }

    /** Returns the list of deadlocked thread cycles for UI use. */
    public List<List<String>> findDeadlockCycles(ThreadDump dump) {
        Map<String, ThreadInfo> lockHolder = new HashMap<>();
        for (ThreadInfo t : dump.threads()) {
            if (t.heldLocks() != null) {
                for (LockInfo li : t.heldLocks()) {
                    lockHolder.put(li.lockId(), t);
                }
            }
        }
        Map<String, String> waitFor = new HashMap<>();
        for (ThreadInfo t : dump.threads()) {
            if (t.waitingOnLock() != null) {
                ThreadInfo holder = lockHolder.get(t.waitingOnLock().lockId());
                if (holder != null && !holder.name().equals(t.name())) {
                    waitFor.put(t.name(), holder.name());
                }
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<List<String>> cycles = new ArrayList<>();
        for (String node : waitFor.keySet()) {
            if (!visited.contains(node)) {
                detectCycle(node, waitFor, visited, inStack, new ArrayList<>(), cycles);
            }
        }
        return cycles;
    }
}
