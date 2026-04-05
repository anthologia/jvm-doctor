# AGENTS.md

Read this file before changing code in this repository. Tool-specific files should point back here instead of duplicating rules.

## Project

`jvm-doctor` is a JavaFX desktop app for parsing and analyzing JVM thread dumps.

Primary goals:

- keep single-dump triage fast
- keep multi-dump review understandable
- preserve behavior unless the task explicitly changes behavior

## Core Commands

Run these from the repo root.

- `bash gradlew run`
- `bash gradlew test --rerun-tasks`
- `bash gradlew build`
- `bash scripts/verify.sh`
- `bash scripts/verify.sh --full`

If you change FXML or CSS, run `bash scripts/verify.sh`.

## Repo Map

- `src/main/java/io/jvmdoctor/parser`
  `JstackParser` converts raw dump text into the in-memory model.
- `src/main/java/io/jvmdoctor/model`
  immutable records for dumps, threads, locks, and stack frames.
- `src/main/java/io/jvmdoctor/analyzer`
  single-dump and multi-dump heuristics.
- `src/main/java/io/jvmdoctor/service`
  non-UI orchestration helpers such as dump analysis and session persistence.
- `src/main/java/io/jvmdoctor/ui`
  JavaFX controllers. `MainController` coordinates screens and shared app state.
- `src/main/resources/io/jvmdoctor`
  FXML and CSS.
- `src/test`
  parser, analyzer, and service tests.
- `src/test/resources/dumps`
  reusable sample dumps for local debugging and future tests.

## Architecture Rules

- Keep parsing logic out of UI controllers.
- Keep heuristic thresholds documented in `docs/heuristics.md`.
- Keep durable product decisions recorded in `docs/adr/`.
- Keep user-flow changes reflected in `docs/ui-flows.md`.
- Prefer small services over growing `MainController` further.

## UX Rules

- `Top Frames` should only jump to `Threads` on double click.
- `Key Metrics` are triage shortcuts, not a duplicate of thread-state distribution.
- `Snapshot Review` lives in the right rail and is the single place for multi-dump management.
- Moving the baseline also switches the active current dump to that snapshot.
- `Dump Timeline` shows session history. `Compare To Baseline` shows a point-in-time comparison against the baseline.

## Heuristic Stability

These are product behavior, not implementation details:

- state colors are semantic and must stay consistent across chart, table, and chips
- `CRITICAL`, `HOT LOCK`, `POOL ISSUES`, and multi-dump suspicious signals must not drift silently
- thread name pattern heuristics for infrastructure threads and pool grouping should be updated deliberately

If you change any of those, update `docs/heuristics.md` and add an ADR if the user-visible meaning changes.

## Change Discipline

- Do not mix unrelated fixes into the same commit.
- Prefer refactors that preserve behavior over broad rewrites.
- Add or update tests when changing parser behavior, heuristics, or services.
- Do not commit generated artifacts from `build/`.

## Before Commit

- run `bash scripts/verify.sh`
- scan `git diff --stat`
- make sure docs changed with code when heuristics or UX changed
