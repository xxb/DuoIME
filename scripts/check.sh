#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Adaptive local and CI verification script for DuoIME.
# Usage:
#   bash scripts/check.sh            # --local mode (default)
#   bash scripts/check.sh --local    # hygiene + shellcheck + ktlint + detekt + tests + lint + release build/verify
#   bash scripts/check.sh --ci-fast  # hygiene + shellcheck + ktlintCheck + detekt

set -euo pipefail

MODE="${1:---local}"
if [[ "$MODE" != "--local" && "$MODE" != "--ci-fast" ]]; then
    echo "Usage: $0 [--local|--ci-fast]" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "==> Running PII & license hygiene gates..."
bash scripts/pii_check.sh
bash scripts/license_check.sh

if command -v shellcheck >/dev/null 2>&1; then
    echo "==> Running shellcheck..."
    shellcheck scripts/*.sh .githooks/pre-commit
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
    echo "==> Running fast static checks (ktlintCheck detekt)..."
    ./gradlew ktlintCheck detekt "${GRADLE_ARGS[@]}"
    exit 0
fi

if (( HAS_JAVA == 1 && HAS_ANDROID_SDK == 1 )); then
    echo "==> Running full local verification..."
    ./gradlew \
        ktlintCheck \
        detekt \
        :core:test \
        :engine-lite:test \
        testDebugUnitTest \
        :app:lintDebug \
        :app:assembleDebug \
        :app:assembleRelease \
        :duoime-embed:assembleRelease \
        "${GRADLE_ARGS[@]}"
    bash scripts/verify_artifacts.sh
elif (( HAS_JAVA == 1 )); then
    echo "==> Notice: Android SDK not found locally; running pure-JVM checks (:core :engine-lite)..."
    ./gradlew :core:ktlintCheck :core:detekt :core:test :engine-lite:ktlintCheck :engine-lite:detekt :engine-lite:test "${GRADLE_ARGS[@]}"
else
    echo "==> Notice: Neither JDK nor Android SDK is installed in this environment; skipping Gradle checks."
fi
