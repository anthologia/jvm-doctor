# UI Flows

This file describes intended user behavior. Use it to prevent accidental UX regressions.

## Single-Dump Entry

Users can start analysis by:

- `Open File`
- dragging a dump file onto the app
- `Paste Text`
- reopening the last dump path after app restart

Current expectations:

- file open and drag-and-drop analyze immediately
- pasted text analyzes immediately
- reopening the previous file is explicit and opt-in

## Summary And Triage

Top summary area is for fast triage:

- `Thread States` shows overall state distribution
- `Key Metrics` shows issue-oriented shortcuts

Current expectations:

- clicking a metric narrows `Threads`
- clicking a pie slice narrows `Threads` by state
- `Key Metrics` should not simply duplicate the pie chart

## Threads

`Threads` is the primary investigation table.

Current expectations:

- selecting a row reveals stack details below
- clicking the same selected row again clears selection
- selection details remain visible even if the row scrolls away
- row state colors keep semantic meaning even when selected

## Top Frames

Current expectations:

- single click selects only
- double click jumps to `Threads` and filters by frame

## Snapshot Review

The right rail is the only multi-dump management surface.

Current expectations:

- the current dump becomes the first baseline snapshot when review starts
- additional snapshots append to the existing review
- the rail can collapse and expand
- baseline can be moved from the rail
- moving the baseline also switches the active current dump everywhere else

## Dump Timeline

Purpose:

- show the whole loaded review over time

Current expectations:

- timeline order follows snapshot order, not baseline position
- timeline is about session history, not pair comparison

## Compare To Baseline

Purpose:

- compare one selected snapshot against the active baseline

Current expectations:

- baseline is fixed for the comparison view until the user changes it
- target snapshot is user-selectable and must not equal the baseline

## Raw Dump

Current expectations:

- raw text is read-only
- selecting a thread can reveal its raw location
- raw dump is the source-of-truth text view, not an editor
