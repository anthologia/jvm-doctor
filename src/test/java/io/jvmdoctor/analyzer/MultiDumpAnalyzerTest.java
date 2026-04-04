package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiDumpAnalyzerTest {

    @Test
    void analyzeHandlesThreadsMissingFromSomeSnapshots() {
        ThreadInfo threadA = new ThreadInfo("Thread-A", 1, "RUNNABLE", 5, false, null, List.of(), List.of());
        ThreadInfo threadB = new ThreadInfo("Thread-B", 2, "WAITING", 5, false, null, List.of(), List.of());

        ThreadDump dump1 = new ThreadDump(Instant.now(), "", List.of(threadA));
        ThreadDump dump2 = new ThreadDump(Instant.now(), "", List.of(threadB));

        MultiDumpAnalysis analysis = assertDoesNotThrow(() -> new MultiDumpAnalyzer().analyze(List.of(
                new TimelineSnapshot("dump-1", "dump-1", dump1),
                new TimelineSnapshot("dump-2", "dump-2", dump2)
        )));

        assertEquals(2, analysis.unionThreadCount());
        assertEquals(2, analysis.threadSeries().size());
        assertEquals("1/2", analysis.threadSeries().stream()
                .filter(series -> series.threadName().equals("Thread-A"))
                .findFirst()
                .orElseThrow()
                .seenLabel(analysis.snapshotCount()));
    }
}
