# Private-copy backend integration

This connects the existing CopyMaintenance, RegisteredCopyStorage and
PrivateCopyCleanup protocols. It does not add another cleanup engine, age purge,
external-SAF deletion or deletion authority on unverified paths.
See SEALED_ARCHIVE_CONTRACT.md for the same-UID noncooperating-writer boundary.

## Bound backend pieces

PrivateCopyBackend binds only Android Context.applicationContext.noBackupFilesDir.
It resolves the OS-provided application directories, verifies private UID/mode and
device, opens the no-follow native root and compares its descriptor device/inode
before/after. Callers provide opaque operation IDs, not filesystem roots or chooser
paths. The native absolute walk pins system ancestors with O_PATH: Android may grant
search without directory-listing permission on /data. The final owned root and
relative mutation parents remain readable descriptors for inventory/fsync.
Runtime acceptance is limited to the exact Android evidence recorded separately;
it is not a claim covering every Android/OEM/device or native ABI.

PrivateCopyBackend.openSession(context) returns an AutoCloseable PrivateCopySession.
Its inventory/preview/confirm/operations/remainingHash/resume/restoreSurvivingGroup
methods bind the existing PrivateCopyCleanup engine to that trusted root and gate.
Keep the session alive through one preview/confirmation interaction, close it when
finished, and obtain a fresh preview after reopening. Closing does not delete bytes,
continue an operation or release durable ownership tokens. Process restart exposes
the journal; resume still needs a separate explicit action and exact remaining hash.

PrivateCopyCatalog validates known roots through descriptor reads. Rollback/restore
inputs pass the existing streaming backup reader; forms use their existing codec;
exports require COMPLETE/CANCELLED and a consumed chooser result. COMPLETE requires
the exact verified two-file group; CANCELLED may retain only its receipt or known
data/data.part staging members, with INCOMPLETE_EXPORT_STAGING acknowledgement.
Restore-input staging may contain refused/unparsed bytes: its known private owner,
exact descriptor manifest and durable reference state govern discard, not backup validity. No original user name/ID is inferred from display translation.
Recovery journals and scheduler authority files are never catalog selections.

DatabaseArchives produces the same raw source manifest. A durable archive-draft
reference protects preparation/retirement. After all original/retired files are
installed and synced, backend completion records archive-ready. Finalization adds
cleanup-seal.json format1 with exact source-manifest hash, files, hashes and directory
forest. Later unexpected bytes cannot silently rewrite a final seal. Ready references
survive unavailable native binding and can be completed by explicit registration;
unknown draft/UI references are never cleared by a read or by age.

Legacy registration is a separate explicit non-destructive API:
PrivateCopyBackend.registerLegacy(context). It validates and registers known groups,
excluding pending recovery archives and active exact draft owners. It partitions
sealed owners by actual shared inode graph; an incomplete component does not stop
independent components. All aliases must be known before any authority is registered.
Existing registrations verify idempotently. Changed immutable bytes refuse.

PrivateCopyBackend.inspect and PrivateCopyAudit.inspect only inspect; they do not
start legacy sealing or resume deletion. Registered inventory replaces audit-only
candidates when binding succeeds; unregistered/unsafe groups retain blockers.
Registration under a protective reference does not bypass it: inventory/quarantine
always recheck live durable references. Operational format3 preserves every legacy token; trusted descriptor cutover scopes prior unknown global protection to all byte groups present at cutover. New independent groups use recorded lifetimes; no old owner is guessed away.

## Writers and ownership

Backend writers hold durable references; failed/uncertain work keeps them. Before
altering a registered form/export, the writer invalidates the prior registration
under the maintenance gate, making old previews stale. Completed bytes are validated
and re-registered when the descriptor binding is available. Failure retains bytes
and reports unavailable/deferred registration; it is not deletion authority.

Existing ExportOperations.awaitResult/receiveResult now own the chooser reference:
await persists one token, result callback durably records consumption before
releasing that exact backend token. Cancellation alone does not infer a chooser
result. The existing ViewModel already calls those APIs; no UI source was changed.

RollbackStore.protecting wraps full replacement and CSV apply across
save→readback verification→domain commit. PrivateRollbackStore implements a durable
consumer reference, so its rollback cannot become selectable between those steps.
Failure keeps the reference for explicit recovery.

RecoveryJob format3 records exact source digest, source leases, backend reference tokens and
related archive IDs before preparation. After installed DB readback it records a durable
completion hash/count before any token release. Explicit completed resume verifies that
installed Dataset, releases only matching recorded tokens, then deletes/fsyncs the pending
journal LAST. It never repeats replacement after durable completion. Legacy formats1–2
are read conservatively; unrecorded references and other-source owners remain protected.
See BACKEND_WORKSPACES_HANDOFF.md for Activity/form/input/preview ownership and callbacks.

## Exact Opus boundary

- Expose explicit legacy registration results/refusals, inventory, hardBlockers and
  confirmableWarnings. Wire preview/confirm, separate last-copy acknowledgement and
  PARTIAL/remaining digest/explicit resume only through the existing cleanup protocol.
- Use the installed Application lifecycle and saved backend controllers described in
  BACKEND_WORKSPACES_HANDOFF.md. Additional consumers retain/release exact tokens, close
  canceled previews and explicitly abandon interrupted stages. Configuration/process
  uncertainty is not abandonment. The chooser already has awaitResult/receiveResult ownership.
- Legacy cutover preserves unknown references scoped to legacy byte groups; unavailable
  native inventory retains the conservative global witness. It never releases a token by
  owner-name/age guess. Opus wires presentation/explicit review; no blanket witness bypass.
- Broader Android/OEM/recreation acceptance remains separate. A valid backend binding
  cannot turn an unavailable root or protected item into a selectable candidate.

Core POSIX fixtures exercise final retired forests, hardlinks, references, independent
refusals, all group validators, stale preview after writer mutation, changed seals,
symlink refusal and explicit deletion of their own newly created data only. Shared
Memory/Room tests cover rollback consumer scope; Android/Robolectric tests cover
chooser reopen, retained failure refs and recovery-chain completion. These checks
are not physical-device or frontend-lifecycle acceptance.
