package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadInfo;
import io.jvmdoctor.model.StackFrame;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record ThreadPool(String name, List<ThreadInfo> threads) {

    public int total() { return threads.size(); }

    public Map<String, Long> stateCounts() {
        return threads.stream()
                .collect(Collectors.groupingBy(
                        t -> normalizeState(t.state()),
                        Collectors.counting()));
    }

    public long blocked() {
        return stateCounts().getOrDefault("BLOCKED", 0L);
    }

    public long waiting() {
        return stateCounts().getOrDefault("WAITING", 0L)
                + stateCounts().getOrDefault("TIMED_WAITING", 0L);
    }

    public long runnable() {
        return stateCounts().getOrDefault("RUNNABLE", 0L);
    }

    public String kind() {
        String safeName = Objects.toString(name, "");
        if (safeName.startsWith("ForkJoinPool")) return "ForkJoin";
        if (safeName.startsWith("http-") || safeName.startsWith("https-") || safeName.startsWith("ajp-")) return "HTTP";
        if (safeName.startsWith("qtp")) return "Jetty";
        if (safeName.startsWith("nioEventLoopGroup")) return "Netty";
        if (safeName.contains("OkHttp")) return "OkHttp";
        if (safeName.contains("Hikari")) return "Hikari";
        if (safeName.contains("grpc")) return "gRPC";
        if (safeName.contains("Executor") || safeName.startsWith("pool-")) return "Executor";
        return total() == 1 ? "Single" : "Generic";
    }

    public String health() {
        if (blocked() >= Math.max(2, total() / 3)) return "Contended";
        if (waiting() >= Math.max(3, Math.round(total() * 0.8)) && runnable() == 0) return "Starved";
        if (runnable() >= Math.max(1, Math.round(total() * 0.6))) return "Busy";
        if (waiting() > 0 && runnable() > 0) return "Active";
        return "Healthy";
    }

    /** Dominant state: whichever has the most threads */
    public String dominantState() {
        return stateCounts().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("UNKNOWN");
    }

    public String dominantTopFrame() {
        return threads.stream()
                .map(this::firstAppFrame)
                .filter(frame -> !frame.isBlank())
                .collect(Collectors.groupingBy(frame -> frame, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("—");
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

    private String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return "UNKNOWN";
        }
        return state.toUpperCase();
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
