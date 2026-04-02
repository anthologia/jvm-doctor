package io.jvmdoctor.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AnalysisReport {

    public enum Severity { INFO, WARNING, CRITICAL }

    public record Finding(Severity severity, String title, String detail) {}

    private final String analyzerName;
    private final List<Finding> findings = new ArrayList<>();

    public AnalysisReport(String analyzerName) {
        this.analyzerName = analyzerName;
    }

    public void addFinding(Severity severity, String title, String detail) {
        findings.add(new Finding(severity, title, detail));
    }

    public String analyzerName() { return analyzerName; }

    public List<Finding> findings() { return Collections.unmodifiableList(findings); }

    public boolean hasIssues() {
        return findings.stream().anyMatch(f -> f.severity() != Severity.INFO);
    }

    public boolean hasCritical() {
        return findings.stream().anyMatch(f -> f.severity() == Severity.CRITICAL);
    }
}
