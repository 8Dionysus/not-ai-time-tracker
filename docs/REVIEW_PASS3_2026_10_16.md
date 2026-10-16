# Parent personal review pass3 corrections

Reviewed package: `754196e98d590083a93f290dcbd777590e841327`.
The parent accepted pass2 and completed the third personal full review:
201 code/build/test/tool/patch files, 30034 lines, 1364 source hashes matched.
The finding set is closed: P3-001 through P3-006. P3-001 is independently accepted;
the remaining corrections and final delivery await targeted parent acceptance.
Main remains unmerged. No agent, model or execution environment was changed.

## P3-001: bounded historical references

Two positive certain stopped intervals of one classification, S1 `[1000,2000)`
with Note A and S2 `[3000,4000)` with Note B, expose an identity mismatch:
frequency/recency/quick discovery inside `[1000,2500)` shows S1, whereas the
old classification-only reference resolves the canonical S2 outside the window.
Core start copied Note B; the strict VM equality guard rejected the valid shown S1.

Reproduction source: `b799695b2703d235ea016bdda83afc60f12e59cc`.
Original results: 3/3 core failed; 5/6 app failed (Room 3, VM 2).
The VM revision-staleness regression passed even before the fix.

Fix/test source: `f2de99cf79ef8a5190857b59dbc7494504600b1d`.
10 core and 33 app checks passed with no failures, errors or skips.
Scope includes shared Memory/Room scenarios, existing localization/window/P2
history cases and the complete VM regression class. Raw XML and source manifests
are in `evidence/review-pass3-2026-10-16/`.

### Backend integration contract

`ChoiceReference` has optional `sourceSessionId` and `sourceSegmentId` fields.
History references bind the displayed occurrence; their fingerprint covers both
canonical classification and the exact literal note. Resolution locates that
occurrence in its classification variant, without canonical fallback. Changing
the note/classification, removing the source, or changing dataset/barrier fails
STALE. Another occurrence being added does not rebind the reference. Existing
VM token and full displayed-entry guards are unchanged.

Consumers must use `ChoiceView.reference(shownEntry)` or the returned quick
reference, serialize it whole and send it unchanged. `resolve(String)` remains
canonical discovery; it is not a substitute for resolving a displayed reference.
Old four-field references still decode: TYPE/CARD/FAVORITE retain their existing
fingerprint semantics; old HISTORY references without a source fail closed and
require a freshly displayed choice. References are action transport, not Dataset
archive fields: Room and JSON archive versions do not change here.

Coverage includes bounded frequent/recent/quick and unbounded search/recency,
serialization roundtrip, START/SWITCH after store reopen, source note/classification
change and removal, old non-history transport, ambiguous old history rejection,
tampered occurrence rejection and a later canonical occurrence with Note C.

## P3-002: skipped widget opt-out

Reproduction source `343b860aff8f34fe766c516a19c38b6a746bb997` shows the enabled
widget surviving V1 disable, an independent V2 revision during preparation, and
the next disabled collector view. Supplemental source
`0b2e4244dcd2f38409b37212c7bb94aa4d16f65e` independently reproduces it on Room.
Tests collect the actual `tracker.views` flow using `TimerSurfaceObserver`, the
same adapter now called by AndroidRuntime. No `forceWidget=true` workaround.

Fix `168bd0f`: refresh acknowledges successful current publication with Boolean.
The observer updates its `previous` only after success. Stale attempts perform
no effects; preparation/publication failure cannot claim successful completion.
Independent effects still run when another surface fails. Every installed widget
converges to the current enabled/disabled policy, including a skipped intermediate
view or a different publisher. `forceWidget` remains source-compatible; rendering
installed widgets no longer depends on that flag or a remembered transition.
The final domain gate and immutable shown action tokens are unchanged.

Overlay stop-request transitions use the same acknowledged history; the test
checks the real platform stop request after a skipped transition. The service
also observes disabled settings itself, so a persistent overlay retention defect
is not claimed. Notification/shortcut behavior and RemoteViews rendering stay
as before. No additional background loop or automatic timer command.

## P3-003: technical Focus settings lost update

The actual checkbox invokes `configureTechnicalFocusSignal`. Its old full-Settings
read followed by a fresh token overwrote concurrent zone/boundary, surface opt-out,
schedule and long-record settings. `343b860` and `0b2e424` contain original failing
results. Tests inject an independent write after the read and before transaction
entry; after-token races must fail STALE rather than rebase. An explicit retry
then enables Focus while preserving all neighboring settings.

Fix `3a53781` uses existing `Tracker.configureFocusSignal(expected,enabled)`.
Only the requested field changes inside the core transaction. No new settings
authority or automatic retry. The small internal adapter exists so tests exercise
the production checkbox action, not a duplicate implementation.

## P3-004: typed Focus delivery at actual UI callers

The VM used receipt-only deprecated `deliver(Context,FocusSnapshot)` to decide
whether to emit a local fallback. A first DUE had no receipt, so the VM emitted
a fallback before a later runtime refresh posted the notification. Original VM
fresh/unknown outcome regressions fail at `343b860`; previous/blocked cases provide
controls. The reachable technical adapter is tested on an actual Activity too.

Fix `7169027`, completion checks `93d516d`: both callbacks run fresh
`FocusSignals.deliverResult(context,tracker)` on IO. Only BLOCKED_BEFORE_POST with
the same currently DUE signal key permits a local cue/dialog. POST_ACCEPTED,
PREVIOUS_RECEIPT, OUTCOME_UNKNOWN and NOT_DUE never trigger an automatic fallback.
Previous receipt is not presented as successful physical delivery. No replay or
phase advance. Technical callbacks return their Job for deterministic completion
verification and use existing structured error formatting. Tests wait for actual
VM publication, not just a reserved receipt, and for the technical callback to
finish before checking absence of a duplicate dialog after permission loss.

## P3-005: preserve a refused entry Save

Original real MainActivity form callbacks at `343b860` dismiss manual invalid/
overlapping drafts and stale correction previews before their command fails.
The fixtures retain literal note, selected Card ID and times, then require a new
explicit corrected Save to succeed. A transaction-entry barrier inserts an
independent settings revision between shown correction preview and apply.

Fix `ffc974f`, ownership regression `282495a`: the existing command runner returns
an optional completion result to the retained form target. `EntryFormTarget`
adds `saving`, `error` and `reviewNonce`. `submitEntryForm(targetId,...)` admits one
pending submission per form, uses existing manual/applyCorrection commands and
closes only the same target after confirmed success. Failure retains all fields,
shows the current error and invalidates/recomputes the preview; it never applies
the new preview automatically. Completion of an older form cannot dismiss a new
one. The additional barrier regression proves no duplicate mutation while pending
and preserves a newly opened target when the old command finishes.

Frontend wiring: EntrySheet receives `targetId` from App, submits through the VM
and does not call dismiss immediately after Save. Existing saveable inputs and
picker behavior are retained. Save is disabled while its command is pending;
Cancel remains an explicit user action. Layout/design/assets are not rewritten.
Configuration recreation remains covered; process-death persistence of the
retained target/inbox is outside this claim.

## P3-006: initial retrospective boundary

Fix `2acaa96` clamps the initial minute-rounded boundary to `[segmentStart,now]`.
When rounding would precede start, the exact seconds/milliseconds are retained.
Core validation stays unchanged. Actual MainActivity -> EarlierSheet -> activity
picker callbacks at `0b2e424` fail INVALID_INTERVAL for a start with seconds and
for a millisecond start with only 250ms elapsed. The fixed callbacks create the
explicit retrospective switch at that exact start, keeping the zero-duration
historical identity and all elapsed work in the selected session.

## Verification checkpoints and remaining acceptance

The fixed cohort at `2acaa96be379b43a88484fa3cd61ff0c52ac149f` passed 4 core and
69 app tests, without failures/errors/skips. It includes Memory/Room P3 history,
the complete TimerSurfaceRaceTest, technical settings/export adapters, VM/form
regressions and FocusDeliveryRaceTest. This is targeted verification, not a new
aggregate or new APK. The aggregate is running at `282495a` after stronger callback
completion and pending-form ownership checks; its result will be recorded below.

First cohort source `d0c653f` failed to compile a test-only generic call to the
non-generic Robolectric Application getter; no new XML is attributed to that run.
At `343b860`, 14 app tests ran with 11 failures: nine product reproductions and
two Earlier fixture failures from calling scrollTo on an unscrollable button.
Those two are not product evidence. `0b2e424` fixes the selector and gives five
actual failing reproductions (two Earlier, two Room opt-out, one settings).
All original logs and XML are preserved; no regenerated reports replace them.

Parent targeted acceptance of P3-002..006/delivery remains required before main.
Physical OEM/device/TalkBack/RTL, broader frontend resource/overlay P2/goal-edit,
process-death editor/inbox persistence, catalog source rights/native-speaker QA,
and independent server reproducibility remain outside JVM/APK claims. No fourth
full parent review is planned. No independent runtime model/effort attestation.
