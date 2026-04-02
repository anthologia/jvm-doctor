package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.LockInfo;
import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeadlockAnalyzerTest {

    private DeadlockAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new DeadlockAnalyzer();
    }

    @Test
    void detectsSimpleDeadlock() {
        // Thread-A holds lock1, waits for lock2
        // Thread-B holds lock2, waits for lock1
        ThreadInfo a = new ThreadInfo("Thread-A", 1, "BLOCKED", 5, false,
                new LockInfo("lock2", "java.lang.Object"),
                List.of(new LockInfo("lock1", "java.lang.Object")),
                List.of());

        ThreadInfo b = new ThreadInfo("Thread-B", 2, "BLOCKED", 5, false,
                new LockInfo("lock1", "java.lang.Object"),
                List.of(new LockInfo("lock2", "java.lang.Object")),
                List.of());

        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(a, b));
        AnalysisReport report = analyzer.analyze(dump);

        assertTrue(report.hasCritical(), "Should detect deadlock as CRITICAL");
        assertEquals(1, report.findings().stream()
                .filter(f -> f.severity() == AnalysisReport.Severity.CRITICAL).count());
    }

    @Test
    void noDeadlockWhenNoCycle() {
        // Thread-A holds lock1, waits for lock2
        // Thread-B holds lock2 (nobody waits for lock1 to create a cycle)
        ThreadInfo a = new ThreadInfo("Thread-A", 1, "BLOCKED", 5, false,
                new LockInfo("lock2", "java.lang.Object"),
                List.of(new LockInfo("lock1", "java.lang.Object")),
                List.of());

        ThreadInfo b = new ThreadInfo("Thread-B", 2, "RUNNABLE", 5, false,
                null,
                List.of(new LockInfo("lock2", "java.lang.Object")),
                List.of());

        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(a, b));
        AnalysisReport report = analyzer.analyze(dump);

        assertFalse(report.hasCritical());
        assertEquals(AnalysisReport.Severity.INFO, report.findings().get(0).severity());
    }

    @Test
    void detectsThreeWayDeadlock() {
        // A → lock2 (held by B), B → lock3 (held by C), C → lock1 (held by A)
        ThreadInfo a = new ThreadInfo("Thread-A", 1, "BLOCKED", 5, false,
                new LockInfo("lock2", "java.lang.Object"),
                List.of(new LockInfo("lock1", "java.lang.Object")), List.of());

        ThreadInfo b = new ThreadInfo("Thread-B", 2, "BLOCKED", 5, false,
                new LockInfo("lock3", "java.lang.Object"),
                List.of(new LockInfo("lock2", "java.lang.Object")), List.of());

        ThreadInfo c = new ThreadInfo("Thread-C", 3, "BLOCKED", 5, false,
                new LockInfo("lock1", "java.lang.Object"),
                List.of(new LockInfo("lock3", "java.lang.Object")), List.of());

        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(a, b, c));
        List<List<String>> cycles = analyzer.findDeadlockCycles(dump);

        assertEquals(1, cycles.size());
        assertEquals(3, cycles.get(0).size());
    }

    @Test
    void emptyDumpHasNoDeadlock() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of());
        AnalysisReport report = analyzer.analyze(dump);
        assertFalse(report.hasCritical());
    }
}
