# Combined journal fourteen-item verification, 16 October 2026

Runtime source **8bbd19ae88dcb446a29e2e13bb5ca9f2cc7908cc**, local branch backend/journal-a-2026-10-16,
after read-only main70a7155b3ef548be4638cf71849d3b05dbbef7a6. Final documentation/recipe
commits are separate; they do not change compiled source. No new public push, PR,
merge, store upload or release key. Requested model/effort are not an independently
verified runtime attestation.

All fourteen backend interfaces are mapped in JOURNAL_14_COVERAGE_2026_10_16.md;
Opus wiring is in JOURNAL_OPUS_INTEGRATION_2026_10_16.md. Room12/full14/read1–14 and
selective1–4 are the merged floor. Production app changes are limited to the shared
TrackerDatabase migration; Compose/ViewModel/resources/TechnicalActivity/RemoteViews
remain unchanged from main. Personal originals and history are not translated.

## Fresh execution

`run-gradle :core:test :app:testDebugUnitTest --max-workers=1 -PtrackerUnsigned=true
-PtrackerCleanupLibrary=<isolated-host-JNI-fixture> -Pkotlin.compiler.execution.strategy=in-process`

**163core +131app**, zero failures/errors/skips, BUILD SUCCESSFUL4m55s. This includes
shared Memory/Room A/B/C tests, full/selective transport, legacy hash vectors, live
and retained audit guards, signed-change basis time, migration rejection/interruption
rollback, COPY/Undo/compaction/replay, independent same-name Card/subject origins,
no hidden session selection, and copied parent-type/grouping-axis query references.

`run-gradle :app:assembleDebug :app:assembleRelease :app:lintRelease :app:releaseInventory
-PtrackerUnsigned=true -PtrackerCleanupLibrary=<isolated-host-JNI-fixture>
-Pkotlin.compiler.execution.strategy=in-process`

BUILD SUCCESSFUL2m26s. Both public APKs are unsigned; zipalign-P16 passes. aapt2
confirms compile/target36 and min26. Release embeds the runtime Git revision;
debug has no embedded VCS record and is bound by the exact invocation/source manifest.
Lint0errors/0fatal/68warnings. Inventory66 artifacts:65 external SHA256 values match
the retained POM-declared license inventory, plus own Apache-2.0 core.jar. This is
not a new legal/native-publisher provenance claim. Onest OFL and third-party notices
remain intact. Merged manifest SHA256 `b69cc3f66930285c46838c34e1ef3d08ce027bd24756997800150756f22d6f7c` is byte-identical
to accepted C949 and contains no INTERNET permission. No new network AI/data flow.

| Artifact | Bytes | SHA256 |
| --- | --- | --- |
| Public debug | 15447666 | `07258ca30ca1b1b4bbd28f01ce47070be51569a2d178f8b6b70dd774583f638e` |
| Public release | 9196222 | `d4d898ce94a0ae619263d9ca3a9b1818ed1c7d6c9f50a0084e2bcbceb056ad38` |
| Private11 release | 11170333 | `9dd15ad13cc743987666d22aa615cf1776fc9b003748edf55cefbbf10430f403` |

## Private eleven-locale evidence

Private source **b3d79bb9d6a6e868a40086490c1c85384a45ce2a** has all273 public core/app source/test/build/schema
files byte-identical to runtime8bbd19a, with accepted locale sidecars and two private
positive fixtures added. `:core:test --tests '*PrivateCatalogPackIntegrationTest*'
--tests '*CoreScenariosTest.combinedQuantity*' :app:testDebugUnitTest
--tests '*PrivateBundledCatalogIntegrationTest*' --tests '*RoomScenariosTest.combinedQuantity*'
:app:assembleRelease --max-workers=1 -PtrackerUnsigned=true` with the same JNI/compiler
options passes **5core/5app**, BUILD SUCCESSFUL5m43s. These are actual executions,
not the earlier cached private component result.

All24 sidecars equal the prior accepted private sidecars and the new APK assets.
The11 tags retain26235entries/74723fields with reviewed quality/offline availability
separate from SOURCE_RIGHTS_REVIEW_PENDING/licenseNull and unclaimed native-speaker QA.
Original draft ZIP/provenance remains private. Public source contains only the existing
generic locales/manifest.json metadata, without index/per-locale payloads. Private
translations are not published in public Git and are not UI resource localization.

AGP's private worktree VCS record is literally `generate_error_reason: NO_VALID_GIT_FOUND`.
It is not replaced with a claimed revision. Independent byte comparison binds both
private DEX entries to public8bbd19a. Four private-copy native ELF files differ only
in .note.gnu.build-id; all other sections, including text/rodata, match. Common source,
full build log and asset manifests bind the private checkout. This is an artifact
metadata limitation, not a physical-device or reproducibility claim.

## Source and history integrity

B/C exact transferred patches were verified against base70a7155:27 B and14 C base/result
Git blobs. Gzip/decoded hashes are retained. Owner merge commits4df0568/cbfca34 preserve
both domains, one migration and one full14 writer. Further fixes cover every retained
audit snapshot, signed-change audit ordering, half-open filtered quantity attribution,
shared selected identity closure, independent roots/origin membership, correct loss
accounting, and bounded/streaming legacy-field rejection parity. No new importer/timer.

Failed new fixtures (segment creation API, switch/new-session assumption, old-plan
owner reuse) and a detected actual multi-Card COPY/origin defect are retained separately.
Source ddfb33b had a picker Compose timeout during concurrent release compilation;
its isolated picker passed unchanged, and the subsequent full8bbd19a app131 passed
with one Gradle worker. Root cause is not established; the failed log is preserved.
No failed result is silently re-labelled as successful.

## F-Droid and remaining conditions

fdroidserver2.4.5 scanner on8bbd19a:0errors/1 retained gzip-fixture warning; scanner
and metadata lint exit0. The actual HTTPS disabled recipe is pinned to8bbd19a and
lints0. A disposable local-source clone supplied the unpublished commit for scanning;
there is no independent server build or reproducibility comparison. Editorial ZIP
bytes remain intact. Public main still70a7155; no external publication was requested.

Backend fourteen-item implementation and source-bound checks above are complete.
Opus owns new controls/resource migration, Android language bindings, summary preview/
share, effective-goal labels/editor, quantity projections, schedule surface rendering,
overlayP2 and actual UI acceptance. OEM/device/TalkBack/RTL/notification/Focus permission
and real delivery checks remain open. Legacy cleanup-owner retirement still requires
a trustworthy historical witness. Native publisher provenance, store choices, signing
and an independent reproducible build remain release conditions. Unit tests close
none of these by implication. No real-user import/delete was performed.

Raw exact XML/logs, source/bundle/diff, manifest/APK hashes and private overlay/provenance
are delivered in the private full ZIP and readable text appendix. Repository summary:
[evidence](../evidence/journal14-2026-10-16/verification.json).
