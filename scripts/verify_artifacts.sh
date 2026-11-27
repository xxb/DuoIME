#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Verifies built :app release APK, :duoime-embed release AAR, and optional :engine-rime AAR
# against M1-M2 gates:
# - APK size <= 15 MiB
# - Package ID == dev.duoime.app
# - Zero android.permission.INTERNET (and no storage/location/contacts permissions)
# - android:allowBackup="false" and directBootAware absent/false
# - When --require-release-signed (or REQUIRE_RELEASE_SIGNED=1):
#   - Requires app-release.apk (rejects app-release-unsigned.apk)
#   - Verifies APK signature with apksigner and rejects Android Debug certificates
# - When --require-rime-native (or REQUIRE_RIME_NATIVE=1):
#   - Requires lib/arm64-v8a/libduoime_rime.so in release APK (and in :engine-rime AAR if module exists)
# - Every native .so in APK/AAR has ELF PT_LOAD p_align >= 16384 (16 KB) and APK .so entries
#   are stored uncompressed (ZIP_STORED) at 16 KB-aligned file offsets
# - Zero banned binaries/assets (Yuyan, pinyin4j, GPL/LGPL Rime packs, prebuilt *.bin) in APK/AAR
# - :duoime-embed AAR has zero jni/*.so, zero IME <service>, zero permissions, zero Material classes
# - Optional :engine-rime AAR has zero IME <service>, zero permissions, zero Material classes
#
# Usage:
#   bash scripts/verify_artifacts.sh [--require-release-signed] [--require-rime-native]

set -euo pipefail

REQUIRE_RELEASE_SIGNED="${REQUIRE_RELEASE_SIGNED:-0}"
REQUIRE_RIME_NATIVE="${REQUIRE_RIME_NATIVE:-0}"
for arg in "$@"; do
    case "$arg" in
        --require-release-signed)
            REQUIRE_RELEASE_SIGNED=1
            ;;
        --require-rime-native)
            REQUIRE_RIME_NATIVE=1
            ;;
        *)
            echo "Usage: $0 [--require-release-signed] [--require-rime-native]" >&2
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

RIME_AAR_PATH="engine-rime/build/outputs/aar/engine-rime-release.aar"
if [[ "$REQUIRE_RIME_NATIVE" == "1" && -f "engine-rime/build.gradle.kts" && ! -f "$RIME_AAR_PATH" ]]; then
    echo "ERROR: :engine-rime AAR required at $RIME_AAR_PATH when --require-rime-native is set" >&2
    exit 1
fi

echo "==> Inspecting APK and AAR contents, 16 KB ELF/ZIP alignment, and hygiene..."
python3 - "$APK_PATH" "$AAR_PATH" "$RIME_AAR_PATH" "$REQUIRE_RIME_NATIVE" <<'PY'
import io
import os
import re
import struct
import sys
import zipfile

apk_path, aar_path, rime_aar_path, require_rime_flag = sys.argv[1:5]
require_rime_native = require_rime_flag == "1"

banned_re = re.compile(
    r"(libyuyanime\.so|libyuyanwriting\.so|rec\.nb|pinyindb|pinyin4j|"
    r"rime-ice|rime-frost|rime-prelude|rime-essay|rime-luna-pinyin|"
    r"essay\.txt|luna_pinyin|\.(?:table|prism|reverse)\.bin$)",
    re.IGNORECASE,
)


def verify_elf_16kb(raw: bytes, label: str) -> None:
    if len(raw) < 64 or raw[:4] != b"\x7fELF":
        sys.exit(f"ERROR: {label} is not a valid ELF shared object")
    ei_class = raw[4]
    ei_data = raw[5]
    endian = "<" if ei_data == 1 else ">"
    load_aligns = []
    if ei_class == 2:
        (e_phoff,) = struct.unpack_from(endian + "Q", raw, 32)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", raw, 54)
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            (p_type,) = struct.unpack_from(endian + "I", raw, off)
            if p_type == 1:  # PT_LOAD
                (p_align,) = struct.unpack_from(endian + "Q", raw, off + 48)
                load_aligns.append(p_align)
    elif ei_class == 1:
        (e_phoff,) = struct.unpack_from(endian + "I", raw, 28)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", raw, 42)
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            (p_type,) = struct.unpack_from(endian + "I", raw, off)
            if p_type == 1:  # PT_LOAD
                (p_align,) = struct.unpack_from(endian + "I", raw, off + 28)
                load_aligns.append(p_align)
    else:
        sys.exit(f"ERROR: {label} has unsupported ELF class {ei_class}")
    if not load_aligns:
        sys.exit(f"ERROR: {label} has no PT_LOAD segments")
    for idx, align in enumerate(load_aligns):
        if align < 16384 or (align % 16384) != 0:
            sys.exit(f"ERROR: {label} PT_LOAD[{idx}] p_align={hex(align)} < 0x4000 (16 KB)")


def verify_apk_so_zip_alignment(apk_file, info: zipfile.ZipInfo) -> None:
    if info.compress_type != zipfile.ZIP_STORED:
        sys.exit(
            f"ERROR: APK native library {info.filename} must be uncompressed (ZIP_STORED) for 16 KB alignment"
        )
    apk_file.seek(info.header_offset)
    local_hdr = apk_file.read(30)
    if len(local_hdr) != 30 or local_hdr[:4] != b"PK\x03\x04":
        sys.exit(f"ERROR: invalid ZIP local file header for {info.filename}")
    fname_len, extra_len = struct.unpack("<HH", local_hdr[26:30])
    data_offset = info.header_offset + 30 + fname_len + extra_len
    if data_offset % 16384 != 0:
        sys.exit(
            f"ERROR: APK native library {info.filename} data offset {data_offset} "
            f"(mod 16384 = {data_offset % 16384}) is not 16 KB aligned"
        )


def verify_library_aar(archive_path: str, label: str, forbid_native: bool) -> list[str]:
    found_so = []
    with zipfile.ZipFile(archive_path, "r") as zf:
        names = zf.namelist()
        for name in names:
            if banned_re.search(name):
                sys.exit(f"ERROR: Banned entry in {label}: {name}")
            if name.startswith("assets/") and not name.startswith("assets/duoime/"):
                sys.exit(f"ERROR: {label} asset outside assets/duoime/: {name}")
            if name.startswith("jni/") or name.endswith(".so"):
                if forbid_native:
                    sys.exit(f"ERROR: {label} must not package native libraries: {name}")
                if name.endswith(".so"):
                    verify_elf_16kb(zf.read(name), f"{label}:{name}")
                    found_so.append(name)
        if "AndroidManifest.xml" in names:
            manifest = zf.read("AndroidManifest.xml").decode("utf-8", errors="replace")
            if "<service" in manifest or "BIND_INPUT_METHOD" in manifest:
                sys.exit(f"ERROR: {label} AndroidManifest must not declare an IME service")
            if "<uses-permission" in manifest:
                sys.exit(f"ERROR: {label} AndroidManifest must declare zero permissions")
        if "classes.jar" in names:
            with zipfile.ZipFile(io.BytesIO(zf.read("classes.jar")), "r") as cjf:
                for cls in cjf.namelist():
                    if "androidx/compose/material3/" in cls or "com/google/android/material/" in cls:
                        sys.exit(f"ERROR: Forbidden Material class in {label} classes.jar: {cls}")
    return found_so


apk_so_entries = []
with open(apk_path, "rb") as raw_apk, zipfile.ZipFile(raw_apk, "r") as zf:
    for info in zf.infolist():
        name = info.filename
        if banned_re.search(name):
            sys.exit(f"ERROR: Banned entry in APK: {name}")
        if name.startswith("lib/") and name.endswith(".so"):
            verify_elf_16kb(zf.read(info), f"APK:{name}")
            verify_apk_so_zip_alignment(raw_apk, info)
            apk_so_entries.append(name)

if require_rime_native and "lib/arm64-v8a/libduoime_rime.so" not in apk_so_entries:
    sys.exit(
        "ERROR: --require-rime-native set, but lib/arm64-v8a/libduoime_rime.so is missing from APK"
    )

verify_library_aar(aar_path, "Embed AAR", forbid_native=True)

if os.path.isfile(rime_aar_path):
    rime_so = verify_library_aar(rime_aar_path, "Rime AAR", forbid_native=False)
    if require_rime_native and "jni/arm64-v8a/libduoime_rime.so" not in rime_so:
        sys.exit(
            "ERROR: --require-rime-native set, but jni/arm64-v8a/libduoime_rime.so is missing from Rime AAR"
        )
    print(f"Rime AAR inspected ({len(rime_so)} native libs, 16 KB ELF verified).")

print(f"APK ({len(apk_so_entries)} native libs, 16 KB ELF/ZIP verified) and Embed AAR inspections passed.")
PY

echo "==> Artifact verification passed."
