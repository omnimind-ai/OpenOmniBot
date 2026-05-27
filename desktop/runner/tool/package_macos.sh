#!/usr/bin/env bash
# Package the macOS app as a universal-binary .dmg.
#
# Usage:
#   tool/package_macos.sh [--codesign-identity "Developer ID Application: ..."]
#                        [--notarize-keychain-profile <profile>]
#                        [--skip-codesign]
#
# Prereqs:
#   - Xcode + flutter desktop enabled
#   - `cargo` on PATH (rustup default-toolchain stable)
#   - `create-dmg` (`brew install create-dmg`) for the final .dmg

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$(cd "$ROOT_DIR/../backend" && pwd)"
BUILD_DIR="$ROOT_DIR/build/macos/Build/Products/Release"
APP_NAME="omnibot_desktop_runner.app"
APP_PATH="$BUILD_DIR/$APP_NAME"

CODESIGN_IDENTITY=""
NOTARIZE_PROFILE=""
SKIP_CODESIGN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --codesign-identity) CODESIGN_IDENTITY="$2"; shift 2 ;;
    --notarize-keychain-profile) NOTARIZE_PROFILE="$2"; shift 2 ;;
    --skip-codesign) SKIP_CODESIGN=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

echo "==> Building Rust backend universal binary"
pushd "$BACKEND_DIR" > /dev/null
rustup target add x86_64-apple-darwin aarch64-apple-darwin || true
cargo build --release --target x86_64-apple-darwin -p omnibot-backend
cargo build --release --target aarch64-apple-darwin -p omnibot-backend
mkdir -p target/release
lipo -create \
  target/x86_64-apple-darwin/release/omnibot-backend \
  target/aarch64-apple-darwin/release/omnibot-backend \
  -output target/release/omnibot-backend
file target/release/omnibot-backend
popd > /dev/null

echo "==> Flutter release build"
pushd "$ROOT_DIR" > /dev/null
flutter build macos --release
popd > /dev/null

if [[ $SKIP_CODESIGN -eq 0 && -n "$CODESIGN_IDENTITY" ]]; then
  echo "==> Codesigning with: $CODESIGN_IDENTITY"
  codesign --force --options runtime --timestamp \
    --sign "$CODESIGN_IDENTITY" \
    "$APP_PATH/Contents/Resources/omnibot-backend"
  codesign --force --options runtime --timestamp \
    --sign "$CODESIGN_IDENTITY" \
    --deep "$APP_PATH"
  codesign --verify --deep --strict --verbose=2 "$APP_PATH"
fi

if [[ -n "$NOTARIZE_PROFILE" ]]; then
  echo "==> Notarizing via $NOTARIZE_PROFILE"
  ZIP="$BUILD_DIR/$APP_NAME.zip"
  ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "$ZIP"
  xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARIZE_PROFILE" --wait
  xcrun stapler staple "$APP_PATH"
fi

echo "==> Creating .dmg"
DMG="$BUILD_DIR/Omnibot.dmg"
rm -f "$DMG"
if command -v create-dmg > /dev/null; then
  create-dmg \
    --volname "Omnibot" \
    --window-size 600 400 \
    --icon-size 96 \
    --app-drop-link 440 200 \
    --icon "$APP_NAME" 160 200 \
    "$DMG" "$APP_PATH"
else
  hdiutil create -volname Omnibot -srcfolder "$APP_PATH" -format UDZO "$DMG"
fi
echo "==> Done: $DMG"
