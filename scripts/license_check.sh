#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Enforces Option A pure-permissive licensing (BSD-3-Clause / MIT SPDX headers),
# bans proprietary/GPL artifacts & material-icons-extended, and checks M1-M2 manifest privacy.

set -euo pipefail

exec python3 - <<'PY'
import os
import re
import subprocess
import sys
from pathlib import Path


def git_run(*cmd, text=False):
    return subprocess.run(
        ["git", *cmd],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
        check=True,
    )


repo_root = Path(git_run("rev-parse", "--show-toplevel", text=True).stdout.strip())
os.chdir(repo_root)

tracked = git_run("ls-files", "-z").stdout.split(b"\x00")
untracked = git_run("ls-files", "--others", "--exclude-standard", "-z").stdout.split(b"\x00")
paths = sorted({raw.decode("utf-8", errors="surrogateescape") for raw in tracked + untracked if raw})

SOURCE_EXTS = {".kt", ".java", ".cc", ".h", ".sh", ".py"}
ALLOWED_SPDX = (
    "SPDX-License-Identifier: BSD-3-Clause",
    "SPDX-License-Identifier: MIT",
)
RE_COPYLEFT_SPDX = re.compile(r"SPDX-License-Identifier:\s*.*(?:GPL|LGPL|AGPL)", re.IGNORECASE)

BANNED_PATH_PATTERNS = [
    (re.compile(r"(?:^|/)libyuyanime\.so$", re.IGNORECASE), "banned_yuyan_so"),
    (re.compile(r"(?:^|/)libyuyanwriting\.so$", re.IGNORECASE), "banned_yuyan_so"),
    (re.compile(r"(?:^|/)rec\.nb$", re.IGNORECASE), "banned_yuyan_model"),
    (re.compile(r"(?:^|/)pinyin4j(?:/|$)", re.IGNORECASE), "banned_pinyin4j"),
    (re.compile(r"(?:^|/)assets/pinyindb(?:/|$)", re.IGNORECASE), "banned_pinyindb"),
    (re.compile(r"(?:^|/)assets/rime/build/.*\.bin$", re.IGNORECASE), "banned_prebuilt_rime_bin"),
    (re.compile(r"(?:^|/)dict-[^/]+(?:/|$)", re.IGNORECASE), "banned_gpl_dict_module"),
    (re.compile(r"(?:^|/)assets/.*(?:rime-ice|rime-frost).*", re.IGNORECASE), "banned_gpl_dict_asset"),
]

violations = []


def fail(path, rule, detail=""):
    msg = f"  {path}: {rule}" + (f" ({detail})" if detail else "")
    violations.append(msg)


for rel in paths:
    for pat, rule in BANNED_PATH_PATTERNS:
        if pat.search(rel):
            fail(rel, rule)

    full = repo_root / rel
    if not full.is_file() or full.is_symlink():
        continue

    suffix = full.suffix.lower()
    if suffix in SOURCE_EXTS:
        text = full.read_text(encoding="utf-8", errors="replace")
        head = "\n".join(text.splitlines()[:15])
        if RE_COPYLEFT_SPDX.search(head):
            fail(rel, "copyleft_spdx_forbidden_under_option_a")
        elif not any(tag in head for tag in ALLOWED_SPDX):
            fail(rel, "missing_permissive_spdx_header", "expected BSD-3-Clause or MIT within first 15 lines")

    # Enforce build/dependency rules on Gradle and version catalog files.
    if rel == "gradle/libs.versions.toml" or rel.endswith("build.gradle.kts") or rel == "settings.gradle.kts":
        text = full.read_text(encoding="utf-8", errors="replace")
        if "material-icons-extended" in text:
            fail(rel, "banned_material_icons_extended")
        if "pinyin4j" in text.lower():
            fail(rel, "banned_pinyin4j_dependency")
        if re.search(r"""project\s*\(\s*["']:dict-""", text) or re.search(r"""include\s*\(\s*["']:dict-""", text):
            fail(rel, "banned_gpl_dict_dependency")

    # Enforce M1-M2 manifest privacy and embed isolation rules.
    if rel.endswith("AndroidManifest.xml"):
        text = full.read_text(encoding="utf-8", errors="replace")
        if "android.permission.INTERNET" in text:
            fail(rel, "banned_internet_permission_in_m1_m2")
        if re.search(r"""android:directBootAware\s*=\s*["']true["']""", text):
            fail(rel, "banned_direct_boot_aware")
        if rel == "app/src/main/AndroidManifest.xml":
            if not re.search(r"""android:allowBackup\s*=\s*["']false["']""", text):
                fail(rel, "missing_allow_backup_false")
        if rel.startswith("duoime-embed/"):
            if "<service" in text or "BIND_INPUT_METHOD" in text:
                fail(rel, "embed_aar_must_not_declare_ime_service")

if violations:
    print("License & dependency policy check FAILED:", file=sys.stderr)
    for item in violations:
        print(item, file=sys.stderr)
    sys.exit(1)

print("License, SPDX, dependency & manifest hygiene check passed (Option A: 100% permissive).")
PY
