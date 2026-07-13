#!/usr/bin/env bash
#
# Assemble a macOS .app bundle around the GraalVM native-image binary.
#
# Invoked from the pontif-playground `native` profile (see pom.xml) after
# native-image has produced target/pontif-editor. It is deliberately safe to
# run anywhere:
#   * On non-macOS it no-ops (the .app format is macOS-only).
#   * If the native binary is absent it no-ops (so a plain `mvn package` that
#     did NOT activate -Pnative doesn't fail just because this profile's OS
#     activation fired).
#
# The native binary is self-contained: dasum's NativeLibLoader extracts the
# GLFW / NFD dylibs (embedded in the image as classpath resources) to a temp
# dir at runtime, so nothing extra needs to live inside the bundle.
#
# Usage: package-macos-app.sh <binary> <app-dir> [info-plist-template] [icns]
set -euo pipefail

BINARY="${1:?usage: package-macos-app.sh <binary> <app-dir> [plist] [icns]}"
APP_DIR="${2:?usage: package-macos-app.sh <binary> <app-dir> [plist] [icns]}"
PLIST="${3:-}"
ICNS="${4:-}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "[package-macos-app] not macOS ($(uname -s)); skipping .app assembly."
  exit 0
fi
if [[ ! -f "$BINARY" ]]; then
  echo "[package-macos-app] native binary '$BINARY' not found; skipping (build with -Pnative)."
  exit 0
fi

EXE_NAME="$(basename "$BINARY")"

echo "[package-macos-app] assembling $APP_DIR from $BINARY"
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/Contents/MacOS" "$APP_DIR/Contents/Resources"

cp "$BINARY" "$APP_DIR/Contents/MacOS/$EXE_NAME"
chmod +x "$APP_DIR/Contents/MacOS/$EXE_NAME"

# Classic 8-byte PkgInfo (APPL + no specific signature).
printf 'APPL????' > "$APP_DIR/Contents/PkgInfo"

if [[ -n "$PLIST" && -f "$PLIST" ]]; then
  sed "s/@EXECUTABLE@/${EXE_NAME}/g" "$PLIST" > "$APP_DIR/Contents/Info.plist"
else
  echo "[package-macos-app] WARNING: no Info.plist template given; bundle will lack metadata."
fi

if [[ -n "$ICNS" && -f "$ICNS" ]]; then
  cp "$ICNS" "$APP_DIR/Contents/Resources/AppIcon.icns"
fi

# Ad-hoc code signature so Gatekeeper lets a locally-built, un-notarized app
# launch (double-click / `open`). Best-effort: a signing failure is not fatal
# for a dev build you run yourself. Distribution would need a Developer ID
# signature + notarization, which is out of scope here.
if command -v codesign >/dev/null 2>&1; then
  if codesign --force --deep --sign - "$APP_DIR" >/dev/null 2>&1; then
    echo "[package-macos-app] ad-hoc code signature applied."
  else
    echo "[package-macos-app] ad-hoc codesign failed (non-fatal)."
  fi
fi

echo "[package-macos-app] built $APP_DIR"
