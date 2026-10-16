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

## Original A–F package coverage at this checkpoint

This corrective pass retains the existing complete engines and migrations rather
than introducing a parallel timer/importer. The following links describe the actual
backend and explicit acceptance boundaries; the final aggregate run covers their
shared regression suite.

| Scope | Retained implementation / correction | Remaining boundary |
| --- | --- | --- |
| A localization/catalog | [Localization/content interfaces](CATALOG_CONTENT_PACKS.md), typed errors and ID/meaningVersion/locale labels;11 bundled locale packs plus Russian source; stable user originals/history. Picker uses shown-choice guards. | Frontend resource migration/language-authority binding and native-speaker/rights approval. No zh-Hant or unreviewed empty pack is advertised as ready. |
| B schedule | [Schedule/receipt interface](INDEXED_SCHEDULE_RECEIPTS.md), series/overrides/start links/projections, explicit start, canonical receipt conflict checks and [delivery ports](SCHEDULE_DELIVERY_HANDOFF.md). Recovery preserves start-link identities. | Three-surface rendering and actual Android/OEM permission/delivery acceptance. |
| C persistence/archive | Existing Room12/full14/read1–14 and selective1–4 with legacy migrations, Memory/Room parity, preview and verified rollback. P1 adds reference-preserving recovery, rollback-clock parity and restore-review lease ownership. | Real-device legacy migration and interruption acceptance remain distinct from fixtures. |
| D manual copy cleanup | [Descriptor/sealed-archive contract](SEALED_ARCHIVE_CONTRACT.md), registry/leases, explicit plans/confirmations, journal/quarantine/resume and exact partial outcomes retained. Restore preview cancellation now releases exact leases without deleting bytes. | UI integration, legacy-owner retirement witness and Android filesystem/device acceptance. No age purge or real-owner deletion performed. |
| E selective transfer | [Portability](PORTABILITY.md), dependency closure/origins/COPY/replay/Undo/retention retained. P1 fixes content-derived format4 closure and CSV interpretation receipts while preserving full JSON replacement and legacy/COPY replay. | Product selective-transfer screens remain frontend-owned; CSV is still a limited append format. |
| F release engineering | Existing own-code Apache2, Onest OFL/third-party notices, API36, unsigned builds, actual merged manifest, pinned dependency hashes and disabled F-Droid draft. Final artifact receipt records fresh outcomes. | Catalog rights, native publisher provenance, independent reproducibility, device/OEM/TalkBack/RTL, store/signing/publication and UI P2/goal-edit remain open. |

## Fresh aggregate result

Runtime/build source `0ed5b253e65cf4fec375f1a93c7b48f0e47cb0e4`:189core +180app, zero failures/errors/skips;
Python fingerprint3/3. Unsigned debug/release, lint0errors/0fatal/68warnings,
zipalign-P16, min26/target36,24 locale assets/source/baseline equality and all65
external dependency hashes pass. [Exact artifact hashes and commands](../evidence/review-pass1-2026-10-16/final-result.json)
and [original XML/checkpoints](../evidence/review-pass1-2026-10-16/README.md) are retained.
The final delivery commit may add only documentation/evidence after this source;
its receipt separately proves identical runtime/test/build/schema/assets bytes.
