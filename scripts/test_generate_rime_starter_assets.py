#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
"""Unit tests for scripts/generate_rime_starter_assets.py and clean-room Rime schemas."""

from __future__ import annotations

import json
import re
import tempfile
import unittest
from pathlib import Path

from generate_rime_starter_assets import (
    generate_starter_assets,
    parse_core_dict,
    parse_s2t_dict,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
CORE_DICT = REPO_ROOT / "engine-lite/src/main/resources/duoime/dict/core_dict.tsv"
S2T_TSV = REPO_ROOT / "engine-lite/src/main/resources/duoime/dict/s2t.tsv"
SCHEMAS_DIR = REPO_ROOT / "native/rime/schemas"


def extract_yaml_algebra_rules(schema_path: Path) -> list[str]:
    """Extracts speller/algebra list items without requiring external PyYAML."""
    rules: list[str] = []
    in_algebra = False
    algebra_indent = -1
    for raw in schema_path.read_text(encoding="utf-8").splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if stripped == "algebra:":
            in_algebra = True
            algebra_indent = indent
            continue
        if in_algebra:
            if indent <= algebra_indent:
                break
            if stripped.startswith("- "):
                rule = stripped[2:].strip().strip('"').strip("'")
                rules.append(rule)
    return rules


def apply_rime_algebra(syllables: set[str], rules: list[str]) -> set[str]:
    """Pure-Python simulation of Rime Calculus erase/xform/derive/xlit/abbrev rules."""
    current = set(syllables)
    for rule in rules:
        sep_idx = 0
        while sep_idx < len(rule) and "a" <= rule[sep_idx] <= "z":
            sep_idx += 1
        op = rule[:sep_idx]
        sep = rule[sep_idx]
        parts = rule.split(sep)
        nxt: set[str] = set()
        if op == "erase":
            pat = re.compile(parts[1])
            for s in current:
                if not pat.fullmatch(s):
                    nxt.add(s)
        elif op == "xform":
            pat = re.compile(parts[1])
            repl = re.sub(r"\$(\d+)", r"\\\1", parts[2])
            for s in current:
                nxt.add(pat.sub(repl, s))
        elif op in ("derive", "abbrev"):
            pat = re.compile(parts[1])
            repl = re.sub(r"\$(\d+)", r"\\\1", parts[2])
            for s in current:
                nxt.add(s)
                derived = pat.sub(repl, s)
                if derived != s:
                    nxt.add(derived)
        elif op == "xlit":
            table = str.maketrans(parts[1], parts[2])
            for s in current:
                nxt.add(s.translate(table))
        else:
            raise AssertionError(f"Unsupported algebra op: {op} in {rule}")
        current = nxt
    return current


class TestGenerateRimeStarterAssets(unittest.TestCase):
    def test_full_generation_and_determinism(self) -> None:
        with tempfile.TemporaryDirectory() as tmp1, tempfile.TemporaryDirectory() as tmp2:
            out1 = Path(tmp1)
            out2 = Path(tmp2)

            m1 = generate_starter_assets(CORE_DICT, S2T_TSV, out1, SCHEMAS_DIR)
            m2 = generate_starter_assets(CORE_DICT, S2T_TSV, out2, SCHEMAS_DIR)

            self.assertEqual(m1, m2)
            self.assertGreater(m1["dict_entries"], 36000)
            self.assertEqual(m1["s2t_entries"], 3160)

            expected_files = {
                "default.yaml",
                "duoime_pinyin.dict.yaml",
                "duoime_pinyin_26.schema.yaml",
                "duoime_pinyin_26_incognito.schema.yaml",
                "duoime_flypy.schema.yaml",
                "duoime_flypy_incognito.schema.yaml",
                "duoime_t9.schema.yaml",
                "duoime_t9_incognito.schema.yaml",
                "opencc/duoime_s2t.txt",
                "opencc/duoime_s2t.json",
            }
            self.assertEqual(set(m1["files"].keys()), expected_files)

            # Byte-for-byte determinism across independent runs
            for rel in expected_files | {"starter_manifest.json"}:
                self.assertEqual((out1 / rel).read_bytes(), (out2 / rel).read_bytes(), rel)

            # Validate duoime_pinyin.dict.yaml header & swapped column order
            dict_text = (out1 / "duoime_pinyin.dict.yaml").read_text(encoding="utf-8")
            self.assertIn("SPDX-License-Identifier: MIT", dict_text[:400])
            self.assertIn("name: duoime_pinyin", dict_text[:400])
            self.assertIn("use_preset_vocabulary: false", dict_text[:400])
            self.assertNotIn("essay.txt", dict_text)

            data_lines = dict_text.split("...\n", 1)[1].splitlines()
            self.assertEqual(len(data_lines), m1["dict_entries"])
            sample_pairs = set()
            for row in data_lines:
                cols = row.split("\t")
                self.assertEqual(len(cols), 3)
                self.assertTrue(cols[0] and cols[1] and cols[2].isdigit())
                if cols[0] in ("啊", "伴侣", "妈妈"):
                    sample_pairs.add((cols[0], cols[1]))
            self.assertIn(("啊", "a"), sample_pairs)
            self.assertIn(("伴侣", "ban lv"), sample_pairs)
            self.assertIn(("妈妈", "ma ma"), sample_pairs)

            # Validate OpenCC text config and strictly unique keys
            s2t_json = json.loads((out1 / "opencc/duoime_s2t.json").read_text(encoding="utf-8"))
            self.assertEqual(s2t_json["segmentation"]["dict"]["type"], "text")
            self.assertEqual(s2t_json["segmentation"]["dict"]["file"], "duoime_s2t.txt")
            self.assertEqual(s2t_json["conversion_chain"][0]["dict"]["type"], "text")
            self.assertEqual(s2t_json["conversion_chain"][0]["dict"]["file"], "duoime_s2t.txt")

            s2t_lines = [
                line
                for line in (out1 / "opencc/duoime_s2t.txt").read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#")
            ]
            s2t_keys = [line.split("\t")[0] for line in s2t_lines]
            self.assertEqual(len(s2t_keys), len(set(s2t_keys)))
            self.assertIn("万\t萬", s2t_lines)

    def test_clean_room_schema_invariants(self) -> None:
        default_text = (SCHEMAS_DIR / "duoime_default.yaml").read_text(encoding="utf-8")
        self.assertIn("SPDX-License-Identifier: BSD-3-Clause", default_text[:200])
        for sid in (
            "duoime_pinyin_26",
            "duoime_t9",
            "duoime_flypy",
            "duoime_pinyin_26_incognito",
            "duoime_t9_incognito",
            "duoime_flypy_incognito",
        ):
            self.assertIn(f"schema: {sid}", default_text)

        pairs = [
            ("duoime_pinyin_26", "duoime_pinyin_26_incognito"),
            ("duoime_flypy", "duoime_flypy_incognito"),
            ("duoime_t9", "duoime_t9_incognito"),
        ]
        for normal_id, incog_id in pairs:
            normal_text = (SCHEMAS_DIR / f"{normal_id}.schema.yaml").read_text(encoding="utf-8")
            incog_text = (SCHEMAS_DIR / f"{incog_id}.schema.yaml").read_text(encoding="utf-8")
            for text in (normal_text, incog_text):
                self.assertIn("SPDX-License-Identifier: BSD-3-Clause", text[:200])
                self.assertIn("dictionary: duoime_pinyin", text)
                self.assertIn(f"prism: {normal_id}", text)
                self.assertIn("opencc_config: duoime_s2t.json", text)
                self.assertIn("option_name: traditionalization", text)
            self.assertIn("enable_user_dict: true", normal_text)
            self.assertIn("enable_user_dict: false", incog_text)

    def test_t9_and_flypy_speller_algebra(self) -> None:
        t9_rules = extract_yaml_algebra_rules(SCHEMAS_DIR / "duoime_t9.schema.yaml")
        self.assertEqual(apply_rime_algebra({"ma"}, t9_rules), {"ma", "62"})
        self.assertEqual(apply_rime_algebra({"zhuang"}, t9_rules), {"zhuang", "948264"})
        self.assertEqual(apply_rime_algebra({"lv"}, t9_rules), {"lv", "58"})

        flypy_rules = extract_yaml_algebra_rules(SCHEMAS_DIR / "duoime_flypy.schema.yaml")
        expected_flypy = {
            "a": "aa",
            "e": "ee",
            "o": "oo",
            "ai": "ai",
            "an": "an",
            "ao": "ao",
            "ei": "ei",
            "en": "en",
            "er": "er",
            "ou": "ou",
            "ang": "ah",
            "eng": "eg",
            "ni": "ni",
            "hao": "hc",
            "xiao": "xn",
            "he": "he",
            "zhuan": "vr",
            "shuang": "ul",
            "chui": "iv",
            "zhong": "vs",
            "xiong": "xs",
            "jue": "jt",
            "lv": "lv",
            "nve": "nt",
            "ju": "ju",
            "yuan": "yr",
            "wang": "wh",
        }
        for syllable, expected_code in expected_flypy.items():
            actual = apply_rime_algebra({syllable}, flypy_rules)
            self.assertEqual(actual, {expected_code}, f"Xiaohe mapping mismatch for '{syllable}'")

    def test_deduplication_and_validation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            core = root / "core.tsv"
            core.write_text(
                "# comment\nma\t妈\t100\nma\t妈\t500\nma ma\t妈妈\t300\n",
                encoding="utf-8",
            )
            rows = parse_core_dict(core)
            self.assertEqual(rows, [("妈", "ma", 500), ("妈妈", "ma ma", 300)])

            s2t = root / "s2t.tsv"
            s2t.write_text("# comment\n万\t萬\n万\t萬\n后\t後\n", encoding="utf-8")
            s2t_rows = parse_s2t_dict(s2t)
            self.assertEqual(s2t_rows, [("万", "萬"), ("后", "後")])

            bad = root / "bad.tsv"
            bad.write_text("only_two\tcolumns\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                parse_core_dict(bad)


if __name__ == "__main__":
    unittest.main()
