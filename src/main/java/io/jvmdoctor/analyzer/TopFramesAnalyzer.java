package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.util.*;

public class TopFramesAnalyzer implements Analyzer {

    @Override
    public String name() { return "Top Frames Analyzer"; }

    @Override
    public AnalysisReport analyze(ThreadDump dump) {
        AnalysisReport report = new AnalysisReport(name());
        List<FrameStat> top = topFrames(dump, 5);

        if (top.isEmpty()) {
            report.addFinding(AnalysisReport.Severity.INFO, "No stack frames", "No stack frames found in this dump.");
            return report;
        }

        StringBuilder sb = new StringBuilder("Most common frames across all threads:\n");
        for (int i = 0; i < top.size(); i++) {
            FrameStat fs = top.get(i);
            sb.append(String.format("  %d. %s.%s  (%d threads, %.1f%%)\n",
                    i + 1, fs.simpleClassName(), fs.methodName(), fs.threadCount(), fs.percentage()));
        }
        FrameStat top1 = top.get(0);
        report.addFinding(AnalysisReport.Severity.INFO,
                "Top frame: " + top1.simpleClassName() + "." + top1.methodName()
                        + " (" + top1.threadCount() + " threads)",
                sb.toString());
        return report;
    }

    /**
     * Returns top {@code limit} frames sorted by thread count descending.
     * A frame is counted once per thread even if it appears multiple times in that thread's stack.
     */
    public List<FrameStat> topFrames(ThreadDump dump, int limit) {
        int total = dump.threads().size();
        if (total == 0) return List.of();

        // frameKey → thread count (deduplicated per thread)
        Map<String, Long> threadCounts = new LinkedHashMap<>();
        Map<String, StackFrame> frameByKey = new LinkedHashMap<>();

        for (ThreadInfo t : dump.threads()) {
            if (t.stackFrames() == null || t.stackFrames().isEmpty()) continue;
            Set<String> seen = new HashSet<>();
            for (StackFrame f : t.stackFrames()) {
                String key = f.className() + "." + f.methodName();
                if (seen.add(key)) {
                    threadCounts.merge(key, 1L, Long::sum);
                    frameByKey.putIfAbsent(key, f);
                }
            }
        }

        return threadCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .map(e -> {
                    StackFrame f = frameByKey.get(e.getKey());
                    return new FrameStat(f.className(), f.methodName(), e.getValue(), total);
                })
                .toList();
    }
}
