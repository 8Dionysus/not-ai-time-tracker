"""Host preparation regressions; these do not substitute for Kotlin loader execution."""
import copy
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import catalog_content_pack as pack


ROOT = Path(__file__).resolve().parents[1]


class CatalogPackPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest, cls.original = pack.source(ROOT / "catalog")

    def test_pinned_source_counts_and_parent_qualified_values(self):
        summary = pack.validate_payload(self.original, self.original)
        self.assertEqual({"entries": 2385, "kinds": {"AXIS": 337, "TYPE": 1918, "VALUE": 130},
                          "aliases": 2490, "descriptions": 1918, "textFields": 6793}, summary)
        values = [row["key"] for row in self.original["entries"] if row["key"]["kind"] == "VALUE"]
        self.assertEqual(53, len({key["stableId"] for key in values}))
        self.assertEqual(130, len({pack.identity(key) for key in values}))
        self.assertEqual("9efaea5313b40ed2ae762437450f1a69759bfdd1e5fe1bb28e7ca2f8f5dc36b3", self.manifest["sha256"])

    def test_partial_aliases_and_unknown_or_duplicate_identities_refuse(self):
        for mutation in ("alias", "missing", "duplicate", "parent", "version", "schema"):
            with self.subTest(mutation=mutation):
                value = copy.deepcopy(self.original)
                if mutation == "alias":
                    row = next(row for row in value["entries"] if len(row["text"]["aliases"]) > 1)
                    row["text"]["aliases"] = row["text"]["aliases"][:1]
                elif mutation == "missing":
                    value["entries"].pop()
                elif mutation == "duplicate":
                    value["entries"].append(value["entries"][0])
                elif mutation == "parent":
                    next(row for row in value["entries"] if row["key"]["kind"] == "VALUE")["key"]["parentId"] = None
                elif mutation == "version":
                    value["entries"][0]["key"]["meaningVersion"] = True
                else:
                    value["schemaVersion"] = True
                with self.assertRaises(ValueError):
                    pack.validate_payload(value, self.original)

    def test_preparation_does_not_normalize_or_install_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate.json"
            value = copy.deepcopy(self.original)
            value["entries"][0]["text"]["label"] = "Synthetic e\u0301 / é / العربية / 𝕋"
            raw = pack.encoded(value) + b" \n"
            candidate.write_bytes(raw)
            target = root / "en"
            result = self.run_package(candidate, target, "--expected-sha256", pack.digest(raw), "--reviewed", "--editor", "synthetic fixture")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(raw, (target / "labels.json").read_bytes())
            _, metadata = pack.read_json(target / "manifest.json")
            self.assertEqual({"file": "labels.json", "bytes": len(raw), "sha256": pack.digest(raw)}, metadata["payload"])
            self.assertIsNone(metadata["license"])
            self.assertEqual("source_rights_review_pending", metadata["rightsStatus"])
            self.assertIn('"eligibleForDisplay": false', result.stdout)
            self.assertIn('"installed": false', result.stdout)
            before = (target / "manifest.json").read_bytes()
            self.assertNotEqual(0, self.run_package(candidate, target).returncode)
            self.assertEqual(before, (target / "manifest.json").read_bytes())

    def test_hash_utf8_and_review_fail_before_creating_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate.json"
            candidate.write_bytes(pack.encoded(self.original))
            for index, flags in enumerate((("--expected-sha256", "0" * 64), ("--reviewed",))):
                target = root / str(index)
                self.assertNotEqual(0, self.run_package(candidate, target, *flags).returncode)
                self.assertFalse(target.exists())
            candidate.write_bytes(b'{"entries": ["\xc3("]}')
            target = root / "invalid-utf8"
            self.assertNotEqual(0, self.run_package(candidate, target).returncode)
            self.assertFalse(target.exists())
            candidate.write_bytes(b'{"entries": [], "entries": []}')
            self.assertNotEqual(0, self.run_package(candidate, target).returncode)
            self.assertFalse(target.exists())

    def test_explicit_fallback_chains_never_cross_chinese_scripts(self):
        for tag, expected in (("es-MX", ["es-419", "es"]), ("es-419", ["es"]), ("pt-BR", ["pt"]),
                              ("zh-Hans", []), ("zh-Hant", []), ("zh-Hans-CN", ["zh-Hans"]), ("en", [])):
            self.assertEqual(expected, pack.locale_fallbacks(tag))
        for tag in ("../en", "en_US", "en-us", "zh-hans", "und", "und-US", "iw", "iw-IL", "in", "in-ID", "ji", "ji-001"):
            with self.assertRaises(ValueError):
                pack.locale_fallbacks(tag)

    @staticmethod
    def run_package(candidate, target, *flags):
        return subprocess.run([sys.executable, str(ROOT / "scripts/catalog_content_pack.py"), "package",
                               "--labels", str(candidate), "--locale", "en", "--release", "synthetic-fixture",
                               "--authority", "synthetic fixture", "--translator", "synthetic fixture", "--output", str(target),
                               *flags], text=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
