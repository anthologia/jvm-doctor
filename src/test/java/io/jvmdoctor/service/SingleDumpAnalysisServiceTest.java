package io.jvmdoctor.service;

import io.jvmdoctor.analyzer.AnalysisReport;
import io.jvmdoctor.analyzer.Analyzer;
import io.jvmdoctor.parser.JstackParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SingleDumpAnalysisServiceTest {

    @Test
    void analyzeReturnsParsedDumpAndAnalyzerReports() {
        AtomicBoolean analyzerCalled = new AtomicBoolean(false);
        Analyzer analyzer = new Analyzer() {
            @Override
            public String name() {
                return "marker";
            }

            @Override
            public AnalysisReport analyze(io.jvmdoctor.model.ThreadDump dump) {
                analyzerCalled.set(true);
                AnalysisReport report = new AnalysisReport(name());
                report.addFinding(AnalysisReport.Severity.INFO, "threads", String.valueOf(dump.threads().size()));
                return report;
            }
        };

        SingleDumpAnalysisService service = new SingleDumpAnalysisService(new JstackParser(), List.of(analyzer));

        SingleDumpAnalysisService.AnalysisResult result = service.analyze(SAMPLE_DUMP, "/tmp/sample.dump");

        assertTrue(analyzerCalled.get());
        assertEquals("/tmp/sample.dump", result.sourcePath());
        assertEquals(SAMPLE_DUMP, result.rawDumpText());
        assertEquals(3, result.dump().threads().size());
        assertEquals(1, result.reports().size());
        assertEquals("marker", result.reports().getFirst().analyzerName());
    }

    private static final String SAMPLE_DUMP = """
            2024-01-01 12:00:00
            Full thread dump OpenJDK 64-Bit Server VM (21 mixed mode):

            "main" #1 prio=5 os_prio=0 tid=0x00000001 nid=0x100 runnable
               java.lang.Thread.State: RUNNABLE
            \tat com.example.Main.main(Main.java:10)

            "Thread-1" #2 prio=5 os_prio=0 tid=0x00000002 nid=0x101 waiting for monitor entry
               java.lang.Thread.State: BLOCKED (on object monitor)
            \tat com.example.Worker.run(Worker.java:55)
            \t- waiting to lock <0xabcdef01> (a java.lang.Object)

            "Thread-2" #3 prio=5 os_prio=0 tid=0x00000003 nid=0x102 waiting on condition
               java.lang.Thread.State: WAITING (parking)
            \tat java.lang.Object.wait(Native Method)
            \t- locked <0xabcdef01> (a java.lang.Object)
            \tat com.example.Producer.produce(Producer.java:30)
            """;
}
