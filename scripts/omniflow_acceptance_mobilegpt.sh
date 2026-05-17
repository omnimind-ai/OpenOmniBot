#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_ROOT="${OMNIFLOW_ACCEPTANCE_ROOT:-/private/tmp/omniflow-acceptance}"
REPO_DIR="${OMNIFLOW_MOBILEGPT_REPO:-$WORK_ROOT/mobilegpt}"
VENV_DIR="$WORK_ROOT/venv"
DIST_DIR="$WORK_ROOT/dist"
CODEX_OUTPUT="$WORK_ROOT/codex-output.txt"

mkdir -p "$WORK_ROOT" "$DIST_DIR"

if [ ! -d "$REPO_DIR/.git" ]; then
  if [ -d "/private/tmp/omniflow-probe-mobilegpt/.git" ]; then
    REPO_DIR="/private/tmp/omniflow-probe-mobilegpt"
  else
    git clone --depth 1 https://github.com/hchoi256/mobilegpt.git "$REPO_DIR"
  fi
fi

python3 -m pip wheel --no-deps --no-build-isolation -w "$DIST_DIR" "$ROOT_DIR"
python3 -m venv "$VENV_DIR"
"$VENV_DIR/bin/python" -m pip install --no-deps --force-reinstall "$DIST_DIR"/omniflow_agentkit-0.1.0-py3-none-any.whl

(
  cd "$REPO_DIR"
  "$VENV_DIR/bin/python" - <<'PY'
from omniflow_agentkit import OmniFlowAgentKit, RepoProbe

kit = OmniFlowAgentKit()
report = RepoProbe(".").run()
prompt = kit.agent_prompt("Run the safest saved Function", report.summary())

assert "GUIAgent OmniFlow Skill" in kit.skill()
assert kit.sample_function()["function_id"] == "open_settings_demo"
assert report.recommended_mode == "python_skill_plus_mcp"
assert "Run the safest saved Function" in prompt

print("python_import=ok")
print("sample_function=ok")
print(f"recommended_mode={report.recommended_mode}")
PY
  "$VENV_DIR/bin/omniflow-agentkit" probe-repo .
  "$VENV_DIR/bin/omniflow-agentkit" prompt "Run the safest saved Function" --repo . >/tmp/omniflow-agentkit-prompt.txt
)

if [ "${OMNIFLOW_RUN_CODEX:-0}" = "1" ] && command -v codex >/dev/null 2>&1; then
  codex exec \
    --cd "$REPO_DIR" \
    --sandbox read-only \
    --skip-git-repo-check \
    -o "$CODEX_OUTPUT" \
    - <<PROMPT
You are in an external open-source repo, MobileGPT. Do not modify files.
Run exactly these two commands and no variants:

$VENV_DIR/bin/omniflow-agentkit probe-repo .
$VENV_DIR/bin/omniflow-agentkit prompt 'Run the safest saved Function' --repo .

Then summarize whether both commands succeeded and include recommended_mode.
PROMPT
  cat "$CODEX_OUTPUT"
else
  echo "codex_step=skip_set_OMNIFLOW_RUN_CODEX_1_to_enable"
fi

echo "omniflow_mobilegpt_acceptance=ok"
