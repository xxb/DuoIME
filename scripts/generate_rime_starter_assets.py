#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
"""Deterministically generates DuoIME's 100%-permissive starter Rime assets.

Transforms :engine-lite's permissive wordlists into a ready-to-deploy Rime
shared_data_dir outside git:
  - core_dict.tsv (MIT)  -> duoime_pinyin.dict.yaml (word<TAB>pinyin<TAB>weight)
  - s2t.tsv (Apache-2.0) -> opencc/duoime_s2t.txt + opencc/duoime_s2t.json
  - native/rime/schemas/ -> default.yaml + *.schema.yaml
  - starter_manifest.json (deterministic SHA-256 & counts)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path


DICT_HEADER = """# SPDX-License-Identifier: MIT
# Generated deterministically from engine-lite/src/main/resources/duoime/dict/core_dict.tsv
# Derived from mozillazg/pinyin-data (MIT), mozillazg/phrase-pinyin-data (MIT),
# and fxsjy/jieba (MIT). See THIRD_PARTY_NOTICES.md.
---
name: duoime_pinyin
version: "1.0"
sort: by_weight
use_preset_vocabulary: false
...
"""

S2T_HEADER = """# SPDX-License-Identifier: Apache-2.0
# Generated deterministically from engine-lite/src/main/resources/duoime/dict/s2t.tsv
# Derived from BYVoid/OpenCC STCharacters.txt and STPhrases.txt (Apache-2.0).
"""

OPENCC_S2T_CONFIG = {
    "name": "DuoIME Simplified to Traditional Chinese (Permissive Text Dict)",
    "segmentation": {
        "type": "mmseg",
        "dict": {
            "type": "text",
            "file": "duoime_s2t.txt",
        },
    },
    "conversion_chain": [
        {
            "dict": {
                "type": "text",
                "file": "duoime_s2t.txt",
            }
        }
    ],
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_core_dict(core_dict_path: Path) -> list[tuple[str, str, int]]:
    """Parses pinyin<TAB>word<TAB>weight into deduplicated (word, pinyin, weight) rows."""
    best_weights: dict[tuple[str, str], int] = {}
    with core_dict_path.open("r", encoding="utf-8") as f:
        for line_no, raw in enumerate(f, start=1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = raw.rstrip("\r\n").split("\t")
            if len(parts) != 3:
                raise ValueError(
                    f"{core_dict_path}:{line_no}: expected 3 tab-separated columns, got {len(parts)}"
                )
            pinyin = " ".join(parts[0].strip().lower().split())
            word = parts[1].strip()
            weight_str = parts[2].strip()
            if not pinyin or not word:
                raise ValueError(f"{core_dict_path}:{line_no}: empty pinyin or word")
            try:
                weight = int(weight_str)
            except ValueError as exc:
                raise ValueError(
                    f"{core_dict_path}:{line_no}: invalid integer weight '{weight_str}'"
                ) from exc
            if weight < 0:
                raise ValueError(f"{core_dict_path}:{line_no}: negative weight {weight}")
            key = (word, pinyin)
            prev = best_weights.get(key)
            if prev is None or weight > prev:
                best_weights[key] = weight

    rows = [(word, pinyin, weight) for (word, pinyin), weight in best_weights.items()]
    rows.sort(key=lambda item: (item[1], -item[2], item[0]))
    return rows


def parse_s2t_dict(s2t_path: Path) -> list[tuple[str, str]]:
    """Parses simplified<TAB>traditional into strictly unique, sorted OpenCC TextDict rows."""
    mapping: dict[str, str] = {}
    with s2t_path.open("r", encoding="utf-8") as f:
        for line_no, raw in enumerate(f, start=1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = raw.rstrip("\r\n").split("\t")
            if len(parts) != 2:
                raise ValueError(
                    f"{s2t_path}:{line_no}: expected 2 tab-separated columns, got {len(parts)}"
                )
            src = parts[0].strip()
            dst = parts[1].strip()
            if not src or not dst:
                raise ValueError(f"{s2t_path}:{line_no}: empty simplified or traditional entry")
            if src not in mapping:
                mapping[src] = dst

    return sorted(mapping.items(), key=lambda item: item[0])


def generate_starter_assets(
    core_dict_path: Path,
    s2t_path: Path,
    output_dir: Path,
    schemas_dir: Path | None = None,
) -> dict:
    dict_rows = parse_core_dict(core_dict_path)
    s2t_rows = parse_s2t_dict(s2t_path)

    output_dir.mkdir(parents=True, exist_ok=True)
    opencc_dir = output_dir / "opencc"
    opencc_dir.mkdir(parents=True, exist_ok=True)

    dict_out = output_dir / "duoime_pinyin.dict.yaml"
    with dict_out.open("w", encoding="utf-8", newline="\n") as f:
        f.write(DICT_HEADER)
        for word, pinyin, weight in dict_rows:
            f.write(f"{word}\t{pinyin}\t{weight}\n")

    s2t_txt_out = opencc_dir / "duoime_s2t.txt"
    with s2t_txt_out.open("w", encoding="utf-8", newline="\n") as f:
        f.write(S2T_HEADER)
        for src, dst in s2t_rows:
            f.write(f"{src}\t{dst}\n")

    s2t_json_out = opencc_dir / "duoime_s2t.json"
    with s2t_json_out.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(OPENCC_S2T_CONFIG, f, ensure_ascii=False, indent=2)
        f.write("\n")

    copied_files: list[str] = [
        "duoime_pinyin.dict.yaml",
        "opencc/duoime_s2t.txt",
        "opencc/duoime_s2t.json",
    ]

    if schemas_dir is not None:
        if not schemas_dir.is_dir():
            raise FileNotFoundError(f"Schemas directory not found: {schemas_dir}")
        default_src = schemas_dir / "duoime_default.yaml"
        if not default_src.is_file():
            raise FileNotFoundError(f"Expected default config missing: {default_src}")
        shutil.copyfile(default_src, output_dir / "default.yaml")
        copied_files.append("default.yaml")

        schema_files = sorted(schemas_dir.glob("*.schema.yaml"))
        if not schema_files:
            raise FileNotFoundError(f"No *.schema.yaml files found under {schemas_dir}")
        for schema_src in schema_files:
            shutil.copyfile(schema_src, output_dir / schema_src.name)
            copied_files.append(schema_src.name)

    copied_files.sort()
    manifest = {
        "schema_version": 1,
        "dict_entries": len(dict_rows),
        "s2t_entries": len(s2t_rows),
        "files": {rel: sha256_file(output_dir / rel) for rel in copied_files},
    }
    manifest_out = output_dir / "starter_manifest.json"
    with manifest_out.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    return manifest


def main(argv: list[str] | None = None) -> int:
    repo_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(
        description="Generate DuoIME permissive starter Rime dictionary, OpenCC text config, and schemas."
    )
    parser.add_argument(
        "--core-dict",
        type=Path,
        default=repo_root / "engine-lite/src/main/resources/duoime/dict/core_dict.tsv",
        help="Path to permissive core_dict.tsv",
    )
    parser.add_argument(
        "--s2t",
        type=Path,
        default=repo_root / "engine-lite/src/main/resources/duoime/dict/s2t.tsv",
        help="Path to permissive s2t.tsv",
    )
    parser.add_argument(
        "--schemas-dir",
        type=Path,
        default=repo_root / "native/rime/schemas",
        help="Directory containing clean-room DuoIME YAML schemas",
    )
    parser.add_argument(
        "--no-schemas",
        action="store_true",
        help="Skip copying YAML schema files into output directory",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Output directory for generated Rime starter assets (must be outside tracked git paths)",
    )
    args = parser.parse_args(argv)

    manifest = generate_starter_assets(
        core_dict_path=args.core_dict,
        s2t_path=args.s2t,
        output_dir=args.output_dir,
        schemas_dir=None if args.no_schemas else args.schemas_dir,
    )
    print(
        f"Generated Rime starter assets in {args.output_dir}: "
        f"{manifest['dict_entries']} dict entries, {manifest['s2t_entries']} s2t entries, "
        f"{len(manifest['files'])} files."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
