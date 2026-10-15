# Legacy owner migration: explicit proof boundary

Investigated after baseline435c3be and the checked A projection fix. Operational maintenance format3 remains unchanged. No reference, copy, file, journal, Domain/Room/JSON data or UI is removed by this investigation.

## Observed behavior

adoptLifecycle is an inventory cutover: it scopes previously global legacy references to all byte groups present at that trusted descriptor inventory. It does not record that any historical owner completed. finishLifetime releases only the new lifetime's exact recorded tokens. Consequently an old group's legacy-ui-lifecycle/UNKNOWN_FILE_OR_STATE witness remains after a new exact owner finishes the same group. The new owner did not own the old token. This is protective retention/blocked cleanup eligibility, not data loss.

A metadata-only Java probe against the built current core exercised that exact overlap: old witness→cutover(old-copy,other-old-copy)→new exact form owner(old-copy)→finish(new owner). The exact old token/scope remain, the new token is gone, old-copy remains UNKNOWN-protected, a future independent group is unblocked and synthetic bytes have the same SHA256. No cleanup/deletion API was called. See evidence/legacy-owner-migration-2026-10-15. The probe is not Android or fsync/crash-durability acceptance.

## Existing safe explicit handoffs

- Form claim acquires its durable lifetime reference before releasing only the captured exact unclaimed token; another form owner is untouched.
- Staged restore input uses recorded pending/writer/handoff tokens. A new consumer first retains the input; only those exact staging tokens are completed. Failed/unknown inputs retain protection.
- Chooser completion persists the actual result before releasing its exact chooser token.
- Recovery journal3 carries exact source/writer references and installed Dataset hash; it completes matching tokens after verified installation and removes the pending journal last.

These handoffs have positive origin/lineage records. A generic old witness has no equivalent per-Activity/form/source origin record. A current exact owner or empty current owner list does not prove that a dormant historical saved form/restore preview/chooser/recovery source no longer needs a copy. Age, process restart, new owner completion and matching display names are insufficient. No automatic witness release or blanket owner-prefix purge is added.

## Concrete contract for an explicit migration

A safe apply needs all of the following, on a frozen preview:

1. Exact old reference token and its original durable owner/lineage proof; explicit selected opaque group IDs and verified current manifests/hashes, not filenames/display labels.
2. A trusted owner adapter's coverage of the relevant active and retained/saved lifetimes. It must classify uncovered historical references as UNKNOWN, not UNUSED. Known writers/chooser/recovery stay hard blockers.
3. Exact replacement lifetime/reference tokens persisted before narrowing any old scope. Their protection must cover every migrated group and every required owner. Unselected scope and all other unknown tokens remain unchanged.
4. Separate explicit user review/confirmation of that owner handoff, revision+canonical payload hash and idempotent durable receipt. Confirmation is reference migration only; deletion still uses the separate cleanup preview/acknowledgements/protocol.
5. Stale/changed/missing proof refuses without mutation; interruption preserves at least the old or complete replacement protection. Closing one new owner cannot complete an unrelated historical witness. No age expiry or automatic continuation.

For genuinely unused copies, once ownership is positively resolved, the existing last-unused-copy warning is confirmable rather than a permanent prohibition. Unknown ownership is a different condition and cannot be relabeled unused to bypass its blocker.

## Exact unresolved prerequisite

Existing legacy metadata contains a global/scoped witness but no historical owner origin/complete saved-lifetime census. That missing historical fact cannot be reconstructed from present exact references or byte hashes. The existing store therefore cannot safely implement a successful generic migrate/release of that witness solely from current data. The contract above requires an authoritative owner-specific handoff supplied by frontend/lifecycle migration, or a separately specified explicit owner policy for unresolved historical state. Until then, a preview must remain BLOCKED/UNKNOWN and preserve the bytes/tokens. Current backend does not claim that this unknown witness has been migrated or that cleanup eligibility of those legacy groups is closed.

This is an identified proof boundary, not an automatic data-repair/deletion task. No new generic migration engine, metadata format, blanket cleanup or real-user destructive test is introduced.
