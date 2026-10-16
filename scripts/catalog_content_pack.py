#!/usr/bin/env python3
"""Prepare display-only catalog sidecars; never install, translate or modify source data.

`source` emits the exact schema-1 identity/presentation rows for editorial adaptation.
`package` accepts only that payload shape, preserves its original UTF-8 bytes, checks
complete coverage and writes a new, uninstalled pack with source rights still pending.
No Kotlin/Android toolchain or third-party Python package is needed.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys


CATALOG_ID = "tracker.catalog"
MAX_PAYLOAD = 16 * 1024 * 1024


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON object key: {key}")
        result[key] = value
    return result


def read_json(path, limit=MAX_PAYLOAD):
    with path.open("rb") as stream:
        raw = stream.read(limit + 1)
    require(len(raw) <= limit, f"File exceeds {limit} bytes: {path.name}")
    return raw, json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                           parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"Invalid JSON number: {value}")))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def source(catalog_dir):
    _, manifest = read_json(catalog_dir / "manifest.json", 16 * 1024)
    require(manifest["formatVersion"] == 1 and manifest["file"] == "runtime-catalog.json", "Unsupported source manifest")
    raw, vocabulary = read_json(catalog_dir / manifest["file"])
    require(len(raw) == manifest["bytes"] and digest(raw) == manifest["sha256"], "Source catalog size/hash mismatch")
    rows = []

    def add(kind, row, label, parent=None):
        rows.append({"key": {"kind": kind, "stableId": row["id"],
                              "meaningVersion": 1 if kind == "UNIT" else row.get("meaningVersion", 1),
                              "parentId": parent["id"] if parent else None,
                              "parentMeaningVersion": parent.get("meaningVersion", 1) if parent else None},
                     "text": {"label": label, "aliases": row.get("aliases", []) if kind == "TYPE" else [],
                              "description": row.get("definition", "") if kind == "TYPE" else ""}})

    for row in vocabulary.get("types", []):
        add("TYPE", row, row["name"])
    for axis in vocabulary.get("axes", []):
        add("AXIS", axis, axis["name"])
        for value in axis.get("values", []):
            add("VALUE", value, value["label"], axis)
    for row in vocabulary.get("metrics", []):
        add("METRIC", row, row["name"])
    for row in vocabulary.get("units", []):
        add("UNIT", row, row["name"])
    return manifest, {"schemaVersion": 1, "entries": rows}


def identity(key):
    require(isinstance(key, dict) and set(key) <= {"kind", "stableId", "meaningVersion", "parentId", "parentMeaningVersion"}, "Unknown catalog identity fields")
    require(key.get("kind") in {"TYPE", "AXIS", "VALUE", "METRIC", "UNIT"}, "Unknown catalog kind")
    require(isinstance(key.get("stableId"), str) and key["stableId"], "Missing stable ID")
    require(type(key.get("meaningVersion")) is int and key["meaningVersion"] > 0, "Invalid meaningVersion")
    if key["kind"] == "VALUE":
        require(isinstance(key.get("parentId"), str) and key["parentId"] and
                type(key.get("parentMeaningVersion")) is int and key["parentMeaningVersion"] > 0, "VALUE requires its exact axis ID/version")
    else:
        require(key.get("parentId") is None and key.get("parentMeaningVersion") is None, "Only VALUE has a parent-qualified key")
    return tuple(key.get(field) for field in ("kind", "stableId", "meaningVersion", "parentId", "parentMeaningVersion"))


def validate_payload(value, original):
    require(isinstance(value, dict) and set(value) <= {"schemaVersion", "entries"} and type(value.get("schemaVersion", 1)) is int and value.get("schemaVersion", 1) == 1,
            "Expected CatalogContentPayload schema 1; adapt editorial wrappers explicitly")
    require(isinstance(value.get("entries"), list), "Missing entries")
    wanted = {identity(row["key"]): row["text"] for row in original["entries"]}
    found = set()
    aliases = descriptions = 0
    counts = Counter()
    for row in value["entries"]:
        require(isinstance(row, dict) and set(row) == {"key", "text"}, "Expected key/text entry")
        key = identity(row["key"])
        require(key not in found, f"Duplicate catalog identity: {key}")
        require(key in wanted, f"Unknown catalog identity: {key}")
        found.add(key)
        text = row["text"]
        require(isinstance(text, dict) and set(text) <= {"label", "aliases", "description"}, f"Unknown presentation fields: {key}")
        require(isinstance(text.get("label"), str) and text["label"].strip(), f"Blank label: {key}")
        row_aliases = text.get("aliases", [])
        description = text.get("description", "")
        require(isinstance(row_aliases, list) and all(isinstance(a, str) and a.strip() for a in row_aliases), f"Invalid aliases: {key}")
        require(len(row_aliases) >= len(wanted[key]["aliases"]), f"Incomplete aliases: {key}")
        require(isinstance(description, str) and (not wanted[key]["description"].strip() or description.strip()), f"Missing description: {key}")
        aliases += len(row_aliases)
        descriptions += bool(description.strip())
        counts[key[0]] += 1
    require(found == wanted.keys(), f"Incomplete source identity coverage: {len(found)}/{len(wanted)}")
    return {"entries": len(found), "kinds": dict(sorted(counts.items())), "aliases": aliases,
            "descriptions": descriptions, "textFields": len(found) + aliases + descriptions}


def locale_fallbacks(tag):
    # Preparation intentionally supports canonical language[-Script][-REGION] only.
    # Runtime supports broader BCP-47 tags. No platform-dependent locale inference here.
    require(tag.split("-")[0] not in {"und", "iw", "in", "ji"} and
            re.fullmatch(r"[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?", tag) is not None,
            "Preparation requires canonical language[-Script][-REGION] locale")
    parts = tag.split("-")
    if parts[0] == "zh":
        return ["zh-" + parts[1]] if len(parts) > 2 and parts[1] in {"Hans", "Hant"} else []
    return ["es-419", "es"] if tag == "es-MX" else [parts[0]] if len(parts) > 1 else []


def package(args):
    manifest, original = source(args.catalog_dir)
    raw, value = read_json(args.labels)
    require(len(raw) > 0, "Empty payload")
    if args.expected_sha256:
        require(digest(raw) == args.expected_sha256, "Editorial candidate SHA-256 mismatch")
    summary = validate_payload(value, original)
    fallbacks = locale_fallbacks(args.locale)
    for field in (args.release, args.authority, args.translator):
        require(field.strip(), "Release, authority and translator must be nonblank")
    require(not args.reviewed or args.editor and args.editor.strip(), "REVIEWED requires an explicitly named editor")
    metadata = {"schemaVersion": 1, "catalogId": CATALOG_ID, "catalogRevision": manifest["catalogVersion"],
                "catalogSha256": manifest["sha256"], "localeTag": args.locale, "sourceLocaleTag": "ru",
                "release": args.release, "quality": "REVIEWED" if args.reviewed else "DRAFT", "complete": True,
                "fallbackTags": fallbacks, "authority": args.authority, "license": None,
                "rightsStatus": "source_rights_review_pending", "translator": args.translator, "editor": args.editor,
                "payload": {"file": "labels.json", "bytes": len(raw), "sha256": digest(raw)}}
    require(len(encoded(metadata)) <= 64 * 1024, "Manifest exceeds runtime byte limit")
    # Create-only destination: existing deliveries/locales cannot be overwritten by this tool.
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "labels.json").write_bytes(raw)
    (args.output / "manifest.json").write_bytes(encoded(metadata))
    require((args.output / "labels.json").read_bytes() == raw, "Payload readback differs")
    return {**summary, "payload": metadata["payload"], "localeTag": args.locale,
            "quality": metadata["quality"], "rightsStatus": metadata["rightsStatus"],
            "eligibleForDisplay": args.reviewed, "installed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("source", help="Emit source labels in the exact runtime payload schema")
    export.add_argument("--catalog-dir", type=Path, default=Path(__file__).resolve().parents[1] / "catalog")
    export.add_argument("--output", type=Path, required=True)
    build = commands.add_parser("package", help="Validate and preserve a complete editorial payload; no installation or rights clearance")
    build.add_argument("--catalog-dir", type=Path, default=Path(__file__).resolve().parents[1] / "catalog")
    build.add_argument("--labels", type=Path, required=True)
    build.add_argument("--expected-sha256")
    build.add_argument("--locale", required=True)
    build.add_argument("--release", required=True)
    build.add_argument("--authority", required=True)
    build.add_argument("--translator", required=True)
    build.add_argument("--editor")
    build.add_argument("--reviewed", action="store_true")
    build.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "source":
            manifest, value = source(args.catalog_dir)
            summary = validate_payload(value, value)
            with args.output.open("xb") as output:
                output.write(encoded(value))
            result = {**summary, "catalogId": CATALOG_ID, "catalogRevision": manifest["catalogVersion"],
                      "catalogSha256": manifest["sha256"], "sourceLocaleTag": "ru"}
        else:
            result = package(args)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Catalog pack refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
