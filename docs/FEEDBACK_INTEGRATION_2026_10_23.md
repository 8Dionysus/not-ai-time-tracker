# Tracker feedback integration — 2026-10-23

Base: `21f1a82ba83a3c393eff503ea6fe045ba99248a9`, 0.15.1 / code 17. No version bump, release, signing material or real device changes.

## Behavior

The history selection can delete completed records in one transaction. Session IDs are canonicalized across day rows. The read-only preview captures the original token and exact dependent-fact counts and recorded duration. Unknown, active, Focus, schedule-linked or stale targets reject the entire operation. The existing single Undo restores all selected records and facts while retaining the protection against resurrecting consumed picker nonces.

`BatchDeletionUiHost` uses that real preview. It rejects a changed selection and keeps selection on failure. Confirmed application belongs to `viewModelScope`; disposing the composable caller cannot cancel the commit or lose the Undo notice. Selection changes are briefly guarded during application without holding a UI lock across database work. Cancellation remains cancellation across both the core storage boundary and the VM bridge.

Reporting-zone AUTO is device-local. New installations default to AUTO; existing fixed zones remain fixed until an explicit choice. Selecting the same IANA zone manually still exits AUTO. Startup, resume and the existing timezone event reconcile through `configureReportingDay`, retaining timestamps, IDs, day-start minutes and goal zones. JSON15 is unchanged.

The preference and core are separate stores, so their writes are not described as atomic. Before changing the core, the controller durably records a pending choice and its original expected token in local preferences. After the core commit it acknowledges the mode and removes the pending choice. If acknowledgement fails, the pending mode prevents the former opposite AUTO/FIXED mode from reverting the committed zone. Startup retries the original token or acknowledges its observed result. Conflicting state blocks automatic rewriting, displays a recovery message, and requires a fresh explicit choice. Storage/cancellation outcomes retain the intent for observation rather than assuming rollback.

Settings expose AUTO/FIXED and IANA search, move the zero day boundary into an explicit editor, and do not round saved minutes on open/cancel. Picker rows suppress only the current category fallback, retaining user details and context for global results. Existing license assets are shown verbatim. The selected accent now applies in Neon too, with the narrower B palette/glow refinement. Other theme palettes and warm start captions are retained.

Launcher choices come solely from `LauncherIconController`: stable keys, localized labels and actual manifest resources. The available catalogue groups aliases sharing an image resource; saved keys are not rewritten just to change presentation. The final tree has four distinct assets: default, amber, mint and violet. Native adaptive drawables are rendered directly, avoiding a vector-only Compose loader. MainActivity and immutable shortcut payloads remain intact.

Collapsed idle notifications replace their secondary caption with bounded favorite icons and accessibility labels. Expanded labels, widget layout and active/paused/recovery controls remain. Notification publication precedes shortcut/widget work, with independent stale gates. No measured physical latency improvement or universal OEM fit is claimed.

## Evidence and limits

All nine original input images were viewed as PNG pixels through authorized Page file-reference reads. Original user image bytes are not published. The screenshots below use repository-generated synthetic fixtures only.

- Targeted Memory and Room deletion cases covered one commit/Undo, refusal without deletion and cancellation without losing the prior Undo.
- The new UI bridge checks covered changed selection, cancellation, disposed caller scope, same-zone AUTO-to-FIXED and the controller resource catalogue.
- Four reporting-preference cases passed, including failed final preference writes returning false or throwing, a fresh controller startup/reconcile, and a conflicting recovery requiring explicit choice.
- B's two category-context and two day-boundary regressions passed. The UI confirmation committed a batch and restored the exact original records through the snackbar Undo.
- `UiScreensTest.neonAccentSettings` passed all four accent choices and produced five actual native-graphics Robolectric PNGs. Source data remained equal.
- The final targeted UI scenario and `:app:assembleDebug -PtrackerUnsigned=true` passed. `apksigner verify` reported no signature, as intended.
- The CI workflow in `.github/workflows/tracker-feedback.yml` runs the affected host checks and an unsigned APK build. Remote CI results belong to the PR; local results are not presented as remote CI.

The early failed cancellation assertion compared exception object identity across coroutine stack recovery; it was corrected to check cancellation type/meaning. Production cancellation handling did not change to satisfy that assertion. Independent already accepted suites were not repeatedly run locally.

No physical device/emulator, OEM notification policy, lock-screen display, launcher cache behavior or real cold-start/action latency was verified. Lint and signed release builds are separate from this acceptance.

## Synthetic host screenshots

Neon settings: [amber](ui/screens/33-neon-settings-amber.png), [teal](ui/screens/33-neon-settings-teal.png), [lime](ui/screens/33-neon-settings-lime.png), [graphite](ui/screens/33-neon-settings-graphite.png). [Running timer](ui/screens/34-neon-running-amber.png).

History/settings: [batch confirmation, dark](ui/screens/feedback-batch-delete-dark.png), [light](ui/screens/feedback-batch-delete-light.png), [more settings, dark](ui/screens/feedback-settings-more-dark.png), [light](ui/screens/feedback-settings-more-light.png).

C's synthetic native host previews, verified from its handoff: [launcher palettes](ui/screens/feedback-c-launcher-palettes.png), [notification dark](ui/screens/feedback-c-notification-dark.png), [notification light](ui/screens/feedback-c-notification-light.png). These are C host renders, not physical screen captures.
