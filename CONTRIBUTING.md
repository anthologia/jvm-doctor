# Contributing

## Requirements

- Java 21
- Gradle wrapper from this repo
- macOS is the primary desktop target today, but code should stay plain JavaFX

## Local Workflow

Typical loop:

1. make the smallest coherent change
2. run `bash scripts/verify.sh`
3. review `git diff --stat`
4. commit only the files that belong to that change

Use `bash scripts/verify.sh --full` before larger refactors or release-oriented work.

## Documentation Sync Rules

Update `docs/heuristics.md` when you change:

- severity thresholds
- pool issue rules
- infrastructure thread patterns
- multi-dump suspicious signal logic
- the meaning of `Key Metrics`

Update `docs/ui-flows.md` when you change:

- file loading
- snapshot review rail behavior
- navigation between views
- filter or selection behavior

Add or update an ADR in `docs/adr/` when you make a durable product decision that future contributors should not rediscover from code.

## Tests

Add or update tests when touching:

- `JstackParser`
- analyzer heuristics
- services in `src/main/java/io/jvmdoctor/service`

Prefer focused unit tests over large end-to-end tests.

## Commit Style

Use concise commit subjects, for example:

- `feat: improve snapshot review rail workflow`
- `fix: handle missing states in multi-dump analysis`
- `refactor: separate dump analysis and snapshot review UI`

Keep one intention per commit.
