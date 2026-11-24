#!/usr/bin/env bash
# Fetch, verify, and extract the pinned built-in streaming engine (Sunshine, GPL-3.0-only)
# for bundling inside depth3d Host.app.
#
# Upstream provenance:
#   LizardByte/Sunshine renamed its GitHub organization to SunshineStream/Sunshine
#   (https://github.com/LizardByte/Sunshine returns HTTP 301 -> SunshineStream/Sunshine).
#
# Strict integrity guarantees:
#   - Every supported architecture MUST have a hardcoded, pinned SHA-256 digest below.
#   - Environment overrides to bypass or weaken SHA-256 verification are NOT supported.
#   - Extracted files are recorded with per-file SHA-256 digests inside ENGINE-MANIFEST.json
#     and re-verified both on cached reuse AND via `--verify-only` (used by build.sh --skip-engine-fetch).
#
# Usage:
#   host/scripts/fetch-engine.sh [--arch arm64|x86_64] [--out <dir>] [--verify-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HOST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ENGINE_VERSION="v2026.914.233613"
ENGINE_COMMIT="63d35f702ee9e362e43263742981836ec0710384"
ENGINE_LICENSE="GPL-3.0-only"
# SunshineStream is LizardByte's renamed GitHub organization (LizardByte/Sunshine 301-redirects here).
BASE_URL="https://github.com/SunshineStream/Sunshine/releases/download/${ENGINE_VERSION}"

# Pinned SHA-256 checksums published on the official v2026.914.233613 GitHub release.
# Never read checksums from environment variables.
readonly SHA256_ARM64="795f03a389c5726f9a7b15f501dc1ddf204b442cae565859ac83aae027a9e07c"
readonly SHA256_X86_64="764b11674babac0cd838c2883359207a481c462f355ccbfd1ec91f6d44672101"

ARCH="$(uname -m)"
OUT_DIR=""
VERIFY_ONLY="0"

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
    --verify-only)
      VERIFY_ONLY="1"
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [--arch arm64|x86_64] [--out <dir>] [--verify-only]"
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
    echo "depth3d: unsupported architecture '$ARCH' (expected arm64 or x86_64)" >&2
    exit 1
    ;;
esac

if [[ ! "$EXPECTED_SHA" =~ ^[0-9a-f]{64}$ ]]; then
  echo "depth3d: missing or invalid pinned SHA-256 for arch '${ARCH}'; refusing unverified fetch" >&2
  exit 1
fi

CACHE_DIR="${HOST_DIR}/.cache"
OUT_DIR="${OUT_DIR:-${HOST_DIR}/build/engine-${ARCH}/stream-engine}"
DMG_PATH="${CACHE_DIR}/${DMG_NAME}"
DOWNLOAD_URL="${BASE_URL}/${DMG_NAME}"
MANIFEST_PATH="${OUT_DIR}/ENGINE-MANIFEST.json"

verify_sha256() {
  local file="$1"
  local expected="$2"
  if [[ -z "$expected" ]]; then
    echo "depth3d: internal error: empty expected SHA-256" >&2
    return 1
  fi
  local actual
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "depth3d: SHA-256 mismatch for $file" >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $actual" >&2
    return 1
  fi
  echo "$actual"
}

verify_extracted_manifest() {
  local dir="$1"
  local expected_arch="$2"
  local expected_dmg_sha="$3"
  python3 - "$dir" "$expected_arch" "$expected_dmg_sha" <<'PY'
import hashlib, json, os, sys

out_dir, expected_arch, expected_dmg_sha = sys.argv[1], sys.argv[2], sys.argv[3]
manifest_path = os.path.join(out_dir, "ENGINE-MANIFEST.json")
if not os.path.isfile(manifest_path):
    sys.stderr.write(f"depth3d: missing manifest at {manifest_path}\n")
    sys.exit(1)

try:
    manifest = json.loads(open(manifest_path, "r", encoding="utf-8").read())
except Exception as e:
    sys.stderr.write(f"depth3d: invalid ENGINE-MANIFEST.json: {e}\n")
    sys.exit(1)

if manifest.get("arch") != expected_arch:
    sys.stderr.write(f"depth3d: manifest arch mismatch (expected {expected_arch}, got {manifest.get('arch')})\n")
    sys.exit(1)

if manifest.get("sha256") != expected_dmg_sha:
    sys.stderr.write(f"depth3d: manifest DMG sha256 mismatch (expected {expected_dmg_sha}, got {manifest.get('sha256')})\n")
    sys.exit(1)

files = manifest.get("files")
if not isinstance(files, dict) or "MacOS/depth3d-stream-engine" not in files:
    sys.stderr.write("depth3d: manifest missing per-file SHA-256 inventory\n")
    sys.exit(1)

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()

seen = set()
for root, _, filenames in os.walk(out_dir):
    for name in filenames:
        full = os.path.join(root, name)
        if os.path.islink(full):
            continue
        rel = os.path.relpath(full, out_dir).replace(os.sep, "/")
        if rel == "ENGINE-MANIFEST.json":
            continue
        seen.add(rel)

expected_set = set(files.keys())
missing = sorted(expected_set - seen)
extra = sorted(seen - expected_set)
if missing:
    sys.stderr.write(f"depth3d: extracted engine missing files: {missing[:5]}\n")
    sys.exit(1)
if extra:
    sys.stderr.write(f"depth3d: extracted engine contains unexpected files: {extra[:5]}\n")
    sys.exit(1)

engine_bin = os.path.join(out_dir, "MacOS/depth3d-stream-engine")
if not os.access(engine_bin, os.X_OK):
    sys.stderr.write(f"depth3d: engine binary not executable: {engine_bin}\n")
    sys.exit(1)

for rel in sorted(expected_set):
    full = os.path.join(out_dir, rel)
    actual = sha256_file(full)
    if actual != files[rel]:
        sys.stderr.write(f"depth3d: extracted file hash mismatch for {rel}\n")
        sys.stderr.write(f"  expected: {files[rel]}\n")
        sys.stderr.write(f"  actual:   {actual}\n")
        sys.exit(1)

print(f"depth3d: verified {len(expected_set)} extracted engine files against ENGINE-MANIFEST.json")
PY
}

if [[ "$VERIFY_ONLY" == "1" ]]; then
  verify_extracted_manifest "$OUT_DIR" "$ARCH" "$EXPECTED_SHA"
  exit 0
fi

mkdir -p "$CACHE_DIR"

if [[ ! -f "$DMG_PATH" ]]; then
  echo "depth3d: downloading streaming engine ${ENGINE_VERSION} (${ARCH})..."
  TMP_DMG="${DMG_PATH}.tmp.$$"
  trap 'rm -f "$TMP_DMG"' EXIT
  if ! curl -fL --retry 3 --retry-delay 2 -o "$TMP_DMG" "$DOWNLOAD_URL"; then
    echo "depth3d: failed to download ${DOWNLOAD_URL} (${ARCH}); cannot proceed without verified engine binary" >&2
    exit 1
  fi
  verify_sha256 "$TMP_DMG" "$EXPECTED_SHA" >/dev/null
  mv "$TMP_DMG" "$DMG_PATH"
  trap - EXIT
else
  echo "depth3d: using cached ${DMG_NAME}"
fi

ACTUAL_SHA="$(verify_sha256 "$DMG_PATH" "$EXPECTED_SHA")"
echo "depth3d: verified ${DMG_NAME} sha256=${ACTUAL_SHA}"

if [[ -f "$MANIFEST_PATH" ]]; then
  if verify_extracted_manifest "$OUT_DIR" "$ARCH" "$ACTUAL_SHA"; then
    echo "depth3d: extracted engine verified and up to date at ${OUT_DIR}"
    exit 0
  fi
  echo "depth3d: extracted engine failed integrity check; re-extracting cleanly from verified DMG..." >&2
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

# Ad-hoc sign renamed executable so staged engine can also run directly in tests.
codesign --force --sign - "${OUT_DIR}/MacOS/depth3d-stream-engine" >/dev/null

# Verify zero non-system absolute dylib references remain
BAD_LINKS="$(otool -L "${OUT_DIR}/MacOS/depth3d-stream-engine" | tail -n +2 | awk '{print $1}' | grep -vE '^(/System/|/usr/lib/|@executable_path/|@loader_path/|@rpath/)' || true)"
if [[ -n "$BAD_LINKS" ]]; then
  echo "depth3d: unexpected non-relocated dylib links:" >&2
  echo "$BAD_LINKS" >&2
  exit 1
fi

# Record complete per-file SHA-256 inventory in ENGINE-MANIFEST.json for tamper detection.
python3 - "$OUT_DIR" "$ENGINE_VERSION" "$ENGINE_COMMIT" "$ARCH" "$ENGINE_LICENSE" "$DOWNLOAD_URL" "$ACTUAL_SHA" <<'PY'
import hashlib, json, os, sys

out_dir, version, commit, arch, lic, url, dmg_sha = sys.argv[1:8]
files = {}
for root, _, filenames in os.walk(out_dir):
    for name in filenames:
        full = os.path.join(root, name)
        if os.path.islink(full):
            continue
        rel = os.path.relpath(full, out_dir).replace(os.sep, "/")
        if rel == "ENGINE-MANIFEST.json":
            continue
        h = hashlib.sha256()
        with open(full, "rb") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                h.update(chunk)
        files[rel] = h.hexdigest()

manifest = {
    "component": "Sunshine",
    "organization": "SunshineStream (formerly LizardByte)",
    "version": version,
    "commit": commit,
    "arch": arch,
    "license": lic,
    "sourceUrl": url,
    "sha256": dmg_sha,
    "files": dict(sorted(files.items())),
}
with open(os.path.join(out_dir, "ENGINE-MANIFEST.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2)
    f.write("\n")
PY

verify_extracted_manifest "$OUT_DIR" "$ARCH" "$ACTUAL_SHA"
echo "depth3d: extracted streaming engine to ${OUT_DIR}"
