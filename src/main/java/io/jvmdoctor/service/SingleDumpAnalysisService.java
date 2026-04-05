package io.jvmdoctor.service;

import io.jvmdoctor.analyzer.AnalysisReport;
import io.jvmdoctor.analyzer.Analyzer;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.parser.JstackParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class SingleDumpAnalysisService {

    private final JstackParser parser;
    private final List<Analyzer> analyzers;

    public SingleDumpAnalysisService(JstackParser parser, List<Analyzer> analyzers) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.analyzers = List.copyOf(Objects.requireNonNull(analyzers, "analyzers"));
    }

    public LoadedDump load(Path path) throws IOException {
        Path normalizedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return new LoadedDump(normalizedPath, Files.readString(normalizedPath));
    }

    public AnalysisResult analyze(String rawDumpText, String sourcePath) {
        String normalizedText = rawDumpText == null ? "" : rawDumpText;
        ThreadDump dump = parser.parse(normalizedText);
        List<AnalysisReport> reports = analyzers.stream()
                .map(analyzer -> analyzer.analyze(dump))
                .toList();
        return new AnalysisResult(dump, normalizedText, sourcePath, reports);
    }

    public record LoadedDump(Path sourcePath, String rawDumpText) {
        public LoadedDump {
            sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            rawDumpText = Objects.requireNonNullElse(rawDumpText, "");
        }

        public String normalizedSourcePath() {
            return sourcePath.toString();
        }

        public String displayName() {
            Path fileName = sourcePath.getFileName();
            return fileName == null ? sourcePath.toString() : fileName.toString();
        }
    }

    public record AnalysisResult(ThreadDump dump, String rawDumpText, String sourcePath, List<AnalysisReport> reports) {
        public AnalysisResult {
            dump = Objects.requireNonNull(dump, "dump");
            rawDumpText = Objects.requireNonNullElse(rawDumpText, "");
            reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
        }
    }
}
