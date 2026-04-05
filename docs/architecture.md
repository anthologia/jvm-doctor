# Architecture

## Overview

`jvm-doctor` has three main layers:

- parsing
- analysis
- UI orchestration

The app turns raw thread-dump text into a `ThreadDump` model, runs a set of analyzers, and binds the results into JavaFX views for single-dump and multi-dump workflows.

## Package Layout

### `parser`

- `JstackParser`
  parses raw `jstack`-style text into `ThreadDump`

### `model`

- `ThreadDump`
- `ThreadInfo`
- `StackFrame`
- `LockInfo`

These are immutable records used across parser, analyzers, and UI.

### `analyzer`

Single-dump analyzers:

- `DeadlockAnalyzer`
- `ThreadStateAnalyzer`
- `LockContentionAnalyzer`
- `ThreadPoolHealthAnalyzer`
- `EventLoopBlockingAnalyzer`
- `TopFramesAnalyzer`

Multi-dump analyzers:

- `MultiDumpAnalyzer`
- `DumpDiffer`
- `DumpDiff`
- `TimelineSnapshot`
- `MultiDumpAnalysis`

### `service`

- `SingleDumpAnalysisService`
  handles raw text loading, parsing, and analyzer execution for one dump
- `LastDumpSessionStore`
  persists the last opened dump path in `~/.jvm-doctor/last-dump-path.txt`

### `ui`

- `MainController`
  owns app-level state and routes results into child views
- `SnapshotReviewRailController`
  owns the right-side multi-dump review rail
- `ThreadTableController`
- `DeadlockViewController`
- `TopFramesController`
- `ThreadPoolController`
- `TimelineController`
- `DumpDiffController`

## Single-Dump Flow

1. user opens, drops, or pastes a dump
2. `MainController` stores raw text and clears any active multi-dump review if needed
3. `SingleDumpAnalysisService` parses and analyzes the dump
4. `MainController` applies results to:
   - summary chart and metrics
   - thread table
   - issues/deadlock view
   - top frames
   - thread pools
   - raw dump view
5. `LastDumpSessionStore` persists the source path if the dump came from a file

## Multi-Dump Flow

1. the current dump becomes the initial baseline snapshot
2. more snapshots are added from the snapshot review rail
3. `MultiDumpAnalyzer` builds `MultiDumpAnalysis`
4. `TimelineController` renders review history
5. `DumpDiffController` renders comparison against the active baseline
6. moving the baseline also switches the active current dump

## State Ownership

`MainController` owns:

- current single-dump model
- raw text
- current source path
- active metric and state filters
- current multi-dump analysis
- active compare target index

Child controllers own view-local rendering and interactions, but not the authoritative application state.

## Extension Guidance

When adding a new heuristic:

1. add it in `analyzer`
2. decide whether it is single-dump, multi-dump, or both
3. document thresholds in `docs/heuristics.md`
4. expose it through existing views instead of adding a new tab by default

When adding a new workflow:

1. decide whether it belongs to single-dump or snapshot review
2. update `docs/ui-flows.md`
3. record any durable product decision in `docs/adr/`
