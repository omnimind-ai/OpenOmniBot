#!/usr/bin/env bash
# Configure an installed OOB debug build with any OpenAI-compatible base URL,
# API key, and model id through adb. The API key is never printed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ANDROID_SERIAL:-}"
PACKAGE_NAME="${OOB_PACKAGE_NAME:-cn.com.omnimind.bot.debug}"
BASE_URL="${OMNIMIND_API_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}"
MODEL_ID="${OMNIMIND_MODEL:-${OPENAI_MODEL:-qwen-vl-max-latest}}"
PROFILE_ID="${OOB_PROVIDER_PROFILE_ID:-debug-runtime-provider}"
PROFILE_NAME="${OOB_PROVIDER_PROFILE_NAME:-Debug Runtime Provider}"
API_KEY_ENV="${OMNIMIND_API_KEY_ENV:-DASHSCOPE_API_KEY}"
API_KEY_ARG=""
SCENE_IDS="${OOB_MODEL_SCENE_IDS:-scene.dispatch.model,scene.vlm.operation.primary,scene.compactor.context,scene.compactor.context.chat}"
READ_RESULT=1

usage() {
  cat <<'EOF'
Usage:
  scripts/configure-oob-model-provider.sh --device emulator-5554
  scripts/configure-oob-model-provider.sh --device <serial> --base-url https://host --api-key-env DASHSCOPE_API_KEY --model qwen-vl-max-latest

Options:
  --device <serial>          Target adb device or emulator serial. Defaults to ANDROID_SERIAL or the only connected device.
  --package <package-name>   Installed package. Default: cn.com.omnimind.bot.debug
  --base-url <url>           OpenAI-compatible base URL. Default: https://dashscope.aliyuncs.com/compatible-mode/v1
  --api-key-env <name>       Env var containing the API key. Default: DASHSCOPE_API_KEY
  --api-key <key>            Direct API key value. Avoid this in shared shell history.
  --model <model-id>         Model id. Default: $OMNIMIND_MODEL, $OPENAI_MODEL, or qwen-vl-max-latest
  --profile-id <id>          Provider profile id. Default: debug-runtime-provider
  --name <name>              Provider profile name. Default: Debug Runtime Provider
  --scene-ids <ids>          Comma/semicolon separated scene ids to bind.
  --no-read-result           Do not read back the app-private result JSON.
  --help                     Show this help text.

Environment:
  DASHSCOPE_API_KEY          Default API key source. If missing from the current shell, the script also tries ~/.zshrc via zsh.
  OMNIMIND_API_BASE_URL      Optional base URL default.
  OMNIMIND_MODEL             Optional model id default.
  OPENAI_MODEL               Backward-compatible model id default.
  OOB_PACKAGE_NAME           Optional package override.
  OOB_PROVIDER_PROFILE_ID    Optional profile id default.
  OOB_PROVIDER_PROFILE_NAME  Optional profile name default.
  OOB_MODEL_SCENE_IDS        Optional scene binding list default.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -lt 2 ]] && { echo "--device requires a serial" >&2; exit 1; }
      DEVICE_SERIAL="$2"
      shift
      ;;
    --device=*)
      DEVICE_SERIAL="${1#--device=}"
      ;;
    --package)
      [[ $# -lt 2 ]] && { echo "--package requires a value" >&2; exit 1; }
      PACKAGE_NAME="$2"
      shift
      ;;
    --package=*)
      PACKAGE_NAME="${1#--package=}"
      ;;
    --base-url)
      [[ $# -lt 2 ]] && { echo "--base-url requires a value" >&2; exit 1; }
      BASE_URL="$2"
      shift
      ;;
    --base-url=*)
      BASE_URL="${1#--base-url=}"
      ;;
    --api-key-env)
      [[ $# -lt 2 ]] && { echo "--api-key-env requires a value" >&2; exit 1; }
      API_KEY_ENV="$2"
      shift
      ;;
    --api-key-env=*)
      API_KEY_ENV="${1#--api-key-env=}"
      ;;
    --api-key)
      [[ $# -lt 2 ]] && { echo "--api-key requires a value" >&2; exit 1; }
      API_KEY_ARG="$2"
      shift
      ;;
    --api-key=*)
      API_KEY_ARG="${1#--api-key=}"
      ;;
    --model)
      [[ $# -lt 2 ]] && { echo "--model requires a value" >&2; exit 1; }
      MODEL_ID="$2"
      shift
      ;;
    --model=*)
      MODEL_ID="${1#--model=}"
      ;;
    --profile-id)
      [[ $# -lt 2 ]] && { echo "--profile-id requires a value" >&2; exit 1; }
      PROFILE_ID="$2"
      shift
      ;;
    --profile-id=*)
      PROFILE_ID="${1#--profile-id=}"
      ;;
    --name)
      [[ $# -lt 2 ]] && { echo "--name requires a value" >&2; exit 1; }
      PROFILE_NAME="$2"
      shift
      ;;
    --name=*)
      PROFILE_NAME="${1#--name=}"
      ;;
    --scene-ids)
      [[ $# -lt 2 ]] && { echo "--scene-ids requires a value" >&2; exit 1; }
      SCENE_IDS="$2"
      shift
      ;;
    --scene-ids=*)
      SCENE_IDS="${1#--scene-ids=}"
      ;;
    --no-read-result)
      READ_RESULT=0
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

trimmed() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

resolve_env_value() {
  local name="$1"
  local value="${!name:-}"
  if [[ -n "$(trimmed "$value")" ]]; then
    printf '%s' "$value"
    return 0
  fi
  if command -v zsh >/dev/null 2>&1 && [[ -f "$HOME/.zshrc" ]]; then
    local raw=""
    raw="$(
      zsh -ic 'name="$1"; value="${(P)name}"; printf "\n__OOB_KEY_BEGIN__\n%s\n__OOB_KEY_END__\n" "$value"' \
        oob-read-env "$name" 2>/dev/null || true
    )"
    if [[ "$raw" == *"__OOB_KEY_BEGIN__"* && "$raw" == *"__OOB_KEY_END__"* ]]; then
      value="${raw#*__OOB_KEY_BEGIN__$'\n'}"
      value="${value%%$'\n'__OOB_KEY_END__*}"
      if [[ -n "$(trimmed "$value")" ]]; then
        printf '%s' "$value"
        return 0
      fi
    fi
  fi
  return 1
}

if [[ -z "$(trimmed "$DEVICE_SERIAL")" ]]; then
  DEVICE_LIST="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  DEVICE_COUNT="$(printf '%s\n' "$DEVICE_LIST" | awk 'NF { count++ } END { print count + 0 }')"
  if [[ "$DEVICE_COUNT" -eq 1 ]]; then
    DEVICE_SERIAL="$(printf '%s\n' "$DEVICE_LIST" | awk 'NF { print; exit }')"
  else
    echo "--device is required when zero or multiple adb devices are connected." >&2
    exit 1
  fi
fi

API_KEY="$API_KEY_ARG"
if [[ -z "$(trimmed "$API_KEY")" ]]; then
  if ! API_KEY="$(resolve_env_value "$API_KEY_ENV")"; then
    API_KEY=""
  fi
fi
if [[ -z "$(trimmed "$API_KEY")" && "$API_KEY_ENV" != "OMNIMIND_API_KEY" ]]; then
  API_KEY="${OMNIMIND_API_KEY:-}"
fi

[[ -n "$(trimmed "$BASE_URL")" ]] || { echo "--base-url must not be empty." >&2; exit 1; }
[[ -n "$(trimmed "$API_KEY")" ]] || { echo "$API_KEY_ENV is required and must not be empty." >&2; exit 1; }
[[ -n "$(trimmed "$MODEL_ID")" ]] || { echo "--model must not be empty." >&2; exit 1; }
[[ -n "$(trimmed "$PROFILE_ID")" ]] || { echo "--profile-id must not be empty." >&2; exit 1; }
[[ -n "$(trimmed "$SCENE_IDS")" ]] || { echo "--scene-ids must not be empty." >&2; exit 1; }

ADB=(adb -s "$DEVICE_SERIAL")
RECEIVER="$PACKAGE_NAME/cn.com.omnimind.bot.debug.DebugModelProviderConfigReceiver"

echo "Configuring OOB model provider on $DEVICE_SERIAL"
echo "  package=$PACKAGE_NAME"
echo "  base_url=$BASE_URL"
echo "  model=$MODEL_ID"
echo "  profile_id=$PROFILE_ID"
if [[ -n "$(trimmed "$API_KEY_ARG")" ]]; then
  echo "  api_key_source=argument"
else
  echo "  api_key_source=env:$API_KEY_ENV"
fi

"${ADB[@]}" shell am broadcast \
  -a cn.com.omnimind.bot.debug.CONFIGURE_MODEL_PROVIDER \
  -n "$RECEIVER" \
  --es baseUrl "$BASE_URL" \
  --es apiKey "$API_KEY" \
  --es modelId "$MODEL_ID" \
  --es profileId "$PROFILE_ID" \
  --es name "$PROFILE_NAME" \
  --es sceneIds "$SCENE_IDS" >/dev/null

if [[ "$READ_RESULT" -eq 1 ]]; then
  sleep 1
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" cat files/debug-model-provider-config-result.json
  echo
fi
