# Explicitly fill the start of the current ordinary session

Source: the accepted convenience “start at the end of the previous record” in
[the journal](https://chatgpt.com/space/page_cc7a02fed97081918f59ffa4517bb6dc#block-bb02de88-3987-42eb-bd0d-a7acc20a0849).
This extends the existing Tracker correction boundary. It does not introduce another timer.

## Caller contract for Opus

1. Read the current `Snapshot` and retain its `token` and `session.id`.
2. Call `previewStartAtPreviousEnd(token, sessionId)` for the convenience proposal,
   or `previewStartBackfill(token, sessionId, proposedStart)` for an explicitly entered start.
3. Display `originalStart`, `proposedSegment.start/end`, `addedMs`, the exact
   `previousStoppedEnd` reference when present, and `requiredWarning`:
   `USER_CONFIRMED_TIME_ADDED`. This time was confirmed by the user; the device did not
   observe it. Discarding a preview changes nothing.
4. Only after confirmation call `applyStartBackfill(preview)`. Refresh from the returned
   `Change`. Use the existing immediate Undo action with the returned token.

Preview and apply validate the current token, owner, ordinary RUNNING/PAUSED state,
clock certainty, positive past interval and absence of overlap. A mutation after
preview makes apply stale. A repeated apply is stale and adds no duplicate interval.
Focus and Recovery require their existing resolution flows. A missing previous
certain stopped interval is a refusal, not an inferred date. Adjoining intervals
with no gap cannot add time.

The prefix is a **new closed Segment in the same current Session**, using the first
segment's saved classification. Existing session/segment IDs, measurements, notes,
classification snapshots, monotonic anchor, observed start and `TimerTarget` remain
exact. The current session's recorded duration includes the confirmed prefix; a
session-count goal still counts the same session. Internal pauses remain gaps.
`Snapshot.active.start` remains the observed open segment's start. To display the
whole session's earliest start, use the session's segments; do not rebase its anchor.
The `Snapshot.actions` set exposes `startBackfill` for ordinary RUNNING/PAUSED states.
No UI-owned screen or renderer is changed in this backend patch.

`Segment.manualConfirmation.confirmedAt` records the apply wall time. A preview's
proposed timestamp is provisional. `ReportRow.manualConfirmation` retains this
provenance even when a report clips the interval. Null means **unmarked/unknown**;
it does not certify device observation, including for old manual/CSV facts.

## Persistence and portability

- Room **10**, migration **9 → 10** adds nullable `segments.manualConfirmation` TEXT.
  Existing facts, active/recovery anchors and metadata are preserved. The historical
  4 → 5 migration continues to use its original twelve columns before 9 → 10 runs.
- Full archive **12**, readers **1–12**. Versions ≤11 cannot contain nonnull manual
  confirmation, including retained selective operation/origin payloads. Confirmation
  timestamps after archive `asOf` are rejected. Preview, verified rollback and explicit
  replacement remain the existing Backups flow.
- Selective archive **3 / minimumReader 3** when an exported group contains confirmed
  intervals; unannotated exports retain format **1** or **2**. Formats ≤2 reject
  annotated segments. Same-session segments stay in their existing atomic group.
- A null annotation is omitted from JSON even though other defaults are encoded.
  Therefore existing unannotated selective content hashes remain stable.
- Existing CSV remains a limited fact transfer. It does not restore session grouping,
  manual-time confirmation provenance or measurements. The existing
  `SESSION_GROUPING_LOSS` issue now includes typed `manuallyConfirmedIntervalCount`
  and diagnostic coverage of this limitation. Use full JSON or selective
  format3 when preserving these is required. Analysis CSV exposes time rows; callers
  must use the ReportRow provenance when rendering a distinction in the application.

## Validation boundary

Shared scenarios run through Memory and Room: unchanged observed segments and
anchors, visible preview, explicit confirmation, same-session duration/count identity,
Undo, stale/repeated/wrong-owner refusal, pause, Focus/Recovery refusal, overlap,
reopen, full export/stream/preview/restore and selective transfer. A v9 migration
scenario and a legacy compiled segment-hash vector supplement these checks.
Exact commands, source SHA and actual outcomes belong in the accompanying verification
record; this document alone does not certify a device or UI integration.
