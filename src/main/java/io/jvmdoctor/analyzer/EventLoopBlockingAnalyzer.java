package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class EventLoopBlockingAnalyzer implements Analyzer {

    private static final List<Pattern> INFRA_THREAD_PATTERNS = List.of(
            Pattern.compile("^nioEventLoopGroup-\\d+-\\d+$"),
            Pattern.compile("^qtp\\d+-\\d+$"),
            Pattern.compile("^((?:http|https|ajp)-nio(?:-\\d+)?)-exec-\\d+$"),
            Pattern.compile("^OkHttp\\s+.*"),
            Pattern.compile("^grpc-.*")
    );

    @Override
    public String name() {
        return "Event Loop Blocking Analyzer";
    }

    @Override
    public AnalysisReport analyze(ThreadDump dump) {
        AnalysisReport report = new AnalysisReport(name());

        dump.threads().stream()
                .filter(this::isInfraThread)
                .forEach(thread -> {
                    String topFrame = firstAppFrame(thread);
                    if ("BLOCKED".equalsIgnoreCase(thread.state())) {
                        report.addFinding(
                                AnalysisReport.Severity.CRITICAL,
                                "Infrastructure thread blocked: " + thread.name(),
                                thread.name() + " is BLOCKED"
                                        + (topFrame.isBlank() ? "." : " in " + topFrame + ".")
                        );
                    } else if (thread.isWaiting() && !topFrame.isBlank()) {
                        report.addFinding(
                                AnalysisReport.Severity.WARNING,
                                "Infrastructure thread parked in app code: " + thread.name(),
                                thread.name() + " is " + thread.state()
                                        + " with top application frame " + topFrame + "."
                        );
                    }
                });

        if (report.findings().isEmpty()) {
            report.addFinding(AnalysisReport.Severity.INFO,
                    "No blocked infrastructure threads detected",
                    "No event-loop or request-dispatch threads matched the blocking heuristics.");
        }

        return report;
    }

    private boolean isInfraThread(ThreadInfo thread) {
        String name = thread == null ? null : thread.name();
        if (name == null || name.isBlank()) {
            return false;
        }
        return INFRA_THREAD_PATTERNS.stream().anyMatch(p -> p.matcher(name).matches());
    }

    private String firstAppFrame(ThreadInfo thread) {
        if (thread.stackFrames() == null || thread.stackFrames().isEmpty()) {
            return "";
        }
        return thread.stackFrames().stream()
                .filter(frame -> !isJdkFrame(frame))
                .findFirst()
                .map(this::frameLabel)
                .orElse("");
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
