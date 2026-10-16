# Combined journal backend integration

This integrates the approved fourteen-item journal extension after public source
70a7155b3ef548be4638cf71849d3b05dbbef7a6. A remains the integration owner; B reporting
and C subject deltas were independently transported and verified against that base.
Producer results are historical component evidence, not acceptance of this merge.
No new locale text is included in the public branch. No UI renderer was changed.

## Shared persistence boundary

Room12, full archive14/read1–14, selective4/read1–4. One migration11→12 validates
legacy defaults and preserves all existing rows, active/recovery state and IDs.
New default fields are omitted so old selective content hashes remain stable.
Full replacement still requires preview and verified rollback. Selective transfer
uses the existing inspect→plan→preview→apply/resume engine with dependency closure,
COPY identity rewriting, replay receipts, Undo and explicit metadata compaction.

Full archive audit guards inspect live subject records and every retained origin,
operation source, inserted and previous-origin snapshot. A compacted hash capsule
contains no removed timestamps to validate. Signed change audit time cannot precede
either basis observation; historical effective dates remain independent.

## Reporting interfaces for Opus

`Settings.reportDayStartMinute` and `ReportingDayPolicy(zone,startMinute)` define civil
report labels and boundaries. Local DST gaps shift by the gap duration; overlaps
use the earlier offset. This is the explicit reporting policy, separate from
schedule occurrence resolution. All report/calendar/goal computations use the
specified policy; changing it never rewrites recorded facts.

`GoalMetric.DISTINCT_RECORDED_DAYS` counts distinct labelled days with positive
recorded contribution, not launches or planned occurrences. `saveGoal` records an
effective definition revision; default NEXT_PERIOD preserves completed periods,
CORRECT_CURRENT is explicit. Period/zone/boundary changes require a new goal ID.
`goalDefinition`/`goalProgress` expose the effective definition and evidence.

`saveReportQuery`/`removeReportQuery`, `savedReports`/`savedReport` persist stable-ID
filters, grouping, fixed or relative windows and their policy snapshot. Missing
and archived references are exposed and never broaden a query. Queries and goal
revision dependencies travel in existing full/selective archives.

`reportSummary(...,includeNotes=false)` produces deterministic reviewable text
from the captured report. Notes require explicit opt-in. There is no network AI,
automatic send, sharing activity or new permission. Opus owns preview/ACTION_SEND.

`quantityReport(start,end,filter)` and `savedQuantityReport(id)` capture ONE current
moment and ONE immutable dataset revision. `QuantityReport.report` carries that
same window/asOf/policy/filter; `.quantities.totals()` sums event quantities only.
For a classification filter an event must have an explicit session link AND lie
inside a matching historical ReportRow [start,end). Session.selection is never a
shortcut. Global queries retain independently timed sessionless events. Empty
candidate results do not become global queries. Unknown effective time remains
in `unknownTime`, outside totals. `excluded` exposes stable reasons for missing
classification, outside matching interval, future audit/effective time, and mixed
whole-session measurements. A mixed whole-session value is never attributed to a
matching slice. Legacy whole-target values stay separately labelled; no time
apportionment and no silent addition to ReportText.

## Subject and quantity interfaces for Opus

`defineSubject(CardSubject)` assigns a role to an existing Card.id. `saveSubjectEvent`
creates a separately identified event linked optionally to that card; explicit
`linkSubject`/`unlinkSubject` associate sessions. Counts deduplicate stable IDs,
never names. Ordinary tracking has no mandatory subject role.

`observeSubject` stores StateObservation with definition/version/unit, effective
and audit times, source and optional correction predecessor. `subjects().stateAt`
returns latest known state, explicit equal-time conflict or unknown time; state
values are not summed. `recordSubjectChange` records a signed exact delta between
two compatible ordered observations. Continuous unsuperseded bases are required
for a summed change. Decimal remains nonnegative; SignedDecimal is separate.

Metric.bounds contains semantic endpoint labels within its immutable meaning
version. `recordSet` stores ordered repetitions/load/units and explicit total versus
per-implement meaning. Set totals convert compatible units and refuse ambiguous
inputs. These are user records, not health recommendations.

`recordEventQuantity` distinguishes occurrence time from audit time, retains unknown
time and correction lineage, and requires explicit subject/session links when
session-bound. `pairDistanceTime` pairs a concrete distance measurement and target
with recorded or explicitly supplied moving time. `subjects().rates` uses summed
compatible distance / summed explicit time, exposing zero, overlap, clipped,
changed-target and incompatible inputs. It never averages speeds or borrows time
from another session. Whole-target legacy measurements retain their old meaning.

All commands route through Tracker and the StateStore transaction. Subject records,
bounds, links and correction dependencies travel in full/selective archives;
selected sessions do not pull hidden unrelated sessions. Correction/conflict peers
are explicit closure. See source types for exact signatures and typed results.

## A-owned interfaces

See JOURNAL_A_CONTRACT_2026_10_16.md for ancestor-aware existing ChoiceView search,
Card/Favorite profiles and note policy, typed recording-gap drafts, and atomic
previewed batch metadata correction with Undo. No second timer/importer/catalog
engine was introduced.

## Acceptance boundary

Final combined run evidence is recorded separately with its exact source SHA.
JVM/Room/Robolectric checks, APK builds and lint do not establish physical-device,
OEM, TalkBack, RTL or UI integration acceptance. Opus owns Compose/resources,
TechnicalActivity, RemoteViews, overlay P2 and goal-edit presentation. Store signing,
publishing, native publisher provenance and independent reproducibility remain
separate release conditions. Private eleven-locale functional evidence must be
bound to its actual private source/APK, never inferred from a public APK.
