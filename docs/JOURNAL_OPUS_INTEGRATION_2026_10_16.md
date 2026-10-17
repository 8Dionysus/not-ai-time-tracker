> Current r14 handoff: [полная карта остатка](BACKEND_COMPLETION_2026_10_17.md), [verified candidate](BACKEND_READINESS_2026_10_17.md). Accepted r12 retained; r14 coordinator acceptance pending. Typed Focus callers, atomic switchDraft, reporting-day/goal callers and shortcut preference controller are now connected. Old "caller remains open" wording below records earlier handoff state. CSV sourceLocale/mapping persistence, new forms/controls and real Focus DND/audio choices remain specific frontend/platform work. Application Schedule/ordinary notification composition is already closed by r12; current source/evidence boundaries are in the linked matrix.

# Opus integration points for the combined fourteen-item backend

Backend source adds no Compose, ViewModel, resource, TechnicalActivity or RemoteViews
production edits. These are required frontend integrations, not completed UI claims.
Read JOURNAL_COMBINED_CONTRACT_2026_10_16.md and JOURNAL_A_CONTRACT_2026_10_16.md first.

| Existing integration point | Exact change to make in the frontend-owned layer |
| --- | --- |
| TrackerViewModel.kt calendar date selection around lines163 and494 | Derive the labelled current date from `ReportingDayPolicy(settings.reportZone,settings.reportDayStartMinute).dateAt(capturedNow)`. Avoid civil-midnight `LocalDate` shortcuts once the user selects a nonzero boundary. Backend calendar/day already uses the boundary. |
| TrackerViewModel.kt goalUi around line524 | Add a distinct `DISTINCT_RECORDED_DAYS` display branch with localized day count. The existing non-duration `раз` label is insufficient. Use the returned GoalProgress.goal effective definition, not the current stored head, for historical periods. Around line515 derive enabled state from goalDefinition at the displayed date; filtering on stored head.enabled can hide a still-effective goal before a scheduled revision. |
| Goal editor | Explicitly choose NEXT_PERIOD or CORRECT_CURRENT, show the effective period, then call saveGoal with the displayed token. A changed period/zone/boundary requires a new goal ID. Do not write revisions directly. |
| Report settings/editor | Call configureReportingDay with the policy. Use saveReportQuery/deleteReportQuery and savedReport. Display missing/archived references without silently widening the saved filter. |
| Quantities beside filtered report | Call `view.quantityReport` or `view.savedQuantityReport`; consume events, unknownTime, wholeTargets and typed exclusions separately. Do not join by latest Session.selection, sum legacy whole-target values per day, or use `subjects().window(...,sessionIds)` as a classification-filter adapter. |
| Text summary action | Preview `view.reportSummary(...,includeNotes=false)`. Notes require explicit opt-in; only a confirmed share invokes ACTION_SEND/EXTRA_TEXT. Quantities are not silently appended by ReportText. No backend network request occurs. |
| Existing choice search | Build localized labels once for the captured Dataset, use existing withCatalogLabels/search, and show ancestor provenance when useful. Retain stable ChoiceReference IDs across locale changes. Personal aliases are literal strings. |
| Personal Card/Favorite editor | Use personalizeChoice and orderChoices, preserve default null notePolicy, and present explicit COPY_SOURCE/CLEAR. Never infer a note copy from title similarity or copy measurement values into a start. |
| Recording-gap review | Obtain the day's bounds from the reporting policy, then historyGaps. Display PAUSE/UNRECORDED/RECOVERY_UNCERTAIN separately. gapDraft creates a draft only; explicit reviewed recordDraft saves. Uncertainty uses recovery. |
| Batch metadata correction | Select stable stopped-session IDs, previewMetadata, display exact field changes, then applyMetadata with its original token. EMPTY_ONLY is default; REPLACE and note clearing are explicit. Expose existing Undo only while valid. |
| Subject/event editor | Reuse Card.id through defineSubject; separate SubjectEvent IDs represent separate occurrences/stages. Link sessions explicitly. Show stateAt conflict/unknown-time reasons and immutable correction lineage. |
| Change/set/rate editor | Require explicit basis observations, signed delta, set load meaning/implement count, and paired distance/time basis. Present unavailable reasons; never fill moving time from elapsed session time or invent absent observations. |
| Existing full/selective import UI | Keep full replacement preview/rollback. New reader floors are full14/read1–14 and selective4/read1–4. Render dependency/conflict preview and partial results from the same existing selective engine. |

Application language/resource migration, Android LocaleManager/system-language authority,
RTL/TalkBack, schedule surface consumer registration and renderer bindings remain
frontend/device acceptance. The earlier overlay P2, goal-edit, actual OEM delivery,
Focus permission/source bindings and real-device checks remain open. Unit tests do
not close these points. No release signing, publishing or store acceptance is implied.
