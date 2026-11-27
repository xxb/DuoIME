# DuoIME

An open-source, dual-screen-first Android input method editor (IME) built with **100% Jetpack Compose**, shipping both a standalone system IME (`:app`) and an embeddable Compose library (`:duoime-embed`) from a single permissive **BSD-3-Clause** codebase.

## Highlights

- **Dual-Screen Native UX**: Zero-height local IME window paired with an opposite-display overlay keyboard (60%–90% adjustable height), one-tap screen swap, and automatic inline fallback when overlay permission is absent, the opposite screen is off, or a host window restricts overlays (e.g., password fields).
- **Single Codebase, Dual Output**: `:app` depends directly on `:duoime-embed`, so third-party apps can embed the exact same Compose keyboard without installing a separate system IME.
- **Privacy & Permissive License First**:
  - **100% BSD-3-Clause** repository and default binaries (with MIT / Apache-2.0 notices preserved for ported/integrated components).
  - **Zero `INTERNET` permission** in Milestones 1–2; optional network features arrive only in Milestone 3 behind explicit `offline` (default) vs `online` flavors.
  - No GPL-3 dictionaries or proprietary `.so` binaries are committed or bundled in default builds. Large dictionaries (e.g., `rime-ice`) and optional AI/OCR models are user-initiated downloads or local SAF imports with upfront license notices.

## Module Architecture

| Module | Type | Responsibility |
|---|---|---|
| `:core` | Pure JVM | Domain models, `InputEngine`, `InputSink`, keyboard layouts, pure `DisplayPlacementPolicy`, extension protocol schema |
| `:engine-lite` | Pure JVM | Lightweight pure-Kotlin input engine (M1 brain & permanent fallback) |
| `:keyboard-ui` | Android Library (Compose) | Theme tokens, single-pointer `KeyGrid`, `CandidateBar`, toolbar, feedback & sketch packages |
| `:duoime-embed` | Android Library (AAR) | Pure-Kotlin Jetpack Compose embed API wrapping `:keyboard-ui` + `:engine-lite` (zero native `.so`, zero Material 3) |
| `:engine-rime` | Android Library (optional) | Clean-room BSD-3 JNI consumer over self-built `librime` + `librime-lua` + OpenCC (kept outside `:duoime-embed`; automatic fallback to `:engine-lite` when native `.so` is absent) |
| `:app` | Android Application | System `InputMethodService`, inline & opposite-display overlay hosts, settings, onboarding |

## Roadmap

- **M1 — Dual-Screen Compose Shell + Lite Brain**: Repo bootstrap & hygiene gates, `:core`, `:engine-lite`, `:keyboard-ui` + `:duoime-embed`, inline IME service, opposite-display overlay engine with fallbacks, and keystroke feedback.
- **M2 — Self-Built Rime Engine**: Clean-room BSD-3 JNI over upstream `librime` + `librime-lua` (16 KB page-aligned via CI NDK build), S/T toggle, symbols, clipboard history, incognito mode, and optional user-imported dictionaries.
- **M3 — Pen & Whiteboard**: Unified Handwriting ↔ Geometry/Physics Whiteboard canvas, on-demand ONNX PP-OCRv5 handwriting model, and dual-channel image export (`commitContent` / clipboard / extension URI callback).
- **M4 — Voice, Embed & Extension Protocol**: Extension protocol v1, `:duoime-embed` publication, optional `online` flavor for user-configured OpenAI-compatible ASR/polish and local voice model import.

## Build Requirements

- **JDK 21** (targets Java/Kotlin bytecode 17)
- **Android SDK**: `compileSdk = 35`, `buildToolsVersion = "34.0.0"`, `minSdk = 26`, `targetSdk = 35`
- Optional local tools for full verification: `shellcheck`, `python3`, `aapt2`

### Quick Start

```bash
# Configure git hooks and GitHub noreply author identity
git config core.hooksPath .githooks
git config user.email "<id>+<username>@users.noreply.github.com"

# Run PII/license hygiene checks, ktlint, detekt, unit tests, and build verification
bash scripts/check.sh

# Verify PII scanner self-tests
bash scripts/test_pii_check.sh
```

### Native Rime Build & Prebuilt Integration (M2-1)

GitHub Actions (`.github/workflows/build-rime-native.yml`) is the **default reproducible builder** for DuoIME's 16 KB page-aligned `libduoime_rime.so` (Option A 100% permissive: `librime` + `librime-lua` + `Lua 5.4` + header-only `Boost` + `yaml-cpp` + `leveldb` + `marisa-trie` under BSD-2-Clause + `OpenCC`). Native `.so` binaries are never committed to git. Releases are pinned by tag and SHA-256 in [`native/rime/prebuilt.env`](native/rime/prebuilt.env), fetched outside the repository by `scripts/fetch_rime_prebuilt.sh`, packaged into `:engine-rime` and `:app` (`arm64-v8a`, uncompressed 16 KB aligned), and gated in CI via `--require-rime-native`.

```bash
# Fetch the pinned SHA-256-verified prebuilt bundle outside the repository
bash scripts/fetch_rime_prebuilt.sh

# Run full local verification with bundled native Rime enforced
REQUIRE_RIME_NATIVE=1 bash scripts/check.sh

# Generate permissive starter Rime dictionary & OpenCC text config into an external directory
python3 scripts/generate_rime_starter_assets.py --output-dir /tmp/duoime-rime-starter

# Optional local Android NDK build (requires Android NDK r27+ via ANDROID_NDK_HOME)
ANDROID_NDK_HOME=/path/to/ndk bash native/rime/build_android.sh --output-dir /tmp/duoime-rime-dist
```

### NixOS Notes

On NixOS with a read-only Android SDK, pass the Nix-packaged `aapt2` binary (detected automatically by `scripts/check.sh`):

```bash
./gradlew assembleDebug \
  -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/34.0.0/aapt2"
```

An optional [`flake.nix`](flake.nix) is provided for reproducible dev shells (`nix develop` for JDK 21 / hygiene tools, and `nix develop .#android-ndk` on `x86_64-linux` for optional local NDK builds). GitHub Actions remains the default native build path so day-to-day Kotlin development never requires installing the Android NDK locally.

Never commit `local.properties`, absolute host paths, `.so`/`.a` binaries, or keystore files.

## License

Licensed under the [BSD-3-Clause License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party attributions.
