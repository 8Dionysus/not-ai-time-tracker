# Android timer surfaces

See [Activity selection and timer surface integration](WAVE_A_DATA_2026_10_20.md) for the current APIs, backup compatibility, build requirements and verification results.

## Presentation bindings

- SurfaceSettings.notificationPrivacy maps null/PRIVATE to Android PRIVATE with a redacted publicVersion, FULL to PUBLIC and HIDDEN to SECRET. Existing timer row, actions and chronometer remain available. Platform privacy and permission/channel policy take precedence.
- widgetBackgroundAlphaPercent controls only a dedicated background ImageView. The original shape, provider dimensions, padding and foreground actions remain unchanged at the default 100.
- overlayChooserBackgroundAlphaPercent controls the AssignmentCard background through observed Tracker state. It does not fade text or the WindowManager window.
- Reset these fields with the existing `setSurfaces` callback while retaining unrelated settings. Both alpha values are validated in 0..100.
- Notification, surface picker and overlay use the shared ordered quick projection. SWITCH excludes the current selection before the limit; START and stopped assignment do not. The full catalog still allows an explicit same-activity switch.

## Controls and routes

The obsolete static open_timer alias is removed. Dynamic timer_action uses the existing start/pause/resume command path; timer_choose opens SurfaceChoices with an immutable target. Opt-out, recovery, Focus and launcher-count limits withdraw unavailable entries, including pinned chooser copies. Retained stale intents cannot act on a new timer.

SurfaceSettingsBackend reports actual channel importance and lock-screen visibility and opens the existing control channel settings. MIN requests no status-bar icon while retaining a drawer notification, with possible reduced lock-screen prominence. The overlay foreground-service indicator remains mandatory. No channel migration or transparent-icon substitution is used.

The full notification chooser remains reachable with no compact suggestions. `FloatingTimerService.openAssignmentChooser()` transfers the original stopped-assignment route/receipt into the application; its visible overlay button remains to be connected. App.kt and PickerSheet.kt preserve that route through the full catalog and cancel without deleting recorded time.

## Remaining interface and platform checks

Connect privacy/alpha controls, preview/reset, channel settings access and the overlay full-catalog button. Review typography, clipping, contrast and the redundant glyph inside custom notification content. SystemUI owns the notification's outer card/header. Android launcher rounding may crop the widget's app-owned background; no OEM geometry guarantee is made.

Expose external JSON backup separately from CSV/private archives and retain timestamp/readback status and full-replacement restore confirmation. SAF provider retention is independent of app uninstall; this is manual backup, not synchronization.

Targeted RoomScenariosTest cases cover launcher replay/opt-out/Focus, channel preservation, persisted notification privacy/permission denial and widget background alpha. TimerSurfaceRaceTest covers stale preparation/publication. Current results and raw initial/repeat reports are linked from the integration document. Device/OEM/TalkBack checks remain separate from Robolectric verification.

## Platform references

- [Notification channels](https://developer.android.com/develop/ui/compose/notifications/channels)
- [Notification presentation and privacy](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications)
- [Custom notifications](https://developer.android.com/develop/ui/views/notifications/custom-notification)
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [App-specific storage](https://developer.android.com/training/data-storage/app-specific)
- [Android 12 widgets](https://developer.android.com/about/versions/12/features/widgets)
