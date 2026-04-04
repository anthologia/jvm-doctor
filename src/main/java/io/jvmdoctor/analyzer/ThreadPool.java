package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadInfo;
import io.jvmdoctor.model.StackFrame;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ThreadPool(String name, List<ThreadInfo> threads) {

    public int total() { return threads.size(); }

    public Map<String, Long> stateCounts() {
        return threads.stream()
                .collect(Collectors.groupingBy(t -> t.state().toUpperCase(), Collectors.counting()));
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
        if (name.startsWith("ForkJoinPool")) return "ForkJoin";
        if (name.startsWith("http-") || name.startsWith("https-") || name.startsWith("ajp-")) return "HTTP";
        if (name.startsWith("qtp")) return "Jetty";
        if (name.startsWith("nioEventLoopGroup")) return "Netty";
        if (name.contains("OkHttp")) return "OkHttp";
        if (name.contains("Hikari")) return "Hikari";
        if (name.contains("grpc")) return "gRPC";
        if (name.contains("Executor") || name.startsWith("pool-")) return "Executor";
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
        return frame.className() + "." + frame.methodName();
    }

    private boolean isJdkFrame(StackFrame frame) {
        String className = frame.className();
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.");
    }
}
