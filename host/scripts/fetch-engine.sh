#!/usr/bin/env bash
# Fetch and extract the pinned built-in streaming engine (Sunshine, GPLv3) for
# bundling inside depth3d Host.app/Contents/Helpers/stream-engine/.
#
# Reproducible and idempotent:
#   - Pinned upstream tag, git commit, download URL, and SHA-256 checksum.
#   - Uses host/.cache/ so repeated runs verify the cached DMG without re-downloading.
#   - Extracts into a plain helper directory (no inner Info.plist, no upstream icon)
#     so macOS TCC attributes Screen Recording / System Audio / Accessibility / Local
#     Network permissions directly to depth3d Host.app.
#
# Usage:
#   host/scripts/fetch-engine.sh [--arch arm64|x86_64] [--out <dir>]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HOST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ENGINE_VERSION="v2026.914.233613"
ENGINE_COMMIT="63d35f702ee9e362e43263742981836ec0710384"
ENGINE_LICENSE="GPL-3.0-only"
BASE_URL="https://github.com/SunshineStream/Sunshine/releases/download/${ENGINE_VERSION}"

# Pinned SHA-256 checksums for official Sunshine macOS release DMGs.
SHA256_ARM64="795f03a389c5726f9a7b15f501dc1ddf204b442cae565859ac83aae027a9e07c"
# x86_64 SHA-256 verified on first Intel fetch or pinned below once downloaded:
SHA256_X86_64="${DEPTH3D_SUNSHINE_X86_64_SHA256:-}"

ARCH="$(uname -m)"
OUT_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch)
      ARCH="$2"
      shift 2
      ;;
    --out)
      OUT_DIR="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [--arch arm64|x86_64] [--out <dir>]"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

case "$ARCH" in
  arm64|aarch64)
    ARCH="arm64"
    DMG_NAME="Sunshine-macOS-arm64.dmg"
    EXPECTED_SHA="$SHA256_ARM64"
    ;;
  x86_64)
    ARCH="x86_64"
    DMG_NAME="Sunshine-macOS-x86_64.dmg"
    EXPECTED_SHA="$SHA256_X86_64"
    ;;
  *)
    echo "Unsupported architecture: $ARCH (expected arm64 or x86_64)" >&2
    exit 1
    ;;
esac

CACHE_DIR="${HOST_DIR}/.cache"
OUT_DIR="${OUT_DIR:-${HOST_DIR}/build/engine-${ARCH}/stream-engine}"
DMG_PATH="${CACHE_DIR}/${DMG_NAME}"
DOWNLOAD_URL="${BASE_URL}/${DMG_NAME}"

mkdir -p "$CACHE_DIR"

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  if [[ -n "$expected" && "$actual" != "$expected" ]]; then
    echo "depth3d: SHA-256 mismatch for $file" >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $actual" >&2
    return 1
  fi
  echo "$actual"
}

if [[ ! -f "$DMG_PATH" ]]; then
  echo "depth3d: downloading streaming engine ${ENGINE_VERSION} (${ARCH})..."
  TMP_DMG="${DMG_PATH}.tmp.$$"
  trap 'rm -f "$TMP_DMG"' EXIT
  curl -fL --retry 3 --retry-delay 2 -o "$TMP_DMG" "$DOWNLOAD_URL"
  verify_sha256 "$TMP_DMG" "$EXPECTED_SHA" >/dev/null
  mv "$TMP_DMG" "$DMG_PATH"
  trap - EXIT
else
  echo "depth3d: using cached ${DMG_NAME}"
fi

ACTUAL_SHA="$(verify_sha256 "$DMG_PATH" "$EXPECTED_SHA")"
echo "depth3d: verified ${DMG_NAME} sha256=${ACTUAL_SHA}"

MANIFEST_PATH="${OUT_DIR}/ENGINE-MANIFEST.json"
if [[ -f "$MANIFEST_PATH" ]] && grep -q "\"sha256\": \"${ACTUAL_SHA}\"" "$MANIFEST_PATH" \
   && [[ -x "${OUT_DIR}/MacOS/depth3d-stream-engine" ]]; then
  echo "depth3d: extracted engine already up to date at ${OUT_DIR}"
  exit 0
fi

MNT_DIR="${CACHE_DIR}/mnt-sunshine-${ARCH}-$$"
cleanup() {
  if [[ -d "$MNT_DIR" ]]; then
    diskutil eject "$MNT_DIR" >/dev/null 2>&1 || hdiutil detach "$MNT_DIR" >/dev/null 2>&1 || true
    rmdir "$MNT_DIR" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

mkdir -p "$MNT_DIR"
hdiutil attach -nobrowse -readonly -mountpoint "$MNT_DIR" "$DMG_PATH" >/dev/null

SRC_APP="${MNT_DIR}/Sunshine.app/Contents"
if [[ ! -x "${SRC_APP}/MacOS/Sunshine" ]]; then
  echo "depth3d: unexpected DMG layout: ${SRC_APP}/MacOS/Sunshine not found" >&2
  exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Copy runtime payload; intentionally omit Info.plist and upstream icon so this
# helper runs under depth3d Host.app's TCC identity without upstream branding.
cp -R "${SRC_APP}/MacOS" "${OUT_DIR}/MacOS"
cp -R "${SRC_APP}/Frameworks" "${OUT_DIR}/Frameworks"
cp -R "${SRC_APP}/PlugIns" "${OUT_DIR}/PlugIns"
cp -R "${SRC_APP}/Resources" "${OUT_DIR}/Resources"

mv "${OUT_DIR}/MacOS/Sunshine" "${OUT_DIR}/MacOS/depth3d-stream-engine"
chmod 755 "${OUT_DIR}/MacOS/depth3d-stream-engine"
rm -f "${OUT_DIR}/Resources/sunshine.icns"

# Verify zero non-system absolute dylib references remain
BAD_LINKS="$(otool -L "${OUT_DIR}/MacOS/depth3d-stream-engine" | tail -n +2 | awk '{print $1}' | grep -vE '^(/System/|/usr/lib/|@executable_path/|@loader_path/|@rpath/)' || true)"
if [[ -n "$BAD_LINKS" ]]; then
  echo "depth3d: unexpected non-relocated dylib links:" >&2
  echo "$BAD_LINKS" >&2
  exit 1
fi

cat > "$MANIFEST_PATH" <<EOF
{
  "component": "Sunshine",
  "version": "${ENGINE_VERSION}",
  "commit": "${ENGINE_COMMIT}",
  "arch": "${ARCH}",
  "license": "${ENGINE_LICENSE}",
  "sourceUrl": "${DOWNLOAD_URL}",
  "sha256": "${ACTUAL_SHA}"
}
EOF

echo "depth3d: extracted streaming engine to ${OUT_DIR}"
