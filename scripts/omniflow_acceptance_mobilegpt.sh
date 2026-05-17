#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export OMNIFLOW_PROJECT_NAME="${OMNIFLOW_PROJECT_NAME:-mobilegpt}"
export OMNIFLOW_REPO_URL="${OMNIFLOW_REPO_URL:-https://github.com/hchoi256/mobilegpt.git}"
export OMNIFLOW_EXPECTED_MODE="${OMNIFLOW_EXPECTED_MODE:-python_skill_plus_mcp}"
export OMNIFLOW_REUSE_DIR="${OMNIFLOW_REUSE_DIR:-/private/tmp/omniflow-probe-mobilegpt}"
export OMNIFLOW_ACCEPTANCE_ROOT="${OMNIFLOW_ACCEPTANCE_ROOT:-/private/tmp/omniflow-acceptance}"
if [ -n "${OMNIFLOW_MOBILEGPT_REPO:-}" ]; then
  export OMNIFLOW_REPO_DIR="$OMNIFLOW_MOBILEGPT_REPO"
fi

exec bash "$ROOT_DIR/scripts/omniflow_acceptance_external_repo.sh"
