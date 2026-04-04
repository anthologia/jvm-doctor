package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ThreadPoolHealthAnalyzer implements Analyzer {

    private final ThreadPoolGrouper grouper = new ThreadPoolGrouper();

    @Override
    public String name() {
        return "Thread Pool Health Analyzer";
    }

    @Override
    public AnalysisReport analyze(ThreadDump dump) {
        AnalysisReport report = new AnalysisReport(name());

        grouper.group(dump).stream()
                .filter(pool -> pool.total() >= 3)
                .forEach(pool -> {
                    if (pool.waiting() >= Math.max(3, Math.round(pool.total() * 0.8)) && pool.runnable() == 0) {
                        report.addFinding(
                                pool.total() >= 8 ? AnalysisReport.Severity.CRITICAL : AnalysisReport.Severity.WARNING,
                                "Possible pool starvation in " + pool.name(),
                                "Pool " + pool.name() + " has " + pool.waiting() + " waiting threads, "
                                        + pool.runnable() + " runnable threads, and dominant frame " + pool.dominantTopFrame() + "."
                        );
                    }

                    if (pool.blocked() >= Math.max(2, pool.total() / 3)) {
                        report.addFinding(
                                pool.blocked() >= 4 ? AnalysisReport.Severity.CRITICAL : AnalysisReport.Severity.WARNING,
                                "Lock-heavy pool detected: " + pool.name(),
                                "Pool " + pool.name() + " has " + pool.blocked() + " blocked threads out of "
                                        + pool.total() + ". Dominant frame: " + pool.dominantTopFrame() + "."
                        );
                    }

                    String dominantFrame = dominantAppFrame(pool);
                    long frameMatches = pool.threads().stream()
                            .filter(t -> dominantFrame.equals(firstAppFrame(t)))
                            .count();
                    if (!dominantFrame.isBlank() && frameMatches >= Math.max(3, Math.round(pool.total() * 0.7))) {
                        report.addFinding(
                                AnalysisReport.Severity.INFO,
                                "Repeated pool workload in " + pool.name(),
                                frameMatches + " threads in pool " + pool.name()
                                        + " share top frame " + dominantFrame + "."
                        );
                    }
                });

        if (report.findings().isEmpty()) {
            report.addFinding(AnalysisReport.Severity.INFO,
                    "No unhealthy thread pools detected",
                    "No pool matched the starvation or lock-contention heuristics.");
        }

        return report;
    }

    private String dominantAppFrame(ThreadPool pool) {
        return pool.threads().stream()
                .map(this::firstAppFrame)
                .filter(frame -> !frame.isBlank())
                .collect(Collectors.groupingBy(frame -> frame, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private String firstAppFrame(ThreadInfo thread) {
        if (thread.stackFrames() == null || thread.stackFrames().isEmpty()) {
            return "";
        }
        StackFrame frame = thread.stackFrames().stream()
                .filter(f -> !isJdkFrame(f))
                .findFirst()
                .orElse(thread.stackFrames().get(0));
        return frameLabel(frame);
    }

    private boolean isJdkFrame(StackFrame frame) {
        if (frame == null) {
            return false;
        }
        String className = frame.className();
        if (className == null || className.isBlank()) {
            return false;
        }
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.");
    }

    private String frameLabel(StackFrame frame) {
        if (frame == null) {
            return "";
        }
        String className = Objects.toString(frame.className(), "");
        String methodName = Objects.toString(frame.methodName(), "");
        if (className.isBlank() && methodName.isBlank()) {
            return "";
        }
        if (className.isBlank()) {
            return methodName;
        }
        if (methodName.isBlank()) {
            return className;
        }
        return className + "." + methodName;
    }
}
