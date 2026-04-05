package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThreadRuntimeAnalyzerTest {

    @Test
    void detectsHotCpuAndSpinCandidates() {
        ThreadInfo spinThread = new ThreadInfo(
                "busy-worker",
                1,
                "RUNNABLE",
                5,
                false,
                false,
                null,
                "0x101",
                950.0,
                1.0,
                null,
                List.of(),
                List.of(new io.jvmdoctor.model.StackFrame("com.example.Worker", "spin", "Worker.java", 42))
        );
        ThreadInfo waitingThread = new ThreadInfo(
                "parked-worker",
                2,
                "WAITING",
                5,
                false,
                false,
                null,
                "0x102",
                10.0,
                10.0,
                null,
                List.of(),
                List.of()
        );

        ThreadRuntimeAnalyzer.CpuFocus focus = ThreadRuntimeAnalyzer.analyzeCpu(List.of(spinThread, waitingThread));

        assertTrue(ThreadRuntimeAnalyzer.isHotCpuThread(spinThread));
        assertTrue(ThreadRuntimeAnalyzer.isSpinCandidate(spinThread));
        assertTrue(focus.present());
        assertEquals(1, focus.hotThreadCount());
        assertEquals(1, focus.spinCandidateCount());
        assertEquals("busy-worker", focus.hottestThreadName());
    }

    @Test
    void normalizesFormattedRuntimeValues() {
        assertEquals("1.50s", ThreadRuntimeAnalyzer.formatCpuMillis(1500));
        assertEquals("2.0m", ThreadRuntimeAnalyzer.formatElapsedSeconds(120));
        assertEquals("75%", ThreadRuntimeAnalyzer.formatCpuLoad(0.75));
    }
}
