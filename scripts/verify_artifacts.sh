#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Verifies built :app release APK and :duoime-embed release AAR against M1 gates:
# - APK size <= 15 MiB
# - Package ID == dev.duoime.app
# - Zero android.permission.INTERNET (and no storage/location/contacts permissions)
# - android:allowBackup="false" and directBootAware absent/false
# - When --require-release-signed (or REQUIRE_RELEASE_SIGNED=1):
#   - Requires app-release.apk (rejects app-release-unsigned.apk)
#   - Verifies APK signature with apksigner and rejects Android Debug certificates
# - Zero banned binaries/assets in APK or AAR
# - :duoime-embed AAR declares no IME <service> and scopes any assets to assets/duoime/
#
# Usage:
#   bash scripts/verify_artifacts.sh [--require-release-signed]

set -euo pipefail

REQUIRE_RELEASE_SIGNED="${REQUIRE_RELEASE_SIGNED:-0}"
for arg in "$@"; do
    case "$arg" in
        --require-release-signed)
            REQUIRE_RELEASE_SIGNED=1
            ;;
        *)
            echo "Usage: $0 [--require-release-signed]" >&2
            exit 2
            ;;
    esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SIGNED_APK="app/build/outputs/apk/release/app-release.apk"
UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"

APK_PATH=""
if [[ "$REQUIRE_RELEASE_SIGNED" == "1" ]]; then
    if [[ ! -f "$SIGNED_APK" ]]; then
        echo "ERROR: release-signed APK required at $SIGNED_APK (unsigned APK is rejected when signing is required)" >&2
        exit 1
    fi
    APK_PATH="$SIGNED_APK"
else
    for candidate in "$SIGNED_APK" "$UNSIGNED_APK"; do
        if [[ -f "$candidate" ]]; then
            APK_PATH="$candidate"
            break
        fi
    done
    if [[ -z "$APK_PATH" ]]; then
        echo "ERROR: release APK not found under app/build/outputs/apk/release/" >&2
        exit 1
    fi
fi

AAR_PATH="duoime-embed/build/outputs/aar/duoime-embed-release.aar"
if [[ ! -f "$AAR_PATH" ]]; then
    echo "ERROR: embed AAR not found at $AAR_PATH" >&2
    exit 1
fi

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

find_sdk_tool() {
    local tool_name="$1"
    if [[ -n "$SDK_DIR" && -x "$SDK_DIR/build-tools/34.0.0/$tool_name" ]]; then
        printf '%s\n' "$SDK_DIR/build-tools/34.0.0/$tool_name"
        return 0
    fi
    if [[ -n "$SDK_DIR" && -d "$SDK_DIR/build-tools" ]]; then
        local candidate
        candidate="$(find "$SDK_DIR/build-tools" -maxdepth 2 -type f -name "$tool_name" 2>/dev/null | sort -V | tail -n 1 || true)"
        if [[ -n "$candidate" && -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    if command -v "$tool_name" >/dev/null 2>&1; then
        command -v "$tool_name"
        return 0
    fi
    return 1
}

AAPT2_BIN="$(find_sdk_tool aapt2 || true)"
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

if [[ "$REQUIRE_RELEASE_SIGNED" == "1" || "$APK_PATH" == "$SIGNED_APK" ]]; then
    APKSIGNER_BIN="$(find_sdk_tool apksigner || true)"
    if [[ -z "$APKSIGNER_BIN" ]]; then
        echo "ERROR: apksigner binary not found in ANDROID_HOME/build-tools or PATH" >&2
        exit 1
    fi
    echo "==> Verifying APK signature via apksigner..."
    SIGNER_OUT="$("$APKSIGNER_BIN" verify --print-certs "$APK_PATH")"
    printf '%s\n' "$SIGNER_OUT"
    if ! printf '%s\n' "$SIGNER_OUT" | grep -Fq "Signer #1 certificate DN:"; then
        echo "ERROR: apksigner verify output missing Signer #1 certificate DN" >&2
        exit 1
    fi
    if [[ "$REQUIRE_RELEASE_SIGNED" == "1" ]]; then
        if printf '%s\n' "$SIGNER_OUT" | grep -Eiq 'CN=Android Debug|OU=Android,\s*O=Android'; then
            echo "ERROR: Release APK is signed with an Android Debug certificate!" >&2
            exit 1
        fi
    fi
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
import io
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
        if "<uses-permission" in manifest:
            print("ERROR: Embed AAR AndroidManifest must declare zero permissions", file=sys.stderr)
            sys.exit(1)
    if "classes.jar" in names:
        with zipfile.ZipFile(io.BytesIO(zf.read("classes.jar")), "r") as cjf:
            for cls in cjf.namelist():
                if "androidx/compose/material3/" in cls or "com/google/android/material/" in cls:
                    print(f"ERROR: Forbidden Material class in Embed AAR classes.jar: {cls}", file=sys.stderr)
                    sys.exit(1)

print("APK and AAR archive inspections passed.")
PY

echo "==> Artifact verification passed."
