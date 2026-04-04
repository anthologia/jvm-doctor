package io.jvmdoctor.analyzer;

import io.jvmdoctor.analyzer.MultiDumpAnalysis.ThreadSeries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class MultiDumpAnalyzer {

    private final DumpDiffer dumpDiffer = new DumpDiffer();

    public MultiDumpAnalysis analyze(List<TimelineSnapshot> snapshots) {
        List<TimelineSnapshot> orderedSnapshots = List.copyOf(snapshots);
        DumpDiff boundaryDiff = orderedSnapshots.size() >= 2
                ? dumpDiffer.diff(orderedSnapshots.get(0).dump(), orderedSnapshots.get(orderedSnapshots.size() - 1).dump())
                : new DumpDiff(List.of());
        Map<String, DumpDiff.ThreadDelta> boundaryByName = boundaryDiff.deltas().stream()
                .collect(Collectors.toMap(DumpDiff.ThreadDelta::threadName, delta -> delta, (left, right) -> left));

        Set<String> allThreadNames = new LinkedHashSet<>();
        orderedSnapshots.stream()
                .map(TimelineSnapshot::states)
                .forEach(map -> allThreadNames.addAll(map.keySet()));

        List<ThreadSeries> series = allThreadNames.stream()
                .map(threadName -> buildSeries(threadName, orderedSnapshots, boundaryByName.get(threadName)))
                .sorted((left, right) -> {
                    int scoreCompare = Integer.compare(right.suspicionScore(), left.suspicionScore());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    int blockedCompare = Integer.compare(right.blockedSnapshots(), left.blockedSnapshots());
                    if (blockedCompare != 0) {
                        return blockedCompare;
                    }
                    int appearanceCompare = Integer.compare(right.appearances(), left.appearances());
                    if (appearanceCompare != 0) {
                        return appearanceCompare;
                    }
                    return left.threadName().compareTo(right.threadName());
                })
                .toList();

        return new MultiDumpAnalysis(orderedSnapshots, boundaryDiff, series);
    }

    private ThreadSeries buildSeries(String threadName, List<TimelineSnapshot> snapshots, DumpDiff.ThreadDelta boundaryDelta) {
        String safeThreadName = Objects.toString(threadName, "<unnamed-thread>");
        List<String> states = new ArrayList<>(snapshots.size());
        List<String> topFrames = new ArrayList<>(snapshots.size());
        List<String> observedStates = new ArrayList<>();
        List<String> observedFrames = new ArrayList<>();
        int appearances = 0;
        int blockedSnapshots = 0;
        int transitions = 0;
        String previousObservedState = null;

        for (TimelineSnapshot snapshot : snapshots) {
            String state = snapshot.states().get(threadName);
            String topFrame = snapshot.topFrames().get(threadName);
            states.add(state);
            topFrames.add(topFrame);
            if (state == null || state.isBlank()) {
                continue;
            }
            appearances++;
            observedStates.add(state);
            if (topFrame != null && !topFrame.isBlank()) {
                observedFrames.add(topFrame);
            }
            if ("BLOCKED".equalsIgnoreCase(state)) {
                blockedSnapshots++;
            }
            if (previousObservedState != null && !previousObservedState.equalsIgnoreCase(state)) {
                transitions++;
            }
            previousObservedState = state;
        }

        Set<String> distinctStates = observedStates.stream()
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> distinctFrames = observedFrames.stream()
                .collect(Collectors.toCollection(LinkedHashSet::new));

        boolean presentInAllSnapshots = appearances == snapshots.size() && !snapshots.isEmpty();
        String firstObservedState = observedStates.isEmpty() ? null : observedStates.get(0);
        String lastObservedState = observedStates.isEmpty() ? null : observedStates.get(observedStates.size() - 1);
        String dominantState = observedStates.stream()
                .collect(Collectors.groupingBy(String::toUpperCase, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse("—");
        String stableTopFrame = distinctFrames.size() == 1 ? distinctFrames.iterator().next() : null;

        boolean waitingLikeStableState = distinctStates.size() == 1
                && distinctStates.stream().findFirst().map(state ->
                state.equals("BLOCKED") || state.equals("WAITING") || state.equals("TIMED_WAITING")).orElse(false);
        boolean stuckCandidate = appearances >= 2
                && waitingLikeStableState
                && transitions == 0
                && stableTopFrame != null
                && !stableTopFrame.isBlank();
        boolean flapping = transitions >= 2 || distinctStates.size() >= 3;
        boolean repeatedlyBlocked = blockedSnapshots >= 2;

        boolean newlyBlocked = boundaryDelta != null && boundaryDelta.becameBlocked();
        boolean resolvedBlocked = boundaryDelta != null && boundaryDelta.resolvedBlocked();

        return new ThreadSeries(
                safeThreadName,
                Collections.unmodifiableList(new ArrayList<>(states)),
                Collections.unmodifiableList(new ArrayList<>(topFrames)),
                appearances,
                transitions,
                blockedSnapshots,
                presentInAllSnapshots,
                stuckCandidate,
                flapping,
                repeatedlyBlocked,
                newlyBlocked,
                resolvedBlocked,
                firstObservedState,
                lastObservedState,
                dominantState,
                stableTopFrame
        );
    }
}
