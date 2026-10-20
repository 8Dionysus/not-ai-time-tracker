# Wave B Android checkpoint (2026-10-20)

Base: `652a63ad332ccd64c82013bbb2a7f09c06c700b7`. Separate branch `wave-b-android-surfaces`.
This is an independent platform checkpoint, not acceptance of the entire B package.
No core, TrackerViewModel, manifest, layout XML, theme or Compose layout was changed.
No `.agents/skills` directory exists in this checkout; repository AGENTS.md was read.

## Implemented

- Static `open_timer` MAIN alias removed from shortcut metadata; refresh retires retained pinned copies. Ordinary application tap is unchanged.
- Existing dynamic `timer_action` remains the only command path, with the original shown target/token. New `timer_choose` opens the existing SurfaceChoices route (no second command implementation), respects launcher count, shortcut opt-out and immutable timer target. Focus only offers the existing primary action and explicitly disables retained pinned chooser entries; recovery withdraws both actions. Entries omitted by launcher limits are also disabled, with timer_action first.
- SurfaceChoices accepts SHORTCUT and gates application on the persisted shortcut preference and absence of Focus inside the existing Tracker action. Old routes do not retarget a new timer.
- SurfaceSettingsBackend exposes `timerNotification`: observed importance, requested status-bar icon behavior and channel lock-screen visibility, plus `OPEN_TIMER_CHANNEL_SETTINGS` for the existing control channel. No channel migration, importance rewriting, icon substitution or overlay FGS suppression.
- Notification full chooser remains available even when compact quick suggestions are empty.
- FloatingTimerService exposes `openAssignmentChooser()`: explicit UI callback opens the existing route with the original assignment receipt/target, hides only the overlay panel, and does not cancel that receipt. Opus must bind its visible action.
- BackupDocuments documents the actual full-copy/restore contract below.

## A integration contract pending (not implemented in this checkpoint)

A alone owns core and TrackerViewModel. No new core API was created here.

Proposed minimum fields on existing SurfaceSettings (exact names to be frozen by A):
- notification lock-screen policy: PRIVATE (legacy default), FULL, HIDDEN.
- widgetBackgroundAlpha and overlayChooserBackgroundAlpha: finite 0..1, legacy default 1, reset by copying defaults. Apply only to owned background, never text/buttons or WindowManager.LayoutParams.alpha.
- No persisted status-icon flag is needed for the chosen system-settings path: observe/control the existing channel through Android settings. MIN requests drawer-only presentation; LOW requests a status-bar icon. NONE disables delivery. OEM/system policy remains authoritative.

After A publishes exact source: bind privacy in SurfaceViews.notification (PRIVATE + redacted publicVersion; FULL + PUBLIC full content; HIDDEN + SECRET); preserve existing custom chronometer/actions and permission fallback. No FGS for timer, no fullscreen intent or per-second worker.

A candidate `ChoiceView.quick(window, limit, excludeSelection: Classification? = null)` excludes current identity BEFORE limit, retaining shared favorite order. Still pending exact final API/source: replace SurfaceViews' current post-limit filtering and pass explicit current selection for switch pickers. Assignment-after-stop must not exclude the stopped activity. No separate ranking.

## Existing SAF full JSON contract (verified by reading current source)

`TrackerViewModel.requestExport(false)` -> `prepareBackup()` -> `ExportOperations.begin(application/json, not-ai-backup-DATE.json)` -> private `prepare { Backups.write(it) }` -> MainActivity CreateDocument -> owner-checked `receiveDocumentExportResult` -> ExportOperations.write(uri).

Backups.write captures the complete portable Dataset at one moment. CSV is a report, not a full backup. Private staged files, database recovery archives and verified rollback are app-private and disappear on uninstall; they must never be described as a surviving user backup.

ExportOperations checks staged length/hash before provider write, closes/flushes output, reads the document back when supported and compares SHA-256. A mismatched readback fails and retains staged bytes for retry. A provider refusing readback yields verified=false, not a claim of verification. Cancellation and stale chooser-owner handling already exist. Last BackupInfo stores timestamp, byte count and verified status after successful JSON export. It does not retain a destination URI, and cannot prove future existence/provider durability.

Restore: user OpenDocument -> bounded stage -> Backups.prepare -> RestorePreview (asOf, IDs, counts, unfinished tail/focus, settings, replacement consequence) -> explicit apply. Preview alone does not mutate Dataset. Backups.apply validates the original token, saves/verifies rollback, then replaces atomically. Closed/stale previews reject. Existing RestoreReviewOwner handles UI lifecycle.

`allowBackup=false` and data extraction exclusions remain. No cloud service/account/INTERNET or automatic export was introduced. A user-selected SAF provider may itself sync, under its own retention/access policy. A user-owned external document survives app uninstall only while its provider/user retains it.

## Exact Opus handoff

- Add lock-screen privacy selector and concise OS/channel/permission explanation, after A persists the above contract. Full content is conditional on system privacy, POST_NOTIFICATIONS, enabled app/channel and OEM lock-screen policy.
- Bind `OPEN_TIMER_CHANNEL_SETTINGS`; display actual importance/capability. Explain MIN may reduce lock-screen prominence. Preserve mandatory overlay foreground-service indicator.
- Bind alpha slider/preview/reset to A's settings; alter background only. Widget requires an owned background view/drawable ID: existing root shape cannot be faded with setAlpha without fading content. Opus owns that narrow visual binding/layout. Notification outer card/header/icon belong to SystemUI; remove redundant app glyph only inside custom layout if desired.
- Bind overlay card "all activities" to `openAssignmentChooser()`. Preserve assignment route through full picker: current App.kt onAll cancels the surface picker and opens a general START/SWITCH picker, losing assignment ownership. Opus/A must carry route/ChoiceApplication.ASSIGN into full chooser and submit through SurfaceChoices.choose; never reinterpret assignment as START.
- Labels/icons/layout remain Opus scope. Existing QuickPickSheet already provides full-picker entry for notification and launcher.
- Present full external JSON backup separately from CSV/private archives; retain verification status and explain full replacement at restore preview. No new backend required for current SAF contract.

## Checks and blockers

`git diff --check`: passed. Shortcut XML parsed as XML. Manual caller-chain review performed.
Added two narrow existing RoomScenariosTest cases: launcher chooser replay/opt-out; MIN channel observation/preservation. Existing shortcut publication checks now select timer_action by ID, covering two entries without weakening state assertions.

Compile/tests NOT RUN: default Gradle home is outside writable roots; retried with GRADLE_USER_HOME=/workspace/.gradle-wave-b. Wrapper cannot resolve services.gradle.org (UnknownHostException), no Gradle distribution or Android SDK was found in standard locations. No emulator/OEM/device claims. No lint/APK evidence.

Required next checks in an equipped environment: :app:compileDebugKotlin; RoomScenariosTest.launcherChooserUsesExistingTargetAndRejectsReplayAfterNewTimer; RoomScenariosTest.timerChannelCapabilityObservesMinAndPreservesExistingChannel; RoomScenariosTest.shortcutDisableWithdrawsMutableEntriesAndGatesRetainedActionsAfterReopen; RoomScenariosTest.surfaceActions; TimerSurfaceRaceTest. Add privacy/alpha persistence regression only after A freezes fields, and ordered quick regression in A's existing shared scenario.

## Official sources read

- https://developer.android.com/develop/ui/compose/notifications/channels — immutable channel behavior after creation; user settings; MIN requests no status-bar appearance; channel settings intent.
- https://developer.android.com/design/ui/mobile/guides/home-screen/notifications — system presentation and privacy constraints.
- https://developer.android.com/develop/ui/views/notifications/custom-notification — decorated custom views retain system notification controls/header.
- https://developer.android.com/training/data-storage/shared/documents-files — user-selected SAF document storage and one-shot access.
- https://developer.android.com/training/data-storage/app-specific — app-specific files are removed on uninstall.


## Follow-up source review after checkpoint

The existing presentation theme is private SharedPreferences("ui"). It is not part of full JSON backup. The proposed privacy/alpha fields belong in existing serializable SurfaceSettings, not this private theme store. Validate finite Float values in 0f..1f in validate(Dataset); absent legacy fields default to PRIVATE/1f/1f. Backups already serializes Settings and RestorePreview.settings exposes the incoming values; explicit whole-copy restore can carry them. Reset only these fields via Tracker.settings, preserving delivery opt-ins and unrelated settings. Exact A field names/source remain pending.

No tracked .github/workflows or alternate CI configuration exists on the verified main. No existing CI was available to dispatch. Compile requires a pre-provisioned Gradle 8.13 distribution and Android SDK36/declared NDK, then the previously listed Gradle commands; network/security settings were not changed and no repeated unchanged download was attempted.

Root assigned the full-picker assignment routing fix exclusively to A, so the receipt loss is a required functional integration fix, not deferred visual redesign.
