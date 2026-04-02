package io.jvmdoctor.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ThreadDump(
        Instant timestamp,
        String jvmInfo,
        List<ThreadInfo> threads
) {
    public Map<String, Long> stateDistribution() {
        return threads.stream()
                .collect(Collectors.groupingBy(ThreadInfo::state, Collectors.counting()));
    }

    public long blockedCount() {
        return threads.stream().filter(ThreadInfo::isBlocked).count();
    }

    public long waitingCount() {
        return threads.stream().filter(ThreadInfo::isWaiting).count();
    }
}
