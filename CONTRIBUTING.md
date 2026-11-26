# Contributing to DuoIME

Thank you for helping build DuoIME! Please follow these workflow, hygiene, and licensing rules on every contribution.

## Development Workflow

1. **Open or reference an Issue** describing the change before writing code.
2. **Create a feature branch** from `main` (e.g., `feat/m1-core-policy`, `fix/overlay-insets`). Never commit directly to `main`.
3. **Configure repository git hooks and a noreply email** before committing:
   ```bash
   git config core.hooksPath .githooks
   git config user.email "<id>+<username>@users.noreply.github.com"
   ```
4. **Run local verification** before pushing:
   ```bash
   bash scripts/test_pii_check.sh
   bash scripts/check.sh
   ```
5. **Open a Pull Request** targeting `main`, referencing the Issue (`Fixes #<num>`). Wait for CI checks and review approval before merging.

## Zero-PII & Privacy Rules

DuoIME is a public open-source project with automated pre-commit and CI gates (`scripts/pii_check.sh` and Gitleaks):

- **No personal identifiers**: Never commit real email addresses (use `@users.noreply.github.com`), private/public IP addresses (except RFC 5737 documentation ranges or `127.0.0.1`), Tailscale MagicDNS hostnames, local filesystem paths, SSH URIs, ADB network connection commands, or hostnames.
- **No secrets or signing artifacts**: Never commit `*.jks`, `*.keystore`, `*.p12`, `*.pem`, `*.key`, `local.properties`, `keystore.properties`, `.env*`, or API tokens. Release signing uses CI environment variables only (`SIGNING_*`).
- **Local extra denylist (optional)**: You may place personal strings to guard against accidental leaks in `~/.config/duoime/pii_denylist.txt` (or set `$DUOIME_PII_DENYLIST_FILE`); never commit that file.
- **No `INTERNET` permission in M1–M2**: Keep the default IME strictly offline.

## Licensing Rules (Option A — Pure Permissive)

- All new DuoIME source files (`.kt`, `.java`, `.cc`, `.h`, `.sh`, `.py`) must carry a valid SPDX header in the first 15 lines:
  - `SPDX-License-Identifier: BSD-3-Clause` for DuoIME code
  - `SPDX-License-Identifier: MIT` when porting MIT-licensed code (retaining the original copyright header and updating `THIRD_PARTY_NOTICES.md`)
- **Hard-banned items**: Do not commit proprietary binaries (`libyuyanime.so`, `libyuyanwriting.so`, `rec.nb`), GPL-2+ `pinyin4j` / `assets/pinyindb/`, GPL-3 dictionaries (`rime-ice`, `rime-frost`) or `:dict-*` modules, or `material-icons-extended` (use vector drawables instead to stay within APK size budgets).
- Optional GPL-3 dictionaries and non-permissive AI models will be supported solely via user-initiated download or local SAF import with upfront license notices.

## Code Quality

- Formatting: `ktlint` (`.editorconfig` with `android_studio` style, Composable function naming allowed).
- Static analysis: `detekt` (`config/detekt/detekt.yml`, zero issues allowed) and Android Lint.
- Shell scripts: must pass `shellcheck`.
