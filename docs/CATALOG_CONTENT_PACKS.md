# Catalog sidecar contract, schema 1

Backend `CatalogContentPacks` and Android `TrackerRuntime.catalogContent` load display content independently of Dataset, Room11, full JSON13 and CSV. They never write a translated name into historical classifications or user objects. UI wiring belongs to Opus. Empty/uninstalled/draft packs do not count as supported translations. No translation content is invented by this adapter.

Each locale has `catalog/locales/<canonical-BCP47-tag>/manifest.json` and `labels.json`. `catalog/locales/index.json` is `{ "schemaVersion": 1, "locales": ["en", "es-419"] }`, listing only folders actually bundled. Until editorial delivery is validated there is no installed locale claim. Gradle includes `locales/**` as data assets; it excludes source research. Current source has 1,918 TYPE, 337 AXIS and 130 VALUE entries; metrics/units are empty. All 2,385 exact identities must occur once in a complete pack. 130 values have only 53 raw IDs: never key by raw value ID.

Manifest fields (case-sensitive Kotlin enum names):

```json
{
  "schemaVersion": 1,
  "catalogId": "tracker.catalog",
  "catalogRevision": "0.9.3.0",
  "catalogSha256": "4a3505ad79e0c204137ef68e0835ef01a771736716cf508012008ff927418bc2",
  "localeTag": "es-419",
  "sourceLocaleTag": "ru",
  "release": "editorial-release-identifier",
  "quality": "DRAFT",
  "complete": false,
  "fallbackTags": ["es"],
  "authority": "named editorial authority",
  "license": null,
  "rightsStatus": "source_rights_review_pending",
  "translator": "named translator or process/revision",
  "editor": null,
  "payload": {"file": "labels.json", "bytes": 123, "sha256": "exact-lowercase-64-hex-sha256"}
}
```

The example is a format illustration, not an installable or reviewed pack. `bytes` and SHA cover exact UTF-8 labels.json bytes. A REVIEWED manifest needs a nonempty editor; review remains an editorial release decision, never inferred from hash correctness. Candidate license may be null and rightsStatus defaults to source_rights_review_pending. Source/catalog rights are separate from translation quality; they never inherit Apache from application code. Complete coverage and REVIEWED editorial quality determine runtime display readiness. Pending rights metadata and nullable license remain inspectable provenance; they are not a blanket runtime veto. Runtime readiness does not grant publication rights or clear content licensing. This follows the accepted full semantic-review decision; native-speaker QA remains separate. `complete=true` requires every exact source key, nonempty labels, at least the source alias count for each entry, and description where source definition exists. Additional editorial aliases are allowed; a single alias cannot stand in for several missing source aliases. `fallbackTags` must equal the documented chain excluding the requested tag: es-MX→[es-419,es], es-419→[es], pt-BR/pt-PT→[pt], zh-Hans→[], zh-Hant→[]. Hans/Hant never cross. Generic ja/ko/ar/hi/etc are allowed; canonical tags and exact source catalog revision/hash are mandatory. Updating source requires a new compatible pack, not guessed ID reassignment.

Payload is `{ "schemaVersion": 1, "entries": [ { "key": { "kind": "VALUE", "stableId": "low", "meaningVersion": 1, "parentId": "the-exact-axis-id", "parentMeaningVersion": 1 }, "text": { "label": "…", "aliases": [], "description": "" } } ] }`. TYPE/AXIS/METRIC/UNIT keys use null parent fields. VALUE includes the axis ID and axis meaningVersion. `meaningVersion` belongs to the entry itself; never change IDs or versions to match display strings. UNIT uses version1 (the current unit model has no version field). TYPE aliases and definition map to text.aliases and text.description; AXIS/VALUE currently have labels only. Exact source rows can be obtained from `sourceEntries()`.

`getLocalizedCatalogEntries(catalogId, keys, requestedLocale, overrides)` preserves input order/cardinality and returns identity, requested/resolved/source locale, original and display label/aliases/description, missing, userOverride, quality, availability and full manifest provenance. It resolves one complete REVIEWED pack for the whole batch, then source. It never mixes arbitrary available entry locales. Draft/incomplete metadata remain inspectable without being displayed as reviewed content. NOT_INSTALLED is distinct from DRAFT and from AVAILABLE_OFFLINE. No pack signature/authenticity is claimed: hashes verify bytes against the manifest only.

Android `catalogContent.getLocalizedCatalogEntries(keys, locale, capturedDataset)` compares exact source presentation and preserves literal local overrides. Personal Cards/Favorites, custom type/axis/metric IDs absent from this exact source, tags, imported labels, free facet values and notes stay outside preset translation and display literal domain presentation. Unknown/foreign preset keys refuse rather than being matched by name. Callers pass the same captured Dataset to avoid mixed revisions. Source text and translated aliases are separate search inputs; selection persists exact domain IDs/values only. Android reads only the index on construction, then lazily reads the requested locale/fallback payloads until a ready match. health().unverifiedBundledPacks distinguishes index-listed bytes from validated installedReviewedPacks; merely listing a folder does not assert integrity/review/rights. Requested packs stay cached for this runtime; no background download or eager all-locale payload load occurs. No resources/LocaleConfig/visible selector or UI search wiring is changed here.

A manifest declaring complete=true must retain at least the source alias count for each exact identity, in addition to full key coverage and nonblank required descriptions. A nonempty subset is insufficient. Rejected replacement bytes leave the prior installed complete pack intact; there is no mutation to source aliases or user overrides. The check proves structural coverage, not editorial quality or rights clearance.

## Reproducible editorial preparation

Use the standard-library-only [preparation tool](../scripts/catalog_content_pack.py) before handing payloads to the runtime loader:

```sh
python3 scripts/catalog_content_pack.py source --output /tmp/tracker-source-labels.json
python3 scripts/catalog_content_pack.py package --labels /path/to/labels.json \
  --expected-sha256 EXACT_LABELS_SHA256 --locale en --release EDITORIAL_RELEASE \
  --authority EDITORIAL_AUTHORITY --translator TRANSLATOR --output /tmp/new-en-pack
```

`source` emits `CatalogContentPayload`, with Russian original text, for explicit adaptation by stable identity. It does not translate anything. Current source totals are 2,385 labels + 2,490 aliases + 1,918 descriptions = 6,793 text fields. Payload JSON and source JSONL are different byte formats: an editorial JSONL hash must be checked before adaptation and must not be reused as `labels.json` hash. Unknown editorial wrappers/fields refuse; do not guess identity fields or match translated names. VALUE identity includes both parent fields. No ID or meaningVersion conversion is performed.

`package` validates full coverage and source bytes/hash, preserves the supplied labels.json bytes exactly, and creates a new directory. It never overwrites an existing delivery or modifies `catalog/locales/index.json`. Default quality is DRAFT. `--reviewed --editor NAME` records an explicit editorial decision; it does not perform review. Every generated manifest keeps `license=null` and `rightsStatus=source_rights_review_pending`, including editorial REVIEWED candidates, so `eligibleForDisplay` follows the explicit reviewed decision and `installed=false`. Rights clearance remains an external decision; this tool offers no flag to manufacture it. Preparation accepts canonical language[-Script][-REGION] tags; the Kotlin runtime accepts broader canonical BCP-47 tags. Legacy Java language aliases iw/in/ji refuse instead of producing an incompatible manifest; callers must explicitly provide he/id/yi. Undefined language tags refuse.

Run `python3 -B -m unittest discover -s scripts -p 'test_catalog_content_pack.py' -v` for host preparation checks. These checks do not certify Kotlin compilation, Android asset loading, translation quality or rights. The runtime regression remains in `LocaleContractTest` and verifies that an incomplete update refuses without replacing the previous loaded pack.

The [English source candidate](CATALOG_EN_CANDIDATE.md) is prepared separately under `catalog/editorial/en`, with all editorial parts and source-record hashes checked by [catalog_editorial_adapter.py](../scripts/catalog_editorial_adapter.py). It is not in the Android asset/index path and does not count as an installed display locale. Its full-candidate loader fixture passed on the established JVM toolchain at B head51b2ec5 (four LocaleContractTest cases); host adaptation checks and JVM loading do not provide rights, linguistic or Android/device acceptance.

The same adapter accepts `--locale es` (or another explicit canonical tag) for the same source-bound transport. It reads `<locale>-input-parts-manifest.json` and checks `<locale>-reviewed-part-N.json` members and matching per-part locale metadata. EN remains the default for existing commands. Supplying another tag does not translate text, install a pack or imply that its editorial files have arrived. Each delivery still requires its exact manifest/candidate hash, source identities, complete fields and pending rights/QA metadata.

[Prepared candidates](CATALOG_EDITORIAL_CANDIDATES.md) now include EN and ES source files, with exact input/output hashes and separate review/provenance states. ES retains quality DRAFT as requested; neither candidate is in the Android asset/index path. The adapter accepts the two explicitly checked minimal/source-bound manifest shapes, refuses unknown fields and verifies source/locale/rights/QA bindings on both the manifest where present and every part.

The later complete11-language delivery has a [private technical integration record](CATALOG_PRIVATE11_TECHNICAL_2026_10_16.md): all26235entries/74723fields independently source-bound checked, existing core/Android asset loaders tested, private unsigned APK byte-verified. New texts remain outside the public tree/APKs; raw DRAFT/nativeSpeakerQA=false/rights-pending provenance is retained; current private manifests record REVIEWED from the accepted independent semantic-review receipt, without inventing rights clearance. No additional translation pass is required by this technical integration.

## Existing ChoiceView search integration (6fcc4c2)

Capture a Dataset once, call `catalogContent.choiceLabels(locale, capturedDataset)`,
and attach the immutable overlay using `choiceView.withCatalogLabels(labels)`. The
overlay binds the Dataset token/barrier and exact type signatures. Wrong/stale
snapshots refuse; cursors belong to the resulting view. No domain mutation occurs.
TYPE titles and current path display use reviewed labels; Card/Favorite/History
personal titles remain literal. Existing search adds translated names/aliases of
the current linked type across these kinds, retaining original fields, ranking,
canonical IDs and command references. `MatchEvidence.localeTag` identifies the
resolved translated match. Missing/DRAFT/incomplete packs fall back; Hans/Hant do
not cross. Localized ancestor display and ID subtree filtering are implemented;
matching a query word against ancestor names is NOT implemented. Opus must attach
this overlay when building locale-aware views and rebuild views/cursors on a
confirmed locale or Dataset change. UI resources/LocaleManager/RTL stay with Opus.
