# Fourteen-item backend coverage

Approved source: https://chatgpt.com/space/page_c797313375188191a2975d3f80969f5c#block-42a37bc2-0843-4345-8a62-a5015edd5a5f

These are implemented interfaces, with source-bound execution results in the final
verification receipt. A producer's earlier result is not a result for the combined
source. Shared ScenarioContract cases execute in CoreScenariosTest (Memory) and
RoomScenariosTest (SQLite/Room). JournalAScenarioContract and ChoiceLocalizationScenarioContract
also have matching Memory/Room consumers. No physical-device execution is claimed.

| Item | Backend API/source under core/src/main/kotlin/dev/notai/tracker | Relevant tests under core/src/testFixtures/kotlin/dev/notai/tracker unless stated |
| --- | --- | --- |
| 1 Distinct recorded days | Goals.kt: GoalMetric.DISTINCT_RECORDED_DAYS; Reports.kt positive contribution and reporting-day labels | ReportingScenarios.reportingDaysAndGoalVersions; test/ReportingDayTest.zeroRoundedContributionDoesNotCreateARecordedDayAndIndexAgrees |
| 2 Effective goal revisions | Goals.kt: GoalRevision, GoalEditMode, Goals.effective/save; Tracker.saveGoal | ReportingScenarios; test/GoalVersionsTest; CombinedJournalScenarios full/COPY/Undo/compaction |
| 3 Custom reporting-day boundary | ReportingDay.kt: ReportingDayPolicy; Tracker.configureReportingDay; Reports calendar/day/compare | test/ReportingDayTest DST gap/fold; test/GoalVersionsTest relative windows; ReportingScenarios; CombinedJournalScenarios Berlin04:00 |
| 4 Ancestor/context search | ChoiceCatalogLabels.kt / Choices.kt: existing ChoiceView.withCatalogLabels/search and MatchEvidence.sourceTypeId/localeTag | ChoiceLocalizationScenarios.ancestorContextDisambiguatesBranchesWithStableLocaleEvidenceAndOwnNamePriority; private real11 tests |
| 5 Personal ordering/aliases/note policy | PersonalChoices.kt; Card.personal/Favorite.personal; Tracker.personalizeChoice/orderChoices/repeat | JournalAScenarios profiles, defaults/no measurement copy, explicit unclassified repeat, full/selective/COPY/compaction/replay |
| 6 Saved report queries | SavedReports.kt: SavedReportQuery/ReportWindow/SavedReports.open/references; Tracker.saveReportQuery/deleteReportQuery | ReportingScenarios.savedReportsAndPortableHistory; test/GoalVersionsTest; CombinedJournalScenarios copied Card/type/axis references |
| 7 Reviewed deterministic text | ReportText.kt; TrackerView.reportSummary(includeNotes=false) | ReportingScenarios note privacy/determinism and exact report basis; sharing preview/send remains frontend-owned |
| 8 Recording gaps | HistoryGaps.kt; TrackerView.historyGaps; Tracker.gapDraft then explicit recordDraft | JournalAScenarios pause/unrecorded/recovery uncertainty, future exclusion, stale/overlap refusal |
| 9 Batch correction | BatchMetadata.kt; Tracker.previewMetadata/applyMetadata | JournalAScenarios batchMetadataIsPreviewedAtomicVersionCheckedAndUndoable; fieldwise EMPTY_ONLY, explicit replacement, unchanged times/measurements |
| 10 Card roles and separate events | SubjectQuantities.kt: CardSubject/SubjectEvent/SubjectSessionLink; Tracker.defineSubject/saveSubjectEvent/linkSubject | SubjectQuantityScenarios.subjectBooks/subjectEvents; stable IDs, same names, stage links, no hidden sessions |
| 11 State observations | SubjectQuantities.kt: StateObservation, SubjectRead.history/stateAt; Tracker.observeSubject | SubjectQuantityScenarios.subjectState/subjectTransport; equal-time conflict/corrections/unknown time and round-trip |
| 12 Signed changes | SubjectQuantities.kt: SignedDecimal/SubjectChange/changeTotal; Tracker.recordSubjectChange | SubjectQuantityScenarios.subjectChanges; CombinedJournalScenarios retained audit guards and basis audit ordering |
| 13 Semantic bounds and sets | Vocabulary.kt Metric.bounds; SubjectQuantities.kt ScaleBounds/ExerciseSet/setTotal; Tracker.recordSet | SubjectQuantityScenarios.subjectSets/subjectTransport; JournalAMigrationTest future bounds guard |
| 14 Timed quantities and rates | SubjectQuantities.kt EventQuantity/QuantityWindow/DistanceTimeInput/rates; QuantityReports.kt; TrackerView.quantityReport/savedQuantityReport | SubjectQuantityScenarios.subjectWindow/subjectRates/subjectTransport; CombinedJournalScenarios all four shared cases |

Integration protections are in CombinedJournalScenarios: historical half-open
classification rows across Berlin DST04:00; global/sessionless versus filtered
amounts; explicit unknown-time/classification exclusions; mixed whole-target
separation; A-profile + B-versioned goal/query + C-subject quantities full replacement,
COPY, Undo, compaction and exact-payload replay; equal names with distinct Card IDs;
independent selected components and their origin membership during re-export after COPY/compaction; exclusion of an unselected session; copied parent
type and grouping-axis references; live/origin/source/inserted/previous-origin audit
guards; positive hash-only compaction; signed-change audit not before its bases;
bounded/streaming rejection parity for legacy labels.

Room boundary: app/.../TrackerDatabase.kt MIGRATION_11_12; app/.../JournalAMigrationTest
reaches profile, subject metadata and metric-bounds guards, checks every isolated
row/version remains intact after rejection, and tests interrupted migration rollback.
The legacy default RUNNING/PAUSED/STOPPED/RECOVERY migration→export→preview→restore
comparison is separate from the new combined domain scenarios. Older migration,
rollback-failure, cleanup, schedule, attention and selective tests remain in the
fresh full regression. Default fields preserve legacy content hash vectors.

## Original A–F package continuity

A: localization/catalog presentation and stable IDs remain separate from originals,
user overrides, rights and offline availability. The accepted private eleven packs
have dedicated real-payload tests; locale payloads were private during preparation; this is superseded by Dionysus’s explicit2026-10-16 public authorization: all eleven accepted catalog packs are now in public Git/APK, with original rights/QA metadata retained (see PRIVATE_CATALOG11.md).
B: the existing schedule series/overrides/start links/projection/receipts remain one
engine and receive the shared full/selective floor. UI surface binding is separate.
C: Room12/full14/read1–14/selective1–4, one merged migration, complete new snapshots,
legacy defaults, full replacement/rollback and selected COPY/Undo/compaction.
D: existing manual private-copy inventory/leases/journal/quarantine/native descriptor
safety remain in the full regression; no user files or external SAF originals are
deleted, and legacy owner retirement still needs a trustworthy historical witness.
E: the existing selective engine now carries all new records and dependencies;
losses include excluded subject record kinds and accurately exclude measurements
already included as rate dependencies. CSV remains its restricted atomic fact append.
F: own Apache-2.0 and preserved Onest OFL/third-party notices, API36 build, merged
manifest and66-runtime-artifact inventory, lint and source F-Droid checks. Disabled
recipe, unsigned APKs, no store actions or independent reproducibility claim.

UI resources/language authority bindings, sharing/preview controls, overlay P2,
goal-edit labels and effective-date presentation, schedule surfaces, OEM/device/
TalkBack/RTL and real delivery remain Opus/device acceptance. Exact wiring is in
JOURNAL_OPUS_INTEGRATION_2026_10_16.md. Native publisher provenance, store choices,
release signing and an independent reproducible server build remain release work.
