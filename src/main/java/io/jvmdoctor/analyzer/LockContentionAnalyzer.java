package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.LockInfo;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.util.*;
import java.util.stream.Collectors;

public class LockContentionAnalyzer implements Analyzer {

    @Override
    public String name() { return "Lock Contention Analyzer"; }

    @Override
    public AnalysisReport analyze(ThreadDump dump) {
        AnalysisReport report = new AnalysisReport(name());

        // Count how many threads wait for each lock
        Map<String, Long> waitersPerLock = dump.threads().stream()
                .filter(t -> t.waitingOnLock() != null)
                .collect(Collectors.groupingBy(
                        t -> t.waitingOnLock().lockId(),
                        Collectors.counting()));

        if (waitersPerLock.isEmpty()) {
            report.addFinding(AnalysisReport.Severity.INFO, "No lock contention detected",
                    "No threads are waiting to acquire a lock.");
            return report;
        }

        // Build lockId -> lockInfo map
        Map<String, LockInfo> lockInfoMap = dump.threads().stream()
                .filter(t -> t.waitingOnLock() != null)
                .collect(Collectors.toMap(
                        t -> t.waitingOnLock().lockId(),
                        ThreadInfo::waitingOnLock,
                        (a, b) -> a));

        // Build lockId -> holder map
        Map<String, ThreadInfo> lockHolder = new HashMap<>();
        for (ThreadInfo t : dump.threads()) {
            if (t.heldLocks() != null) {
                for (LockInfo li : t.heldLocks()) {
                    lockHolder.put(li.lockId(), t);
                }
            }
        }

        // Sort by contention (most waiters first)
        waitersPerLock.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    String lockId = entry.getKey();
                    long waiters = entry.getValue();
                    LockInfo li = lockInfoMap.get(lockId);
                    ThreadInfo holder = lockHolder.get(lockId);

                    String holderName = holder != null ? "\"" + holder.name() + "\"" : "unknown";
                    String detail = String.format(
                            "Lock %s (%s)\n  Held by: %s\n  Waiting threads: %d",
                            lockId, li != null ? li.lockClassName() : "?", holderName, waiters);

                    AnalysisReport.Severity sev = waiters >= 5
                            ? AnalysisReport.Severity.CRITICAL
                            : AnalysisReport.Severity.WARNING;
                    report.addFinding(sev,
                            waiters + " threads contending on " + (li != null ? li.lockClassName() : lockId),
                            detail);
                });

        return report;
    }
}
