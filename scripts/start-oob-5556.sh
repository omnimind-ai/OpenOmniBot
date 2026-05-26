#!/usr/bin/env bash
# Compatibility wrapper for the dedicated OOB validation emulator.
#
# Keep this filename working for older habits, but route all real startup logic
# through the canonical one-click entrypoint so startup behavior and diagnostics
# stay in one place.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

exec scripts/oob-start.sh --profile 5556 "$@"
