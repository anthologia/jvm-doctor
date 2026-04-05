package io.jvmdoctor.ui;

import java.util.Collection;
import java.util.stream.Collectors;

final class ThreadStateLabels {
    private ThreadStateLabels() {
    }

    static String display(String state) {
        if (state == null || state.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = state.trim()
                .toUpperCase()
                .replace('-', '_')
                .replace(' ', '_');
        String squashed = normalized.replace("_", "");
        return switch (squashed) {
            case "TIMEDWAITING", "TIMEDWATING", "TIMEWAITING", "TIMEWATING" -> "TIMED_WAITING";
            case "WAITING", "WATING" -> "WAITING";
            case "RUNNABLE" -> "RUNNABLE";
            case "BLOCKED" -> "BLOCKED";
            case "NEW" -> "NEW";
            case "TERMINATED" -> "TERMINATED";
            default -> normalized;
        };
    }

    static String displayList(Collection<String> states) {
        return states.stream()
                .map(ThreadStateLabels::display)
                .collect(Collectors.joining(", "));
    }
}
