#!/usr/bin/env bash
# Build a self-contained, ad-hoc signed "depth3d Host.app" bundle.
#
# Usage:
#   host/scripts/build.sh [--arch arm64|x86_64] [--config Release|Debug] [--out <app-path>] [--skip-engine-fetch]
#
# Default output:
#   host/build/depth3d Host.app

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HOST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ARCH="$(uname -m)"
CONFIG="Release"
SKIP_ENGINE_FETCH="0"
APP_OUT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch)
      ARCH="$2"
      shift 2
      ;;
    --config)
      CONFIG="$2"
      shift 2
      ;;
    --out)
      APP_OUT="$2"
      shift 2
      ;;
    --skip-engine-fetch)
      SKIP_ENGINE_FETCH="1"
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [--arch arm64|x86_64] [--config Release|Debug] [--out <app-path>] [--skip-engine-fetch]"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

case "$ARCH" in
  arm64|aarch64) ARCH="arm64" ;;
  x86_64) ARCH="x86_64" ;;
  *) echo "Unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

BUILD_DIR="${HOST_DIR}/build"
DERIVED_DIR="${BUILD_DIR}/DerivedData-${ARCH}-${CONFIG}"
ENGINE_STAGE="${BUILD_DIR}/engine-${ARCH}/stream-engine"
APP_OUT="${APP_OUT:-${BUILD_DIR}/depth3d Host.app}"
ICONSET_DIR="${HOST_DIR}/Sources/Depth3DHost/Resources/Assets.xcassets/AppIcon.appiconset"

mkdir -p "$BUILD_DIR" "$(dirname "$APP_OUT")"

# 1. Generate AppIcon asset catalog if missing
if [[ ! -f "${ICONSET_DIR}/icon_512x512@2x.png" ]]; then
  echo "depth3d: generating AppIcon assets..."
  python3 "${SCRIPT_DIR}/generate-icon.py" "$ICONSET_DIR"
fi

# 2. Fetch & stage built-in streaming engine (mandatory — never build an unusable app)
if [[ "$SKIP_ENGINE_FETCH" != "1" ]]; then
  "${SCRIPT_DIR}/fetch-engine.sh" --arch "$ARCH" --out "$ENGINE_STAGE"
fi

if [[ ! -x "${ENGINE_STAGE}/MacOS/depth3d-stream-engine" ]]; then
  echo "depth3d: error: built-in streaming engine missing at ${ENGINE_STAGE}/MacOS/depth3d-stream-engine" >&2
  echo "depth3d: run host/scripts/fetch-engine.sh --arch ${ARCH} or omit --skip-engine-fetch" >&2
  exit 1
fi

# 3. Generate Xcode project via XcodeGen
if ! command -v xcodegen >/dev/null 2>&1; then
  echo "depth3d: xcodegen is required (brew install xcodegen)" >&2
  exit 1
fi

echo "depth3d: generating Xcode project..."
(cd "$HOST_DIR" && xcodegen generate --spec project.yml >/dev/null)

# 4. Build depth3d Host.app and Depth3DChildGuard helper
echo "depth3d: compiling depth3d Host (${CONFIG}, ${ARCH})..."
xcodebuild \
  -project "${HOST_DIR}/Depth3DHost.xcodeproj" \
  -scheme Depth3DHost \
  -configuration "$CONFIG" \
  -arch "$ARCH" \
  -derivedDataPath "$DERIVED_DIR" \
  ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=NO \
  build

PRODUCTS_DIR="${DERIVED_DIR}/Build/Products/${CONFIG}"
BUILT_APP="${PRODUCTS_DIR}/depth3d Host.app"
BUILT_GUARD="${PRODUCTS_DIR}/depth3d-child-guard"

if [[ ! -d "$BUILT_APP" ]]; then
  echo "depth3d: built app not found at ${BUILT_APP}" >&2
  exit 1
fi
if [[ ! -x "$BUILT_GUARD" ]]; then
  echo "depth3d: built child guard helper not found at ${BUILT_GUARD}" >&2
  exit 1
fi

rm -rf "$APP_OUT"
cp -R "$BUILT_APP" "$APP_OUT"

# 5. Embed built-in streaming engine and child watchdog helper following Apple's strict bundle layout:
#    - Helper executables -> Contents/Helpers/depth3d-stream-engine, Contents/Helpers/depth3d-child-guard
#    - Engine dylibs & Qt frameworks -> Contents/Frameworks/ (resolves @executable_path/../Frameworks/)
#    - Qt plugins -> Contents/PlugIns/
#    - Engine runtime assets & manifest -> Contents/Resources/ (resolves ../Resources/assets)
echo "depth3d: embedding streaming engine and watchdog helper into bundle..."
mkdir -p "${APP_OUT}/Contents/Helpers" "${APP_OUT}/Contents/Frameworks" "${APP_OUT}/Contents/PlugIns" "${APP_OUT}/Contents/Resources"
cp "${ENGINE_STAGE}/MacOS/depth3d-stream-engine" "${APP_OUT}/Contents/Helpers/depth3d-stream-engine"
cp "${BUILT_GUARD}" "${APP_OUT}/Contents/Helpers/depth3d-child-guard"
chmod 755 "${APP_OUT}/Contents/Helpers/depth3d-stream-engine" "${APP_OUT}/Contents/Helpers/depth3d-child-guard"
cp -R "${ENGINE_STAGE}/Frameworks/." "${APP_OUT}/Contents/Frameworks/"
cp -R "${ENGINE_STAGE}/PlugIns/." "${APP_OUT}/Contents/PlugIns/"
cp -R "${ENGINE_STAGE}/Resources/." "${APP_OUT}/Contents/Resources/"
cp "${ENGINE_STAGE}/ENGINE-MANIFEST.json" "${APP_OUT}/Contents/Resources/ENGINE-MANIFEST.json"

# 6. Sign nested code objects inside-out, then sign the outer app bundle.
#    For future Developer ID distribution, set DEPTH3D_CODESIGN_IDENTITY to
#    "Developer ID Application: <Team Name> (<Team ID>)".
echo "depth3d: signing bundle..."
SIGN_IDENTITY="${DEPTH3D_CODESIGN_IDENTITY:--}"
SIGN_ARGS=(--force --sign "$SIGN_IDENTITY")
if [[ "$SIGN_IDENTITY" != "-" ]]; then
  SIGN_ARGS+=(--options runtime --timestamp)
fi

if [[ -d "${APP_OUT}/Contents/PlugIns" ]]; then
  find "${APP_OUT}/Contents/PlugIns" -type f \( -name "*.dylib" -o -name "*.so" \) -print0 \
    | xargs -0 -I{} codesign "${SIGN_ARGS[@]}" "{}"
fi

if [[ -d "${APP_OUT}/Contents/Frameworks" ]]; then
  find "${APP_OUT}/Contents/Frameworks" -maxdepth 1 -type f -name "*.dylib" -print0 \
    | xargs -0 -I{} codesign "${SIGN_ARGS[@]}" "{}"
  find "${APP_OUT}/Contents/Frameworks" -maxdepth 1 -name "*.framework" -print0 \
    | xargs -0 -I{} codesign "${SIGN_ARGS[@]}" "{}"
fi

codesign "${SIGN_ARGS[@]}" "${APP_OUT}/Contents/Helpers/depth3d-stream-engine"
codesign "${SIGN_ARGS[@]}" "${APP_OUT}/Contents/Helpers/depth3d-child-guard"
codesign "${SIGN_ARGS[@]}" "${APP_OUT}"
codesign --verify --deep --strict "${APP_OUT}"

echo "depth3d: built and verified ${APP_OUT}"
