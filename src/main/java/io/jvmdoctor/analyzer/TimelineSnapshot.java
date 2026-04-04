package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.StackFrame;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * One time-point in a multi-dump timeline.
 * label  = e.g. the filename or a timestamp
 * states = threadName → state (UPPER_CASE)
 */
public record TimelineSnapshot(String label, ThreadDump dump) {

    public Map<String, String> states() {
        return dump().threads().stream()
                .collect(Collectors.toMap(
                        t -> t.name(),
                        t -> t.state().toUpperCase(),
                        (a, b) -> a));
    }

    public Map<String, String> topFrames() {
        return dump().threads().stream()
                .collect(Collectors.toMap(
                        t -> t.name(),
                        this::topFrame,
                        (a, b) -> a));
    }

    private String topFrame(io.jvmdoctor.model.ThreadInfo thread) {
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
