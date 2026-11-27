#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Downloads and SHA-256 verifies a checksummed DuoIME native Rime prebuilt
# tarball into an external cache directory (NEVER inside the git repository).
#
# Usage:
#   bash scripts/fetch_rime_prebuilt.sh \
#     [--tag <release-tag>] [--sha256 <hex>] [--abi arm64-v8a] \
#     [--repo xxb/DuoIME] [--output-dir <dir>]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PREBUILT_ENV="${REPO_ROOT}/native/rime/prebuilt.env"

RIME_PREBUILT_REPO="xxb/DuoIME"
RIME_PREBUILT_ABI="arm64-v8a"
RIME_PREBUILT_TAG=""
RIME_PREBUILT_ARCHIVE=""
RIME_PREBUILT_SHA256=""

if [[ -f "$PREBUILT_ENV" ]]; then
    # shellcheck source=../native/rime/prebuilt.env disable=SC1091
    source "$PREBUILT_ENV"
fi

REPO="$RIME_PREBUILT_REPO"
ABI="$RIME_PREBUILT_ABI"
TAG="$RIME_PREBUILT_TAG"
ARCHIVE="$RIME_PREBUILT_ARCHIVE"
EXPECTED_SHA256="$RIME_PREBUILT_SHA256"
OUTPUT_DIR=""

ABI_OVERRIDDEN=0

usage() {
    cat <<EOF >&2
Usage: $0 [--tag <tag>] [--sha256 <hex>] [--abi <abi>] [--repo <owner/repo>] [--output-dir <dir>]
Defaults are read from native/rime/prebuilt.env when present.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)
            TAG="${2:?missing value for --tag}"
            shift 2
            ;;
        --sha256)
            EXPECTED_SHA256="${2:?missing value for --sha256}"
            shift 2
            ;;
        --abi)
            ABI="${2:?missing value for --abi}"
            ABI_OVERRIDDEN=1
            shift 2
            ;;
        --repo)
            REPO="${2:?missing value for --repo}"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="${2:?missing value for --output-dir}"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            usage
            exit 2
            ;;
    esac
done

if [[ -z "$TAG" || -z "$EXPECTED_SHA256" ]]; then
    echo "ERROR: both --tag and --sha256 are required (or must be pinned in native/rime/prebuilt.env)." >&2
    exit 1
fi

if [[ -z "$ARCHIVE" || "$ABI_OVERRIDDEN" == "1" ]]; then
    ARCHIVE="duoime-rime-native-${ABI}.tar.gz"
fi

if [[ -z "$OUTPUT_DIR" ]]; then
    CACHE_BASE="${XDG_CACHE_HOME:-${HOME}/.cache}"
    OUTPUT_DIR="${CACHE_BASE}/duoime/rime-prebuilt/${TAG}"
fi

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"

case "$OUTPUT_DIR" in
    "${REPO_ROOT}"|"${REPO_ROOT}"/*)
        echo "ERROR: --output-dir must be outside the git repository (${REPO_ROOT})." >&2
        exit 1
        ;;
esac

SO_TARGET="${OUTPUT_DIR}/jniLibs/${ABI}/libduoime_rime.so"
STAMP_FILE="${OUTPUT_DIR}/.verified-${ABI}-${EXPECTED_SHA256}"
if [[ -f "$SO_TARGET" && -f "$STAMP_FILE" ]]; then
    echo "Verified native Rime prebuilt already present at ${OUTPUT_DIR}"
    exit 0
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

TARBALL_PATH="${TMP_DIR}/${ARCHIVE}"
DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${TAG}/${ARCHIVE}"

echo "==> Fetching ${DOWNLOAD_URL}..."
if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 --retry-delay 2 -o "$TARBALL_PATH" "$DOWNLOAD_URL"
elif command -v gh >/dev/null 2>&1; then
    gh release download "$TAG" --repo "$REPO" --pattern "$ARCHIVE" --dir "$TMP_DIR"
else
    echo "ERROR: curl or gh is required to fetch prebuilt native artifacts." >&2
    exit 1
fi

echo "==> Verifying SHA-256 (${EXPECTED_SHA256})..."
echo "${EXPECTED_SHA256}  ${TARBALL_PATH}" | sha256sum -c -

echo "==> Inspecting archive paths for safety..."
python3 - "$TARBALL_PATH" <<'PY'
import posixpath
import sys
import tarfile

with tarfile.open(sys.argv[1], "r:gz") as tf:
    for member in tf.getmembers():
        if member.issym() or member.islnk():
            sys.exit(f"ERROR: refusing symlink/hardlink in prebuilt archive: {member.name}")
        norm = posixpath.normpath(member.name)
        if norm.startswith("/") or norm == ".." or norm.startswith("../"):
            sys.exit(f"ERROR: refusing unsafe archive path: {member.name}")
PY

rm -rf "${OUTPUT_DIR}/jniLibs/${ABI}" "${OUTPUT_DIR}/manifest.json"
tar -xzf "$TARBALL_PATH" -C "$OUTPUT_DIR"

if [[ ! -f "$SO_TARGET" ]]; then
    echo "ERROR: extracted prebuilt archive missing ${SO_TARGET}" >&2
    exit 1
fi

rm -f "${OUTPUT_DIR}/.verified-${ABI}-"*
printf '%s\n' "$EXPECTED_SHA256" > "$STAMP_FILE"
echo "==> Native Rime prebuilt ready at ${OUTPUT_DIR}"
