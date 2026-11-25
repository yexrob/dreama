#!/usr/bin/env bash
# Fetch, verify, and extract the pinned built-in streaming engine (Sunshine, GPL-3.0-only)
# for bundling inside depth3d Host.app.
#
# Upstream provenance:
#   LizardByte/Sunshine renamed its GitHub organization to SunshineStream/Sunshine
#   (https://github.com/LizardByte/Sunshine returns HTTP 301 -> SunshineStream/Sunshine).
#
# Strict integrity guarantees:
#   - Every supported architecture MUST have a hardcoded, pinned DMG SHA-256 digest below
#     AND a version-controlled file + symlink manifest at host/scripts/engine-manifest-<arch>.json.
#   - Environment overrides to bypass or weaken SHA-256 verification are NOT supported.
#   - Verification (`--verify-only` and cached reuse) ALWAYS checks the extracted stage directory
#     against the version-controlled manifest in host/scripts/, NEVER trusting any manifest file
#     stored inside the writable build/ stage directory.
#   - Both regular files (SHA-256) and symlinks (exact readlink targets, file & directory links)
#     are verified strictly.
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
PINNED_MANIFEST="${SCRIPT_DIR}/engine-manifest-${ARCH}.json"
STAGE_MANIFEST="${OUT_DIR}/ENGINE-MANIFEST.json"

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

verify_extracted_against_pinned_manifest() {
  local dir="$1"
  local pinned_manifest="$2"
  local expected_arch="$3"
  local expected_dmg_sha="$4"

  if [[ ! -f "$pinned_manifest" ]]; then
    echo "depth3d: missing version-controlled manifest at ${pinned_manifest}" >&2
    return 1
  fi

  python3 - "$dir" "$pinned_manifest" "$expected_arch" "$expected_dmg_sha" <<'PY'
import hashlib, json, os, stat, sys

out_dir, pinned_path, expected_arch, expected_dmg_sha = sys.argv[1:5]
if not os.path.isdir(out_dir):
    sys.stderr.write(f"depth3d: staged engine directory does not exist: {out_dir}\n")
    sys.exit(1)

try:
    manifest = json.loads(open(pinned_path, "r", encoding="utf-8").read())
except Exception as e:
    sys.stderr.write(f"depth3d: invalid pinned manifest {pinned_path}: {e}\n")
    sys.exit(1)

if manifest.get("arch") != expected_arch:
    sys.stderr.write(f"depth3d: pinned manifest arch mismatch (expected {expected_arch}, got {manifest.get('arch')})\n")
    sys.exit(1)

if manifest.get("sha256") != expected_dmg_sha:
    sys.stderr.write(f"depth3d: pinned manifest DMG sha256 mismatch (expected {expected_dmg_sha}, got {manifest.get('sha256')})\n")
    sys.exit(1)

expected_files = manifest.get("files")
expected_symlinks = manifest.get("symlinks")
if not isinstance(expected_files, dict) or "MacOS/depth3d-stream-engine" not in expected_files:
    sys.stderr.write("depth3d: pinned manifest missing per-file SHA-256 inventory\n")
    sys.exit(1)
if not isinstance(expected_symlinks, dict):
    sys.stderr.write("depth3d: pinned manifest missing symlink inventory\n")
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

seen_files = {}
seen_symlinks = {}

for root, dirnames, filenames in os.walk(out_dir, followlinks=False):
    # Inspect directory entries for directory symlinks (e.g. Qt framework Versions/Current -> A)
    # and prevent descending into symlinked directories.
    keep_dirs = []
    for d in sorted(dirnames):
        full = os.path.join(root, d)
        rel = os.path.relpath(full, out_dir).replace(os.sep, "/")
        if os.path.islink(full):
            seen_symlinks[rel] = os.readlink(full)
        else:
            keep_dirs.append(d)
    dirnames[:] = keep_dirs

    for name in sorted(filenames):
        full = os.path.join(root, name)
        rel = os.path.relpath(full, out_dir).replace(os.sep, "/")
        if rel == "ENGINE-MANIFEST.json":
            if os.path.islink(full):
                sys.stderr.write("depth3d: ENGINE-MANIFEST.json must not be a symlink\n")
                sys.exit(1)
            continue
        if os.path.islink(full):
            seen_symlinks[rel] = os.readlink(full)
        else:
            st = os.lstat(full)
            if not stat.S_ISREG(st.st_mode):
                sys.stderr.write(f"depth3d: unexpected non-regular entry in staged engine: {rel}\n")
                sys.exit(1)
            seen_files[rel] = full

expected_file_set = set(expected_files.keys())
actual_file_set = set(seen_files.keys())
missing_files = sorted(expected_file_set - actual_file_set)
extra_files = sorted(actual_file_set - expected_file_set)
if missing_files:
    sys.stderr.write(f"depth3d: staged engine missing files: {missing_files[:5]}\n")
    sys.exit(1)
if extra_files:
    sys.stderr.write(f"depth3d: staged engine contains unexpected files: {extra_files[:5]}\n")
    sys.exit(1)

expected_link_set = set(expected_symlinks.keys())
actual_link_set = set(seen_symlinks.keys())
missing_links = sorted(expected_link_set - actual_link_set)
extra_links = sorted(actual_link_set - expected_link_set)
if missing_links:
    sys.stderr.write(f"depth3d: staged engine missing symlinks: {missing_links[:5]}\n")
    sys.exit(1)
if extra_links:
    sys.stderr.write(f"depth3d: staged engine contains unexpected symlinks: {extra_links[:5]}\n")
    sys.exit(1)

for rel in sorted(expected_link_set):
    if seen_symlinks[rel] != expected_symlinks[rel]:
        sys.stderr.write(f"depth3d: symlink target mismatch for {rel}\n")
        sys.stderr.write(f"  expected: {expected_symlinks[rel]}\n")
        sys.stderr.write(f"  actual:   {seen_symlinks[rel]}\n")
        sys.exit(1)

engine_bin = os.path.join(out_dir, "MacOS/depth3d-stream-engine")
if not os.access(engine_bin, os.X_OK):
    sys.stderr.write(f"depth3d: engine binary not executable: {engine_bin}\n")
    sys.exit(1)

for rel in sorted(expected_file_set):
    actual = sha256_file(seen_files[rel])
    if actual != expected_files[rel]:
        sys.stderr.write(f"depth3d: staged file SHA-256 mismatch for {rel}\n")
        sys.stderr.write(f"  expected: {expected_files[rel]}\n")
        sys.stderr.write(f"  actual:   {actual}\n")
        sys.exit(1)

print(f"depth3d: verified {len(expected_file_set)} files and {len(expected_link_set)} symlinks against {os.path.basename(pinned_path)}")
PY
}

if [[ "$VERIFY_ONLY" == "1" ]]; then
  verify_extracted_against_pinned_manifest "$OUT_DIR" "$PINNED_MANIFEST" "$ARCH" "$EXPECTED_SHA"
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

if [[ -d "$OUT_DIR" && -f "$PINNED_MANIFEST" ]]; then
  if verify_extracted_against_pinned_manifest "$OUT_DIR" "$PINNED_MANIFEST" "$ARCH" "$ACTUAL_SHA"; then
    cp "$PINNED_MANIFEST" "$STAGE_MANIFEST"
    echo "depth3d: extracted engine verified and up to date at ${OUT_DIR}"
    exit 0
  fi
  echo "depth3d: extracted engine failed pinned manifest check; re-extracting cleanly from verified DMG..." >&2
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

# Copy runtime payload preserving symlinks; intentionally omit Info.plist and upstream icon so this
# helper runs under depth3d Host.app's TCC identity without upstream branding.
cp -R "${SRC_APP}/MacOS" "${OUT_DIR}/MacOS"
cp -R "${SRC_APP}/Frameworks" "${OUT_DIR}/Frameworks"
cp -R "${SRC_APP}/PlugIns" "${OUT_DIR}/PlugIns"
cp -R "${SRC_APP}/Resources" "${OUT_DIR}/Resources"

mv "${OUT_DIR}/MacOS/Sunshine" "${OUT_DIR}/MacOS/depth3d-stream-engine"
chmod 755 "${OUT_DIR}/MacOS/depth3d-stream-engine"
rm -f "${OUT_DIR}/Resources/sunshine.icns"

# Deterministic ad-hoc sign with fixed identifier so staged binary executes in tests AND hashes
# reproducibly against the version-controlled manifest in host/scripts/.
codesign --force --sign - --identifier dev.depth3d.stream-engine "${OUT_DIR}/MacOS/depth3d-stream-engine" >/dev/null

# Verify zero non-system absolute dylib references remain
BAD_LINKS="$(otool -L "${OUT_DIR}/MacOS/depth3d-stream-engine" | tail -n +2 | awk '{print $1}' | grep -vE '^(/System/|/usr/lib/|@executable_path/|@loader_path/|@rpath/)' || true)"
if [[ -n "$BAD_LINKS" ]]; then
  echo "depth3d: unexpected non-relocated dylib links:" >&2
  echo "$BAD_LINKS" >&2
  exit 1
fi

# Verify extracted payload strictly against the version-controlled manifest in host/scripts/.
verify_extracted_against_pinned_manifest "$OUT_DIR" "$PINNED_MANIFEST" "$ARCH" "$ACTUAL_SHA"
cp "$PINNED_MANIFEST" "$STAGE_MANIFEST"
echo "depth3d: extracted and verified streaming engine at ${OUT_DIR}"
