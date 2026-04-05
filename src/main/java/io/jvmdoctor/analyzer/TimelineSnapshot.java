package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.StackFrame;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One time-point in a multi-dump timeline.
 * label  = e.g. the filename or a timestamp
 * states = threadName → state (UPPER_CASE)
 */
public record TimelineSnapshot(String label, String sourcePath, String rawText, ThreadDump dump) {

    public TimelineSnapshot(String label, String sourcePath, ThreadDump dump) {
        this(label, sourcePath, null, dump);
    }

    public Map<String, String> states() {
        return dump().threads().stream()
                .collect(Collectors.toMap(
                        t -> Objects.toString(t.name(), "<unnamed-thread>"),
                        t -> normalizeState(t.state()),
                        (a, b) -> a));
    }

    public Map<String, String> topFrames() {
        return dump().threads().stream()
                .collect(Collectors.toMap(
                        t -> Objects.toString(t.name(), "<unnamed-thread>"),
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
