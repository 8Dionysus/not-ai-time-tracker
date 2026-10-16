# Open parent review pass3 evidence

This is incremental evidence. The parent has not closed or accepted pass3.

- `p3-001-b799695-reproduced`: original failing XML and source hashes for
  `b799695b2703d235ea016bdda83afc60f12e59cc` (core 3/3 failed, app 5/6 failed).
- `p3-001-f2de99c-fixed`: original passing XML and source hashes for
  `f2de99cf79ef8a5190857b59dbc7494504600b1d` (core 10, app 33 passed).

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
