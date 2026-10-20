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

Frozen root contract (supersedes initial proposal):
- nullable privacy: null preserves legacy PRIVATE; explicit PRIVATE/FULL/HIDDEN.
- widgetBackgroundAlphaPercent and overlayChooserBackgroundAlphaPercent: Int 0..100, defaults100. Reset only these presentation fields through Tracker.settings, preserving delivery opt-ins and unrelated settings.
- No persisted status-icon flag: observe/control existing timer_controls via Android system settings. MIN requests drawer-only presentation; LOW requests status-bar icon. NONE blocks delivery; OEM/user policy remains final.
- Full JSON explicitly advances to15. New readers retain1..14 with defaults; old documents are not rewritten. An old APK is not promised to read new15. CSV remains unchanged; no sidecar/new preferences store.

Exact A enum/property source is still pending. B's Android presentation adapters are ready: SurfaceViews.notification(context,view,visibility) accepts PUBLIC/PRIVATE/SECRET with PRIVATE default; AssignmentCard accepts validated backgroundAlpha=1f applied only to raised background. The eventual caller maps saved percentages to Float by /100f. No FGS for timer, fullscreen intent or per-second worker.

A candidate `ChoiceView.quick(window, limit, excludeSelection: Classification? = null)` excludes current identity BEFORE limit, retaining shared favorite order. Still pending exact final API/source: replace SurfaceViews' current post-limit filtering and pass explicit current selection for switch pickers. Assignment-after-stop must not exclude the stopped activity. No separate ranking.

## Existing SAF full JSON contract (verified by reading current source)

`TrackerViewModel.requestExport(false)` -> `prepareBackup()` -> `ExportOperations.begin(application/json, not-ai-backup-DATE.json)` -> private `prepare { Backups.write(it) }` -> MainActivity CreateDocument -> owner-checked `receiveDocumentExportResult` -> ExportOperations.write(uri).

Backups.write captures the complete portable Dataset at one moment. CSV is a report, not a full backup. Private staged files, database recovery archives and verified rollback are app-private and disappear on uninstall; they must never be described as a surviving user backup.

ExportOperations checks staged length/hash before provider write, closes/flushes output, reads the document back when supported and compares SHA-256. A mismatched readback fails and retains staged bytes for retry. A provider refusing readback yields verified=false, not a claim of verification. Cancellation and stale chooser-owner handling already exist. Last BackupInfo stores timestamp, byte count and verified status after successful JSON export. It does not retain a destination URI, and cannot prove future existence/provider durability.

Restore: user OpenDocument -> bounded stage -> Backups.prepare -> RestorePreview (asOf, IDs, counts, unfinished tail/focus, settings, replacement consequence) -> explicit apply. Preview alone does not mutate Dataset. Backups.apply validates the original token, saves/verifies rollback, then replaces atomically. Closed/stale previews reject. Existing RestoreReviewOwner handles UI lifecycle.

`allowBackup=false` and data extraction exclusions remain. No cloud service/account/INTERNET or automatic export was introduced. A user-selected SAF provider may itself sync, under its own retention/access policy. A user-owned external document survives app uninstall only while its provider/user retains it.

## Exact Opus handoff

- Add lock-screen privacy selector and concise OS/channel/permission explanation, after A publishes the above contract. Full content is conditional on system privacy, POST_NOTIFICATIONS, enabled app/channel and OEM lock-screen policy.
- Bind `OPEN_TIMER_CHANNEL_SETTINGS`; display actual importance/capability. Explain MIN may reduce lock-screen prominence. Preserve mandatory overlay foreground-service indicator.
- Bind alpha slider/preview/reset to A's settings; alter background only. Widget requires an owned background view/drawable ID: existing root shape cannot be faded with setAlpha without fading content. Opus owns that narrow visual binding/layout. Notification outer card/header/icon belong to SystemUI; remove redundant app glyph only inside custom layout if desired.
- Bind overlay card "all activities" to `openAssignmentChooser()`. Preserve assignment route through full picker: current App.kt onAll cancels the surface picker and opens a general START/SWITCH picker, losing assignment ownership. Opus/A must carry route/ChoiceApplication.ASSIGN into full chooser and submit through SurfaceChoices.choose; never reinterpret assignment as START.
- Labels/icons/layout remain Opus scope. Existing QuickPickSheet already provides full-picker entry for notification and launcher.
- Present full external JSON backup separately from CSV/private archives; retain verification status and explain full replacement at restore preview. No new backend required for current SAF contract.

## Checks and blockers

`git diff --check`: passed. Shortcut XML parsed as XML. Manual caller-chain review performed.
Added three narrow existing RoomScenariosTest cases: launcher chooser replay/opt-out/Focus withdrawal; MIN channel observation/preservation; explicit PUBLIC/PRIVATE/SECRET notification presentation. Existing shortcut publication checks now select timer_action by ID, covering two entries without weakening state assertions.

Compile/tests NOT RUN: default Gradle home is outside writable roots; retried with GRADLE_USER_HOME=/workspace/.gradle-wave-b. Wrapper cannot resolve services.gradle.org (UnknownHostException), no Gradle distribution or Android SDK was found in standard locations. No emulator/OEM/device claims. No lint/APK evidence.

Required next checks in an equipped environment: :app:compileDebugKotlin; RoomScenariosTest.lockScreenPolicyKeepsFullTimerOnlyForExplicitPublicPresentation; RoomScenariosTest.launcherChooserUsesExistingTargetAndRejectsReplayAfterNewTimer; RoomScenariosTest.timerChannelCapabilityObservesMinAndPreservesExistingChannel; RoomScenariosTest.shortcutDisableWithdrawsMutableEntriesAndGatesRetainedActionsAfterReopen; RoomScenariosTest.surfaceActions; TimerSurfaceRaceTest. Add privacy/alpha persistence regression only after A freezes fields, and ordered quick regression in A's existing shared scenario.

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


## Prepared presentation adapters (persistence binding still pending A)

SurfaceViews.notification(context, view, lockScreenVisibility) now accepts only Android PUBLIC/PRIVATE/SECRET values. PRIVATE is the legacy default and supplies the redacted publicVersion. PUBLIC uses the complete existing custom timer/actions/chronometer; SECRET requests hidden lock-screen content. This is not yet connected to persisted policy fields. Added one focused existing RoomScenariosTest case for the three presentation values; not run due the build dependency described above.

AssignmentCard accepts backgroundAlpha=1f and applies it only to the current raised background color, with finite/range validation. Foreground content and WindowManager window alpha are unchanged. The caller awaits A's saved field. This is a single background binding, with no layout redesign.

The widget currently has no background view ID. A foreground ImageView behind the existing row (owned by Opus) with setImageAlpha would allow RemoteViews alpha without fading text; the existing root drawable cannot be altered that way through RemoteViews. A narrow binding/resource change must be assigned before that source application can be completed.

Because codec.encodeDefaults=true, use EncodeDefault(NEVER) for added defaulted fields to preserve legacy default JSON output; old archives default the absent fields, unknown enum/invalid alpha rejects rather than silently resetting. Whole-copy restore exposes values through RestorePreview.settings before confirmation.


## Frozen root decision superseding the earlier proposal

Persisted writer A owns nullable privacy (null preserves legacy PRIVATE; non-null PRIVATE/FULL/HIDDEN), widgetBackgroundAlphaPercent and overlayChooserBackgroundAlphaPercent as Int 0..100 with default100. Full archive advances explicitly to15; new readers retain1..14 defaults. Old JSON documents are not rewritten, CSV is unchanged. Do not claim an old APK can read new15. No rights/channel consent is restored by these values.

A still needs to publish exact enum/property source before B binds it; current remote A branch was still the base when this adapter checkpoint was prepared. No shared files were changed by B.

`WIDGET_ALPHA_BINDING.patch` is a concrete proposed narrow XML binding, NOT applied to the Opus-owned layout: existing shape becomes a background ImageView behind the unchanged padded row. `SurfaceViews.widgetBackgroundAlpha` accepts that view ID and percent, then uses setImageAlpha (0..255) on that background only. No bitmap allocation or universal theme engine. The layout owner/root must accept/apply the patch before the binding is invoked by widget().


## Widget layout binding applied with root authorization

The exact WIDGET_ALPHA_BINDING.patch is now applied to timer_widget.xml. SurfaceViews.widget(context,view,enabled,backgroundAlphaPercent=100) invokes the dedicated ImageView binding. Existing TimerSurfaces caller uses the default until A fields are present. No geometry or provider-size changes: original24dp GradientDrawable/color source, row XML, provider XML, include attributes and16/10/8/8dp content padding are unchanged. Static XML assertions passed. The added background ImageView is not clickable/focusable and is excluded from accessibility; existing row/actions remain foreground siblings. One focused RemoteViews regression checks background-only alpha and foreground/action preservation; not yet executed.

Android12 launcher rounding is still system-controlled: no opt-out/clip flags, replacement radius, sizing or corner content was introduced. The shape retains its original corners and can still be cropped by the launcher. Transparent backgrounds can affect automatic background detection, so no OEM/host visual guarantee is claimed without a device. Sources: https://developer.android.com/about/versions/12/features/widgets and https://developer.android.com/develop/ui/views/appwidgets/enhance .

The proposed-patch status above is historical; root explicitly authorized this exact narrow binding. Remaining A source bindings: privacy at notification caller; widget alpha percent at TimerSurfaces.widget; overlay chooser percent at AssignmentCard caller; quick(excludeSelection) before limit. Root retains A ownership of full-picker assignment routing.
