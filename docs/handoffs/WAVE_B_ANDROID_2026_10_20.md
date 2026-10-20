# Wave B final Android source bindings (2026-10-20)

Binding base: A checkpoint `9d90830800822891b21ea6b67f715737afa4d545`, which already incorporates B through `d6a26ac6d7a1b8398b3befd4572c0dccb882580e`. Original main base remains `652a63ad332ccd64c82013bbb2a7f09c06c700b7`.

Final B binding branch: `wave-b-android-bindings`, created directly from exact A checkpoint so the integrator can cherry-pick one binding commit. The older `wave-b-android-surfaces` remains the independent checkpoint history. No core, TrackerViewModel, archive, migration or manifest changes were made in this binding commit. No other repositories or AbyssPC were used; no additional agents were launched.

## Applied A contract

- `SurfaceSettings.notificationPrivacy: NotificationPrivacy?`: null and PRIVATE render Android PRIVATE with a redacted publicVersion; FULL renders PUBLIC with the complete existing row/actions/chronometer; HIDDEN renders SECRET without a publicVersion. Production `TimerSurfaces.notification` reaches this saved default through SurfaceViews; an explicit adapter argument remains available for previews.
- `widgetBackgroundAlphaPercent` defaults100 and binds to the dedicated background ImageView through setImageAlpha. Text/buttons, row clicks and the rest of the widget remain opaque. Production TimerSurfaces.widget uses the saved default.
- `overlayChooserBackgroundAlphaPercent / 100f` is observed from Tracker.views into Compose state. AssignmentCard applies it only to its existing raised background, so saved edits/reset update an open panel without fading text or the WindowManager window.
- Core validates both integer percentages in0..100. Opus resets the three presentation fields through existing `vm.setSurfaces { it.copy(notificationPrivacy = null, widgetBackgroundAlphaPercent = 100, overlayChooserBackgroundAlphaPercent = 100) }`, retaining unrelated opt-ins/settings. No preferences sidecar or theme engine.
- SurfaceViews notification quick choices and SurfaceChoices picker use the shared `quick(window, limit, excludeSelection)` order. Current selection is excluded BEFORE limit for SWITCH. START and stopped assignment pass null. Overlay assignment consumes the same ordered prefix without a second filter/ranking.
- An explicit same-activity switch remains available in the full catalog; the existing Android contract regression now resolves its reference from full favorites instead of expecting the current activity in a compact switch list.

A owns full JSON15 (`CURRENT_BACKUP_FORMAT`), old1–14 defaults, both-reader validation and roundtrip. Full copies retain presentation settings; restored values neither grant Android permissions nor rewrite system channels. Old user JSON documents are not rewritten. Old APKs are not promised to read JSON15. CSV is unchanged.

## Inherited independent B platform work

- Static open_timer MAIN alias removed; retained pinned copies retired. Normal app tap unchanged.
- Existing dynamic timer_action is the sole start/pause/resume command path. Dynamic timer_choose uses the existing SurfaceChoices target/route. Opt-out/recovery withdraw both. Focus and launcher-count omission explicitly disable retained pinned chooser copies; the transaction permission callback also rejects chooser application during Focus. timer_action is first when count is limited. No retargeting stale actions to a new timer.
- SurfaceSettingsBackend exposes actual timer_controls importance, requested status-bar behavior and channel lock-screen visibility, plus OPEN_TIMER_CHANNEL_SETTINGS. MIN requests no status-bar icon while retaining a normal drawer notification. App/system permission and user channel settings remain final. No channel migration, locked-channel rewriting or transparent icon. Overlay FGS indicator remains separate and mandatory.
- Notification full chooser remains reachable when compact suggestions are empty.
- `FloatingTimerService.openAssignmentChooser()` transfers the original assignment route/receipt into the app and hides only the panel. A's full-picker routing retains that route and assignmentId; there is no ASSIGN enum and no conversion to generic START/SWITCH.
- The exact root-authorized WIDGET_ALPHA_BINDING.patch is applied to timer_widget.xml. Existing24dp shape/color, provider sizes, row/actions and16/10/8/8dp content padding are unchanged. The background ImageView is noninteractive and excluded from accessibility. The patch remains as an applied recipe, not an outstanding task.

## Existing full SAF copy and restore contract

`requestExport(false)` -> prepareBackup -> private ExportOperations.prepare { Backups.write(it) } -> MainActivity CreateDocument -> owner-checked receiveDocumentExportResult -> ExportOperations.write(uri).

Backups captures a complete portable Dataset at one moment. CSV is a report, not a full copy. Private staging/database archives/rollback disappear on uninstall and must not be described as surviving user backups. A successful user-selected external SAF document remains while the user/provider retains it.

ExportOperations verifies staged size/hash before writing, closes/flushes output, compares SHA-256 readback when the provider supports it, and reports verified=false for write-only providers. Mismatches fail and preserve staged bytes for retry. BackupInfo records timestamp/bytes/verification only after successful JSON write; it cannot prove future provider retention. Existing chooser ownership/cancellation handling remains.

Restore uses user OpenDocument -> bounded staging -> Backups.prepare -> RestorePreview -> explicit apply. Preview alone does not replace facts. Apply checks the original token, saves/verifies rollback, then replaces atomically. Preview exposes incoming settings/counts/asOf and replacement consequence. Existing RestoreReviewOwner handles lifecycle.

allowBackup=false and extraction exclusions remain; no cloud service/account/INTERNET, automatic export or system backup was introduced. A SAF provider may itself sync under its own policy.

## Exact Opus remainder

- Bind privacy selector, percent sliders/preview/reset to existing saved SurfaceSettings/vm.setSurfaces; display the real channel capability and open its settings. Explain system privacy/POST_NOTIFICATIONS/channel/OEM limits and reduced prominence at MIN.
- Bind visible overlay "all activities" action to openAssignmentChooser. Preserve A's route/assignmentId handling in the full picker. The functional routing fix is already A scope, not deferred redesign.
- Styling, colors/contrast, typography, redundant app glyph inside custom notification layout, labels and broader visual changes remain Opus scope. SystemUI outer notification card/header/icon alpha is outside app control.
- Present external JSON copy separately from CSV/private archives, show timestamp/verification, and retain explicit full-replacement restore confirmation.

## Checks and exact remaining verification

Passed: git diff --check; widget/shortcut XML parsing; static widget assertions that original shape/color, include attrs, padding, row/action resource and provider dimensions are unchanged; source mapping/caller review against exact A fields/API. Background is not focusable/clickable and is excluded from accessibility.

Four narrow existing RoomScenariosTest cases cover: launcher chooser replay/opt-out/Focus; real MIN channel preservation/capability; persisted privacy after Room reopen with FULL permission-denial fallback; persisted widget alpha after reopen with foreground/actions preserved. Existing TimerSurfaceRaceTest now selects timer_action by ID, retaining its state assertions. A's shared tests cover ordered core projection and archive persistence; they are not duplicated here.

Android compile, Robolectric, lint, APK, emulator/OEM/TalkBack runtime checks NOT RUN by B. No SDK found in inspected standard paths; B's Gradle cache contains only the interrupted wrapper download (services.gradle.org DNS failure). A independently demonstrated Gradle/core execution using its environment proxy, but its normal project configuration also lacks SDK. No repeated unchanged download or network/security change was attempted. No tracked .github/workflows or other CI exists on verified main; main PR workflow lookup was empty.

Equipped-environment verification path: Java21 + Gradle8.13 + SDK platform36/AGP8.11.1 and the repository's declared NDK30.0.16248370; run `:app:compileDebugKotlin`, then targeted RoomScenariosTest methods lockScreenPolicyKeepsFullTimerOnlyForExplicitPublicPresentation, widgetOpacityFadesOnlyTheDedicatedBackground, timerChannelCapabilityObservesMinAndPreservesExistingChannel, launcherChooserUsesExistingTargetAndRejectsReplayAfterNewTimer, backendPickerWidgetAndOverlayContracts, shortcutDisableWithdrawsMutableEntriesAndGatesRetainedActionsAfterReopen and TimerSurfaceRaceTest. Build/lint/APK scope remains distinct from JVM checks. A's full-picker routing regression remains its integration check.

Android12 launcher rounding stays system-controlled. No opt-out/clip flags, geometry changes or new corner content were added; the original24dp shape can still be cropped by a launcher. Transparent backgrounds may affect host background detection. No device/OEM geometry guarantee is claimed.

## Official sources read

- https://developer.android.com/develop/ui/compose/notifications/channels — immutable channel behavior after creation, MIN, user control and channel settings intent.
- https://developer.android.com/design/ui/mobile/guides/home-screen/notifications — presentation/privacy constraints.
- https://developer.android.com/develop/ui/views/notifications/custom-notification — decorated custom views retain system notification presentation.
- https://developer.android.com/training/data-storage/shared/documents-files — user-selected SAF documents.
- https://developer.android.com/training/data-storage/app-specific — uninstall removes app-specific files.
- https://developer.android.com/about/versions/12/features/widgets and https://developer.android.com/develop/ui/views/appwidgets/enhance — launcher rounding/presentation ownership.
