# Backend audit follow-up and Opus handoff

15 October 2026. Follow-up details: [sealed archive contract](SEALED_ARCHIVE_CONTRACT.md), [schedule delivery and mandatory caller migration](SCHEDULE_DELIVERY_HANDOFF.md). Based on main `f14fa3c`; Room 7 / portable JSON 9 / Android
0.11.0 (12), compile/target API36. No new persistent Dataset fields in this
follow-up, so no schema/archive version bump. The user restored backend-only
scope at 15:10 UTC. UI code and UI fixes are intentionally absent from this change.
Requested model configuration was gpt-6.1-sol/high; no independent runtime attestation.

The [addressed static follow-up](BACKEND_STATIC_FOLLOWUP.md), runtime `df10261`,
fixes missing-path provenance, selection-wide last-copy acknowledgement, independent
schedule disarm failures and metadata FIFO preflight. Schedule receipt bytes/hashing
are reduced without eviction; finite16MiB/O(n) lifetime/resource limits remain explicit.
The `d0888a5` follow-up splits rendering from terminal notify and serializes the final
current-occurrence check/post with StateStore commands. Opus's factory signature is
unchanged; custom backend platform adapters must use the new prepare/terminal contract.

## API deltas to connect

- Use `Tracker.switchDraft(expected, draft: EntryDraft)` for an incoming text
  switch. Session close/start and the incoming note commit once. TrackerViewModel.applyChoice still uses switch then labelCurrent; that app chain remains OPEN until caller migration. Do not call
  `switch` followed by `label`. `switch(expected,selection,note=null)` also accepts
  an optional note atomically. A failed commit leaves the preceding session intact.
- Use `Snapshot.isCounting()` for a visible countdown predicate: Focus RUNNING
  owns counting; Focus PAUSED/DUE/RECOVERY do not count even when the underlying
  work session status remains RUNNING. It does not itself schedule redraws.
- `FocusSignals.refresh(context,tracker)` / `deliver(context,tracker)` run on IO,
  serialize current state capture with notification effects, and revoke obsolete
  notice 43. Snapshot/view overloads exist only for source compatibility. Legacy
  refresh queues work in an already-existing runtime's IO scope; it never opens a
  database. Legacy deliver returns true only for an existing delivery receipt and
  requests the same fresh refresh; it is not a success acknowledgement for queued
  work. No supplied stale snapshot can post a notice. Receipts remain best effort,
  not a guarantee that an OEM/user actually displayed a notification.
- `CsvMapping.sourceLocale: String? = null`: null means ROOT, explicit BCP-47
  controls source month/day text. UI language never determines source parsing.
  Typed `ImportIssueCode.SOURCE_LOCALE_INVALID` is the 65th issue; the reviewed
  editorial package has 64 issue mappings. The new issue has its exact typed contract/source drafts in localization/backend-typed-delta.json; editorial review and UI mapping remain.
- Selective-transfer public APIs are unchanged. Concurrent resume now preserves
  the originally applied result instead of rewriting it as UNCHANGED. Keep the
  original operation ID after an ambiguous response; present per-group outcomes.
- `ChoiceIndexStats.historyScans` exposes scan evidence. No caller supplies or
  serializes the internal completed-history proof. STOP, SWITCH, corrections and
  cold loads still rebuild when required; no history is truncated.

## Private-copy cleanup: implemented boundary and blockers

The existing `PrivateCopyCleanup` protocol now has real `DescriptorCopyFiles`
(JNI POSIX) and `RegisteredCopyStorage` implementations, plus durable
`CopyMaintenance` references, registry, monotonic revision and journals. Internal
sealing binds a validated manifest to exact members and inode identities; UI
paths cannot create deletion authority. The JNI code anchors directories, opens
ancestors without following symlinks, refuses unknown members and incomplete hardlink graphs,
uses same-filesystem no-replace rename, validates identities before unlink and
fsyncs directories. Reads/reconciliation do not resume deletion. Explicit resume
checks remaining hash; only intact surviving groups can be returned. Deleted bytes
have no Undo. No age-based purge, recursive unknown-group cleanup or SAF deletion.

Android writers/rollback/recovery share `PrivateFileMaintenance`. Long ordinary
writes keep a durable protection reference while streaming outside the monitor;
failed/interrupted writers retain protection. Recovery remains exclusive. A
persistent `legacy-ui-lifecycle` reference records that existing frontend APIs
cannot prove which form/chooser/restore references remain live. Do not clear it
by age, process death or blanket reset.

**Production deletion remains disabled.** Remaining prerequisites:

1. Bind the descriptor root to a trusted Android private directory and verify the
   actual Android path/ABI/filesystem behavior; OS-managed ancestor symlinks must
   not be worked around with canonical-path-only checks. No device JNI test ran.
2. Connect each frontend form, chooser, restore preview and recovery source to an
   explicit durable owner/token and release only on a confirmed lifecycle action.
   Review legacy references individually; never infer that a file is orphaned.
3. Register existing candidates only after exact state/manifest/hash validation, including final sealing after recovery writes.
   PREPARING/READY/FAILED staging and unknown files stay blocked. Recovery journal
   is never selectable. Active/unknown ownership is a hard blocker.
4. The complete sealed archive graph and nested member manifest are implemented and host-tested. Existing archive validation/final-sealing integration remains; do not turn an old DatabaseArchive manifest into authority by setting a label.
5. Add Android interruption/filesystem QA before wiring confirmation to deletion.

The host tests operate only on newly created temporary fixtures. Four Android
ABIs compile, which proves compilation/packaging and does not prove device safety.
Both Android and descriptor metadata ports enforce the same 16 MiB UTF-8 bound
before replacing old state. Known preflight refusal preserves references and keeps
explicit owner recovery usable; uncertain I/O still requires reopen/inspection.
The operational sidecar migrates format1 to format2 without writing on read.

## A–F status

| Package | Backend evidence / remaining boundary |
|---|---|
| A | Stable typed errors, catalog sidecars, SYSTEM/EXPLICIT preference authority and locale-independent CSV parsing. Editorial UI package exists. Catalog translation packs and UI resource/LocaleManager migration remain unaccepted; do not label planned/unreviewed content complete. |
| B | Series revisions, date-only/zone/DST projection, exceptions, receipts, atomic explicit start/link and archive/restore exist. The common projection API is ready. Schedule delivery coordination, one inexact alarm, durable attempt receipts and conditional boot/time-change hooks are implemented. The notice factory and three renderer integrations remain Opus-owned; no production reminder rendering is bound. |
| C | Memory/Room scenarios, migration 5→6→7, JSON 1–9 replacement/rollback and recovery coverage continue. Cold contradictory identity rejects without repair. Oversized individual streaming records now refuse export instead of making unreadable archives. Full graph still resides in memory. |
| D | Real durable/gated descriptor storage and host fault tests added. Sealed graphs/nested manifests and bounded Android metadata are added. Production cleanup remains blocked by Android QA, final-sealing/legacy integration and frontend reference prerequisites above. |
| E | Full selective inspect/plan/preview/apply/resume, dependency groups, provenance/dedupe, explicit losses and Undo remain separate from JSON replacement and CSV. Concurrent resume fixed. OPEN/RECOVERY and active Focus portability remain explicitly restricted; not silently converted to facts. |
| F | Apache-2.0 own code / Onest OFL / existing notices retained, API36, NDK r30 pinned, unsigned builds available. Disabled F-Droid draft now pins NDK, and passed metadata lint; no fdroidserver end-to-end build or independent reproducibility claim. Existing AndroidX native source/static-runtime provenance findings remain open. No store publication or release signing key. |

## Opus findings to address and verify

These remain open in main, even where an isolated unaccepted UI experiment exists:

- Validate correction preview against the exact current fields and revision at Save;
  edits must synchronously invalidate it. Wait for actual transaction success before
  closing manual/correction/recovery forms. Keep values and inline errors after refusal.
- Acknowledge favorite/delete only after commit; suppress duplicate queued actions.
  Canceling a delete confirmation should preserve the underlying form.
- Route active incoming text, including blank classification, through atomic switchDraft;
  consume only the exact draft successfully committed. Restore drafts/navigation safely.
- Clamp overlay bounds after rotation/density/window changes and on very small displays;
  retain the user's always-available idle button. Overlay P2 / goal-edit acceptance remains open.
- Apply counting predicate and refresh Focus DUE projections even without a DB revision.
  Current widget/notification chronometers can remain visually stale. A fresh command
  token still protects data; it does not fix the display. Coordinate a bounded one-shot
  refresh with backend; do not add per-second DB writes or a permanent timer service.
- Refresh quick-choice results on relevant committed revisions. Idle home/overview must
  refresh dates/totals on visible lifecycle/day changes without inventing data mutations.
- Map typed errors instead of raw diagnostics; STORAGE must not promise unchanged external
  bytes after a partial SAF write or uncertain recovery result. Retain source locale in
  CSV mapping and fields after mapping validation errors.
- Preserve original custom names, expose intended type/area binding, label DST day-ribbon
  boundaries honestly, and handle reduced-motion changes. Retain OEM/device/TalkBack QA.

Current resource facts: no ordinary-timer FGS or per-second DB writes; history graph
and report/choice indexes grow with stored history. JVM timings/heap observations are
not phone RAM, PSS, battery or a low-consumption acceptance claim.

Build inputs for the new own-code JNI library: `core/src/main/c/private_copy_io.c`,
Android.mk/Application.mk, NDK `30.0.16248370` (r30), C11, no selected C++ STL.
The official Linux NDK archive was checked against its published size/SHA-1 before
installation. The disabled F-Droid draft pins the same NDK and source code commit;
Metadata lint was run with fdroidserver 2.4.5 in an isolated directory and the
current official Time Tracker category supplied as context. The first context-free
run reported a category error because no category catalog was loaded; the recipe
category itself was correct. The contextual run exited 0. An enabled-build metadata fixture also exited 0;
its only change was removing disable in the isolated copy. No fdroidserver build,
store submission or signing initialization ran. New native compilation does not resolve the separate
AndroidX graphics-path release provenance/static-runtime notice questions.

The first-pass native inventory is now dated in NATIVE_LICENSE_EVIDENCE.md: Library v4 contains both AndroidX graphics-path and own tracker_copy_io in four ABIs. Existing LICENSE/NOTICE are present; AndroidX provenance/static-runtime clearance stays open.
