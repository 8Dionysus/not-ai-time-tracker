# Catalog 0.9.2.0: human names, sections in place

What changed for the backend owner, and why. The catalog files are generated from a private editorial sheet; they are
not edited by hand.

## Data

| File | Change |
|---|---|
| `catalog/runtime-catalog.json` | 1850 Russian default names rewritten as short noun phrases. Slashes removed, English translated except proper names and common acronyms (HIIT, BMX, CAD, DAW, LEGO). 62 parents changed. One new type `activity.movement.exercise_snack` («Короткая разминка») under `area.movement`. Definitions, aliases, IDs, `selectable`, `meaningVersion` and suggestions are unchanged. |
| `catalog/compatibility.json` | 1889 type steps and the existing axis step. A step for an entry that also had a 0.9.1.3 step carries the older fingerprint in `alsoFromMeaningSha256` and the older default name in `previousDefaultNameSha256s`. |
| `catalog/locales/*`, `catalog/editorial/*` | Rebound to revision 0.9.2.0 and the new SHA. Each pack has one added entry for the new type. Existing translated labels are unchanged and still translate the earlier long wording. Shortening them is a separate editorial pass per language. |

Moves:

- Professional areas now live under `area.work`. Analysis, data, design, IT, software, legal, logistics, production, healthcare work, safety, business, administration and coordination.
- Digital 3D, audio, video and visual creation, plus collecting, now live under `area.creative`.
- Formal and self-directed education, teaching and research now live under `area.learning`.
- Language learning now lives under `area.language_practice`.
- Writing, editing and translation now lives under `area.writing`.
- Cultural visits and unstructured leisure now live under `area.leisure`.
- Citizen science now lives under `area.outdoor_leisure`.
- The former orphan root sports, the multi-sport compounds, the relay and the quilt project are in their sport or craft groups.

Colour slots stay the same for every moved area, because `Areas.slot` already grouped them this way.

## Core

- `CatalogEditorialRevision` has three new defaults:
  - `parentMove = false`. A changed `parentId` is accepted only with `parentMove = true`. Without it the import is a conflict, the same as before. Only `kind = "type"` may set it.
  - `alsoFromMeaningSha256 = []`
  - `previousDefaultNameSha256s = []`
- `editorialReplacement`:
  - When the installed name hash is one of the default-name hashes, the entry takes the catalog name and the catalog aliases. This keeps `CatalogContentPacks.userOverrides` empty for untouched entries, so translations keep matching.
  - A personal name keeps its personal aliases.
- `Report.byCurrentRoot(types)` maps each row once to the current root of `ancestorPathAtWrite.first()`. The groups are disjoint and safe to sum. `byType()`, `Filter.activityTypeId` and the stored paths are unchanged: history keeps its path at write.

## App

- Overview area totals and the share text use `byCurrentRoot`. Before a move, time still showed under the old root. After it, that root is no longer shown.
- Settings → Каталог занятий shows «есть обновление» when the APK catalog is newer than the installed one. Applying it uses the existing preview and confirmation; personal names stay.
- The picker hides empty groups and lists Games and Nature among everyday areas.

## Verification

Run on the JVM; this was not checked on a device:

- `CatalogEditorialMoveTest` covers move with rename, a personal name surviving a move, a move without a step being a conflict, older installs through the extra fingerprints, and the axis-move refusal.
- `ScenarioContract.readyCatalogLifecycle` upgrades from the bundled previous fixture: every rename and move is covered, 1889 entries are updated, the new type is added and the personal name is kept.
- `scripts/test_catalog_content_pack.py`
- `scripts/activity_icons.py --check`
