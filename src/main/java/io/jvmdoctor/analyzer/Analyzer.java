package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;

/**
 * Extension point for analysis strategies (rule-based or AI-powered).
 */
public interface Analyzer {
    String name();
    AnalysisReport analyze(ThreadDump dump);
}
