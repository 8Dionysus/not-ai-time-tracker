# Versioned external backups

`TrackerRuntime.automaticBackups` creates verified full JSON backups in a user-selected SAF directory. The directory may be local or supplied by an installed cloud document provider with the required capabilities. Provider readback verifies accessible bytes; it does not confirm off-device cloud replication. This is versioned backup with explicit restore, not multi-device synchronization.

## API

Run commands on IO. Launch `SafBackupDestination.selectionIntent()` through the system activity-result flow, then call `select(uri, returnedFlags, policy)` with the actual returned flags. Selection must yield a persisted read/write grant and a currently available directory supporting creation and listing. Unsupported providers, revoked grants, unavailable sources and readback failures produce explicit errors.

- `status()` returns device-local configuration, requested revision, pending operation, verified versions, last success, completion obligations, typed error and consecutive failure count.
- `enable(existingTree, policy)` rechecks an existing persisted grant and provider capabilities.
- `disable()` revokes subsequent delivery and cancels scheduling; interrupted work remains recoverable.
- `requestBackup()` requests a fresh version, including at an unchanged revision.
- `retry()` resumes the existing pending operation or completion obligation.
- `abandonPending(operationId)` explicitly resolves an ambiguous creation outcome. Unknown external documents are retained.
- `prepareRestore(version.operationId)` verifies the recorded document and returns the existing `RestorePreview`. Apply only after confirmation through `runtime.backups.apply(preview)`, retaining revision checks, verified rollback and atomic replacement.

`BackupPolicy(retain = 7, intervalHours = 24)` accepts retention 1..100 and interval 1..168 hours. Five consecutive failures suspend automatic attempts until explicit retry or selection. Exponential retry delays start at 15 minutes.

## Snapshot and scheduling

The existing JSON15 streaming serializer, export staging/hash checks and restore implementation are reused. `Backups.writeSnapshot` returns the exact captured dataset ID, revision and timestamp. A newer revision is never credited to an older snapshot. Existing backup bytes, schemas and readers remain compatible.

Committed revisions coalesce for 120 seconds from the first pending change. Persisted JobScheduler work provides an event-triggered wake and a 15-minute recovery wake, with one serialized runner. Execution is subject to Android background scheduling. There is no continuous foreground timer service or new worker dependency. The JobService requires BIND_JOB_SERVICE; application uses-permission declarations are unchanged.

Changing destination requests a first backup immediately, even at the same revision. At the configured interval a new snapshot of an unchanged revision is intentional: portable running-timer content can change with capture time. Repeated display refreshes before that interval do not upload again.

## Completion and recovery

The controller reserves an operation/staging ID before creating private files. `ExportOperations.beginOwned` is idempotent and records the exact automatic owner; manual pending-export discovery excludes these operations. Automatic retry requires matching owner, expected bytes/hash and valid staged content. Manual export admission is unchanged. Provider write failures retain READY; a failed preparation may retain FAILED with an earlier valid payload.

Every operation creates a new uniquely named document. Success requires matching size and SHA readback. A known interrupted document is verified before replay; correct existing bytes complete without another upload. Creation without a durable returned document ID reports CREATION_UNCERTAIN rather than creating repeated unknown files.

Verified success and its private finalization obligation are committed together. Restart replays the exact receipt or cancellation before removing that obligation. Private completion can be retried after opt-out without writing externally. Asynchronous cleanup-catalog registration remains best effort: process death before optional registration can leave unregistered protected bytes. Existing legacy UNKNOWN ownership protection remains intact.

Retention operates only on recorded document IDs in the selected destination after verified success. Old content is verified before deletion; modified, foreign, unknown and partial documents are retained. Deletion failure preserves the newest verified copy and remains retryable.

## Storage boundaries

Configuration and grants are device-local in `noBackupFilesDir/automatic-backup.json`, outside Dataset/full JSON. Restore never grants URI permissions or enables delivery. Reinstallation requires a new system selection. Only completed external backups can survive uninstall, while the user/provider retains them; private staging is not an external backup.

Portable theme/font/layout state remains a separate storage contract. The finite fields, identifiers, defaults/ranges, font fallback/system-scale behavior, section ordering and reset semantics must be defined before adding typed persistence and backup migrations. Existing transactional Settings/full-backup extension points remain available; this backup feature does not add a generic preference map.

## Verification

[evidence/automatic-backup-20261020/summary.json](../../evidence/automatic-backup-20261020/summary.json) lists all 28 exact test cases: 18 controller tests, 4 Android host tests, 1 existing export callback regression and 5 core tests. All passed with zero failures, errors or skips. Core coverage includes snapshot identity, legacy readers, exact automatic-owner admission and native export-registration/foreign-owner regressions. Production and test Kotlin compilation passed. Native checks use a Linux host POSIX fixture, not an Android device ABI.

The evidence directory retains final XML archives, exact verification logs and their hashes, and 11 source input hashes. No full-suite repeat, lint, emulator, actual cloud provider, device/OEM check, APK build/install or release verification is implied.
