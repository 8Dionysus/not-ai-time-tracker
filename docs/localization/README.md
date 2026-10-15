# Tracker localization handoff

The reviewed editorial package is stored in this branch so Opus can start from main.

- File: [tracker-localization-12-locales-editorial.zip](tracker-localization-12-locales-editorial.zip)
- SHA-256: `eca102490bb89f163141813043e59e351d6bad8d1c0b659027371d25a52cc310`
- Size: 1,911,097 bytes; 93 files, including a manifest with 92 member hashes.
- Pinned backend: `0980c50954a201229c90008610328abd4369aa08`.
- Russian source plus EN, ES, FR, pt-BR, DE, IT, AR, zh-Hans, JA, KO and HI.
- Each translated base has 376 units / 402 variants. Composition supplements and required correction overlays cover the 49 composed units. All 64 typed import issue codes have resources for RU and the 11 translated locales.

Extract into a new working directory and begin with LOCALIZATION-README.md, localization-package-manifest.json and localization-integration-handoff.md. Verify member hashes before using the package. Frozen historical CSV-blocked records are superseded only by the reviewed csv-composition-resolution-overlay.json; do not silently delete their history.

These are reviewed editorial data, not wired Android resources or a claim of runtime locale support. Opus still needs to integrate locale preference, typed rendering, all UI surfaces, RTL/formatters and accessibility, then build and check on devices. Source-copy claims about local-only storage must reflect explicit SAF export. New Schedule/Retention/CopyCleanup surfaces need their own typed UI mapping. Do not translate user values, headers, IDs or technical diagnostics by substring matching.

The 65th typed import issue, SOURCE_LOCALE_INVALID, is specified separately in
[backend-typed-delta.json](backend-typed-delta.json): exact resource key, typed
arguments, stage, field path, source drafts and required caller action. It is not
silently added to the reviewed 64-issue package, whose bytes/hash stay unchanged.
The new RU/EN source copy still needs editorial review; no additional reviewed
translations or wired UI resources are claimed.

## Editorial v2 availability

The same Library item [libfile_c64225fb34008191bc6f58320026783f](https://chatgpt.com/api/library/files/libfile_c64225fb34008191bc6f58320026783f/download) reports version2: 1,879,824 bytes, producer SHA256 b867a645cf7aac335d1fde0f2408927bdfafbb2f70d0c50acad0629890fc1c0b. Parent reports97 ZIP entries/96 payload hashes, with48 added draft rows for4 keys ×12 locales and prior bytes preserved in history. This task read v2 metadata but could not obtain its bytes: signed download host is blocked by the cloud proxy (CONNECT403), direct resolution unavailable; no local v2 CRC/member validation is claimed. The repository ZIP above remains exact v1 until v2 can be materialized and verified. Do not overwrite it with an unverified placeholder.

The new linked-session refusal is a separate [RU/EN source draft](backend-schedule-link-delta.json), not included in the e1cb-bound v2 supplement. Full preset catalog translations are a separate editorial delivery; the backend [sidecar format](../CATALOG_CONTENT_PACKS.md) is ready. No draft pack/empty folder is advertised as supported.

The coordinator separately transmitted [backend-delta-editorial-2026-10-15.json](backend-delta-editorial-2026-10-15.json) as exact text. Local UTF-8 verification:24,331bytes; SHA2562e44813f4db7a6579a08453e23d029d244f749ab426bd76a8efe501a660de04f. JSON parse and4keys×12locales/48rows verified. This closes delivery of that single editorial delta document, not ZIPv2 byte/CRC validation, Android resources or native-speaker QA.
