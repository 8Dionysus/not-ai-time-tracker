# Parent personal review pass3: open findings

Reviewed package: `754196e98d590083a93f290dcbd777590e841327`.
The parent accepted pass2 and is performing the third personal full review.
This is an open checkpoint, not acceptance or completion of the review.
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

## Subsequent findings under reproduction

- P3-002: runtime collector loses a skipped widget opt-out transition. Overlay
  stop-request consistency will also be checked; the overlay service separately
  observes false settings, so persistent overlay retention is not established.
- P3-003: technical Focus checkbox can overwrite independent Settings changes.
- P3-004: receipt-only legacy Focus delivery can cause duplicate VM fallback cues.
- P3-005: asynchronous refused Save dismisses an entry form before its outcome.
- P3-006: initial Earlier time rounding can precede the certain segment start.

These are not yet claimed fixed at this checkpoint. Existing callback bodies
were extracted into small production adapters for deterministic regressions;
reproduction preserves their faulty successful-return behavior. No UI redesign.
