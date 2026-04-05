package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DumpDifferTest {

    @Test
    void computesCpuDeltaForMatchingThreads() {
        ThreadInfo before = new ThreadInfo(
                "busy-worker",
                1,
                "RUNNABLE",
                5,
                false,
                false,
                null,
                "0x101",
                120.0,
                1.0,
                null,
                List.of(),
                List.of()
        );
        ThreadInfo after = new ThreadInfo(
                "busy-worker",
                1,
                "RUNNABLE",
                5,
                false,
                false,
                null,
                "0x101",
                920.0,
                3.0,
                null,
                List.of(),
                List.of()
        );

        DumpDiff diff = new DumpDiffer().diff(
                new ThreadDump(Instant.parse("2026-04-06T00:00:00Z"), "", List.of(before)),
                new ThreadDump(Instant.parse("2026-04-06T00:00:02Z"), "", List.of(after))
        );

        DumpDiff.ThreadDelta delta = diff.deltas().getFirst();
        assertTrue(delta.hasCpuDelta());
        assertEquals(800.0, delta.cpuDeltaMillis(), 0.001);
        assertEquals(0.40, delta.intervalLoad(2_000.0), 0.001);
    }

    @Test
    void ignoresNegativeCpuDelta() {
        ThreadInfo before = new ThreadInfo(
                "worker",
                1,
                "RUNNABLE",
                5,
                false,
                false,
                null,
                "0x101",
                920.0,
                3.0,
                null,
                List.of(),
                List.of()
        );
        ThreadInfo after = new ThreadInfo(
                "worker",
                1,
                "RUNNABLE",
                5,
                false,
                false,
                null,
                "0x101",
                120.0,
                4.0,
                null,
                List.of(),
                List.of()
        );

        DumpDiff diff = new DumpDiffer().diff(
                new ThreadDump(Instant.parse("2026-04-06T00:00:00Z"), "", List.of(before)),
                new ThreadDump(Instant.parse("2026-04-06T00:00:02Z"), "", List.of(after))
        );

        DumpDiff.ThreadDelta delta = diff.deltas().getFirst();
        assertFalse(delta.hasCpuDelta());
        assertTrue(Double.isNaN(delta.cpuDeltaMillis()));
        assertTrue(Double.isNaN(delta.intervalLoad(2_000.0)));
    }
}
