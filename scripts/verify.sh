#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

FULL_RUN=0
if [[ "${1:-}" == "--full" ]]; then
  FULL_RUN=1
fi

if command -v xmllint >/dev/null 2>&1; then
  while IFS= read -r -d '' fxml; do
    xmllint --noout "$fxml"
  done < <(find src/main/resources -name '*.fxml' -print0)
fi

bash gradlew test --rerun-tasks

if [[ "$FULL_RUN" -eq 1 ]]; then
  bash gradlew build
fi
