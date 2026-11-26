#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Verifies built :app release APK and :duoime-embed release AAR against M1 gates:
# - APK size <= 15 MB
# - Package ID == dev.duoime.app
# - Zero android.permission.INTERNET (and no storage/location/contacts permissions)
# - android:allowBackup="false" and directBootAware absent/false
# - Zero banned binaries/assets in APK or AAR
# - :duoime-embed AAR declares no IME <service> and scopes any assets to assets/duoime/

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

APK_PATH=""
for candidate in \
    "app/build/outputs/apk/release/app-release.apk" \
    "app/build/outputs/apk/release/app-release-unsigned.apk"; do
    if [[ -f "$candidate" ]]; then
        APK_PATH="$candidate"
        break
    fi
done

if [[ -z "$APK_PATH" ]]; then
    echo "ERROR: release APK not found under app/build/outputs/apk/release/" >&2
    exit 1
fi

AAR_PATH="duoime-embed/build/outputs/aar/duoime-embed-release.aar"
if [[ ! -f "$AAR_PATH" ]]; then
    echo "ERROR: embed AAR not found at $AAR_PATH" >&2
    exit 1
fi

AAPT2_BIN=""
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$SDK_DIR" && -x "$SDK_DIR/build-tools/34.0.0/aapt2" ]]; then
    AAPT2_BIN="$SDK_DIR/build-tools/34.0.0/aapt2"
elif [[ -n "$SDK_DIR" && -d "$SDK_DIR/build-tools" ]]; then
    AAPT2_CANDIDATE="$(find "$SDK_DIR/build-tools" -maxdepth 2 -type f -name aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
    if [[ -n "$AAPT2_CANDIDATE" && -x "$AAPT2_CANDIDATE" ]]; then
        AAPT2_BIN="$AAPT2_CANDIDATE"
    fi
fi
if [[ -z "$AAPT2_BIN" ]] && command -v aapt2 >/dev/null 2>&1; then
    AAPT2_BIN="$(command -v aapt2)"
fi
if [[ -z "$AAPT2_BIN" ]]; then
    echo "ERROR: aapt2 binary not found in ANDROID_HOME/build-tools or PATH" >&2
    exit 1
fi

MAX_BYTES=15728640
APK_BYTES="$(wc -c < "$APK_PATH" | tr -d '[:space:]')"
APK_MB="$(python3 -c "print(f'{int($APK_BYTES) / (1024 * 1024):.2f}')")"
echo "==> Release APK: $APK_PATH (${APK_BYTES} bytes / ${APK_MB} MiB; limit 15.00 MiB)"
if (( APK_BYTES > MAX_BYTES )); then
    echo "ERROR: APK size ${APK_BYTES} exceeds M1 15 MiB budget (${MAX_BYTES} bytes)" >&2
    exit 1
fi

echo "==> Inspecting APK permissions via aapt2..."
PERMS_OUT="$("$AAPT2_BIN" dump permissions "$APK_PATH")"
printf '%s\n' "$PERMS_OUT"

if ! printf '%s\n' "$PERMS_OUT" | grep -Fq "package: dev.duoime.app"; then
    echo "ERROR: Expected package dev.duoime.app in aapt2 permissions output" >&2
    exit 1
fi

for forbidden_perm in \
    "android.permission.INTERNET" \
    "android.permission.ACCESS_NETWORK_STATE" \
    "android.permission.READ_EXTERNAL_STORAGE" \
    "android.permission.WRITE_EXTERNAL_STORAGE" \
    "android.permission.READ_CONTACTS" \
    "android.permission.ACCESS_FINE_LOCATION" \
    "android.permission.ACCESS_COARSE_LOCATION"; do
    if printf '%s\n' "$PERMS_OUT" | grep -Fq "$forbidden_perm"; then
        echo "ERROR: Forbidden permission found in M1 APK: $forbidden_perm" >&2
        exit 1
    fi
done

echo "==> Inspecting APK AndroidManifest flags via aapt2 xmltree..."
MANIFEST_TREE="$("$AAPT2_BIN" dump xmltree "$APK_PATH" --file AndroidManifest.xml)"
if ! printf '%s\n' "$MANIFEST_TREE" | grep -Eq 'allowBackup\(0x[0-9a-f]+\)=(false|\(type 0x12\)0x0)'; then
    echo "ERROR: APK AndroidManifest must declare android:allowBackup=\"false\"" >&2
    exit 1
fi
if printf '%s\n' "$MANIFEST_TREE" | grep -Eq 'directBootAware\(0x[0-9a-f]+\)=(true|\(type 0x12\)0xffffffff)'; then
    echo "ERROR: APK AndroidManifest must not enable android:directBootAware=\"true\"" >&2
    exit 1
fi

echo "==> Inspecting APK and AAR contents..."
python3 - "$APK_PATH" "$AAR_PATH" <<'PY'
import re
import sys
import zipfile

apk_path, aar_path = sys.argv[1], sys.argv[2]
banned_re = re.compile(r"(libyuyanime\.so|libyuyanwriting\.so|rec\.nb|pinyindb|pinyin4j)", re.IGNORECASE)

for label, archive in (("APK", apk_path), ("AAR", aar_path)):
    with zipfile.ZipFile(archive, "r") as zf:
        for name in zf.namelist():
            if banned_re.search(name):
                print(f"ERROR: Banned entry in {label}: {name}", file=sys.stderr)
                sys.exit(1)

with zipfile.ZipFile(aar_path, "r") as zf:
    names = zf.namelist()
    for name in names:
        if name.startswith("assets/") and not name.startswith("assets/duoime/"):
            print(f"ERROR: Embed AAR asset outside assets/duoime/: {name}", file=sys.stderr)
            sys.exit(1)
    if "AndroidManifest.xml" in names:
        manifest = zf.read("AndroidManifest.xml").decode("utf-8", errors="replace")
        if "<service" in manifest or "BIND_INPUT_METHOD" in manifest:
            print("ERROR: Embed AAR AndroidManifest must not declare an IME service", file=sys.stderr)
            sys.exit(1)

print("APK and AAR archive inspections passed.")
PY

echo "==> Artifact verification passed."
