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
