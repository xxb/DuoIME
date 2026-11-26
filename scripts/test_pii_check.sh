#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Self-test suite for scripts/pii_check.sh.
# Verifies planted fake leaks fail with redacted output and clean fixtures pass.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PII_SCRIPT="$REPO_ROOT/scripts/pii_check.sh"

TMP_BASE="$(mktemp -d)"
trap 'rm -rf "$TMP_BASE"' EXIT

python3 - "$PII_SCRIPT" "$TMP_BASE" <<'PY'
import os
import subprocess
import sys
from pathlib import Path

pii_script = Path(sys.argv[1])
tmp_base = Path(sys.argv[2])


def init_repo(name, email="123456+tester@users.noreply.github.com"):
    repo = tmp_base / name
    repo.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "init", "-b", "main"], cwd=repo, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["git", "config", "user.name", "tester"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.email", email], cwd=repo, check=True)
    return repo


def run_pii(repo, extra_args=None, extra_env=None):
    env = os.environ.copy()
    env.pop("DUOIME_PII_DENYLIST_FILE", None)
    env.pop("PII_EXTRA_PATTERNS", None)
    if extra_env:
        env.update(extra_env)
    cmd = ["bash", str(pii_script)] + (extra_args or [])
    return subprocess.run(cmd, cwd=repo, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


# 1. Clean fixture (RFC 5737 doc IPs, loopback, noreply/example emails, generic nix glob) MUST PASS.
clean_repo = init_repo("clean")
(clean_repo / "sample.txt").write_text(
    "\n".join([
        "Loopback 127.0.0.1 and unspecified 0.0.0.0 and mask 255.255.255.255",
        "Docs 192.0.2.10, 198.51.100.20, 203.0.113.30, and 2001:db8::1",
        "Emails: 42+dev@users.noreply.github.com and info@example.org",
        "Generic glob: /nix/store/*",
        "GitHub Action: uses: actions/checkout@v4",
    ]) + "\n",
    encoding="utf-8",
)
subprocess.run(["git", "add", "sample.txt"], cwd=clean_repo, check=True)
subprocess.run(["git", "commit", "-m", "chore: clean fixture"], cwd=clean_repo, check=True, stdout=subprocess.DEVNULL)

res = run_pii(clean_repo, ["--repo"])
assert res.returncode == 0, f"Clean repo failed unexpectedly:\n{res.stdout}\n{res.stderr}"

res_staged = run_pii(clean_repo, ["--staged"])
assert res_staged.returncode == 0, f"Clean staged check failed:\n{res_staged.stdout}\n{res_staged.stderr}"

# 2. Planted content leaks MUST FAIL with exact rule_id AND redact the leaked secret text.
leak_cases = [
    ("ipv4_private", "ipv4_address", "host=" + "192." + "168." + "1." + "42"),
    ("ipv4_tailscale", "ipv4_address", "peer=" + "100." + "64." + "12." + "34"),
    ("ipv6_non_loopback", "ipv6_address", "addr=" + "fe80:" + ":1234:" + "5678"),
    ("tailscale_dns", "tailscale_domain", "node=box." + "tail1234.ts." + "net"),
    ("unix_home", "local_home_path", "dir=/" + "ho" + "me/alice/src"),
    ("win_home", "local_home_path", "dir=C:\\Us" + "ers\\alice\\proj"),
    ("nix_hash", "nix_store_hash_path", "sdk=/nix/st" + "ore/552xg7vm72x87imip1zy89p6d630gb8m-sdk"),
    ("real_email", "email_address", "contact=alice@" + "company.io"),
    ("ssh_uri", "ssh_uri", "url=" + "ssh" + "://box.internal/repo"),
    ("adb_connect", "adb_connect", "cmd=adb " + "connect device"),
    ("private_key", "private_key_block", "-----BEGIN " + "RSA PRIVATE KEY-----"),
    ("gh_token", "api_token", "tok=" + "ghp" + "_" + ("A" * 36)),
    ("openai_token", "api_token", "tok=" + "sk" + "-proj-" + ("B" * 30)),
    ("signing_literal", "hardcoded_signing_secret", "SIGNING_STORE_PASSWORD=" + "superSecret123"),
]

for case_name, expected_rule, payload in leak_cases:
    repo = init_repo(f"leak_{case_name}")
    (repo / "leak.txt").write_text(payload + "\n", encoding="utf-8")
    subprocess.run(["git", "add", "leak.txt"], cwd=repo, check=True)
    r = run_pii(repo, ["--staged"])
    combined = r.stdout + "\n" + r.stderr
    assert r.returncode == 1, f"{case_name} should fail but exited {r.returncode}"
    assert f"leak.txt:1:{expected_rule}" in combined, f"{case_name} missing {expected_rule} in:\n{combined}"
    # Redaction check: the secret payload itself must never appear in stdout/stderr.
    assert payload not in combined, f"{case_name} leaked raw secret payload in output:\n{combined}"

# 3. Workflow self-hosted runner check MUST FAIL.
wf_repo = init_repo("leak_workflow")
wf_dir = wf_repo / ".github" / "workflows"
wf_dir.mkdir(parents=True)
(wf_dir / "ci.yml").write_text("runs-on: " + "self-" + "hosted\n", encoding="utf-8")
subprocess.run(["git", "add", "."], cwd=wf_repo, check=True)
r_wf = run_pii(wf_repo, ["--staged"])
assert r_wf.returncode == 1 and "self_hosted_runner" in (r_wf.stdout + r_wf.stderr)

# 4. Blocked filename/extension MUST FAIL.
for fname, expected_rule in [
    ("release.keystore", "blocked_extension"),
    ("local.properties", "blocked_file"),
    ("keystore.properties", "blocked_file"),
    (".env.local", "blocked_file"),
    ("debug.apk", "blocked_extension"),
]:
    repo = init_repo(f"blocked_{fname.replace('.', '_')}")
    (repo / fname).write_text("placeholder\n", encoding="utf-8")
    subprocess.run(["git", "add", "-f", fname], cwd=repo, check=True)
    r_blk = run_pii(repo, ["--staged"])
    combined = r_blk.stdout + "\n" + r_blk.stderr
    assert r_blk.returncode == 1, f"{fname} should fail"
    assert expected_rule in combined, f"{fname} missing {expected_rule} in:\n{combined}"

# 5. Non-noreply git author email MUST FAIL in both --staged and --repo modes.
bad_author_repo = init_repo("bad_author", email="person@" + "company.io")
(bad_author_repo / "ok.txt").write_text("hello\n", encoding="utf-8")
subprocess.run(["git", "add", "ok.txt"], cwd=bad_author_repo, check=True)
r_staged_email = run_pii(bad_author_repo, ["--staged"])
assert r_staged_email.returncode == 1 and "staged_author_email" in (r_staged_email.stdout + r_staged_email.stderr)

subprocess.run(["git", "commit", "-m", "chore: bad email"], cwd=bad_author_repo, check=True, stdout=subprocess.DEVNULL)
r_repo_email = run_pii(bad_author_repo, ["--repo"])
assert r_repo_email.returncode == 1 and "commit_author_email" in (r_repo_email.stdout + r_repo_email.stderr)

# 6. Extra denylist via PII_EXTRA_PATTERNS MUST FAIL and redact the pattern.
deny_repo = init_repo("extra_deny")
secret_handle = "customSecretHostMarker99"
(deny_repo / "note.txt").write_text(f"ref={secret_handle}\n", encoding="utf-8")
subprocess.run(["git", "add", "note.txt"], cwd=deny_repo, check=True)
r_deny = run_pii(deny_repo, ["--staged"], extra_env={"PII_EXTRA_PATTERNS": secret_handle})
combined = r_deny.stdout + "\n" + r_deny.stderr
assert r_deny.returncode == 1 and "note.txt:1:extra_denylist" in combined
assert secret_handle not in combined, "extra_denylist leaked matched text"

print("All pii_check.sh self-tests passed.")
PY
