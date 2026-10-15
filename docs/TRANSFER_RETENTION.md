# Explicit SQLite transfer payload compaction

This API removes unused operational snapshot values from the current Tracker database. It does not delete sessions, segments, measurements, schedules, dictionaries, files, private copies or external SAF originals. There is no startup action, age purge, VACUUM or automatic continuation. File cleanup remains fail-closed under its separate no-follow/lease requirements.

## API and confirmation

Call on IO through the same `Tracker` instance used for commands:

```kotlin
val preview = tracker.previewTransferRetention(
    TransferRetentionSelection(operationIds = chosenOperationIds, originIds = chosenOriginIds))
// Show exact targets, dependencies, blockers, bytes, capability changes and every warning.
// Closing this preview has no effects. Only an explicit user confirmation may submit:
val receipt = tracker.applyTransferRetention(preview, durableCommandId, acknowledgedWarnings)
// Query after an uncertain client result/process restart:
val saved = tracker.transferRetentionReceipt(durableCommandId)
```

`acknowledgedWarnings` must equal `preview.requiredAcknowledgements`; callers must obtain these acknowledgements from the user, never submit them automatically on inspection. `canApply` requires at least one change and no target blockers. Target/acknowledgement lists and maps are frozen; a preview retains hashes/IDs/counters only, not the raw snapshots it proposes to prune. A new action after restart requires a fresh preview; an already committed command can be looked up/replayed using its persisted receipt.

Selection uses explicit opaque operation/origin IDs. Preview computes the connected closure: selected terminal operations include current origins they reference; selected origins include terminal operations holding that origin or an embedded previous-origin snapshot. The fixed-point closure is shown with `requested` versus dependency targets. Every item is checked before the single transaction; there is no partial pruning of an invalid selection. Missing IDs are visible blockers. No names, paths or age cutoffs select records.

The command hashes the exact preview, selected closure, token/barrier, state hashes, byte counters and acknowledgements. In the StateStore transaction it checks a persisted receipt first. The same key/hash returns that receipt without a new write, including after reopen or later unrelated edits. Another payload under the key fails `IDEMPOTENCY_CONFLICT`. An uncommitted action must still match the exact revision/barrier/preview; stale state fails without deletion. Failed SQL writes roll back metadata and receipt together. A client failure after commit is resolved through the receipt; there is no automatic retry that removes additional payloads.

## What is removed and retained

Completed `TransferOperation` snapshots become content-free receipts: operation ID/request hash/base/barrier/revision, group IDs/origins/source revisions/hashes/entity manifests, conflict policy, final state/reason and `undone` remain. `payloadsPruned=true`; all group content becomes empty, `inserted` and `previousOrigin` are removed. PENDING operations never enter this state. `SelectiveTransfer.apply` with the original command still returns its current receipt; `resume` on a complete operation remains a receipt replay. Full result payloads are intentionally no longer available. `undo` explicitly rejects `TRANSFER_PAYLOAD_PRUNED`.

`TransferOrigin` keeps origin ID, source hash, local hash and a `TransferOriginCompaction` capsule. Its former `localGroup` is empty. The capsule contains an ordered local entity ID/hash manifest, a content-free original source header, and a verified bijection for changed COPY IDs. It contains no saved names, notes, quantities, classifications or dates beyond the IDs/header information necessary for provenance. IDs can themselves contain user text; required IDs/hashes are retained and are not claimed to be erased or translated.

Dedupe reconstructs the same local group from current domain rows, in original manifest order, including the former extra-segment check. Missing/edited members change its canonical hash and remain conflicts. For compacted origins the inverse source hash and entity manifest are also checked before treating a replay as unchanged. Export of unchanged data inverses the existing COPY ID rewrite, validates the recovered source hash/manifest, and returns the original source group header. This reuses the existing importer/COPY transformation. It does not introduce another importer or merger. Sessions, measurements, linked series/overrides/links, goals, favorites and their dependencies retain their identity and values.

The original snapshot-to-receipt boundary is checked while the full values still exist. In `compactTransferOrigin`, source/local arity and bijection must match and inverse rewriting must exactly reproduce the original content. If an old/custom store has COPY provenance (`localHash != hash`) but no matching original journal header/payload, reconstruction cannot be proven: the origin receives `SOURCE_RECONSTRUCTION_UNAVAILABLE`, and that selection cannot be applied. Removing a header first would break `SelectiveTransfer.export` and could silently change source IDs/hash. Direct identity provenance without a source journal can retain its previous fallback behavior when local/source hashes match. Once a capsule exists, later compactions reuse it; no payload is needed to keep the receipt. A later COPY under the same source hash retains the earliest saved source header/revision, even if its raw journal group was already pruned.

## Protected state and explicit losses

Blockers are typed: `MISSING`, `PENDING_OPERATION`, `PENDING_REFERENCE`, `ACTIVE_OR_RECOVERY_REFERENCE`, `ASSIGNMENT_REFERENCE`, `SOURCE_RECONSTRUCTION_UNAVAILABLE`. Current RUNNING/PAUSED/RECOVERY sessions, Focus-held current sessions and durable pending assignment references protect associated payloads. Pending transfer groups and their referenced origins/previous origins are retained. Unrelated active timers are allowed and keep the same TimerTarget, epoch, barrier and facts.

Preview lists per-operation `undoAvailableBefore/After`, `pendingResumeAvailableBefore/After` and `fullResultPayloadBefore/After`. Only terminal snapshots can be pruned, so this command never removes pending resume capability. An available selective-transfer Undo of a selected operation is an explicit `OPERATION_UNDO_REMOVED` warning. `FULL_RESULT_PAYLOAD_REMOVED` identifies every selected operation whose full returned snapshots disappear. Process-local Dataset Undo is invalidated and cleared in the caller; `processUndoAvailable` reports this caller's availability, and `PROCESS_UNDO_INVALIDATED` always requires acknowledgement. Other processes/old immutable views may still hold prior bytes; their old tokens cannot undo this revision.

For unrelated currently valid transfer operations, compaction advances `nextRevision` by exactly its metadata-only revision increment, with `preservedLeaseOperationIds` in the preview. Their raw pending/undo payloads remain untouched. This preserves their existing strict resume/undo lease because all domain data and control barriers remain identical. Already-stale leases are never renewed. The shared scenarios verify both pending resume and terminal Undo after unrelated compaction.

`OTHER_COPIES_UNCHANGED` always requires acknowledgement: other protected/unselected operational snapshots, old backups/rollback files, SQLite free pages/WAL, chooser/export snapshots and external immutable views can retain previous bytes. Only current metadata and future exports from its new state are compacted. Later explicit imports can create new operational snapshots. This is logical compaction, not secure physical erasure or a promise of reclaimed filesystem bytes. `metadataBytesBefore` and `metadataBytesAfterWithoutReceipt` measure serialized transfer JSON; the latter excludes the new minimal receipt. `removedPayloadBytes` measures dropped full content fields, not net disk savings. `METADATA_NOT_SMALLER` warns when manifests/headers alone make the projected metadata no smaller; the final receipt adds further bytes.

## Persistence and archive compatibility

Room schema 7 adds `MIGRATION_6_7` alongside the existing v5→6 migration. It only records defaults for the new fields in the existing metadata row. All history tables/active/recovery data remain unchanged; no payload is removed during migration. Metadata reads/verification still use bounded SQLite text slices.

Full JSON format 9/read 1–9 carries pruned receipts/capsules. The convenience and streaming readers both reject compaction state disguised as format 8 or earlier. Real legacy v8 snapshots without the new fields migrate with their full payloads intact. Restore remains preview + verified rollback + atomic replacement. Selective package format 1 and CSV semantics are unchanged; a pruned receipt header is not an importable selective group. Receipts survive restore, and a receipt replay never resurrects removed snapshots. An older full archive may intentionally restore old snapshots through the existing explicit replacement flow; this API does not delete those archives.

## Verification boundary

Shared Memory/Room scenarios cover confirmation/closure, immutable previews, stale input, missing IDs, typed blockers, pre-commit failure, SQLite write abort, client failure after commit, receipt lookup/replay after reopen, same/different-key concurrent commands, explicit Undo loss, protected recovery/assignments, preserved unrelated timer/resume/undo leases, COPY source round-trip, changed/missing entity conflicts and old/new JSON restore. Room-only fixtures test schema6→7 without pruning and actual >2-MiB metadata shrinking/reopening. The SQL size artifact measures the current metadata row, not physical database/WAL bytes. All data are synthetic fixtures; no user database or file is a target.

Frontend integration remains with Opus: inventory explicit IDs; display the complete closure, blockers and capability/byte consequences; collect every required acknowledgement; preserve command ID after an uncertain result and use receipt lookup. Do not equate this API with private-file cleanup or expose a blanket cleanup action.
