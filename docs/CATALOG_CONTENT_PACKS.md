# Catalog sidecar contract, schema 1

Backend `CatalogContentPacks` and Android `TrackerRuntime.catalogContent` load display content independently of Dataset, Room9, full JSON11 and CSV. They never write a translated name into historical classifications or user objects. UI wiring belongs to Opus. Empty/uninstalled/draft packs do not count as supported translations. No translation content is invented by this adapter.

Each locale has `catalog/locales/<canonical-BCP47-tag>/manifest.json` and `labels.json`. `catalog/locales/index.json` is `{ "schemaVersion": 1, "locales": ["en", "es-419"] }`, listing only folders actually bundled. Until editorial delivery is validated there is no installed locale claim. Gradle includes `locales/**` as data assets; it excludes source research. Current source has 1,918 TYPE, 337 AXIS and 130 VALUE entries; metrics/units are empty. All 2,385 exact identities must occur once in a complete pack. 130 values have only 53 raw IDs: never key by raw value ID.

Manifest fields (case-sensitive Kotlin enum names):

```json
{
  "schemaVersion": 1,
  "catalogId": "tracker.catalog",
  "catalogRevision": "0.9.1.3",
  "catalogSha256": "9efaea5313b40ed2ae762437450f1a69759bfdd1e5fe1bb28e7ca2f8f5dc36b3",
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

The example is a format illustration, not an installable or reviewed pack. `bytes` and SHA cover exact UTF-8 labels.json bytes. A REVIEWED manifest needs a nonempty editor; review remains an editorial release decision, never inferred from hash correctness. Candidate license may be null and rightsStatus defaults to source_rights_review_pending. Source/catalog rights are separate from translation quality; they never inherit Apache from application code. Only rightsStatus=cleared with an explicit nonblank actual payload license, complete coverage and REVIEWED translation may be advertised or used as a reviewed display locale. Missing rights keep fallback/source and expose pending provenance even for reviewed editorial text. `complete=true` requires every exact source key, nonempty labels, aliases where source aliases exist, and description where source definition exists. `fallbackTags` must equal the documented chain excluding the requested tag: es-MX→[es-419,es], es-419→[es], pt-BR/pt-PT→[pt], zh-Hans→[], zh-Hant→[]. Hans/Hant never cross. Generic ja/ko/ar/hi/etc are allowed; canonical tags and exact source catalog revision/hash are mandatory. Updating source requires a new compatible pack, not guessed ID reassignment.

Payload is `{ "schemaVersion": 1, "entries": [ { "key": { "kind": "VALUE", "stableId": "low", "meaningVersion": 1, "parentId": "the-exact-axis-id", "parentMeaningVersion": 1 }, "text": { "label": "…", "aliases": [], "description": "" } } ] }`. TYPE/AXIS/METRIC/UNIT keys use null parent fields. VALUE includes the axis ID and axis meaningVersion. `meaningVersion` belongs to the entry itself; never change IDs or versions to match display strings. UNIT uses version1 (the current unit model has no version field). TYPE aliases and definition map to text.aliases and text.description; AXIS/VALUE currently have labels only. Exact source rows can be obtained from `sourceEntries()`.

`getLocalizedCatalogEntries(catalogId, keys, requestedLocale, overrides)` preserves input order/cardinality and returns identity, requested/resolved/source locale, original and display label/aliases/description, missing, userOverride, quality, availability and full manifest provenance. It resolves one complete REVIEWED pack with cleared explicit payload rights for the whole batch, then source. It never mixes arbitrary available entry locales. Draft/incomplete metadata remain inspectable without being displayed as reviewed content. NOT_INSTALLED is distinct from DRAFT and from AVAILABLE_OFFLINE. No pack signature/authenticity is claimed: hashes verify bytes against the manifest only.

Android `catalogContent.getLocalizedCatalogEntries(keys, locale, capturedDataset)` compares exact source presentation and preserves literal local overrides. Personal Cards/Favorites, custom type/axis/metric IDs absent from this exact source, tags, imported labels, free facet values and notes stay outside preset translation and display literal domain presentation. Unknown/foreign preset keys refuse rather than being matched by name. Callers pass the same captured Dataset to avoid mixed revisions. Source text and translated aliases are separate search inputs; selection persists exact domain IDs/values only. Android reads only the index on construction, then lazily reads the requested locale/fallback payloads until a ready match. health().unverifiedBundledPacks distinguishes index-listed bytes from validated installedReviewedPacks; merely listing a folder does not assert integrity/review/rights. Requested packs stay cached for this runtime; no background download or eager all-locale payload load occurs. No resources/LocaleConfig/visible selector or UI search wiring is changed here.
