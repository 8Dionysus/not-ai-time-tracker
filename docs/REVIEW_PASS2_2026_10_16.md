# Parent personal review pass2 corrections

Reviewed baseline: `b64e4fd68ba97a78ee4b773cce9f5a3d391107f7`.
The parent completed personal reading of196 code/build/test/tool/patch files,
29312 lines and independently compared all1202 source hashes. The closed packet
is P2-001…009. These corrections remain on `fix/review-pass1-2026-10-16` for
independent acceptance; pass3 and a main merge are not claimed.

| Finding | Correction and regression evidence |
| --- | --- |
| P2-001 report index | Closed empty identities stay out of the binary-search interval index. A marker inside a longer fact has a nonmonotone end and no interval. Shared Memory/Room oracle checks preserve3000ms in `[7000,10000]` from `[1000,11000]` despite empty6000; more bounds, filters, ordering, reopen/calendar parity and nonempty calendar spans with duration0 are covered. Source facts/IDs/notes remain. |
| P2-002 start backfill | Overlap ignores exactly `isEmptyInterval()`. Shared Memory/Room marker6000, active10000, proposed5000 succeeds only after explicit confirmation. Real positive/uncertain and nonempty zero-duration calendar spans still refuse. Preview writes nothing; running anchor, marker, notes, Undo, reopen and full restore are verified. |
| P2-003 history canonical source | A classification with a positive certain closed occurrence uses its latest recorded occurrence as the canonical record. Empty/uncertain-only variants remain explicitly searchable. Recent/quick/reference/search round trips and four actual VM guarded starts cover later empty/uncertain records. Strict token/reference/entry guards remain. |
| P2-004 technical export lifecycle | TechnicalActivity is reachable through MainActivity Settings/repair routes and the existing non-exported manifest activity. Before external chooser launch it acquires the durable lease; launch failure consumes only that operation's result. Callback consumption commits before destination I/O. Explicit cancellation before any chooser is terminal; active chooser cancellation keeps its lease and ID until its real result and blocks a second launch/preparation. Preparation completion/failure preserves the latest cancellation/result flag. Six actual TechnicalActivity tests cover success, cancel, same-byte retry, recreation, blocked writer cancellation and active chooser cancellation; exact native catalog registration and foreign lease retention are checked. |
| P2-005 terminal surface effects | The store-aware suspend refresh prepares existing renderers on IO, then checks dataset/revision/barrier, shown token/target/mode and Focus phase before short effects under the store transaction gate. A stale invocation has no effect, including cancellation of a newer notice. There is no publication lock before the store gate. Shortcut/widget/notification preparation and effects fail independently. All runtime/Activity/receiver/widget callers use the guarded API. Nine tests include actual platform effects across Memory/Room, capture/render barriers, independent opt-outs, pause/barrier races, Focus due at unchanged revision, original PendingIntent tokens, preparation failure and default-view/store IO away from the caller main thread. |
| P2-006 unknown storage outcome | TimerActionReceiver's common widget/notification handler retains the CoreError code (`surface-results.lastCode`) and asks the user to inspect current state. It never guarantees unchanged history. Both actual handler tests commit1000ms, throw post-commit IOException, read back STOPPED/1000ms and verify the STORAGE message/code. |
| P2-007 shown historical segment | `Tracker.repeatSegment(expected,sessionId,segmentId,application,notePolicy)` uses the exact saved segment and existing HISTORY note policy, in the existing ordinary start/switch transaction. Session-level repeat retains its old latest-segment semantics. VM row repeat checks current source selection/note; guarded history picks use sourceSegmentId. Shared Memory/Room tests cover A→B in one session, START/SWITCH, default/CLEAR/COPY_SOURCE, explicit override, stale revision, reopen and SWITCH Undo. START keeps its existing no-Undo contract. VM tests verify early A rather than B, note policy and refusal of an unseen note at a fresh revision. |
| P2-008 editable form recreation | A retained VM owns the open form target. Existing Compose saved state keeps start/end, chosen Classification, Label and note; no editor or persisted domain entity is added. CorrectionPreview stays transient and is recomputed against the current committed view. Actual MainActivity controller recreation tests enter all fields through the form/card picker, verify no domain writes, restore every value including Card ID, then explicitly Save. A concurrent fixture fact before correction recreation remains after the fresh preview/apply. |
| P2-009 incoming text draft | MainActivity's existing ACTION_PROCESS_TEXT adapter enters a retained FIFO inbox. Current and incoming texts stay separate until the existing-style confirmation accepts or declines replacement. Request IDs reject stale/double decisions; consumption of an old in-flight draft cannot clear a newly accepted draft. No timer/history command is automatic. Actual Activity tests cover first/second input, both decisions and recreation while/after pending choice; a VM test covers three inputs and stale decision IDs. |

## Integration changes for Opus

Use `suspend TimerSurfaces.refresh(context,tracker,view = null,previous = null,
forceWidget = false)`. The default view read and all preparation/store work run
inside Dispatchers.IO. A supplied view retains its original tokens. Callers must
not replace tokens or retry with another timer target. RenderViews/layout/assets
are unchanged; the shared wiring changes pass the owning tracker. VM
`refreshSurfaces` accepts a suspend callback.

Use `repeatSegment` for a displayed historical segment; use legacy `repeat` only
when the requested meaning is the latest segment of an entire session. Existing
HISTORY note policy remains COPY_SOURCE by default, with Card policy/explicit
CLEAR or COPY_SOURCE honored. Draft-owned notes keep their existing precedence.

The existing form has one retained `EntryFormTarget` authority; other sheets keep
their previous ownership. Editable fields survive Activity configuration
recreation via Compose saved state. Derived correction previews are never saved
or treated as confirmed. The incoming text inbox owns pending replacement values;
only an explicit decision replaces a still-unused draft.

## Evidence and limits

[Original checkpoint XML/source hashes](../evidence/review-pass2-2026-10-16/README.md)
retain actual failure and success outcomes. Harness failures (paused ActivityScenario
cleanup, incompatible instrumentation-looper idling, selector ambiguity and an
incorrect START Undo expectation) are explicitly separate from product failures.
Direct Robolectric ActivityController callbacks, retained VM and saved-instance-state
restoration provide the recorded Activity recreation evidence, not a device run.

Room12/full JSON14/read1–14/selective1–4, all locale payloads/provenance, notices and
earlier P1 fixes remain. No user data import/delete, signing keys, store upload,
extra model executor, main merge or independent runtime model/effort attestation.
Broader frontend P2/goal-edit/resources/language authority, real OEM/device/
TalkBack/RTL and actual delivery/permission checks, process-death persistence of
retained editor targets/text inboxes, legacy cleanup-owner retirement witness,
catalog rights/native-speaker QA/native provenance and independent reproducibility
remain explicit boundaries. Full aggregate/build outcomes are recorded only after
execution in the final receipt.

## Fresh aggregate checkpoint

Tests on `5289806d3cf5a373f5cbaa5889e64c32783777bc`:194core +216app,0failures/
errors/skips; Python fingerprint3/3. Exact raw XML/commands/log binding are in the
checkpoint evidence. The JNI test uses a unique path with identical host ELF bytes
for each sandbox; production JNI is unchanged. Disabled F-Droid recipe source5289806
lint/scanner pass with one retained gzip fixture warning. Unsigned APK/build/lint/
dependency outcomes will be appended only after the separate build completes.
