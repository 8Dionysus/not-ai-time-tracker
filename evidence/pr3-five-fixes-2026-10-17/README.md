# PR3 five behavior regressions

Base: `ed5fd5fded38f0371fb9bbf5c0d73d4cf7c5ddde` (merged PR3). This change addresses the five confirmed review threads starting at [r4303589323](https://github.com/8Dionysus/not-ai-time-tracker/pull/3#discussion_r4303589323).

- Planned start/switch uses one checked transaction for the old timer, new session, occurrence link and replay receipt. Stale revisions or storage failure leave the previous timer intact.
- One-off/weekly conversion archives the previous series and creates the replacement in one transaction. Rejection/failure preserves the original plan; existing history remains available.
- Goal drafts retain the effective definition's full Filter when changing threshold/name/period behavior. Unsupported tags/details/facets survive; an explicit activity reselection can replace the filter.
- Notification answer identity includes the full ticket, token and answer. Creating a later action cannot retarget an earlier PendingIntent. Old actions retain their original stale guard.
- Shared session notes label their date using the report window's ReportingDayPolicy, including the configured day-start boundary.

The original editor rendering is preserved. The only editor changes carry filter metadata. Resources, navigation, styles and texts are unchanged. `verify-ui-boundary.py` checks these source boundaries; this is not a pixel comparison or physical-device result.

## Verification on final source

Source/test manifest SHA256: `d8352305babc5a468bccaa487e111f2dacfcfc8334432f48138d4846d5d435b8`.
Source-only patch SHA256: `8ed3b426bb7d4d7bae0d67c4aae321c4cf62feae183bc258ea3140c44de579db`.

The21 new regression cases pass both in the final targeted run and in the final full run. They cover Memory and Room stale/failure/reopen/replay atomicity, plan retention, complex filters/history, immutable notification targets, report-day boundaries, and legacy command hash compatibility. See `qualification-summary.json` for exact case identities and counts.

Final offline Java21 host checks: `:core:test`214/214 PASS; `:app:testDebugUnitTest`274/275 PASS, one failure `UiScreensTest.deleteFromMenuAndSwipe` at line366 (5s ComposeTimeoutException). `shareSummary` passes in this final run. `:app:lintRelease` passes with67 warnings and1 hint; `:app:assembleDebug` builds a signed debug APK. No emulator or physical-device verification was performed.

The complete suite is **not all green**. Earlier aggregate runs also had two Compose timeouts (`deleteFromMenuAndSwipe`, `shareSummary`). Their causes remain unqualified. An addressed observer-only diagnostic ran both cases once and passed; this does not explain the aggregate failures. No diagnostic instrumentation, timeout/assertion changes or speculative behavior change is included here. Earlier261-case comparison results are not equivalence evidence: the original pair differed in actual order; corrected main stopped at a resource bound after195 successes, and corrected fix did not run.

Raw XML, logs, resource/worker/source receipts, observer traces and the invalid/incomplete comparison history are preserved in the task's private review bundle. SDK paths, local caches, debug keys and raw workstation metadata are intentionally excluded from this commit.
