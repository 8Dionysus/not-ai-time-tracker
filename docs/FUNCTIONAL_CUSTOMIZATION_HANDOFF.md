# Functional customization backend contract

This extends the existing Tracker/catalog/transfer APIs. Colors, themes and their
model remain with the user and Opus. No UI, TechnicalActivity or rendering changes.
The [requirements matrix](CUSTOMIZATION_REQUIREMENTS_MATRIX.md) distinguishes
existing functions from the actual additions; it is not whole-product acceptance.

## Personal hierarchy and named objects

Use ActivityType for an activity/category tree and Card for a concrete named object:

```kotlin
tracker.saveType(token, ActivityType(id = readingId, name = "Чтение", selectable = false))
tracker.saveType(freshToken, ActivityType(id = philosophyId, name = "Читал философию", parentId = readingId))
tracker.saveCard(freshToken, Card(id = bookId, name = "Заратустра", activityTypeId = philosophyId))
tracker.start(freshToken, tracker.selection(bookId))
```

Obtain each returned token/view after a successful command. These are the existing
commands, not a compound transaction; do not acknowledge a later step before it
commits. Session.note remains separate and optional. The saved Classification
contains card/type IDs, name/details/tags/facets and the historical ancestor path.
Own IDs are not restricted to the prepared taxonomy. Duplicate names do not merge
objects. saveCard/saveType edit the same stable ID; copyCard creates a new one.
Archive/restore use the archived flag. New selection refuses archived targets;
existing explicit Repeat/Favorite historical behavior remains unchanged. Parent
archive is node-local, not an implicit descendant cascade. A semantic type change
needs a new ID; presentation/parent changes affect future classifications only.

## Standalone catalog selection

TransferSelection now has cardIds and typeIds. They select actual definition roots
even with no sessions, goals or favorites. The existing SelectiveTransfer engine
performs export→inspect→plan→preview→apply/resume/Undo; no dummy fact/favorite or
parallel importer is required. Each group exposes content.catalogRoots separately
from mandatory dependencies, so UI can explain what the user selected.

Ancestors, card classification facets and suggested axes/metrics/canonical units
are included. Descendants are not silently selected: pass their explicit IDs when
the user chooses a whole subtree. Selected cards and selected types joined by their
explicit hierarchy form an atomic catalog group. Independent selected roots can
remain independent groups. Root group IDs hash the sorted typed IDs, so opaque
IDs containing delimiters cannot collide by concatenation. Legacy fact/link group
ordering is preserved. Component union and ancestor closure use indexes/work queues;
this is not an unlimited-capacity claim for the loaded graph or all exporter work.

Root sets serialize canonically and reject duplicate input IDs. Missing selections
or roots use CATALOG_ROOT_INVALID with Identifier(rootId), Text(rule) and a stable
fieldPath. Plans retain immutable snapshots. Standard conflict/BLOCK/COPY, exact
origin/hash receipts, interruption/resume and conservative Undo apply. COPY remaps
root IDs together with every dependency. Explicit retention keeps minimal roots in
the origin capsule so an unchanged copied group can reconstruct/re-export its
original source IDs/hash. A changed source conflicts; no matching by name.

Packages using catalog roots are selective format2/minimumReader2. Existing
fact-only exports remain format1/minimumReader1; the reader accepts both. Roots
cannot be smuggled into format1. Default empty-root fields are not serialized,
preserving existing content/receipt hashes; compiled-v6 golden vectors are tested.
Global settings/control assignments remain an explicit disclosed selective loss;
catalog selection does not secretly overwrite the destination's preferences.

## Functional schedule preferences

Settings.schedule is ScheduleSettings(day,quietShadeEnabled=false). Its day policy
has DEVICE_LOCAL (zoneId must be null) or FIXED (valid IANA zoneId required), plus
boundary LocalTime, default00:00 with no fractional seconds. configureSchedule
validates/canonicalizes only this group while preserving reportZone/surfaces/Focus.
The existing full settings setter remains available. Current UI callers already
use settings.copy, preserving the new field.

scheduleDay(deviceZone) returns the derived date, resolved zone and boundary.
listOccurrences, one-occurrence lookup and next-reminder projection use the same
day resolver. The boundary is resolved to an instant using the existing gap-first-
valid/fold-earlier rule, so the second repeated hour cannot become yesterday again.
Future reminder search still uses each rule's actual civil date/time/zone; changing
the grouping day does not move its reminder instant or original occurrence key. The final prepared-notice guard also compares the complete fresh projection; a day-policy change during rendering cannot submit a stale relative-day caption.
Fact instants/durations/anchors, reportZone and timer target are unchanged.

quietShadeEnabled is an independent persisted preference for Opus's quiet schedule
surface. It does not enable interrupting alerts, grant POST_NOTIFICATIONS, invoke
renderers or start a timer. listOccurrences remains a pure read regardless of that
preference. Reminder opt-in remains per-rule alertsEnabled/reminderTime, separate
from the ordinary timer notification preference and current OS capability.

SCHEDULE_DAY_INVALID provides Text(value), Text(rule), and fieldPath under
settings.schedule.day. [Functional typed delta](localization/backend-functional-delta.json)
contains the exact interface and RU/EN source drafts; editorial approval is pending.

## Persistence and integration

Room8 adds MIGRATION_7_8 over the complete prior chain. SQL entities and identity
hash match Room7; only metadata defaults change. Sessions, IDs, active/uncertain
anchors and operational journals are preserved. Nondefault future settings/roots
falsely labeled as v7 are rejected transactionally rather than normalized away.

Full portable JSON is independently format10/read1–10. It carries preferences and
catalog-root source/provenance/capsules in one snapshot. Formats1–9 reject the new
nondefault state. Old selective hashes and old archive semantics stay valid; full
restore still requires preview/verified rollback and restores active time as RECOVERY.
RestorePreview exposes immutable incoming settings and schedule for explicit review, including enabled reminder choices, without reparsing or granting edit authority. Private-copy sidecar format2 and reminder sidecar format1 are unchanged.

Opus must expose selection roots/dependency closure/conflicts and per-group results,
persist the returned operation ID across uncertainty, and connect the settings to
all desired surfaces. No new permissions/rendering were added. Source-caption and
version-label updates, UI lifecycle/drafts, accessibility and real-device/OEM QA
remain UI/integration acceptance. Existing mandatory Focus/switchDraft/CSV caller
migrations remain in SCHEDULE_DELIVERY_HANDOFF.md.
