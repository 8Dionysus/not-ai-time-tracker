# Functional customization: requirements, API and verification

Assessment starts at verified main `9d115df` / Library v6 (core74, app63).
The user's clarification assigns colors, themes and their configuration model to
Opus. No palette/theme model, migration or UI change is part of this backend pass.
This matrix is not a declaration that the whole application is sufficiently customizable.

| Requirement | Actual backend after this follow-up | Verification / next boundary |
|---|---|---|
| Add own categories and nested subcategories | ActivityType(id, name, parentId), Tracker.saveType; no prepared-taxonomy-only restriction | Shared hierarchyAndDecimals scenario: custom hierarchy, cycles, archive/selectable flags and semantic identity checks |
| Edit, move, archive and restore a category | saveType of the same ID; name/parent/archive/selectable editable, meaningVersion/definition change requires a new ID | Historic ancestorPathAtWrite stays fixed; later explicit Repeat/reclassification uses current path as documented |
| Own named object such as Zarathustra under philosophy/reading | Card(id,name,activityTypeId,details,tags,facets), saveCard/copyCard, selection(cardId) | namedPersonalObjectsPreserveClassificationAndHistory checks note=null, rename/move/archive/restore, copy IDs and full restore; selective roots have a separate shared contract |
| Keep activity, classification, object and note distinct | ActivityType is the activity/tree; Card is the named object; Classification is its per-fact snapshot; Session.note is separate | No second book/object engine and no substitution of a name into note |
| Archive without rewriting history | Card.archived and ActivityType.archived; fresh selection refuses, snapshots retain values/IDs | Explicit saved Repeat/Favorite exception remains supported; archive is node-local, not an implicit descendant cascade |
| Add structured custom fields and quantities | saveAxis/saveMetric/saveUnit, versioned facets, exact Decimal measurements | Existing shared hierarchy/decimal tests; source names are not translated or used as identity keys |
| Full backup/restore of personal catalog and settings | Current JSON12/read1–12, Room10, consistent snapshot/preview/rollback; incoming immutable settings/schedule preview | Existing legacy/migration/round-trip tests; any new persistent functional fields require independent archive/schema updates |
| Select a card/category for transfer before recording time | TransferSelection.cardIds/typeIds now select definitions directly, with explicit catalogRoots and dependency closure | standalonePersonalCatalogTransferUsesExistingImportEngine checks exact roots/closure, no facts, replay/Undo, COPY/retention/re-export, changed-source conflict and interrupted resume |
| Flexible functional settings already present | reportZone; widget/notification/overlay/focusSignal flags; FocusPlan work/break durations; per-series dates/weekdays/time/zone/until/alerts | Actual typed Settings/Tracker APIs; OS permission remains capability rather than persisted consent |
| Persist an explicit schedule day boundary/home-zone policy | Settings.schedule.day and configureSchedule persist DEVICE_LOCAL/FIXED IANA + boundary; scheduleDay and the common projection resolve it | functionalScheduleSettingsPersistAndResolveCivilDays checks DST folds/gaps, travel, validation, stale tokens, timer target and full restore |
| Quiet schedule shade separate from interrupting reminders/timer notice | Settings.schedule.quietShadeEnabled is independent of alerts and ordinary timer notification | Shared contract proves no alarm/post from this flag; Android rendering/permission/lifecycle integration remains Opus-owned |
| Colors/themes | Deferred to the user and Opus, including the model decision | No backend implementation or completeness claim |

Sources: current Tracker specification section "Authoritative entities/Settings",
date-only ScheduleDayResolver/day-policy contract and the user's current functional
customization request. The matrix follows the latest colors/themes clarification.

Verification uses Memory and Room for own reading→philosophy→named Card,
stable snapshots through edit/archive, independent catalog transfer without facts,
dependency closure and updated-origin conflicts, COPY/retention/re-export, stale
previews, settings validation and legacy→migration→export→restore comparison.
UI callers/rendering and real-device acceptance remain separate.

Exact runtime `fe0e490a932f3a789e1a7b78de575634ec6fbbef`: core83/app67,
zero failures/errors/skips, full unsigned build and release lint0errors. Commands,
artifact hashes and raw evidence: [functional verification](FUNCTIONAL_CUSTOMIZATION_VERIFICATION.md).
