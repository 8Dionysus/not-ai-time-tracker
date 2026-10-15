# Addressed backend static follow-up, 15 October 2026

Review base: Library v5 / main `14206747be0e986eeb379f51224314e2136092ae`.
Runtime changes: `df10261df853064cfc1d52651cb928e03242f4dd`.
Hash-allocation follow-up: `2671f6e041c829186c3cdfbfe248a4c6e4f912c6`.
Final-post gate follow-up: `d0888a53c0d5446ac201b26933a061a110618be1`.
Scope: registered manifests/graph/provenance, bounded metadata ports and schedule
delivery state/receipts/platform failure transitions. This is not whole-repository,
UI or production-device acceptance. No additional model executor was launched.

| Finding | Addressed behavior | Regression / boundary |
|---|---|---|
| Missing paths certified DONE without proof | Exact operation must have durable quarantine progress; PREPARED disappearance stays PARTIAL | Prepared journal before any rename, then fixture moved aside; bytes remain and resume cannot certify success |
| Empty missing group misidentified as restore | isQuarantined returns unknown when neither location exists | Empty container finalized before DONE receipt reconciles to DONE, never RESTORED |
| Legitimate last mutation interrupted | Keep durable quarantine as authority even without last member/DONE receipt | Last flat-file unlink, final empty rmdir, existing rename/nested unlink/rmdir interruption tests |
| Selecting all remaining recovery owners bypassed last-copy acknowledgement | Recompute selection-wide LAST_RECOVERY_COPY during preview and confirmation | Simulator and actual sealed hardlink group; incomplete acknowledgements cause zero journal/rename |
| Platform effect exception skipped alarm/hook cleanup | Notice operations, cancelAlarm and configureWakeups are independently attempted; errors typed PLATFORM_UNAVAILABLE | Availability, visible, cancelNotice, cancelAlarm, schedule and hook failure matrix; no consumed receipts |
| Reservation capacity failure left stale wake effects | No post after failed durable write; independently attempt disarm before STORAGE propagates | Prior receipts unchanged, repeated failure produces zero posts; explicit provisioned fixture can continue without dropping receipts |
| Android metadata stream could block on a FIFO | Reject preexisting no-follow nonregular/oversized file before stream open | Isolated FIFO regression plus prior malformed UTF-8/oversize/ref recovery tests; not a race-proof open primitive |
| Unbounded receipt array cost | Compact JSON whitespace, transient derived identity cache; no receipt eviction | Legacy JSON and canonical hashes unchanged; separate host ceiling/sync profile. O(n) and finite lifetime remain explicit |
| Repeated Formatter allocation in canonical SHA text | Direct lowercase ASCII hex encoding, same digest algorithm and output | Independent empty/abc/all256-byte vectors under five locales; existing catalog and receipt contracts |
| Renderer could invalidate current occurrence before notify | Split render preparation from terminal post; final matching and notify share the domain StateStore gate | Shared Memory/Room current archive/opt-out/start and concurrent command tests; actual API36 adapter returns NOT_POSTED, not POSTED followed by cancellation |

## Invariants checked

Sealed capture validates unique member IDs/paths, all parents/directories, hash/byte
counts, root kind and sealed state before registration. Graph proof uses registered
device/inode aliases, current exact nlink, hashes and surviving owners; external or
unknown aliases and live references block mutations. Partial observation does not
grant deletion authority. Unknown children, symlink ancestors, uncertain persistence
and recovery sources retain their existing protection. No real user bytes were used.

Both metadata ports still refuse more than16MiB UTF-8 and malformed input, with no
reference truncation or age expiry. Known preflight refusal leaves the old file
readable; uncertain maintenance write/fsync still poisons that gate until reopen.

Schedule reservation is durable before post. Each item rechecks fresh domain state,
original key/revisions/barrier/zone/capability; outcome write uncertainty leaves the
reservation suppressing replay. MOVE/edit/restore do not delete receipt identities.
The compact encoder changes whitespace only and reads old format1 JSON. Batch
canonical hashing still uses its original codec. No schema/portable archive bump.

## Explicit concurrency and resource limits

The maintenance gate protects cooperating writers. Native validate→rename→validate
does not atomically prevent moving a replaced source; fstatat→unlinkat is not an
atomic conditional unlink against same-UID code bypassing the gate. Descriptor
anchoring/no-follow and preflight attributes do not establish that stronger claim.
Production deletion remains disabled pending trusted Android root/device QA,
complete writer/reference adoption and archive final-sealing/legacy integration.

Receipts remain monotonic. The exact byte ceiling is enforced, not a guaranteed
reminder count, number of years or bound on JVM/device working memory. Full decode,
validation, sets and full-state writes remain O(n). No arbitrary tombstone compaction
is introduced: losing dataset+original-key evidence could replay old callbacks or
revive a MOVE/edited/restored occurrence. The delivered profile measures host behavior
only. Indexed operational storage or explicit, tested retention remains a separate
backend scalability item; there is no phone RAM/battery acceptance claim.

Current raw results are in the delivery evidence and the final verification JSON.
Prior Library v5 evidence remains historical rather than being relabeled as current.
Opus-owned caller migrations/rendering and the original A–F residuals remain in
[BACKEND_AUDIT_HANDOFF.md](BACKEND_AUDIT_HANDOFF.md),
[SCHEDULE_DELIVERY_HANDOFF.md](SCHEDULE_DELIVERY_HANDOFF.md) and
[SEALED_ARCHIVE_CONTRACT.md](SEALED_ARCHIVE_CONTRACT.md).

## Verified runtime and host receipt ceiling

Runtime `d0888a53c0d5446ac201b26933a061a110618be1`: core74/74, app63/63,
zero failures/errors/skips. Unsigned debug/release, releaseInventory and lint pass
(0errors/60warnings/1hint). Full run6m9s. Raw [verification](../evidence/provenance-receipts-followup/artifact-verification.json) and XML/logs are retained.

The separate profile-only command passed in19s with every compile task up-to-date.
Host JVM1GiB, new POSIX fixture, three sequential syncs per size, no state writes
for unchanged syncs. No CPU isolation, Android device, phone PSS or battery claim.

| Receipts in this fixture | UTF-8 bytes | Median sync ms | Max of3 ms |
|---|---|---|---|
| 0 | 284 | 0.978 | 3.376 |
| 1000 | 354448 | 114.702 | 154.256 |
| 10000 | 3540448 | 236.169 | 340.804 |
| 47392 | 16777216 | 893.039 | 1163.953 |

The file was filled to exactly16,777,216bytes with47,392 receipts of the declared
fixture shape. This is not a guaranteed or maximal reminder count. New reserve
refused before post, every receipt and original byte remained, and fixture alarm/hooks
were disabled. Full-build-contended samples are preserved separately and were
slower (near-ceiling median1859.993ms). Raw JVM heap samples include fixtures and
uncollected intermediates; they do not establish retained heap or phone capacity.
The finite byte ceiling and O(n)/full-file-write limit remain open.
