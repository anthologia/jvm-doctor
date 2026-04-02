package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;

import java.util.Map;

public class ThreadStateAnalyzer implements Analyzer {

    @Override
    public String name() { return "Thread State Analyzer"; }

    @Override
    public AnalysisReport analyze(ThreadDump dump) {
        AnalysisReport report = new AnalysisReport(name());
        int total = dump.threads().size();

        if (total == 0) {
            report.addFinding(AnalysisReport.Severity.WARNING, "No threads found", "The dump contains no thread entries.");
            return report;
        }

        Map<String, Long> dist = dump.stateDistribution();
        StringBuilder detail = new StringBuilder("Thread state distribution:\n");
        dist.forEach((state, count) ->
                detail.append(String.format("  %-20s %d (%.1f%%)\n", state, count, 100.0 * count / total)));

        report.addFinding(AnalysisReport.Severity.INFO,
                "Total threads: " + total, detail.toString());

        long blocked = dump.blockedCount();
        if (blocked > 0) {
            double pct = 100.0 * blocked / total;
            AnalysisReport.Severity sev = (pct > 20) ? AnalysisReport.Severity.CRITICAL : AnalysisReport.Severity.WARNING;
            report.addFinding(sev,
                    blocked + " BLOCKED threads (" + String.format("%.1f", pct) + "%)",
                    "High number of BLOCKED threads may indicate lock contention or deadlock.");
        }

        long waiting = dump.waitingCount();
        if (waiting > total * 0.5) {
            report.addFinding(AnalysisReport.Severity.WARNING,
                    waiting + " threads in WAITING/TIMED_WAITING",
                    "More than 50% of threads are waiting. Check for thread pool starvation.");
        }

        return report;
    }
}
