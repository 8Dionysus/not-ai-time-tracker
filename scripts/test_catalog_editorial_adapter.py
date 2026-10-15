"""Source binding and lossless identity adaptation, using synthetic editorial text."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import catalog_content_pack as pack
import catalog_editorial_adapter as adapter


class EditorialAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog = self.root / "catalog"
        self.catalog.mkdir()
        self.parts = self.root / "parts"
        self.parts.mkdir()
        vocabulary = {"types": [{"id": "root", "name": "Исходный корень", "meaningVersion": 1},
                                {"id": "child", "parentId": "root", "name": "Исходный тип", "meaningVersion": 2,
                                 "aliases": ["исходный один", "исходный два"], "definition": "Исходное описание"}],
                      "axes": [{"id": axis, "name": "Исходная ось", "meaningVersion": version,
                                "values": [{"id": "same", "label": "Исходное значение", "meaningVersion": 3}]} for axis, version in (("axis-one", 1), ("axis-two", 2))]}
        raw = pack.encoded(vocabulary)
        self.catalog_sha = pack.digest(raw)
        (self.catalog / "runtime-catalog.json").write_bytes(raw)
        (self.catalog / "manifest.json").write_bytes(pack.encoded({"formatVersion": 1, "catalogVersion": "fixture",
            "file": "runtime-catalog.json", "bytes": len(raw), "sha256": self.catalog_sha}))
        self.rows = []
        for (kind, stable_id), (parent, source, key) in adapter.source_records(vocabulary).items():
            self.rows.append({"entityKind": kind, "stableId": stable_id, "parentStableId": parent,
                              "meaningVersion": key["meaningVersion"], "sourceRecordSha256": pack.digest(adapter.canonical(source)),
                              "localizedLabel": "Synthetic e\u0301 / é / العربية " + stable_id,
                              "localizedAliases": ["Synthetic " + str(i) for i in range(len(source.get("aliases", [])))],
                              "localizedDescription": "Synthetic definition" if source.get("definition") else None})

    def write_parts(self, rows=None, change_part=None, locale="en", bound_manifest=False):
        part = {"sourceCatalogSha256": self.catalog_sha, "candidateSha256": "a" * 64, "locale": locale,
                "rightsStatus": "source_rights_review_pending", "nativeSpeakerQA": False,
                "part": 1, "startIndex": 0, "entries": copy.deepcopy(self.rows if rows is None else rows)}
        if change_part:
            change_part(part)
        raw = pack.encoded(part)
        (self.parts / f"{locale}-reviewed-part-1.json").write_bytes(raw)
        manifest = {"parts": [{"path": f"{locale}-reviewed-part-1.json", "bytes": len(raw), "sha256": pack.digest(raw)}],
                    "entries": len(self.rows), "sourceCandidateSha256": "a" * 64}
        if bound_manifest:
            manifest.pop("sourceCandidateSha256")
            manifest.update({"sourceCatalogSha256": self.catalog_sha, "candidateSha256": "a" * 64,
                             "locale": locale, "rightsStatus": "source_rights_review_pending", "nativeSpeakerQA": False})
        raw_manifest = pack.encoded(manifest)
        (self.parts / f"{locale}-input-parts-manifest.json").write_bytes(raw_manifest)
        return pack.digest(raw_manifest)

    def test_explicit_locale_reuses_identity_contract_and_refuses_mismatched_header(self):
        sha = self.write_parts(locale="es", bound_manifest=True)
        payload, report = adapter.adapt(self.catalog, self.parts, sha, "a" * 64, locale="es")
        self.assertEqual("es", report["localeTag"])
        self.assertEqual("source_bound", report["manifestBinding"])
        self.assertEqual(6, len(payload["entries"]))
        sha = self.write_parts(locale="es", bound_manifest=True, change_part=lambda part: part.update({"locale": "en"}))
        with self.assertRaises(ValueError):
            adapter.adapt(self.catalog, self.parts, sha, "a" * 64, locale="es")

    def test_source_bound_manifest_refuses_wrong_binding_even_with_valid_parts(self):
        for field, replacement in (("sourceCatalogSha256", "0" * 64), ("candidateSha256", "b" * 64),
                                   ("locale", "fr"), ("rightsStatus", "cleared"), ("nativeSpeakerQA", True)):
            with self.subTest(field=field):
                self.write_parts(locale="es", bound_manifest=True)
                path = self.parts / "es-input-parts-manifest.json"
                manifest = json.loads(path.read_bytes())
                manifest[field] = replacement
                raw = pack.encoded(manifest)
                path.write_bytes(raw)
                with self.assertRaises(ValueError):
                    adapter.adapt(self.catalog, self.parts, pack.digest(raw), "a" * 64, locale="es")

    def test_preserves_literal_text_and_parent_qualified_identity(self):
        sha = self.write_parts()
        payload, report = adapter.adapt(self.catalog, self.parts, sha, "a" * 64)
        self.assertEqual(6, report["sourceRecordsVerified"])
        self.assertEqual(9, report["textFields"])
        self.assertFalse(report["canonicalCandidateBytesVerified"])
        self.assertFalse(report["installed"])
        values = [row for row in payload["entries"] if row["key"]["kind"] == "VALUE"]
        self.assertEqual(["same", "same"], [row["key"]["stableId"] for row in values])
        self.assertEqual(["axis-one", "axis-two"], [row["key"]["parentId"] for row in values])
        self.assertEqual([1, 2], [row["key"]["parentMeaningVersion"] for row in values])
        child = next(row for row in payload["entries"] if row["key"]["stableId"] == "child")
        self.assertIsNone(child["key"]["parentId"])
        self.assertEqual(self.rows[1]["localizedLabel"], child["text"]["label"])
        self.assertEqual(self.rows[1]["localizedAliases"], child["text"]["aliases"])

    def test_refuses_identity_source_hash_and_field_loss(self):
        for field, replacement in (("stableId", "unknown"), ("parentStableId", "wrong-parent"),
                                   ("meaningVersion", 99), ("sourceRecordSha256", "0" * 64),
                                   ("localizedAliases", ["only one"]), ("localizedDescription", None)):
            with self.subTest(field=field):
                rows = copy.deepcopy(self.rows)
                rows[1][field] = replacement
                sha = self.write_parts(rows)
                with self.assertRaises(ValueError):
                    adapter.adapt(self.catalog, self.parts, sha, "a" * 64)

    def test_refuses_parts_hash_sequence_rights_and_qa_changes(self):
        for field, replacement in (("startIndex", 1), ("part", 2), ("rightsStatus", "cleared"),
                                   ("nativeSpeakerQA", True), ("sourceCatalogSha256", "0" * 64)):
            with self.subTest(field=field):
                sha = self.write_parts(change_part=lambda p: p.update({field: replacement}))
                with self.assertRaises(ValueError):
                    adapter.adapt(self.catalog, self.parts, sha, "a" * 64)
        sha = self.write_parts()
        with self.assertRaises(ValueError):
            adapter.adapt(self.catalog, self.parts, "0" * 64, "a" * 64)
        with self.assertRaises(ValueError):
            adapter.adapt(self.catalog, self.parts, sha, "b" * 64)
        path = self.parts / "en-reviewed-part-1.json"
        path.write_bytes(path.read_bytes() + b" ")
        with self.assertRaises(ValueError):
            adapter.adapt(self.catalog, self.parts, sha, "a" * 64)


if __name__ == "__main__":
    unittest.main()
