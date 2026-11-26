#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Zero-dependency PII, secrets, blocked-file, and commit-identity gate.
# Output is strictly redacted (`location:rule_id`) and never echoes secret text.
#
# Usage:
#   bash scripts/pii_check.sh [--repo|--staged] [--commit-range <revspec>] [--no-commits]

set -euo pipefail

exec python3 - "$@" <<'PY'
import ipaddress
import os
import re
import subprocess
import sys
from pathlib import Path

MODE = "repo"
COMMIT_RANGE = os.environ.get("PII_COMMIT_RANGE", "").strip() or None
CHECK_COMMITS = True

args = sys.argv[1:]
i = 0
while i < len(args):
    arg = args[i]
    if arg == "--staged":
        MODE = "staged"
    elif arg == "--repo":
        MODE = "repo"
    elif arg == "--no-commits":
        CHECK_COMMITS = False
    elif arg == "--commit-range":
        if i + 1 >= len(args):
            print("ERROR: --commit-range requires a value", file=sys.stderr)
            sys.exit(2)
        i += 1
        COMMIT_RANGE = args[i]
    else:
        print(f"Usage: pii_check.sh [--repo|--staged] [--commit-range <revspec>] [--no-commits]", file=sys.stderr)
        sys.exit(2)
    i += 1


def git_run(*cmd, check=True, text=True):
    return subprocess.run(
        ["git", *cmd],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
        check=check,
    )


try:
    repo_root = Path(git_run("rev-parse", "--show-toplevel").stdout.strip())
except subprocess.CalledProcessError:
    print("ERROR: must run inside a git repository", file=sys.stderr)
    sys.exit(2)

os.chdir(repo_root)

# Load optional minimal email allowlist strictly for verified upstream license headers.
allowed_emails = set()
allowlist_file = repo_root / ".pii-allowlist"
if allowlist_file.is_file():
    for raw in allowlist_file.read_text(encoding="utf-8", errors="ignore").splitlines():
        entry = raw.strip().lower()
        if entry and not entry.startswith("#"):
            allowed_emails.add(entry)

# Load optional personal extra denylist from untracked local file or CI secret env var.
extra_patterns = []


def add_denylist_lines(lines):
    for raw in lines:
        entry = raw.strip()
        if not entry or entry.startswith("#"):
            continue
        if entry.startswith("re:"):
            extra_patterns.append(re.compile(entry[3:], re.IGNORECASE))
        else:
            extra_patterns.append(re.compile(re.escape(entry), re.IGNORECASE))


denylist_env_path = os.environ.get("DUOIME_PII_DENYLIST_FILE", "").strip()
if denylist_env_path:
    p = Path(denylist_env_path)
    if p.is_file():
        add_denylist_lines(p.read_text(encoding="utf-8", errors="ignore").splitlines())
else:
    default_denylist = Path.home() / ".config" / "duoime" / "pii_denylist.txt"
    if default_denylist.is_file():
        add_denylist_lines(default_denylist.read_text(encoding="utf-8", errors="ignore").splitlines())

extra_env_raw = os.environ.get("PII_EXTRA_PATTERNS", "")
if extra_env_raw.strip():
    add_denylist_lines(extra_env_raw.splitlines())

BLOCKED_EXTENSIONS = {
    ".jks",
    ".keystore",
    ".p12",
    ".pfx",
    ".pem",
    ".key",
    ".apk",
    ".aab",
    ".hprof",
    ".db",
    ".sqlite",
    ".sqlite3",
}

BLOCKED_BASENAMES = {
    "local.properties",
    "keystore.properties",
    "google-services.json",
    ".env",
}

BLOCKED_DIR_PARTS = {
    ".worktrees",
    ".freebuff",
}

DOC_IPV4_NETS = [
    ipaddress.IPv4Network("192.0.2.0/24"),
    ipaddress.IPv4Network("198.51.100.0/24"),
    ipaddress.IPv4Network("203.0.113.0/24"),
]
ALLOWED_IPV4 = {"127.0.0.1", "0.0.0.0", "255.255.255.255"}

DOC_IPV6_NET = ipaddress.IPv6Network("2001:db8::/32")
ALLOWED_IPV6 = {ipaddress.IPv6Address("::1"), ipaddress.IPv6Address("::")}

# Patterns constructed cleanly without embedding literal secrets or personal paths.
RE_IPV4 = re.compile(r"(?<![0-9.])(\d{1,3}(?:\.\d{1,3}){3})(?![0-9.])")
RE_IPV6_CAND = re.compile(r"(?<![A-Za-z0-9:])([0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7})(?![A-Za-z0-9:])")
RE_TS_NET = re.compile(r"\.ts\." + r"net\b", re.IGNORECASE)
RE_UNIX_HOME = re.compile(r"(?<![A-Za-z0-9_.-])/(?:" + r"ho" + r"me|" + r"Us" + r"ers)/[A-Za-z0-9._-]+(?:/|\b)")
RE_WIN_HOME = re.compile(r"[A-Za-z]:\\Us" + r"ers\\[A-Za-z0-9._-]+", re.IGNORECASE)
RE_NIX_STORE_HASH = re.compile(r"/nix/st" + r"ore/[a-z0-9]{10,}")
RE_EMAIL = re.compile(r"(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})(?![A-Za-z0-9._%+-])")
RE_SSH_URI = re.compile(r"ssh" + r"://", re.IGNORECASE)
RE_ADB_CONNECT = re.compile(r"\badb" + r"\s+" + r"connect\b", re.IGNORECASE)
RE_SSH_USER_HOST = re.compile(
    r"\b(?:ssh|scp|sftp|rsync)\s+(?:-[A-Za-z0-9]+\s+)*[A-Za-z0-9._-]+@[A-Za-z0-9._-]+",
    re.IGNORECASE,
)
RE_GIT_SSH_HOST = re.compile(r"\bgit@[A-Za-z0-9.-]+:")
RE_LAN_USER_HOST = re.compile(
    r"\b[A-Za-z0-9._-]+@(?:[A-Za-z0-9-]+\.(?:local|lan|internal|corp|arpa)\b|\d{1,3}(?:\.\d{1,3}){3})",
    re.IGNORECASE,
)
RE_PRIVATE_KEY = re.compile(r"-----BEGIN " + r"[A-Z0-9 ]*" + r"PRIVATE KEY-----")
RE_API_TOKENS = [
    re.compile(r"\bsk" + r"-(?:proj-|live-|test-)?[A-Za-z0-9_-]{20,}\b"),
    re.compile(r"\bgh" + r"[pousr]_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bgithub_" + r"pat_[A-Za-z0-9_]{20,}\b"),
    re.compile(r"\bAI" + r"za[0-9A-Za-z_-]{20,}\b"),
    re.compile(r"\bAK" + r"IA[0-9A-Z]{16}\b"),
]
RE_HARDCODED_SIGNING = re.compile(
    r"\bSIGNING_(?:STORE_FILE|STORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD|KEYSTORE_BASE64)\s*[:=]\s*([^\s#]+)"
)
RE_SELF_HOSTED = re.compile(r"\bself-" + r"hosted\b", re.IGNORECASE)

findings = []


def add_finding(location, line_no, rule_id):
    findings.append((location, line_no, rule_id))


def check_blocked_path(rel_path):
    p = Path(rel_path)
    parts = set(p.parts)
    if parts & BLOCKED_DIR_PARTS:
        add_finding(rel_path, 0, "blocked_directory")
        return True
    name_lower = p.name.lower()
    if name_lower in BLOCKED_BASENAMES or name_lower.startswith(".env."):
        add_finding(rel_path, 0, "blocked_file")
        return True
    if p.suffix.lower() in BLOCKED_EXTENSIONS:
        add_finding(rel_path, 0, "blocked_extension")
        return True
    return False


def is_allowed_ipv4(raw_ip):
    if raw_ip in ALLOWED_IPV4:
        return True
    try:
        addr = ipaddress.IPv4Address(raw_ip)
    except ValueError:
        return True
    return any(addr in net for net in DOC_IPV4_NETS)


def is_disallowed_ipv6(raw_ip):
    if "::" not in raw_ip and raw_ip.count(":") < 7:
        return False
    try:
        addr = ipaddress.IPv6Address(raw_ip)
    except ValueError:
        return False
    if addr in ALLOWED_IPV6 or addr in DOC_IPV6_NET:
        return False
    return True


def is_allowed_email(email_raw):
    lower = email_raw.lower()
    if lower in allowed_emails:
        return True
    if lower.endswith("@users.noreply.github.com"):
        return True
    if lower.endswith("@example.com") or lower.endswith("@example.org") or lower.endswith("@example.net"):
        return True
    return False


def is_dynamic_signing_ref(value_token):
    cleaned = value_token.strip().strip("\"'")
    if not cleaned:
        return True
    dynamic_prefixes = ("$", "${", "providers.", "secrets.", "System.", "env.", "<")
    return cleaned.startswith(dynamic_prefixes)


def scan_text(location, text, is_workflow=False):
    for line_no, line in enumerate(text.splitlines(), start=1):
        for m in RE_IPV4.finditer(line):
            if not is_allowed_ipv4(m.group(1)):
                add_finding(location, line_no, "ipv4_address")
                break

        for m in RE_IPV6_CAND.finditer(line):
            if is_disallowed_ipv6(m.group(1)):
                add_finding(location, line_no, "ipv6_address")
                break

        if RE_TS_NET.search(line):
            add_finding(location, line_no, "tailscale_domain")

        if RE_UNIX_HOME.search(line) or RE_WIN_HOME.search(line):
            add_finding(location, line_no, "local_home_path")

        if RE_NIX_STORE_HASH.search(line):
            add_finding(location, line_no, "nix_store_hash_path")

        for m in RE_EMAIL.finditer(line):
            if not is_allowed_email(m.group(1)):
                add_finding(location, line_no, "email_address")
                break

        if RE_SSH_URI.search(line):
            add_finding(location, line_no, "ssh_uri")

        if RE_ADB_CONNECT.search(line):
            add_finding(location, line_no, "adb_connect")

        if RE_SSH_USER_HOST.search(line) or RE_GIT_SSH_HOST.search(line) or RE_LAN_USER_HOST.search(line):
            add_finding(location, line_no, "user_at_host")

        if RE_PRIVATE_KEY.search(line):
            add_finding(location, line_no, "private_key_block")

        if any(pat.search(line) for pat in RE_API_TOKENS):
            add_finding(location, line_no, "api_token")

        for m in RE_HARDCODED_SIGNING.finditer(line):
            if not is_dynamic_signing_ref(m.group(1)):
                add_finding(location, line_no, "hardcoded_signing_secret")
                break

        if is_workflow and RE_SELF_HOSTED.search(line):
            add_finding(location, line_no, "self_hosted_runner")

        if any(pat.search(line) for pat in extra_patterns):
            add_finding(location, line_no, "extra_denylist")


def check_staged_identity():
    for var_name, rule_id in (
        ("GIT_AUTHOR_IDENT", "staged_author_email"),
        ("GIT_COMMITTER_IDENT", "staged_committer_email"),
    ):
        ident = os.environ.get(var_name, "").strip()
        if not ident:
            res = git_run("var", var_name, check=False)
            ident = res.stdout.strip()
        m = re.search(r"<([^>]+)>", ident)
        email = m.group(1).strip().lower() if m else ""
        if not email.endswith("@users.noreply.github.com"):
            add_finding("git-identity", 0, rule_id)


GITHUB_WEB_COMMITTER = "noreply" + "@github.com"


def check_commits():
    has_head = git_run("rev-parse", "--verify", "HEAD", check=False).returncode == 0
    if not has_head:
        return
    revspec = COMMIT_RANGE if COMMIT_RANGE else "HEAD"
    ident_out = git_run("log", "--format=%h%x1f%ae%x1f%ce", revspec, check=False)
    if ident_out.returncode != 0:
        return
    for raw in ident_out.stdout.splitlines():
        if not raw.strip():
            continue
        parts = raw.split("\x1f")
        if len(parts) != 3:
            continue
        sha, ae, ce = parts
        ae_lower = ae.strip().lower()
        ce_lower = ce.strip().lower()
        if not ae_lower.endswith("@users.noreply.github.com"):
            add_finding(f"commit:{sha}", 0, "commit_author_email")
        if not (ce_lower.endswith("@users.noreply.github.com") or ce_lower == GITHUB_WEB_COMMITTER):
            add_finding(f"commit:{sha}", 0, "commit_committer_email")

    msg_out = git_run("log", "--format=%h%x1f%B%x1e", revspec, check=False)
    if msg_out.returncode == 0:
        for record in msg_out.stdout.split("\x1e"):
            record = record.strip("\n")
            if not record or "\x1f" not in record:
                continue
            sha, msg = record.split("\x1f", 1)
            scan_text(f"commit-msg:{sha}", msg)


def is_binary_bytes(data):
    return b"\x00" in data[:8192]


if MODE == "staged":
    if CHECK_COMMITS:
        check_staged_identity()
    names_out = git_run("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z", text=False).stdout
    paths = [p.decode("utf-8", errors="surrogateescape") for p in names_out.split(b"\x00") if p]
    for rel_path in paths:
        check_blocked_path(rel_path)
        blob = git_run("show", f":{rel_path}", check=False, text=False)
        if blob.returncode != 0 or is_binary_bytes(blob.stdout):
            continue
        is_wf = rel_path.startswith(".github/workflows/") and rel_path.endswith((".yml", ".yaml"))
        scan_text(rel_path, blob.stdout.decode("utf-8", errors="replace"), is_workflow=is_wf)
else:
    if CHECK_COMMITS:
        check_commits()
    tracked = git_run("ls-files", "-z", text=False).stdout
    untracked = git_run("ls-files", "--others", "--exclude-standard", "-z", text=False).stdout
    seen = set()
    paths = []
    for raw in tracked.split(b"\x00") + untracked.split(b"\x00"):
        if not raw:
            continue
        p_str = raw.decode("utf-8", errors="surrogateescape")
        if p_str not in seen:
            seen.add(p_str)
            paths.append(p_str)
    for rel_path in sorted(paths):
        check_blocked_path(rel_path)
        full_path = repo_root / rel_path
        if not full_path.is_file() or full_path.is_symlink():
            continue
        data = full_path.read_bytes()
        if is_binary_bytes(data):
            continue
        is_wf = rel_path.startswith(".github/workflows/") and rel_path.endswith((".yml", ".yaml"))
        scan_text(rel_path, data.decode("utf-8", errors="replace"), is_workflow=is_wf)

if findings:
    print("PII/secret hygiene check FAILED (matched values redacted):", file=sys.stderr)
    for loc, line_no, rule_id in findings:
        print(f"  {loc}:{line_no}:{rule_id}", file=sys.stderr)
    sys.exit(1)

print(f"PII/secret hygiene check passed ({MODE} mode).")
PY
