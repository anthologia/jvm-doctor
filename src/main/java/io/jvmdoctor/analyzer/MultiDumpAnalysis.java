package io.jvmdoctor.analyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record MultiDumpAnalysis(
        List<TimelineSnapshot> snapshots,
        DumpDiff boundaryDiff,
        List<ThreadSeries> threadSeries
) {
    public int snapshotCount() {
        return snapshots.size();
    }

    public String baselineLabel() {
        return snapshots.isEmpty() ? "—" : snapshots.get(0).label();
    }

    public String latestLabel() {
        return snapshots.isEmpty() ? "—" : snapshots.get(snapshots.size() - 1).label();
    }

    public int unionThreadCount() {
        return threadSeries.size();
    }

    public long persistentThreadCount() {
        return threadSeries.stream().filter(ThreadSeries::presentInAllSnapshots).count();
    }

    public long stuckThreadCount() {
        return threadSeries.stream().filter(ThreadSeries::stuckCandidate).count();
    }

    public long flappingThreadCount() {
        return threadSeries.stream().filter(ThreadSeries::flapping).count();
    }

    public long repeatedlyBlockedThreadCount() {
        return threadSeries.stream().filter(ThreadSeries::repeatedlyBlocked).count();
    }

    public long suspiciousThreadCount() {
        return threadSeries.stream().filter(ThreadSeries::suspicious).count();
    }

    public Map<String, ThreadSeries> seriesByThreadName() {
        return threadSeries.stream()
                .collect(Collectors.toMap(ThreadSeries::threadName, series -> series, (left, right) -> left));
    }

    public List<ThreadSeries> suspiciousThreads() {
        return threadSeries.stream()
                .filter(ThreadSeries::suspicious)
                .sorted(Comparator
                        .comparingInt(ThreadSeries::suspicionScore).reversed()
                        .thenComparingInt(ThreadSeries::blockedSnapshots).reversed()
                        .thenComparingInt(ThreadSeries::appearances).reversed()
                        .thenComparing(ThreadSeries::threadName))
                .toList();
    }

    public record ThreadSeries(
            String threadName,
            List<String> statesBySnapshot,
            List<String> topFramesBySnapshot,
            int appearances,
            int transitions,
            int blockedSnapshots,
            boolean presentInAllSnapshots,
            boolean stuckCandidate,
            boolean flapping,
            boolean repeatedlyBlocked,
            boolean newlyBlocked,
            boolean resolvedBlocked,
            String firstObservedState,
            String lastObservedState,
            String dominantState,
            String stableTopFrame
    ) {
        public String stateAt(int index) {
            return index >= 0 && index < statesBySnapshot.size() ? statesBySnapshot.get(index) : null;
        }

        public String topFrameAt(int index) {
            return index >= 0 && index < topFramesBySnapshot.size() ? topFramesBySnapshot.get(index) : null;
        }

        public boolean suspicious() {
            return stuckCandidate || flapping || repeatedlyBlocked || newlyBlocked || resolvedBlocked;
        }

        public int suspicionScore() {
            int score = 0;
            if (stuckCandidate) {
                score += 7;
            }
            if (flapping) {
                score += 5;
            }
            if (repeatedlyBlocked) {
                score += 4;
            }
            if (newlyBlocked || resolvedBlocked) {
                score += 2;
            }
            if (presentInAllSnapshots) {
                score += 1;
            }
            return score;
        }

        public String seenLabel(int snapshotCount) {
            return appearances + "/" + snapshotCount;
        }

        public String signalLabel() {
            List<String> signals = new ArrayList<>();
            if (stuckCandidate) {
                signals.add("STUCK");
            }
            if (flapping) {
                signals.add("FLAPPING");
            }
            if (repeatedlyBlocked) {
                signals.add("REPEAT_BLOCKED");
            }
            if (newlyBlocked) {
                signals.add("NEW_BLOCKED");
            }
            if (resolvedBlocked) {
                signals.add("BLOCK_RESOLVED");
            }
            if (signals.isEmpty() && presentInAllSnapshots) {
                signals.add("PERSISTENT");
            }
            return String.join(" · ", signals);
        }

        public String displayTopFrame() {
            if (stableTopFrame != null && !stableTopFrame.isBlank()) {
                return stableTopFrame;
            }
            return topFramesBySnapshot.stream()
                    .filter(frame -> frame != null && !frame.isBlank())
                    .collect(Collectors.groupingBy(frame -> frame, Collectors.counting()))
                    .entrySet().stream()
                    .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey)
                    .orElse("—");
        }
    }
}
