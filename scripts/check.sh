#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Adaptive local and CI verification script for DuoIME.
# Usage:
#   bash scripts/check.sh                 # --local mode (default)
#   bash scripts/check.sh --local         # hygiene + shellcheck + ktlint + detekt + tests + lint + release build/verify
#   bash scripts/check.sh --ci-fast       # hygiene + fail-closed shellcheck + ktlintCheck + detekt
#   bash scripts/check.sh --test-signing  # exercise ephemeral PKCS12 release signing + apksigner verify

set -euo pipefail

MODE="${1:---local}"
if [[ "$MODE" != "--local" && "$MODE" != "--ci-fast" && "$MODE" != "--test-signing" ]]; then
    echo "Usage: $0 [--local|--ci-fast|--test-signing]" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "==> Running PII & license hygiene gates..."
bash scripts/pii_check.sh
bash scripts/license_check.sh
python3 scripts/test_generate_rime_starter_assets.py

if command -v shellcheck >/dev/null 2>&1; then
    echo "==> Running shellcheck..."
    shellcheck -x -P SCRIPTDIR scripts/*.sh native/rime/*.sh .githooks/pre-commit
elif [[ "$MODE" == "--ci-fast" ]]; then
    echo "ERROR: shellcheck is required in --ci-fast mode but was not found in PATH." >&2
    exit 1
else
    echo "==> Notice: shellcheck not found; skipping shell script lint."
fi

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f "$REPO_ROOT/local.properties" ]]; then
    SDK_DIR=$(sed -nE 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*(.+)$/\1/p' "$REPO_ROOT/local.properties" | head -n 1 | tr -d '\r')
fi
if [[ -n "$SDK_DIR" && -z "${ANDROID_HOME:-}" ]]; then
    export ANDROID_HOME="$SDK_DIR"
fi

GRADLE_ARGS=(-PappVersionName=0.0.0-ci -PappVersionCode=1)
if [[ -n "$SDK_DIR" && "$SDK_DIR" == /nix/store/* && -x "$SDK_DIR/build-tools/34.0.0/aapt2" ]]; then
    GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=$SDK_DIR/build-tools/34.0.0/aapt2")
fi

HAS_JAVA=0
if command -v java >/dev/null 2>&1; then
    HAS_JAVA=1
fi

HAS_ANDROID_SDK=0
if [[ -n "$SDK_DIR" && -d "$SDK_DIR" ]]; then
    HAS_ANDROID_SDK=1
fi

if [[ "$MODE" == "--ci-fast" ]]; then
    echo "==> Running fast static checks (ktlintCheck detekt :duoime-embed:verifyNoMaterial3)..."
    ./gradlew ktlintCheck detekt :duoime-embed:verifyNoMaterial3 "${GRADLE_ARGS[@]}"
    exit 0
fi

TEMP_SIGNING_DIR=""
cleanup_signing() {
    if [[ -n "$TEMP_SIGNING_DIR" && -d "$TEMP_SIGNING_DIR" ]]; then
        rm -rf "$TEMP_SIGNING_DIR"
    fi
}
trap cleanup_signing EXIT

setup_ephemeral_signing() {
    if ! command -v keytool >/dev/null 2>&1; then
        return 1
    fi
    TEMP_SIGNING_DIR="$(mktemp -d)"
    local ks_path="${TEMP_SIGNING_DIR}/duoime-ephemeral.p12"
    local ks_pass
    ks_pass="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
    local ks_alias="duoime-ephemeral"
    keytool -genkeypair \
        -storetype PKCS12 \
        -keystore "$ks_path" \
        -storepass "$ks_pass" \
        -keypass "$ks_pass" \
        -alias "$ks_alias" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 1 \
        -dname "CN=DuoIME Local Ephemeral, OU=Verification, O=DuoIME, C=US" \
        >/dev/null 2>&1
    chmod 600 "$ks_path"
    export SIGNING_STORE_FILE="$ks_path"
    export SIGNING_STORE_PASSWORD="$ks_pass"
    export SIGNING_KEY_ALIAS="$ks_alias"
    export SIGNING_KEY_PASSWORD="$ks_pass"
    return 0
}

if [[ "$MODE" == "--test-signing" ]]; then
    if (( HAS_JAVA == 0 || HAS_ANDROID_SDK == 0 )); then
        echo "ERROR: --test-signing requires JDK and Android SDK." >&2
        exit 1
    fi
    if [[ -z "${SIGNING_STORE_FILE:-}" ]]; then
        if ! setup_ephemeral_signing; then
            echo "ERROR: keytool is required for --test-signing when SIGNING_STORE_FILE is unset." >&2
            exit 1
        fi
    fi
    echo "==> Running release signing verification build..."
    ./gradlew \
        :app:assembleRelease \
        :duoime-embed:assembleRelease \
        -PrequireReleaseSigning=true \
        "${GRADLE_ARGS[@]}"
    bash scripts/verify_artifacts.sh --require-release-signed
    exit 0
fi

if (( HAS_JAVA == 1 && HAS_ANDROID_SDK == 1 )); then
    RELEASE_SIGNING_ARGS=()
    VERIFY_ARGS=()
    if [[ -n "${SIGNING_STORE_FILE:-}" ]]; then
        RELEASE_SIGNING_ARGS+=(-PrequireReleaseSigning=true)
        VERIFY_ARGS+=(--require-release-signed)
    elif setup_ephemeral_signing; then
        echo "==> Using ephemeral PKCS12 keystore outside workspace to verify release signing..."
        RELEASE_SIGNING_ARGS+=(-PrequireReleaseSigning=true)
        VERIFY_ARGS+=(--require-release-signed)
    else
        echo "==> Notice: keytool not found; building unsigned release APK locally."
    fi

    echo "==> Running full local verification..."
    ./gradlew \
        ktlintCheck \
        detekt \
        :duoime-embed:verifyNoMaterial3 \
        :core:test \
        :engine-lite:test \
        testDebugUnitTest \
        :app:lintDebug \
        :app:assembleDebug \
        :app:assembleRelease \
        :duoime-embed:assembleRelease \
        "${RELEASE_SIGNING_ARGS[@]}" \
        "${GRADLE_ARGS[@]}"
    bash scripts/verify_artifacts.sh "${VERIFY_ARGS[@]}"
elif (( HAS_JAVA == 1 )); then
    echo "==> Notice: Android SDK not found locally; running pure-JVM checks (:core :engine-lite)..."
    ./gradlew :core:ktlintCheck :core:detekt :core:test :engine-lite:ktlintCheck :engine-lite:detekt :engine-lite:test "${GRADLE_ARGS[@]}"
else
    echo "==> Notice: Neither JDK nor Android SDK is installed in this environment; skipping Gradle checks."
fi
