# Tracker backend handoff for Claude

Current 2026-10-15 follow-up: [typed CSV/inspection issue contract](IMPORT_ISSUES.md), [full A–F backend pass](BACKEND_PASS_2026_10.md). The dated code commit below records the earlier handoff.

Code commit: **`9a6cffb3c44916fe8100db23f11e7c01b02f791d`** (2026-10-14).

Backend contracts are implemented and checked. The visible screens, notification layout, floating panel, graphics, fonts, catalog assets and app icon are unchanged in this stage. Claude owns their design and wiring. The APK is a debug build, not a store release.

## All seven requested items

| User item | Implemented backend | Claude's remaining work |
| --- | --- | --- |
| Delete from long press, session detail and swipe | One completed-session deletion API, read-only preview, atomic segment/measurement cascade, existing Undo and refreshed reports/goals | Wire all three entrances to the same preview/confirmation; display affected session/segment counts and Undo |
| Meaningful minimal activity icons | Stable semantic keys, ancestor fallback and unclassified fallback; no catalog migration | Supply the visual key-to-icon mapping and renderer |
| Widget placement | Foreground system pin request, persisted request receipt, actual widget IDs, explicit retry and honest placement states | Wire an explicit add-widget action when appropriate; distinguish control enabled from placement |
| Notification favorites/frequency picker | Identity-based compact source, fresh references, direct immutable Activity PendingIntent, START/SWITCH commands and lifecycle route | Add picker action and compact styling; render picker state; preserve existing direct timer actions |
| Simple overlay START/STOP, assignment after Stop | Separate overlay policy; atomic Stop plus durable receipt for the stopped record; cancellation/replay/restore/next-session protection | Replace the large panel, use the separate policy, and open a short assignment UI from the returned receipt |
| Settings redesign | Typed capability/permission/error/action models and ViewModel state, refresh on foreground and revisions | Render them in the new settings screen; request runtime permission/system settings only on an explicit action |
| App icon: brackets and clock hands | No backend change required | Keep the bracket/style, replace the interior graphic with clock hands |

## 1. Completed-session deletion

Source: [Tracker.kt](../core/src/main/kotlin/dev/notai/tracker/Tracker.kt).

- `Tracker.previewDeleteSession(sessionId): DeletePreview` reads without mutation. `DeletePreview` exposes `expected`, `sessionId`, `before`, `after`, `asOf`, `segmentCount`, `measurementCount`, and `reports(start,end,filter)`.
- `Tracker.applyDeleteSession(preview): Change` is explicit confirmation. `deleteSession(expected: Token,id: String)` is the same ID-based operation for callers that already supply confirmation.
- Only `STOPPED` sessions outside the current Focus run are deletable. Active/paused/recovery/Focus-held records fail. Missing records fail; stale previews fail `STALE`. Closing the confirmation does nothing.
- The transaction removes the whole session, every segment with that session ID, and both session- and segment-scoped measurements belonging to it. Other records stay intact. A session spanning multiple calendar rows is still one session: use `EntryUi.sessionId`, not its title, position, day or `segmentId`.
- `Change.changedSessionIds` contains the removed ID; `undoAvailable` and `state.token` feed the existing `Tracker.undo(token)`/`canUndo(token)` mechanism. Undo restores the retained segment and measurement IDs and values. It is process-local, valid only for the current matching token, and must not be advertised after process death.
- Reports and goals are derived from the committed dataset; no UI recomputation of domain durations or database writes.
- ViewModel: `suspend previewDeleteSession(id)`, `applyDeleteSession(preview)`, existing `notices` with an Undo token, and `undo(token)`.

## 2. Semantic icons and stable choices

Sources: [SurfaceContracts.kt](../core/src/main/kotlin/dev/notai/tracker/SurfaceContracts.kt), [Choices.kt](../core/src/main/kotlin/dev/notai/tracker/Choices.kt).

`ActivityIcons.resolve(dataset,classification): ActivityIcon` returns ordered `keys` and `fallback`. Key order is `card:<id>` when present, then `type:<id>:v<meaningVersion>` from the selected type through its parents. Use the first key known by the visual dictionary; otherwise use `activity.custom` or `activity.unclassified`. Keys are renderer-independent; no drawable/color/Compose or app-icon decision is encoded. IDs/meaning versions remain the source of semantic identity.

`ChoiceView.quick(window: HistoryWindow,limit: Int = 6): List<QuickChoice>` returns `entry`, `reference`, and `icon`. Limit is 1–20. Available favorites are ordered by normalized label and stable ID, followed by up to 100 frequency-ranked history candidates in the explicit window; identity dedupe precedes the final limit. Frequency counts recorded certain positive-duration completed-session participation, not UI taps or a new telemetry ledger. Android uses a trailing 30-day window and six results. This bounded source does not replace the full paginated `search`/`suggestions` APIs.

`selectionIdentity(classification)` distinguishes different card/type IDs and detail/tag/facet variants. It ignores display-name changes for ID-backed choices. The home quick list now uses this identity instead of `distinctBy { title }`; same-name activities remain distinct.

- `resolve(choiceId)` resolves `type:`, `card:`, `favorite:` or `history:` IDs on a snapshot.
- `reference(entry): ChoiceReference` captures dataset ID, restore barrier, stable choice ID and selection fingerprint.
- `resolve(reference)` fails `STALE` for a changed/missing/unavailable choice or changed dataset/barrier.
- `Tracker.startChoice(timerTarget,reference,allowed)` and `applyChoice(timerTarget,reference,application,allowed)` recheck the timer subject and fresh selection atomically. `ChoiceApplication` is `START` or `SWITCH`; switching is explicit and uses existing Undo. Missing permission/opt-in fails without a write. Never retry an old reference with a fresh target silently.

## 3. Widget control versus placement

Source: [SurfaceBackend.kt](../app/src/main/kotlin/dev/notai/tracker/android/SurfaceBackend.kt).

`SurfaceSettings.widget` only enables controls. `WidgetPlacementController.read(reconcileForeground = false): WidgetState` separately reports `supported`, actual `ids`, `placement`, `requestId`, and typed `problem`.

`request(activity): WidgetState` requires a resumed foreground Activity on the main thread. It does not enable the control preference. Call it from an explicit user action. It stores a request ID, invokes `requestPinAppWidget`, and uses an immutable explicit callback to the nonexported `WidgetPinReceiver`. Callback extras supplied by the launcher are not trusted as placement proof: actual widget IDs are queried instead.

| Placement | Meaning / UI action |
| --- | --- |
| `AVAILABLE` | Supported; no placement confirmed; offer add |
| `UNSUPPORTED` | Launcher lacks pin support; explain manual launcher placement |
| `PENDING` | Request accepted; awaiting system interaction; this is not successful installation |
| `PINNED` | At least one actual provider instance exists, independently of enabled preference |
| `UNCONFIRMED` | No actual instance confirmed after foreground reconciliation, removed prior instance or unconfirmed callback; offer manual steps/explicit retry |
| `FAILED` | Platform/request-receipt failure; show typed problem and retry action |

A true platform return is request acceptance, not completed placement. Denial has no callback. On return to foreground, pending becomes `UNCONFIRMED` unless actual IDs exist; it is never called denial by a timeout. An explicit retry gets a new request ID; stale callbacks do not change the active receipt. MainActivity already refreshes capabilities with foreground reconciliation on resume.

ViewModel: `requestWidgetPlacement(activity)` returns the exact result and publishes `widgetRequest`; `capabilities.widgetPlacement` is the refreshed actual observation. No widget is automatically installed by this code.

## 4. Notification choice route

`SurfaceChoices.route(Surface.NOTIFICATION,snapshot)` creates a unique `SurfacePickerRoute(id,source,target,assignmentId,application)`. Idle routes request `START`; active routes request explicit `SWITCH`. Show that action clearly in the picker before applying.

`SurfaceChoices.pending(context,route)` uses **`PendingIntent.getActivity` directly on MainActivity**, immutable and uniquely identified by route ID. Existing Start/Pause/Resume/Stop broadcasts stay direct. Do not call `startActivity` from a notification receiver or service.

- `intent`, `parse`, `save` and `restore` transport the original route; invalid input fails closed.
- `read(tracker,route): SurfacePickerState` exposes the bounded choices and optional stopped-record assignment and checks the original timer target.
- `choose(context,tracker,route,reference,note)` applies with fresh source opt-in and permission checks inside the core transaction. Repeated START/SWITCH delivery fails against the changed timer epoch.
- MainActivity already forwards create/new-intent routes and saves/restores them in its instance-state Bundle. ViewModel exposes `surfacePicker`, `receiveSurfacePicker`, `chooseSurface(reference,note)`, and `cancelSurfacePicker()`. Choices refresh on committed revisions; stale requests are cleared with a notice. A result from an older route cannot clear a newer route.
- Claude must add the action to the existing notification builder and render the picker. No notification style/layout was changed here.

## 5. Overlay Stop and durable assignment

`TimerSurfaces.overlayPrimary(snapshot)` is separate from `primary(snapshot)`: idle → START; an active/paused/Focus run → STOP; recovery → no automatic command. The shared primary still uses PAUSE/RESUME for other surfaces. Claude should wire the overlay renderer to the new function.

For an overlay STOP, `TimerSurfaces.execute(...)` calls `Tracker.stopForAssignment(target,allowed)`. The same transaction closes measured time and stores a `StoppedAssignment`. `Change.assignment` contains its ID, dataset/barrier, stopped session ID, timer epoch and record fingerprint. If the transaction fails, neither time nor receipt is partially committed. The normal checked timer path retains its small verified row commit.

1. Take the returned `Change.assignment` and create `SurfaceChoices.route(Surface.OVERLAY,change.state,assignment)` for the short assignment UI.
2. Pass the selected `QuickChoice.reference` to `choose(...)` or to `Tracker.assignStopped(offerId,reference,note,allowed)`. This labels only the captured completed session and its segments; durations and measurements are retained. A null note preserves the old note.
3. Cancel with `Tracker.cancelAssignment(offerId)` or ViewModel cancellation. Cancel consumes the request and leaves the saved time unchanged.
4. After process recreation, `Tracker.pendingAssignments()` / `suspend vm.pendingStoppedAssignments()` returns still-valid receipts. The ledger is persisted in Room metadata and capped at 32 entries. Portable backup JSON omits it; restore clears it and changes the control barrier.

A consumed/cancelled/replayed request fails `STALE`. A subsequent timer transition, edit/measurement change to that record, deletion or restore invalidates it. A delayed response after the next Start is rejected; it never labels the next/current session. Undo never revives a consumed external receipt. To edit such an older record afterwards, use the existing explicit completed-session correction flow with a fresh preview.

The current floating panel does not yet open an assignment window. Claude owns that window, its size/transparency, and its triggering from the returned receipt; do not add a background activity-start workaround.

## 6. Settings capabilities

`SurfaceSettingsBackend.read(context,settings,reconcileForeground)` returns `SettingsCapabilities` for widget, notification, overlay and Focus signal. Each `SurfaceCapability` carries `enabled`, `permitted`, `problem` and typed `actions`; widget placement is separate.

`SurfaceProblem`: `PERMISSION_REQUIRED`, `CHANNEL_BLOCKED`, `FOREGROUND_REQUIRED`, `LAUNCHER_UNSUPPORTED`, `PLATFORM_FAILURE`, `RECEIPT_STORAGE`. `SurfaceSettingsAction`: notification runtime permission, notification system settings, overlay permission, add/retry widget and refresh. `settingsIntent(context,action)` supplies only appropriate system-settings intents; runtime permission and widget actions use their respective platform APIs.

ViewModel exposes `capabilities`, `capabilityProblem`, `widgetRequest`, `refreshCapabilities`, `setSurfaceEnabled(surface,enabled)`, and existing `setSurfaces` for Focus signal. Preference writes use Tracker transactions. Permission grants/revocations and channel blocking are actual OS observations, not inferred from enabled preferences. Commands recheck source permission/opt-in. Claude owns permission prompts and the settings screen.

## 7. Verification and delivery limits

Supporting evidence: [backend verification](history/backend-contracts-20261014.json).

- 28 JVM/Robolectric tests: 3 shared core scenarios, 12 Room tests, 12 UI regressions, 1 export-lifecycle test. Failures/errors/skips: 0. Shared Memory/Room scenarios now cover deletion, measurements, goals/Undo, references, durable assignment, cancellation/replay, next session, changed record, restore and backup exclusion.
- Android API35 Robolectric: immutable direct Activity PendingIntent, unique routes/Bundle restoration, START/SWITCH replay, overlay versus shared primary, Focus behavior, supported/unsupported pin, simulated no-callback foreground reconciliation, callback-ID guard and actual-ID success.
- Real API35 AOSP emulator: two separate processes, isolated retained QA database, receipt across process termination/reopen, assignment/replay, next-session stale rejection, deletion/Undo with both measurement scopes, Activity PendingIntent type/immutability and route Bundle serialization. Existing canonical table digest was unchanged before/after both phases.
- Native widget observation found one already pinned instance and supported pin capability. **No new platform pin was requested in native QA**. OEM launcher confirmation/denial, physical-device behavior, the future notification picker UI and Claude's future overlay/settings/icon implementation still require their own checks.
- Build: core tests, app unit tests, lint, debug APK and androidTest APK succeeded. Lint: 0 errors, 65 warnings, 1 hint. This does not claim GitHub CI passed.
- APK: `not-ai-time-tracker-0.10.0-9a6cffb-debug.apk`, 12,467,825 bytes, SHA-256 `0d54eac88dbffe0c262baf9e65594ef7a9c03af42cdaa790a5a1917ab9b3f93e`. Debug signer SHA-256 `86e221956fd1ed4dab261a10c1baeac3537816d271601037b309f2ddf186c3cc`. Library identity `libfile_dc0b457de35881918bca0dae9783f6c3`, version 7; previous versions retained. APK CRC and all three bundled catalog assets verified exactly.
- Resources/Compose layout/style/catalog/dependencies and existing permissions unchanged; the manifest adds one nonexported widget callback receiver. No proprietary SDK or network dependency added. Database schema remains 5; portable backup format remains 7 with local receipts excluded.
- F-Droid first, then Play remains the intended order. Product license, permanent release signing, reproducible release/store preparation and publishing are separate user decisions. This stage does not choose a license or release key.
- Prior TimeField/PillButton capacity issues at 150% font / 320 dp remain in Claude's frontend scope. Physical phone/OEM, TalkBack, and power-loss checks are not claimed.

Primary Android contracts: [widget pin API](https://developer.android.com/reference/android/appwidget/AppWidgetManager#requestPinAppWidget(android.content.ComponentName,%20android.os.Bundle,%20android.app.PendingIntent)), [notification trampoline restrictions](https://developer.android.com/about/versions/12/behavior-changes-12#notification-trampolines).
