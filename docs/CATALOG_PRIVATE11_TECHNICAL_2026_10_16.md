# Private eleven-language technical integration, 16 October 2026

Input compact ZIP:7,087,345bytes,
SHA256 `6f0806c57a26a0ae35f4a2b8336ce6d97fbceb4eff2fed1933c2cd997625d944`.
Private Library text transport v1 was read in158 exact line windows (base64 lines5–78753,
END marker78754). Decoded size/hash, all55 ZIP CRCs and54 manifest member hashes
were independently checked. Source catalog bytes exactly match this repository.

Locales: en, es, fr, pt-BR, ar, zh-Hans, de, it, ja, ko, hi. Every locale has2385
entries and6793 fields: total26235entries/74723fields. The coordinator supplied a
completed independent semantic review; no retranslation or additional executor was
used. Every source-record hash, ID/version, pointer, parent identity, original text,
context and alias/description count was checked before adapting to schema1 sidecars.
VALUE keeps raw ID plus parent axis ID/version;130 values are not flattened to53 IDs.

Private commit `ee19355249ac800a2ebd53359888e147b31c506e` adds only these sidecars/index,
two dedicated integration fixtures and a private boundary note, over checked runtime
`c949a8f202e143151e216a8932956a7a93b001bc`. Production core/app/build files are unchanged.
This private branch and translated texts are excluded from the public source bundle
and public APKs. The preparation uses existing catalog_content_pack validation/package
and source-key helpers; no second importer, runtime translation or general engine.

```text
/workspace/tracker-tools/run-gradle :core:test --tests '*PrivateCatalogPackIntegrationTest*' :app:testDebugUnitTest --tests '*PrivateBundledCatalogIntegrationTest*' :app:assembleDebug -PtrackerUnsigned=true -PtrackerCleanupLibrary=/workspace/tracker-tools/libtracker-copy-android-root.so -Pkotlin.compiler.execution.strategy=in-process
```

BUILD SUCCESSFUL in3m47s,61tasks executed. Dedicated core1 + Android/Robolectric1
passed with zero failures/errors/skips, each covering all11 real packs. Existing
Python preparation checks:10passed. This is not another full regression of the
private branch; the unchanged runtime has the separate130core/99app result.

Verified: complete source-key/field coverage; existing core and Android asset loaders;
index-only lazy construction; each pack offline availability; exact payload bytes
in the APK; damaged replacement refusal with previous loaded pack retained;
personal overrides, source/history/timer Token/full JSON unchanged; no Hans/Hant
cross-fallback. No UI resources, LocaleConfig, selector, search or screen was changed.

Private unsigned debug APK:15,550,027bytes,
SHA256 `f5cbc58d0c8f85c9841b198d7db65e4082d2c0d35985faa4a632d4254248980d`.
CRC,16KiB alignment, unsigned signature refusal,min26/target36/noINTERNET passed.
All11 APK labels/manifest files exactly match prepared bytes.

The delivered records retain DRAFT_COMPLETE / DRAFT_NOT_NATIVE_REVIEWED. New manifests
preserve DRAFT; the coordinator's independent semantic-review receipt remains separate.
Native-speaker QA=false and source-rights pending/license=null are not silently changed.
These metadata do not block local packaging/testing. Existing production readiness
policy still returns canonical source plus inspectable DRAFT/offline provenance;
installedReviewedLocales remains empty. No fake rights clearance or ready UI locale
is asserted. The technical hookup is complete; production content-readiness and Opus
UI resource/locale/alias-search/RTL/device acceptance remain separate decisions.

Private package/evidence are retained outside the public repository; concrete external
delivery must preserve the agreed private-text boundary. No new language texts were
pushed publicly and no store release, signing key or real user-data import occurred.
