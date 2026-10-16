# Parent review pass1 corrections

Baseline: public main `59d0ba60e05683d7cab7cd5e0b0237f4094d365d`.
Corrections stay on `fix/review-pass1-2026-10-16` pending the parent's acceptance.
This document records implementation boundaries; exact executed sources, raw XML,
commands, outcomes and artifact hashes accompany the final receipt. It does not
claim the parent's second review, device acceptance or new linguistic expertise.

| Finding | Resulting behavior and integration contract |
| --- | --- |
| P1-001 recovery exclusion | Removing an uncertain tail preserves an empty historical Session/Segment identity when facts, measurements or schedule links require it. The empty interval carries no duration, overlap or previous-recorded-end. Checked indexes and both archive readers admit this identity after wall-clock rollback while retaining all positive-interval/future audit checks. Unreferenced empty sessions still disappear. Shared Memory/Room tests cover ordinary/Focus recovery, each reference kind, reopen, Undo, full and selective transfer. |
| P1-002 selective closure | The same content predicate that selects format4 also activates complete dependency closure. Bounded metrics/common units and dependency-only personalization cannot produce split format4 groups. Existing COPY/replay/Undo/retention/re-export and legacy1–3 admission remain. |
| P1-003 Focus delivery | A durable attempt is reserved before preparation. Immediately before posting, a checked store transaction rechecks dataset/barrier/phase/due/settings/permission against the fresh clock. A stale prepared signal is blocked and never replayed. Background callbacks first check for an existing database; an absent store cannot bootstrap one. No render redesign or automatic timer start. |
| P1-004 report boundaries | Home/overview/technical summaries use the same explicit ReportingDayPolicy. A collected idle view advances at the boundary without a Dataset write. Ribbon labels use elapsed fractions in the report zone, including23/25-hour DST days. |
| P1-005 effective goals | Overview visibility follows the effective goal version for the requested day; DISTINCT_RECORDED_DAYS uses day units. Future enable/disable does not alter today's list prematurely. |
| P1-006 restore ownership | A preview belongs to its screen/dialog request until claimed synchronously by an apply job. Cancel/back/replacement/disposal/recreation/late preparation release only that preview's durable lease. A claimed apply keeps its lease through completion, including cancellation before coroutine start; owner disposal cannot close it early. Release does not delete source bytes. Settings, legacy VM entry and TechnicalActivity use this ownership boundary. |
| P1-007 delete review | Even one segment requires confirmation when subject links, sets, event quantities or rate facts depend on it. The preview exposes exact consequences and correction counts; cancel writes nothing, apply and Undo use the existing domain command. Schedule-linked deletion remains prohibited. |
| P1-008 reviewed picker and atomic draft | Picker data/children/search follow committed views. Choice rows pass the shown reference and token; stale favorites cannot silently rebase. The 'whole area' row copies the parent guards. Active draft switch and note commit once; failure preserves the draft and Undo restores the prior Dataset. |
| P1-009 STORAGE presentation | A storage exception may follow a successful commit. The message requests current-state inspection and does not assert unchanged data. A fault-after-commit fixture verifies actual persisted readback. |
| P1-010 independent fingerprint | Fingerprint schema2 includes non-null manualConfirmation for Room10+. SQL NULL/older absent columns retain equivalent canonical meaning. Schema1 is not comparable when confirmation exists; raw-file/all-table/device attestation is not implied. SQLite fixture tests do not contact a device. |
| P1-011 CSV interpretation | New row receipts bind the versioned interpretation of duration, source activity/category and note as well as raw fields/start/end/zone. Same external ID with a changed interpretation conflicts. Historical receipts are never rewritten; legacy replay requires agreement with the saved fact and remains supported after COPY ID remapping and full restore. |

## CSV compatibility boundary

The strong receipt is an opaque SHA256 in the existing ImportOrigin.rowHash; its
canonical input starts with `csv-interpretation-v2`. No persisted domain field or
second importer is added. Room12/full JSON14/selective4 and their legacy readers
remain unchanged. Source fields, namespace/key and original file hash remain intact.
Classification assignment remains an explicit preview mapping, separate from CSV
column interpretation.

Legacy receipts did not retain a parse profile. Their fallback verifies the old
receipt and the saved stopped single interval (start/end/duration), note and any
explicitly supplied classification mapping. An edited/ambiguous legacy fact fails
with SOURCE_ID_CONFLICT rather than rewriting its provenance. An empty classification
map can replay a proved saved fact, including COPY-local IDs; it does not attest a
historical choice of activity/category columns that the old receipt never recorded.
New receipts distinguish all four interpretation changes without relying on current
facts. Neither CSV nor this compatibility path becomes full selective archive.

## Evidence scope and remaining acceptance

Original raw failure and success XML are retained by checkpoint, including fixture
compilation failures as compilation failures. A passing older checkpoint is not
new acceptance. Final full JVM/Robolectric, unsigned APK, lint and dependency checks
are reported separately. No physical OEM/device/TalkBack/RTL checks, independent
server reproducibility, store publication/signing, release keys, real-user import/
delete or independent runtime model/effort attestation is claimed. UI overlay P2,
goal-edit and the broader frontend work remain with their owner.
