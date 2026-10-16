# Parent review pass3 evidence

The parent closed the finding set P3-001..006 after its third full personal review.
P3-001 is independently accepted; the remaining deltas and delivery await targeted
acceptance. These checkpoints alone do not establish final acceptance.

- `p3-001-b799695-reproduced`: original failing XML and source hashes for
  `b799695b2703d235ea016bdda83afc60f12e59cc` (core 3/3 failed, app 5/6 failed).
- `p3-001-f2de99c-fixed`: original passing XML and source hashes for
  `f2de99cf79ef8a5190857b59dbc7494504600b1d` (core 10, app 33 passed).
- `p3-002-006-343b860-reproduced`: 14 app tests, 11 failures: nine actual
  reproductions and two Earlier scroll-selector fixture failures, explicitly
  distinguished in the contract. No product claim relies on those two failures.
- `p3-room-earlier-0b2e424-reproduced`: five actual failures after selector repair:
  two Room opt-out, two real Earlier INVALID_INTERVAL, one settings lost update.
- `p3-2acaa96-targeted-fixed`: 4 core and 69 app tests passed, no errors/skips.

The first cohort's test getter compilation failure at `d0c653f` has a retained
original log but no new XML; stale previous XML is not attributed to that run.

Reproduction commands selected `CoreScenariosTest.reviewP3*`,
`RoomScenariosTest.reviewP3*`, and `UiReviewRegressionTest.reviewP3*`.
Fixed commands additionally selected core/Room `reviewP2History*`, core
`ChoiceWindowTest` and `ChoiceLocalizationTest`, Room `RoomChoiceLocalizationTest`,
and the entire `UiReviewRegressionTest` class. Both use `:core:test` and
`:app:testDebugUnitTest`, `--max-workers=1`, `-PtrackerUnsigned=true`,
`-PtrackerCleanupLibrary=<isolated host fixture ELF>` and
`-Pkotlin.compiler.execution.strategy=in-process`, with `--continue` to preserve
independent failing suites. Exact invocation logs are retained in the environment.
The verification JSONs bind original XML hashes and source bytes to each commit.
These are JVM/Robolectric checks, not new APK or physical-device acceptance.
