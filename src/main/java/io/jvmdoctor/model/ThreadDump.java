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

    public long virtualThreadCount() {
        return threads.stream().filter(ThreadInfo::isVirtual).count();
    }

    public long platformThreadCount() {
        return threads.stream().filter(ThreadInfo::isPlatformThread).count();
    }

    public boolean hasVirtualThreads() {
        return threads.stream().anyMatch(ThreadInfo::isVirtual);
    }

    public long cpuSampleCount() {
        return threads.stream().filter(ThreadInfo::hasCpuTime).count();
    }

    public boolean hasCpuData() {
        return threads.stream().anyMatch(ThreadInfo::hasCpuTime);
    }
}
