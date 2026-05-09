#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
FLUTTER_DIR="$ROOT_DIR/ui"
ARTIFACT_DIR="$ROOT_DIR/app/build/outputs/release-artifacts"

INSTALL_APK=0
SKIP_SUBMODULES=0
SKIP_FLUTTER=0
EDITION="both"

usage() {
  cat <<'EOF'
Usage:
  bash scripts/build-local-release.sh [options]

Options:
  --edition standard|omniinfer|both
                      Build the slim standard APK, the full OmniInfer APK, or both.
                      Defaults to both.
  --install           Build one release APK and install it with adb.
  --bundle            Unsupported in this split release script; APK only for now.
  --skip-submodules   Skip OmniInfer submodule initialization.
  --skip-flutter      Skip `flutter pub get` in ui/.
  --help              Show this help text.

Required environment variables:
  OMNI_RELEASE_STORE_PWD
  OMNI_RELEASE_KEY_ALIAS

Optional environment variables:
  OMNI_RELEASE_STORE_FILE   Defaults to ./release.jks when present.
  OMNI_RELEASE_KEY_PWD      Defaults to OMNI_RELEASE_STORE_PWD.
  ANDROID_SDK_ROOT          Auto-detected from local.properties when absent.
  ANDROID_NDK_HOME          Auto-detected as $ANDROID_SDK_ROOT/ndk/28.2.13676358 when absent.
  GRADLE_OPTS              Defaults to the same memory settings used in CI.
EOF
}

write_sha256() {
  local file_path="$1"
  local checksum_path="${file_path}.sha256"
  local dir_name
  local base_name
  dir_name="$(dirname "$file_path")"
  base_name="$(basename "$file_path")"

  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$dir_name" && sha256sum "$base_name") > "$checksum_path"
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$dir_name" && shasum -a 256 "$base_name") > "$checksum_path"
  elif command -v openssl >/dev/null 2>&1; then
    local digest
    digest="$(openssl dgst -sha256 -r "$file_path" | awk '{print $1}')"
    printf '%s  %s\n' "$digest" "$base_name" > "$checksum_path"
  else
    echo "Missing checksum tool. Install sha256sum, shasum, or openssl." >&2
    exit 1
  fi

  cat "$checksum_path"
}

edition_needs_omniinfer() {
  [[ "$EDITION" == "omniinfer" || "$EDITION" == "both" ]]
}

task_for_edition() {
  case "$1" in
    standard)
      printf '%s\n' assembleProductionStandardRelease
      ;;
    omniinfer)
      printf '%s\n' assembleProductionOmniinferRelease
      ;;
    *)
      echo "Invalid edition: $1" >&2
      exit 1
      ;;
  esac
}

flutter_target_for_edition() {
  case "$1" in
    standard)
      printf '%s\n' lib/main_standard.dart
      ;;
    omniinfer)
      printf '%s\n' lib/main_omniinfer.dart
      ;;
    *)
      echo "Invalid edition: $1" >&2
      exit 1
      ;;
  esac
}

apk_path_for_edition() {
  case "$1" in
    standard)
      printf '%s\n' "$ROOT_DIR/app/build/outputs/apk/productionStandard/release/app-production-standard-release.apk"
      ;;
    omniinfer)
      printf '%s\n' "$ROOT_DIR/app/build/outputs/apk/productionOmniinfer/release/app-production-omniinfer-release.apk"
      ;;
    *)
      echo "Invalid edition: $1" >&2
      exit 1
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --edition)
      if [[ $# -lt 2 ]]; then
        echo "--edition requires a value: standard, omniinfer, or both" >&2
        exit 1
      fi
      EDITION="$2"
      shift
      ;;
    --edition=*)
      EDITION="${1#--edition=}"
      ;;
    --install)
      INSTALL_APK=1
      ;;
    --bundle)
      echo "AAB builds are out of scope for this release split; build APKs instead." >&2
      exit 1
      ;;
    --skip-submodules)
      SKIP_SUBMODULES=1
      ;;
    --skip-flutter)
      SKIP_FLUTTER=1
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

case "$EDITION" in
  standard)
    EDITIONS=(standard)
    ;;
  omniinfer)
    EDITIONS=(omniinfer)
    ;;
  both)
    EDITIONS=(standard omniinfer)
    ;;
  *)
    echo "Invalid edition: $EDITION" >&2
    usage
    exit 1
    ;;
esac

if [[ "$INSTALL_APK" -eq 1 && "${#EDITIONS[@]}" -ne 1 ]]; then
  echo "--install can only be used with a single --edition." >&2
  exit 1
fi

if [[ -z "${OMNI_RELEASE_STORE_FILE:-}" && -f "$ROOT_DIR/release.jks" ]]; then
  export OMNI_RELEASE_STORE_FILE="$ROOT_DIR/release.jks"
fi

if [[ -z "${OMNI_RELEASE_STORE_PWD:-}" ]]; then
  echo "Missing OMNI_RELEASE_STORE_PWD" >&2
  exit 1
fi

if [[ -z "${OMNI_RELEASE_KEY_ALIAS:-}" ]]; then
  echo "Missing OMNI_RELEASE_KEY_ALIAS" >&2
  exit 1
fi

if [[ -z "${OMNI_RELEASE_STORE_FILE:-}" ]]; then
  echo "Missing OMNI_RELEASE_STORE_FILE and default ./release.jks was not found" >&2
  exit 1
fi

if [[ ! -f "$OMNI_RELEASE_STORE_FILE" ]]; then
  echo "Keystore not found: $OMNI_RELEASE_STORE_FILE" >&2
  exit 1
fi

if [[ -z "${OMNI_RELEASE_KEY_PWD:-}" ]]; then
  export OMNI_RELEASE_KEY_PWD="$OMNI_RELEASE_STORE_PWD"
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" && -f "$ROOT_DIR/local.properties" ]]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tail -n 1)"
  if [[ -n "$sdk_dir" ]]; then
    sdk_dir="${sdk_dir//\\:/:}"
    sdk_dir="${sdk_dir//\\\\/\\}"
    export ANDROID_SDK_ROOT="$sdk_dir"
  fi
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo "Missing ANDROID_SDK_ROOT and could not detect it from local.properties" >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
fi

if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
fi

if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  cat >&2 <<EOF
Android NDK not found: $ANDROID_NDK_HOME
Install the CI-matching NDK with:
  sdkmanager "ndk;$NDK_VERSION"
EOF
  exit 1
fi

if [[ -z "${GRADLE_OPTS:-}" ]]; then
  export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED"
fi

cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
if [[ "$cpu_count" -ge 4 ]]; then
  max_workers=4
elif [[ "$cpu_count" -ge 2 ]]; then
  max_workers="$cpu_count"
else
  max_workers=2
fi

echo "Repo root: $ROOT_DIR"
echo "Edition(s): ${EDITIONS[*]}"
echo "Keystore: $OMNI_RELEASE_STORE_FILE"
echo "Android SDK: $ANDROID_SDK_ROOT"
echo "Android NDK: $ANDROID_NDK_HOME"
echo "Gradle max workers: $max_workers"

chmod +x ./gradlew
mkdir -p "$ARTIFACT_DIR"

if [[ "$SKIP_SUBMODULES" -eq 0 ]] && edition_needs_omniinfer; then
  echo "Initializing OmniInfer submodules..."
  git submodule update --init third_party/omniinfer
  git -C third_party/omniinfer submodule update --init framework/mnn framework/llama.cpp
fi

if [[ "$SKIP_FLUTTER" -eq 0 ]]; then
  echo "Installing Flutter dependencies..."
  (cd "$FLUTTER_DIR" && flutter pub get --enforce-lockfile)
fi

for edition in "${EDITIONS[@]}"; do
  task="$(task_for_edition "$edition")"
  flutter_target="$(flutter_target_for_edition "$edition")"
  source_apk="$(apk_path_for_edition "$edition")"
  artifact_apk="$ARTIFACT_DIR/OpenOmniBot-${edition}.apk"

  echo "Building $edition release APK with $flutter_target..."
  ./gradlew \
    --build-cache \
    --max-workers="$max_workers" \
    "$task" \
    -Ptarget="$flutter_target" \
    -POMNI_RELEASE_STORE_FILE="$OMNI_RELEASE_STORE_FILE" \
    -POMNI_RELEASE_STORE_PWD="$OMNI_RELEASE_STORE_PWD" \
    -POMNI_RELEASE_KEY_ALIAS="$OMNI_RELEASE_KEY_ALIAS" \
    -POMNI_RELEASE_KEY_PWD="$OMNI_RELEASE_KEY_PWD"

  if [[ ! -f "$source_apk" ]]; then
    echo "Build finished but APK was not found: $source_apk" >&2
    exit 1
  fi

  cp "$source_apk" "$artifact_apk"
  echo "APK ready: $artifact_apk"
  write_sha256 "$artifact_apk"

  if [[ "$INSTALL_APK" -eq 1 ]]; then
    echo "Installing $edition APK via adb..."
    adb install -r "$artifact_apk"
  fi
done
