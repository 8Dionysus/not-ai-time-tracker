# Direction B: transfer and catalog follow-up

Base: `435c3be86f7597223addbcf15a1d2f2cb7ec94b8`. The existing full restore, CSV append, selective engine, saved controllers, lifecycle/recovery and lazy sidecar loader are retained. No replacement engine, Dataset/schema/Room/JSON version, UI, ViewModel, resources or Android lifecycle implementation is introduced here.

## Implemented remainder

- A catalog candidate declaring complete coverage must retain at least each source entry's alias count. Previously one translated alias could satisfy an entry with several source aliases, allowing incomplete content to advertise completeness. Validation still happens before replacing the installed immutable pack; refusal retains the previous pack. Additional editorial aliases are accepted.
- Selective export rejects excluded override keys that do not exist in selected series. Previously unknown or unselected keys were silently ignored while `OVERRIDES_EXCLUDED` counted them. Valid selections, serialized request hashes, operation IDs/receipts, resume, source ownership and Undo remain unchanged. The independent fixture is called by the existing shared ScenarioContract for Memory and Room runners.
- `scripts/catalog_content_pack.py` exports exact source identity/presentation rows and prepares complete, uninstalled schema-1 candidate packs from explicitly adapted payloads. Input labels.json bytes/hash are preserved. Rights stay pending and the source catalog is never rewritten. The static locale manifest now documents full parent-qualified identities.
- FILE_IMPORT.md's stale format9 claim is corrected to the actual format11 writer/legacy1–11 reader.

## Exact Opus/editorial contract

See [CATALOG_CONTENT_PACKS.md](CATALOG_CONTENT_PACKS.md). `catalogId=tracker.catalog`, `catalogRevision=0.9.1.3`, source SHA256 `9efaea5313b40ed2ae762437450f1a69759bfdd1e5fe1bb28e7ca2f8f5dc36b3`. There are 1,918 TYPE, 337 AXIS and 130 parent-qualified VALUE rows, with 2,490 aliases and 1,918 definitions (6,793 text fields). Metrics/units are currently empty. Schema1 payload is `{schemaVersion:1,entries:[{key:{kind,stableId,meaningVersion,parentId,parentMeaningVersion},text:{label,aliases,description}}]}`. Parent fields are null for non-VALUE entries. See the preparation tool's `source` output for all exact identities/text.

`getLocalizedCatalogEntries` is display-only. Pass one captured Dataset, keep returned display fields out of persisted classification/history/JSON/CSV, and show custom Card/Favorite/free values literally. Language changes do not mutate data, IDs, meaningVersion or tokens. Bundling is not installation/review/rights clearance. Pending rights continue to use source fallback with provenance; no pack is added to the runtime index by this pass.

## Verification and external dependencies

Host preparation tests: five passed, covering the pinned full source, exact-byte readback, partial aliases, duplicate/unknown parent-qualified identities, hash/UTF-8/review refusal, fallback script boundaries and create-only output. The first run found acceptance of `und`; that preparation bug was fixed and the final run passed. `git diff --check` passed.

Kotlin/JVM, Room/Robolectric, APK and device checks have not run in this checkout: the selected workspace had no saved Kotlin/Gradle/SDK tools. No new compiler/SDK/emulator was installed. Required integration check on the established toolchain is `:core:test --tests '*LocaleContractTest' --tests '*CoreScenariosTest'` and `:app:testDebugUnitTest --tests '*RoomScenariosTest'`, followed by the existing final build checks. Do not treat host Python checks as runtime proof or replace earlier producer counts with new counts.

English editorial Library input `libfile_865e5f940e2481918d89b0a06537bde8`, tracker-catalog-editorial.zip v0, reports 11,669,803 bytes and producer SHA256 `29cfb9d1f6f1b92e1a602ce042a253d6890aa15443def4647958a91c40523137`. Materialization returned a download route, but the cloud proxy refused it with HTTP403 before bytes arrived; Library text read has no readable ZIP content. No local ZIP/hash/member validation or English pack integration is claimed. Expected en.jsonl SHA256 from coordinator is `5b142c273c748829f7d03e11deb28092fba5973debfa48818f72f5db7cb76f41`; exact bytes or a readable standalone Library text member are required before adaptation. Other languages remain with the editorial owner. Source/catalog rights remain unknown/pending under NOTICE.

Publish only the separate branch for review; main integration is sequential through owner A/coordinator. The handoff artifact records exact final commits/hashes and any later evidence; this document does not claim main acceptance.

## Follow-up: English input obtained through Git

The ZIP-download failure above remains a historical access result, but it no longer blocks English preparation. Four editorial parts and their manifest were fetched through the authorized delivery branch at `349b35b334078d5b00f4d0b9839872e815012174`. All member hashes, all 2,385 runtime source-record hashes and all 6,793 translated fields were verified. The latest input includes the consumer/capital goods source-fidelity correction. [CATALOG_EN_CANDIDATE.md](CATALOG_EN_CANDIDATE.md) records exact input/output hashes, identity adaptation and provenance. The coordinator confirmed that this reduced transport requires exact part/source/projection verification; the original canonical JSONL hash remains provenance, with no reconstruction claim or reconstruction blocker.

The source candidate under `catalog/editorial/en` is compatible with the existing loader but excluded from the existing Android asset task, absent from the runtime locale index, and still carries pending rights/null license/nativeSpeakerQA=false. No product/UI locale installation is claimed. The added complete-candidate Kotlin fixture remains pending owner A's established toolchain. Eight host preparation/adapter tests passed; no new Kotlin compiler/SDK/emulator was installed. A separate host follow-up also refuses iw/in/ji language aliases that Java canonicalizes, and all 19 planned tags were checked against the installed Java Locale.Builder.

## Runtime guard verification reported by owner A

Owner A confirmed the first runtime patch on the established toolchain: four JVM tests and one Room test passed, with zero errors/skips. This is owner A's reported execution, not a local Kotlin run in this checkout; raw A logs are not included in this source handoff. The new full English candidate fixture `LocaleContractTest.englishEditorialCandidateLoadsWithoutClaimingRightsOrReplacingOriginals` has not yet run and remains pending. Host Python checks do not close it. APK/device acceptance remains separate. Publication of the separate B branch is authorized for sequential integration through A/coordinator; this does not imply a main merge or EN installation.

## Sequential integration verification by owner A

Owner A fetched the published B head51b2ec55422c04cf57db451aef3873d5e7bf97fd and independently reproduced the exact EN labels bytes from pinned delivery349b35b. All four parts,2,385 source-record hashes/identities and6,793 fields passed; payload SHA256aee5fe5bb39498121f7367b78b6bec84ab62ed4005c7bbf062805cc056638fc8 matches the committed1,184,934bytes. Eight host tests and four LocaleContractTest JVM tests, including englishEditorialCandidateLoadsWithoutClaimingRightsOrReplacingOriginals, passed on the established toolchain (46s). Raw proof is in evidence/backend-b-english-2026-10-15. Rights remain pending/null license, candidate stays outside Android assets/index, no linguistic/native-speaker QA or canonical JSONL reconstruction is inferred. These results supersede the earlier pending-runtime statement only for this precise fixture.

The host adapter now accepts an explicit locale while retaining EN as its default; the same identity/hash/coverage checks prepare ES and later inputs without a second pipeline. Nine host tests passed, including refusal of a mismatched part locale. Re-adapting the exact EN input with the explicit locale reproduced the existing payload bytes/hash. ES preparation remains dependent on delivery of its exact parts/manifest/pin; no ES content or installation is claimed.

## Follow-up: Spanish source draft prepared

ES input subsequently arrived at pin `c3b4bd70bf83de6db7f9e73efecb710295fd6b1e`; the input dependency above is resolved. All four part hashes, all2,385 source-record hashes and all6,793 literal projection fields were verified. `catalog/editorial/es` retains DRAFT quality, source rights pending/null license, nativeSpeakerQA=false and the coordinator's completed semantic-review report without promoting draft provenance. It is outside Android assets/index. The adapter explicitly validates the new source-bound manifest shape alongside the original minimal EN shape; ten host tests passed. Both real-candidate JVM fixtures remain pending owner A; the ES fixture reuses the EN helper and preserves the existing EN test name. [Exact candidate table and remaining checks](CATALOG_EDITORIAL_CANDIDATES.md).

## Owner A EN+ES verification at B head3ae129e

A independently reproduced the exact ES candidate from pinned c3b4bd7:1,265,051bytes/SHA256bc11a626cd9850170d225f9169db728066ebc787f0d08612eaf481de277be35d, all four parts/2,385 source records/6,793 fields. The generalized adapter reproduces EN bytes unchanged. Ten Python tests and five LocaleContractTest JVM cases, including both full EN/ES loader fixtures, passed on the existing toolchain in1m01s. Evidence is in evidence/backend-b-spanish-2026-10-15. This resolves the two pending JVM checks above, not rights/QA/installation/device acceptance. ES remains DRAFT. FR input remains incomplete; the coordinator-reported rejected part4 publication is not retried or routed through this integration.
