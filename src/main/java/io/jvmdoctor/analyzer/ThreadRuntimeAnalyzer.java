package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadInfo;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ThreadRuntimeAnalyzer {
    private static final double HOT_CPU_RATIO = 0.25;
    private static final double HOT_CPU_MIN_MILLIS = 50.0;
    private static final double HOT_CPU_ABSOLUTE_MILLIS = 2_000.0;
    private static final double SPIN_CPU_RATIO = 0.60;
    private static final double SPIN_MIN_MILLIS = 100.0;

    private ThreadRuntimeAnalyzer() {
    }

    public static CpuFocus analyzeCpu(List<ThreadInfo> threads) {
        Set<String> hotThreadNames = new LinkedHashSet<>();
        Set<String> spinCandidateNames = new LinkedHashSet<>();

        ThreadInfo hottestThread = threads.stream()
                .filter(ThreadInfo::hasCpuTime)
                .max(Comparator
                        .comparingDouble((ThreadInfo thread) -> {
                            double ratio = thread.cpuLoadRatio();
                            return Double.isNaN(ratio) ? -1.0 : ratio;
                        })
                        .thenComparingDouble(ThreadInfo::cpuMillis))
                .orElse(null);

        for (ThreadInfo thread : threads) {
            if (isHotCpuThread(thread)) {
                hotThreadNames.add(thread.name());
            }
            if (isSpinCandidate(thread)) {
                spinCandidateNames.add(thread.name());
                hotThreadNames.add(thread.name());
            }
        }

        return new CpuFocus(
                Set.copyOf(hotThreadNames),
                Set.copyOf(spinCandidateNames),
                hottestThread == null ? null : hottestThread.name(),
                hottestThread == null ? Double.NaN : hottestThread.cpuMillis(),
                hottestThread == null ? Double.NaN : hottestThread.cpuLoadRatio());
    }

    public static boolean isHotCpuThread(ThreadInfo thread) {
        if (thread == null || !thread.hasCpuTime()) {
            return false;
        }
        double ratio = thread.cpuLoadRatio();
        if (!Double.isNaN(ratio) && ratio >= HOT_CPU_RATIO && thread.cpuMillis() >= HOT_CPU_MIN_MILLIS) {
            return true;
        }
        return thread.cpuMillis() >= HOT_CPU_ABSOLUTE_MILLIS;
    }

    public static boolean isSpinCandidate(ThreadInfo thread) {
        if (thread == null
                || !"RUNNABLE".equalsIgnoreCase(thread.state())
                || thread.waitingOnLock() != null
                || !thread.hasCpuTime()) {
            return false;
        }
        double ratio = thread.cpuLoadRatio();
        if (Double.isNaN(ratio) || ratio < SPIN_CPU_RATIO || thread.cpuMillis() < SPIN_MIN_MILLIS) {
            return false;
        }
        String preferredFrame = preferredFrameKey(thread);
        return preferredFrame != null && !preferredFrame.startsWith("java.")
                && !preferredFrame.startsWith("javax.")
                && !preferredFrame.startsWith("jdk.")
                && !preferredFrame.startsWith("sun.");
    }

    public static String preferredFrameKey(ThreadInfo thread) {
        if (thread == null || thread.stackFrames() == null || thread.stackFrames().isEmpty()) {
            return null;
        }
        StackFrame preferred = thread.stackFrames().stream()
                .filter(frame -> !isJdkFrame(frame))
                .findFirst()
                .orElse(thread.stackFrames().get(0));
        return preferred.className() + "." + preferred.methodName();
    }

    public static String formatCpuMillis(double cpuMillis) {
        if (Double.isNaN(cpuMillis) || cpuMillis < 0) {
            return "—";
        }
        if (cpuMillis >= 1_000.0) {
            return String.format("%.2fs", cpuMillis / 1_000.0);
        }
        if (cpuMillis >= 10.0) {
            return String.format("%.0fms", cpuMillis);
        }
        return String.format("%.1fms", cpuMillis);
    }

    public static String formatElapsedSeconds(double elapsedSeconds) {
        if (Double.isNaN(elapsedSeconds) || elapsedSeconds < 0) {
            return "—";
        }
        if (elapsedSeconds >= 3_600.0) {
            return String.format("%.1fh", elapsedSeconds / 3_600.0);
        }
        if (elapsedSeconds >= 60.0) {
            return String.format("%.1fm", elapsedSeconds / 60.0);
        }
        if (elapsedSeconds >= 10.0) {
            return String.format("%.0fs", elapsedSeconds);
        }
        return String.format("%.1fs", elapsedSeconds);
    }

    public static String formatCpuLoad(double ratio) {
        if (Double.isNaN(ratio) || ratio < 0) {
            return "—";
        }
        return String.format("%.0f%%", ratio * 100.0);
    }

    private static boolean isJdkFrame(StackFrame frame) {
        String className = frame.className();
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.");
    }

    public record CpuFocus(
            Set<String> hotThreadNames,
            Set<String> spinCandidateNames,
            String hottestThreadName,
            double hottestCpuMillis,
            double hottestCpuRatio
    ) {
        public static CpuFocus empty() {
            return new CpuFocus(Set.of(), Set.of(), null, Double.NaN, Double.NaN);
        }

        public boolean present() {
            return !hotThreadNames.isEmpty();
        }

        public int hotThreadCount() {
            return hotThreadNames.size();
        }

        public int spinCandidateCount() {
            return spinCandidateNames.size();
        }
    }
}
