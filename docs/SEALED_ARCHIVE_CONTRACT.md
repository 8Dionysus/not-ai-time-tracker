# Sealed archive graph and private metadata boundary

Checkpoint source `23ab7a0`, based on `add6312`. [Raw verification](../evidence/sealed-archive-graph/verification.json): core51/51, Android metadata2/2 on Robolectric API35, four native Android ABIs compiled. Host JNI fixtures use only new temporary files. Production deletion remains disabled; compilation is not Android filesystem QA.

`RegisteredCopyStorage.sealArchiveGraph` atomically registers a backend-validated batch of SEALED database archives. Each archive declares every file's opaque ID, relative path, bytes/hash and the complete directory forest, including empty directories and nested retired files. Duplicate opaque IDs, paths, directory/file collisions, missing parents and unknown children reject before granting authority. Other copy kinds retain flat, single-link semantics.

A graph component joins registered sealed member identities by device/inode. Every surviving file is descriptor-opened with its exact expected link count, read/hash checked, then path identity checked. The count of known surviving aliases must equal each inode's actual nlink. An unknown/unregistered/external/live SQLite alias cannot be excused by a matching hash. A durable active/unknown reference on any surviving owner blocks the entire connected component. Adding an archive alias leaves the old component blocked until the new owner is safely registered; no stale stored nlink authorizes unlink.

Quarantine moves the whole group with no-replace, within the same filesystem. The original occurrence of each registered file/directory is identified by pinned descriptors, inode identity and no-follow ancestors. Unlink receives the current proven nlink as well as device/inode. Removing one selected alias leaves unselected archive bytes unchanged. Nested empty directories are finalized bottom-up only during explicit execution, with expected identity and parent fsync. Unknown children are never recursively removed.

Reopen resolves each component member at its original location or at the unique journal-derived quarantine location. Missing bytes are accepted only with durable cleanup provenance. A rename/unlink/rmdir interrupted before its receipt is reconciled without automatic continuation. Read-only progress validates the selected manifest's surviving paths, identities and hashes independently of deletion authority. An extra external hardlink can therefore leave exact selected-member progress readable while the graph blocks every further mutation. Unknown or replaced children still prevent certification of that manifest. Every destructive resume/return proves the complete graph and its references again. Only an intact whole group can be returned; deleted bytes have no Undo.

`archiveGraph(selectedIds)` returns opaque owner/member aliases, a monotonic maintenance revision and shared payload nodes. Logical selected bytes count every selected alias; payload bytes with no remaining alias count each fully selected inode once. Neither number promises filesystem free space: open descriptors, snapshots, allocation and compression are outside this evidence.

The private sidecar is format2, independent of domain Room/portable JSON versions (currently Room8/JSON10). Format1 flat ownership/refs/journals migrate on read without writing or granting shared-link authority. Both Android and descriptor metadata ports enforce the same 16MiB UTF-8 bound before replacing old state, reject malformed UTF-8 and never truncate leases. A known capacity refusal occurs before the write and keeps existing references and explicit owner recovery usable. Uncertain write/fsync failure still requires reopen/inspection. There is no age expiry or automatic lease pruning.

Remaining production prerequisites: trusted Android descriptor-root binding and device QA; backend validation/final sealing of existing archive manifests after all recovery writes; legacy inventory registration; complete frontend form/chooser/restore durable-reference adoption. Current DatabaseArchive manifest alone is not a certificate for nested retired files. recovery-pending.json remains protected; operational metadata is not a cleanup candidate. No external SAF original is a target.

Follow-up runtime `5e43515`: the full suite passes core63/app59. Native timestamps
outside signed nanosecond range reject with EOVERFLOW before arithmetic, including
an isolated filesystem fixture with verified year2400 POSIX time. Inventory errors
remain scoped to the affected copy/root. Unreadable recovery-journal state protects
possible recovery sources instead of being treated as a missing journal. No production
cleanup enablement follows from these host checks.

## Provenance and concurrency follow-up (`df10261`)

`verifyAbsent(item, operationId)` requires the exact operation's durable quarantined
progress before certifying absent original and quarantine paths. PREPARED followed
by an unexplained missing original stays PARTIAL with no inferred deleted members.
It cannot become DONE or an invented restore. A rename observed in quarantine can
still be reconciled before its receipt. Once quarantine was durably recorded, the
last unlink or final directory removal interrupted before its receipt can reconcile
to DONE. `isQuarantined` returns unknown when neither location exists; this also
prevents an empty manifest from being misidentified as an intact restored group.

When a selection includes every currently selectable, unused rollback/archive,
preview adds LAST_RECOVERY_COPY to each selected recovery owner. Confirmation
recomputes this selection-wide warning and requires the separate acknowledgements
before journaling or renaming. Selecting two remaining copies cannot bypass the
last-copy acknowledgement. Active/unknown copies do not mask the warning.

The filesystem guarantee assumes all legitimate same-UID writers use the common
gate and durable references. Native rename does not take an expected source inode;
validation before and after rename can detect, but cannot atomically prevent,
moving a replacement installed by a noncooperating writer between those calls.
Likewise fstatat identity/link check followed by unlinkat is not an atomic
compare-and-unlink against such a writer. Descriptor anchoring/no-follow prevent
symlink traversal; they do not create a sandbox against code with the same UID.
This is separate from the fixed durable-provenance defect. Complete writer adoption,
trusted root binding and Android QA remain production prerequisites; deletion is off.

Android metadata additionally checks no-follow regular-file attributes and size
before opening its bounded stream, rejecting a preexisting FIFO instead of blocking
startup on it. That preflight is not an atomic defense against a same-UID replacement
after the check. Descriptor metadata already opens nonblocking and checks file type.
