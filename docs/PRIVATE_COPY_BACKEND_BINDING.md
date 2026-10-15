# Private-copy backend integration

This connects the existing CopyMaintenance, RegisteredCopyStorage and
PrivateCopyCleanup protocols. It does not add another cleanup engine, age purge,
external-SAF deletion or a production destructive entrypoint on unverified paths.
See SEALED_ARCHIVE_CONTRACT.md for the same-UID noncooperating-writer boundary.

## Bound backend pieces

PrivateCopyBackend binds only Android Context.applicationContext.noBackupFilesDir.
It resolves the OS-provided application directories, verifies private UID/mode and
device, opens the no-follow native root and compares its descriptor device/inode
before/after. Callers provide opaque operation IDs, not filesystem roots or chooser
paths. This is implemented software binding, not completed Android/OEM JNI QA.

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
always recheck live durable references. The production legacy UI witness remains.

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

RecoveryJob format2 records exact source digest, backend reference tokens and related
archive IDs before archive/candidate preparation. Explicit same-source resume carries
the known chain; after installed DB readback, it finalizes related archives, completes
the journal durably and releases only its identified old backend references. The
current writer releases on success. Legacy format1 is read conservatively; unscoped
old references and other-source/UI owners are not guessed away.

## Exact Opus boundary

- Expose explicit legacy registration results/refusals, inventory, hardBlockers and
  confirmableWarnings. Wire preview/confirm, separate last-copy acknowledgement and
  PARTIAL/remaining digest/explicit resume only through the existing cleanup protocol.
- Use retainForm/retainRestoreInput (and retainExportChooser only for an additional
  UI-owned lifetime) and release the exact returned token at confirmed abandonment/
  completion. Keep tokens through process/configuration uncertainty. Never auto-expire.
- Review/migrate the retained legacy-ui-lifecycle witness after all old lifetimes are
  covered. This pass does not silently release that unknown owner. Do not double-own
  the chooser already handled by awaitResult/receiveResult without releasing both.
- Source choice/preview lifetimes, draft state restoration and real Android trusted
  root/JNI/OEM checks remain acceptance work. Inventory/registration APIs expose no
  destructive platform binding before those checks; no unverified deletion was enabled.

Core POSIX fixtures exercise final retired forests, hardlinks, references, independent
refusals, all group validators, stale preview after writer mutation, changed seals,
symlink refusal and explicit deletion of their own newly created data only. Shared
Memory/Room tests cover rollback consumer scope; Android/Robolectric tests cover
chooser reopen, retained failure refs and recovery-chain completion. These checks
are not physical-device or frontend-lifecycle acceptance.
