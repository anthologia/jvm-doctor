package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;

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
}
