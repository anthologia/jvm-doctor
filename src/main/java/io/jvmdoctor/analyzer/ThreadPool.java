package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadInfo;

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

    /** Dominant state: whichever has the most threads */
    public String dominantState() {
        return stateCounts().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("UNKNOWN");
    }
}
