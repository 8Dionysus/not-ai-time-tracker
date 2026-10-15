#!/usr/bin/env python3
"""Adapt hash-verified editorial parts to display-only CatalogContentPayload schema 1.

The editorial VALUE stableId is axisId/rawValueId. Runtime content keys retain the
raw value ID and separate axis identity/version. TYPE taxonomy parents are not
content-key parents. Source records and every translated field are checked before
any output is created. This is release preparation, never a Dataset importer.
"""
import argparse
import json
from pathlib import Path
import re
import sys

import catalog_content_pack as pack


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def source_records(vocabulary):
    records = {}

    def add(kind, stable_id, parent, raw, key):
        identity = (kind, stable_id)
        pack.require(identity not in records, "Duplicate editorial source identity")
        records[identity] = (parent, raw, key)

    for row in vocabulary.get("types", []):
        add("type", row["id"], row.get("parentId"), row,
            {"kind": "TYPE", "stableId": row["id"], "meaningVersion": row.get("meaningVersion", 1), "parentId": None, "parentMeaningVersion": None})
    for axis in vocabulary.get("axes", []):
        add("axis", axis["id"], None, axis,
            {"kind": "AXIS", "stableId": axis["id"], "meaningVersion": axis.get("meaningVersion", 1), "parentId": None, "parentMeaningVersion": None})
        for value in axis.get("values", []):
            add("axis_value", axis["id"] + "/" + value["id"], axis["id"], {"axisId": axis["id"], **value},
                {"kind": "VALUE", "stableId": value["id"], "meaningVersion": value.get("meaningVersion", 1),
                 "parentId": axis["id"], "parentMeaningVersion": axis.get("meaningVersion", 1)})
    return records


def adapt(catalog_dir, parts_dir, expected_manifest_sha256, expected_candidate_sha256):
    source_manifest, originals = pack.source(catalog_dir)
    source_bytes, vocabulary = pack.read_json(catalog_dir / source_manifest["file"])
    pack.require(pack.digest(source_bytes) == source_manifest["sha256"], "Source changed during adaptation")
    sources = source_records(vocabulary)
    raw_manifest, manifest = pack.read_json(parts_dir / "en-input-parts-manifest.json", 64 * 1024)
    pack.require(pack.digest(raw_manifest) == expected_manifest_sha256, "Editorial parts manifest hash mismatch")
    pack.require(set(manifest) == {"parts", "entries", "sourceCandidateSha256"} and
                 manifest["sourceCandidateSha256"] == expected_candidate_sha256, "Unexpected editorial candidate")
    pack.require(isinstance(manifest["parts"], list) and 1 <= len(manifest["parts"]) <= 128, "Invalid editorial parts")
    paths = set()
    rows = []
    verified_parts = []
    for index, member in enumerate(manifest["parts"], 1):
        pack.require(set(member) == {"path", "bytes", "sha256"} and
                     re.fullmatch(r"en-reviewed-part-[1-9][0-9]*\.json", member["path"]) and member["path"] not in paths,
                     "Invalid or duplicate editorial member")
        paths.add(member["path"])
        raw, part = pack.read_json(parts_dir / member["path"])
        pack.require(type(member["bytes"]) is int and len(raw) == member["bytes"] and pack.digest(raw) == member["sha256"], "Editorial part bytes/hash mismatch")
        pack.require(set(part) == {"sourceCatalogSha256", "candidateSha256", "locale", "rightsStatus", "nativeSpeakerQA", "part", "startIndex", "entries"}, "Unknown editorial part fields")
        pack.require(part["sourceCatalogSha256"] == source_manifest["sha256"] and part["candidateSha256"] == expected_candidate_sha256 and
                     part["locale"] == "en" and part["rightsStatus"] == "source_rights_review_pending" and part["nativeSpeakerQA"] is False,
                     "Incompatible editorial source, locale, rights or QA metadata")
        pack.require(type(part["part"]) is int and part["part"] == index and type(part["startIndex"]) is int and part["startIndex"] == len(rows) and isinstance(part["entries"], list), "Editorial part sequence changed")
        rows.extend(part["entries"])
        verified_parts.append(member)
    pack.require(type(manifest["entries"]) is int and len(rows) == manifest["entries"] == len(sources) == len(originals["entries"]), "Editorial entry coverage mismatch")
    found = set()
    converted = {}
    for row in rows:
        pack.require(set(row) == {"entityKind", "stableId", "parentStableId", "meaningVersion", "sourceRecordSha256", "localizedLabel", "localizedDescription", "localizedAliases"}, "Unknown editorial row fields")
        identity = (row["entityKind"], row["stableId"])
        pack.require(identity in sources and identity not in found, "Unknown or duplicate editorial identity")
        found.add(identity)
        parent, source, key = sources[identity]
        pack.require(row["parentStableId"] == parent and type(row["meaningVersion"]) is int and row["meaningVersion"] == key["meaningVersion"], "Editorial source identity/version changed")
        pack.require(row["sourceRecordSha256"] == pack.digest(canonical(source)), "Editorial source-record hash mismatch")
        aliases = row["localizedAliases"]
        expected_aliases = source.get("aliases", []) if row["entityKind"] == "type" else []
        pack.require(isinstance(aliases, list) and len(aliases) == len(expected_aliases), "Editorial source alias coverage changed")
        # Null on AXIS/VALUE means no description, not an untranslated TYPE definition.
        description = row["localizedDescription"]
        if description is None:
            pack.require(row["entityKind"] != "type" or not source.get("definition", ""), "Missing editorial TYPE description")
            description = ""
        converted[pack.identity(key)] = {"key": key, "text": {"label": row["localizedLabel"], "aliases": aliases, "description": description}}
    pack.require(found == sources.keys(), "Missing editorial source identities")
    payload = {"schemaVersion": 1, "entries": [converted[pack.identity(row["key"])] for row in originals["entries"]]}
    counts = pack.validate_payload(payload, originals)
    report = {**counts, "sourceCatalogSha256": source_manifest["sha256"], "sourceRecordsVerified": len(found),
              "inputManifestSha256": expected_manifest_sha256, "parts": verified_parts,
              "producerCandidateSha256": expected_candidate_sha256, "canonicalCandidateBytesVerified": False,
              "canonicalCandidateReconstructionRequired": False,
              "rightsStatus": "source_rights_review_pending", "nativeSpeakerQA": False, "installed": False}
    return payload, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog-dir", type=Path, default=Path(__file__).resolve().parents[1] / "catalog")
    parser.add_argument("--parts-dir", type=Path, required=True)
    parser.add_argument("--expected-manifest-sha256", required=True)
    parser.add_argument("--expected-candidate-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        payload, report = adapt(args.catalog_dir, args.parts_dir, args.expected_manifest_sha256, args.expected_candidate_sha256)
        raw = pack.encoded(payload)
        with args.output.open("xb") as stream:
            stream.write(raw)
        report["payload"] = {"bytes": len(raw), "sha256": pack.digest(raw)}
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Editorial adaptation refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
