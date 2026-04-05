# 0001 Baseline Review Model

## Context

The app supports both single-dump analysis and multi-dump review. Earlier iterations made it easy for the baseline concept, current dump, and comparison target to drift apart in confusing ways.

## Decision

The product model is:

- the current dump can be promoted into snapshot review as the baseline
- snapshot review is managed from the right rail
- `Dump Timeline` preserves snapshot order
- `Compare To Baseline` compares one selected snapshot against the active baseline
- changing the baseline also changes the active current dump shown by single-dump views

## Consequences

- single-dump and multi-dump views stay aligned on the same anchor snapshot
- baseline changes are heavier but easier to understand
- contributors should not implement a separate hidden baseline state without revisiting this ADR
