# Indexed scheduler attempt journal

Extends the existing ScheduleDelivery coordinator; no second scheduler, timer,
importer, retention policy or receipt eviction. The Android store now binds the
platform SQLite implementation to IndexedScheduleDeliveryStore in core. JVM SQL
fixtures use the already cached SQLite JDBC toolchain component, tests only; the
Android release graph gains no JDBC dependency.

## API and identities

Runtime operations are control(), find(datasetId,OccurrenceKey), setPending,
reserve(expectedRevision,RESERVED) and complete(exactReserved,result). Transactions
update the one control revision together with the affected row. A reserve commits
before rendering/post; any read/import/write uncertainty disarms alarm/hooks and
prevents a subsequent post. After a crash, an existing RESERVED is presented as
UNKNOWN and never replayed. The stored original status is not rewritten by reading.

Receipt identity remains exactly the previous payloadHash(datasetId to original
OccurrenceKey). MOVE, rule edits, revision/time/zone changes and a same-dataset
portable restore do not create a new identity. The full original ticket (including
control barrier), attempt time and status remain in the row. Identity and the exact
dataset/series/original-date tuple are unique. Indexed reads verify payload against
indexed coordinates; duplicate/contradictory rows or unknown schema fail closed.

Schedules.nextReminders keeps the existing recurrence engine and exclusion-set
API, adding a point-query predicate. Sync no longer builds a lifetime attempt set.
Work still depends on the schedule graph and number of examined candidates; a
large run of already attempted future candidates can require many point queries.
No unlimited-capacity or constant-time claim for every recurrence is made.

Opus should use ScheduleDelivery.attemptsPage(afterSequence=0,limit=100), bounded to
1–1024. nextAfterSequence is the last returned durable insertion sequence; append
and completion never delete/reorder old sequences. Invalid cursor/limit rejects
before opening storage or disarming delivery. attempts()/store.read() remain explicit
O(n) diagnostic materialization for compatibility, not the normal runtime path.
UI must not call whole-state writeDurably on the indexed store; it deliberately
refuses replacement rather than deleting old receipts.

## Storage and crash-safe v1 migration

- noBackupFilesDir/schedule-delivery.sqlite: operational SQLite schema1, independent
  of current domain Room11/full portable JSON13. FULL synchronous rollback journal commits
  and directory fsync precede return. Corruption never invokes automatic DB deletion.
- schedule-delivery.json: old strict format1 snapshot, then a small format2 authority
  marker naming the database identity and imported source digest.
- schedule-delivery.v1-source.json: exact retained v1 UTF-8 text. No automatic purge.

The old bounded/no-follow JSON reader validates every legacy row, duplicate identity,
ticket and pending batch. It saves/verifies the exact source copy, then imports
control and all attempts in one SQLite transaction, preserving insertion order.
Only after durable commit is the format2 marker atomically written/fsynced/verified.
No operational post is permitted before this initialization returns. A crash before
commit retries the import; after commit it finishes the same marker only when the
source digest still agrees. Changed sources are not merged or silently overwritten.

A v2 marker never allows creation of a missing database or fallback to the old
snapshot. A retained v1 source with its original manifest missing also refuses
empty initialization. Unknown version/schema, missing control, invalid UTF-8,
special files and mismatched authority preserve source bytes and fail closed.
An older binary rejects the new strict marker rather than silently operating on an
empty journal. Restoring the entire private operational filesystem to an old point
is outside portable Dataset restore and cannot be promised monotonic authority.

The16MiB lifetime snapshot ceiling is removed for indexed production storage.
Per-row JSON/control bounds still refuse before unsafe writes; SQLite/disk capacity
is finite and exhaustion is an unavailable-delivery result, never an eviction policy.
The legacy CopyMetadata16MiB limit remains for other sidecars and snapshot adapters.

## Validation boundaries

SQL fixtures cover legacy fields/statuses/order, pagination, duplicate/missing/
future schema, preserved source after interrupted import, commit uncertainty,
MOVE/restore and cold reopen. Android/Robolectric checks exercise the real adapter
API, including its standard android_metadata table. The100000-receipt synthetic
profile records query plans/counts, file/payload bytes, a reserve beyond16MiB and
full old-receipt fingerprints before/after. Exact run results belong to the delivery
verification, not a device-performance inference.

The isolated v7 baseline worktree is pinned to fe0e490; its old full-file ceiling
profile is separate from the current compatibility-adapter profile. Full-build/
test-contended and isolated timings must stay separately labeled. Phone/OEM
delivery, PSS/battery and Android power-loss durability still need device evidence.
