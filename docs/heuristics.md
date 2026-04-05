# Heuristics

This file defines user-visible analysis meaning. If code changes the meaning here, update this document in the same change.

## Single-Dump Metrics

### `TOTAL`

Number of parsed threads in the current dump.

### `CRITICAL`

Union of threads that are considered urgent:

- threads in deadlock cycles
- threads involved in a sufficiently severe hot lock
- threads in critical unhealthy pools
- blocked infrastructure threads

### `DEADLOCKED`

Number of threads participating in detected deadlock cycles.

### `HOT LOCK`

Waiter count for the hottest lock in the dump.

Current behavior:

- choose the lock with the highest waiter count
- include the owner thread in the affected-thread set when available
- treat the hot lock as critical only when waiter count is at least `5`

### `POOL ISSUES`

Count of unhealthy thread pools detected from grouped thread names and states.

## Pool Heuristics

A pool is treated as an issue pool when:

- `total >= 3`
- pool health is `Contended` or `Starved`

A pool is treated as critical when either condition is true:

- `waiting >= max(3, round(total * 0.8))`, `runnable == 0`, and `total >= 8`
- `blocked >= 4`

Thread-pool analyzer also emits informational repeated-workload findings when many threads share the same dominant app frame.

## Infrastructure Thread Heuristics

The app treats these thread-name families as infrastructure-sensitive:

- `nioEventLoopGroup-*`
- `qtp*`
- `http-nio-*`, `https-nio-*`, `ajp-nio-*`
- `OkHttp *`
- `grpc-*`

Current behavior:

- `BLOCKED` infra threads are `CRITICAL`
- waiting infra threads parked in application code are `WARNING`

## Multi-Dump Signals

### `PERSISTENT`

Thread appears in every loaded snapshot.

### `STUCK`

Thread is considered stuck when all are true:

- appears in at least 2 snapshots
- observed state never transitions
- the stable state is one of `BLOCKED`, `WAITING`, or `TIMED_WAITING`
- the stable top frame is non-blank

### `FLAPPING`

Thread changes state frequently:

- at least 2 transitions, or
- at least 3 distinct observed states

### `REPEAT_BLOCKED`

Thread is `BLOCKED` in at least 2 snapshots.

### `NEW_BLOCKED`

The baseline-to-comparison boundary diff shows the thread becoming blocked.

### `BLOCK_RESOLVED`

The baseline-to-comparison boundary diff shows a blocked state resolving.

## Suspicion Ordering

Multi-dump suspicious threads are ranked roughly by:

1. suspicion score
2. blocked snapshot count
3. appearance count
4. thread name

Current score weights:

- `STUCK`: `+7`
- `FLAPPING`: `+5`
- `REPEAT_BLOCKED`: `+4`
- `NEW_BLOCKED` or `BLOCK_RESOLVED`: `+2`
- `PERSISTENT`: `+1`

## Color Semantics

These semantic colors must stay aligned across chart, table, legend, and filter chips:

- `BLOCKED`: red / critical
- `WAITING`: amber
- `TIMED_WAITING`: bronze
- `RUNNABLE`: blue
- `NEW`: muted teal
- `TERMINATED`: muted slate

Change them only deliberately and keep all surfaces in sync.
