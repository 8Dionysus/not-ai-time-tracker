# Prepared catalog candidates

These source-bound files are under `catalog/editorial/<locale>`, outside the current Android asset/index path. They are not installed UI/content locales. Source rights remain pending, license null and nativeSpeakerQA=false. Preparation copies the supplied editorial text literally and verifies all source identities and field coverage; it does not translate, linguistically review or clear rights.

| Locale | Retained quality | Payload bytes | labels.json SHA256 |
|---|---|---:|---|
| en | REVIEWED editorial candidate | 1,184,934 | `aee5fe5bb39498121f7367b78b6bec84ab62ed4005c7bbf062805cc056638fc8` |
| es | DRAFT, independent semantic review reported complete | 1,265,051 | `bc11a626cd9850170d225f9169db728066ebc787f0d08612eaf481de277be35d` |

Each has 2,385 exact entries: 1,918 TYPE, 337 AXIS, 130 parent-qualified VALUE; 2,490 aliases + 1,918 descriptions give 6,793 text fields. The canonical runtime catalog remains revision0.9.1.3 / SHA256 `9efaea5313b40ed2ae762437450f1a69759bfdd1e5fe1bb28e7ca2f8f5dc36b3`.

## Spanish input and checks

ES input was fetched from `delivery/tracker-catalog-en-20261015-865e5f94` at pin `c3b4bd70bf83de6db7f9e73efecb710295fd6b1e`. Exact manifest `editorial-input/es-input-parts-manifest.json` SHA256 is `a0fa6b91ecabc3fc91dd87061d2d241bc6aa604205234b076f27ab9f79af6ef5`. Its four exact member bytes/hash pairs match; all 2,385 source-record hashes and every one of the 6,793 copied projection fields were independently checked. Full member hashes and source pins are retained in `catalog/editorial/es/provenance.json`.

Producer candidate SHA256 `2db9965c2aed877151ed3c84b864832c83ddd70101c4a862cc23b500e7f03093` identifies the original coordinator-reviewed JSONL set. This reduced transport is verified by exact part hashes and source/projection fields; different runtime serialization does not reproduce or claim the original JSONL bytes/hash. The coordinator reports semantic review complete with open material0. Its requested draft provenance is retained: manifest quality remains DRAFT despite this review report. Native-speaker QA and source-rights clearance are not inferred.

```sh
python3 -B scripts/catalog_editorial_adapter.py --locale es \
  --parts-dir /path/to/delivery-checkout/editorial-input \
  --expected-manifest-sha256 a0fa6b91ecabc3fc91dd87061d2d241bc6aa604205234b076f27ab9f79af6ef5 \
  --expected-candidate-sha256 2db9965c2aed877151ed3c84b864832c83ddd70101c4a862cc23b500e7f03093 \
  --output /tmp/new-es-labels.json
```

The same adapter explicitly handles the first EN manifest's minimal `{parts,entries,sourceCandidateSha256}` shape and the new source-bound `{sourceCatalogSha256,candidateSha256,locale,rightsStatus,nativeSpeakerQA,entries,parts}` shape. Unknown/mixed fields refuse. Source-bound manifest metadata is checked in addition to every part's matching header and hash; no permissive ignore-unknown reader is added.

## Runtime verification and remaining acceptance

Ten host preparation tests passed, including source-bound manifest metadata refusal and wrong-part locale refusal. Re-adapting EN reproduces its previous payload unchanged. Owner A reported the original runtime guard checks as four JVM + one Room passed without errors/skips. Both real-candidate fixtures passed on A's existing toolchain at B head3ae129e414ce5903b3c998724faf62f9a27a9f94 (all five LocaleContractTest cases in1m01s):

- `LocaleContractTest.englishEditorialCandidateLoadsWithoutClaimingRightsOrReplacingOriginals`
- `LocaleContractTest.spanishEditorialDraftLoadsWithoutReplacingOriginals`

They share one helper and load the full real files, checking quality, pending-rights source fallback, regional requested locales and literal local overrides. The final merged core suite passes101 tests; raw source-bound reproduction,10host tests and5targeted JVM cases are in evidence/backend-b-spanish-2026-10-15. APK/device/UI/RTL/native-language acceptance remains outside these source checks. The existing Russian runtime catalog, Dataset, IDs/meaningVersions, user strings and JSON/CSV history are unchanged.

English-specific source provenance and its corrected consumer/capital goods description are in [CATALOG_EN_CANDIDATE.md](CATALOG_EN_CANDIDATE.md). Opus's unchanged backend API is in [CATALOG_CONTENT_PACKS.md](CATALOG_CONTENT_PACKS.md).
