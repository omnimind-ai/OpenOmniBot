#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_ROOT="${OMNIFLOW_ACCEPTANCE_ROOT:-/private/tmp/omniflow-acceptance-all}"

run_project() {
  local project_name="$1"
  local repo_url="$2"
  local expected_mode="$3"
  local reuse_dir="$4"

  OMNIFLOW_PROJECT_NAME="$project_name" \
  OMNIFLOW_REPO_URL="$repo_url" \
  OMNIFLOW_EXPECTED_MODE="$expected_mode" \
  OMNIFLOW_REUSE_DIR="$reuse_dir" \
  OMNIFLOW_ACCEPTANCE_ROOT="$BASE_ROOT/$project_name" \
  bash "$ROOT_DIR/scripts/omniflow_acceptance_external_repo.sh"
}

run_project \
  "mobilegpt" \
  "https://github.com/hchoi256/mobilegpt.git" \
  "python_skill_plus_mcp" \
  "/private/tmp/omniflow-probe-mobilegpt"

run_project \
  "mobile-use" \
  "https://github.com/MadeAgents/mobile-use.git" \
  "python_skill_plus_mcp" \
  "/private/tmp/omniflow-probe-mobile-use"

run_project \
  "mobile-mcp" \
  "https://github.com/mobile-next/mobile-mcp.git" \
  "direct_mcp" \
  "/private/tmp/omniflow-probe-mobile-mcp"

echo "omniflow_all_guiagents_acceptance=ok"
