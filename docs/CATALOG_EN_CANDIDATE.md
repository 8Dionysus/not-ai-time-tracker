# English catalog candidate: source-bound preparation

`catalog/editorial/en/{manifest.json,labels.json}` is a complete schema-1 candidate for the existing `CatalogContentPacks` loader. It is not installed or bundled into Android assets: the current asset task includes `locales/**`, not `editorial/**`, and no runtime locale index or UI resource is changed. `REVIEWED` describes the coordinator-reported editorial review. Source/catalog rights remain pending, license is null, and native-speaker QA remains false. The existing loader continues to use Russian source fallback until its explicit rights/quality/coverage conditions are met. Application-code Apache licensing does not cover this content; see NOTICE.

## Verified input and output

- Input repository `8Dionysus/not-ai-time-tracker`, delivery branch `delivery/tracker-catalog-en-20261015-865e5f94`, pinned commit `349b35b334078d5b00f4d0b9839872e815012174`.
- `editorial-input/en-input-parts-manifest.json`: SHA256 `263d4ce622e8fde8987652f7c1a3a803f4d8f84e3129ef05843a7499b35c7d0e`. All four member bytes/hash pairs independently match the coordinator's supplied values.
- Runtime source: revision `0.9.1.3`, SHA256 `9efaea5313b40ed2ae762437450f1a69759bfdd1e5fe1bb28e7ca2f8f5dc36b3`.
- All 2,385 source-record hashes, exact identities, taxonomy parent IDs and meaningVersions verified: 1,918 TYPE, 337 AXIS and 130 VALUE entries. There are 2,490 aliases and 1,918 descriptions, for 6,793 translated text fields. All text is copied literally from the checked parts; no translation or editing is performed here.
- Output `labels.json`: 1,184,934 UTF-8 bytes, SHA256 `aee5fe5bb39498121f7367b78b6bec84ab62ed4005c7bbf062805cc056638fc8`.
- The corrected `activity.errands.goods_purchase` description retains "consumer or capital goods" exactly as supplied by the latest reviewed delivery.

`provenance.json` retains the complete input hashes and verification state. The producer's canonical full JSONL candidate SHA256 is `695cb73d1f9c6f78b448a229a36a35e0d52d9a6a512f7e91638a33b26234e371`. It identifies the coordinator-reviewed original set. The coordinator explicitly confirmed that verifying exact part hashes and all source-bound projection fields is sufficient for this reduced transport: reconstructing the original full JSONL bytes is not required. This preparation does **not** claim that its different schema/serialization reproduces those original bytes/hash. Every transmitted part and referenced source record is independently verified. No hash from the earlier English revision is reused as the latest candidate or the runtime payload hash.

## Exact identity adaptation

Source-record hashing is UTF-8 compact JSON with sorted object keys. TYPE and AXIS use the complete original runtime object; VALUE uses its original object plus `axisId`. This recipe matches all 2,385 supplied source hashes.

Editorial TYPE `parentStableId` is the taxonomy parent, checked against the original object. It is not copied into `CatalogContentKey.parentId`, which is null for TYPE/AXIS. Editorial VALUE `stableId` is `axisId/rawValueId`; it maps through the exact original catalog to raw value ID, value meaningVersion, separate parent axis ID and parent meaningVersion. The adapter never guesses identity from labels or splits an arbitrary string to infer a value ID. Null AXIS/VALUE description becomes the runtime contract's empty string; TYPE descriptions and aliases must retain full source coverage.

## Reproduction and runtime verification

Obtain the pinned delivery branch into a separate source checkout. Then:

```sh
python3 -B scripts/catalog_editorial_adapter.py \
  --parts-dir /path/to/delivery-checkout/editorial-input \
  --expected-manifest-sha256 263d4ce622e8fde8987652f7c1a3a803f4d8f84e3129ef05843a7499b35c7d0e \
  --expected-candidate-sha256 695cb73d1f9c6f78b448a229a36a35e0d52d9a6a512f7e91638a33b26234e371 \
  --output /tmp/new-en-labels.json
python3 -B -m unittest discover -s scripts -p 'test_catalog_*py' -v
```

Eight host tests passed, including duplicate raw value IDs under different axes, taxonomy/content-key parent separation, Unicode preservation, source-hash/alias/description/version refusals and part-integrity/sequence/rights/QA refusal. They do not replace Kotlin execution. The added `LocaleContractTest.englishEditorialCandidateLoadsWithoutClaimingRightsOrReplacingOriginals` passed on owner A's established toolchain at B head51b2ec55422c04cf57db451aef3873d5e7bf97fd, together with all four LocaleContractTest cases (46s). Its original preparation checkout had no Kotlin toolchain. It loads the complete real candidate and verifies pending-rights source fallback and literal user overrides. Raw host/JVM proof and independent exact payload reproduction are in evidence/backend-b-english-2026-10-15. APK/device acceptance remains separately scoped; this fixture does not install or clear the candidate.

Opus can use the unchanged [display API contract](CATALOG_CONTENT_PACKS.md). Product asset/index binding, rights clearance and actual localized UI support are separate pending steps. User Cards/Favorites/free values, Dataset IDs/meaningVersions, historical classifications, JSON and CSV are unchanged by preparation.
