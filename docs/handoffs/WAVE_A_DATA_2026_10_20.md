# Activity selection and timer surface integration

Production source: `8b15b9898082a0f486c33702b7c5d5b85d107fc4`.
This document describes the available APIs, remaining interface work and verification scope.

## Data and action APIs

| Area | Implemented behavior | Interface work remaining |
|---|---|---|
| Favorites | `Tracker.addFavorite` is idempotent by semantic selection and effective note policy. Existing IDs, duplicates and variants are retained. `saveFavorite` edits by ID; removal supports Undo without deleting activities or history. | Consistent star state, add/remove menus and accessible actions on Home and in the picker. |
| Complete favorites list | `TrackerViewModel.pickerData()` reads all existing pages, including archived favorites. Availability still controls whether an entry can start. | Keep the complete list separate from compact quick actions; show archive state and explicit removal. |
| Favorite order | `orderFavorites(expected, ids, completed)` uses `Tracker.orderChoices` and persisted personal.order. IDs are raw favorite IDs. | Drag handle or reorder mode, accessible move buttons and restart checks. Submit the displayed token. |
| Activity editing | Typed callbacks: `editFavorite`, `saveCard`, `saveType`. Creation accepts optional activityTypeId. Existing personal profiles are preserved by ordinary save; profile changes use `Tracker.personalizeChoice`. | Reuse existing editors from Home and picker. Distinguish a favorite label from the activity's name. |
| Parent relationships | Card.activityTypeId and Type.parentId support custom categories. New/reparented links reject missing or archived parents; vocabulary validation rejects cycles. Existing links and historical ancestorPathAtWrite remain unchanged. | Parent selection, breadcrumbs, errors and an explanation of how a move affects future classification. |
| Session summaries | `DayUi.sessions` exposes `SessionDayRow(sessionId, rows, recordedMs, pauses)`. Grouping is by session ID within each reporting day. Work sums existing report durations; pauses are separate. Uncertain work is not counted or inferred as a pause. `mixedSelection` exposes semantic changes. | Render one expandable summary with segments/pauses and mixed classifications. Distinct session IDs must stay separate. Day-clipped summary bounds are not full-session correction bounds. Preserve existing correction preview/apply. |
| Correction step | correctionStepMinutes defaults to 1; setCorrectionStepMinutes accepts 1/5/15; stepCorrectionTime changes draft values only. | Forms.kt still has fixed ±5-minute controls. Connect the selected step and accessibility labels without rounding stored timestamps. |
| Focus editing | focusEditor/openFocusEditor/closeFocusEditor expose a draft. configureFocus changes defaults and the next phase while preserving the current phase's explicit duration. | Long press plus an ordinary accessible edit action; save/cancel and a clear distinction between current and future phases. |

`ChoiceView.quick(window: HistoryWindow, limit: Int = 6, excludeSelection: Classification? = null)` is the shared ordered projection for Home and Android surfaces. It excludes the supplied selection identity before applying the limit, keeps favorites before recorded-frequency suggestions and omits blank unclassified compact entries. Limit is 1..20. SWITCH supplies the current selection; START and stopped-assignment supply null. The full catalog can still explicitly select the current activity.

Core files are under `core/src/main/kotlin/dev/notai/tracker/`; presentation state and callbacks are in `app/src/main/kotlin/dev/notai/tracker/android/ui/TrackerViewModel.kt`.

## Atomic creation and picker routing

`createAndChoose` creates and applies a new card in one transaction for Start, Switch, Label, Focus and Earlier. Refused actions and stale tokens leave no card. The core uses the shared card preparation and existing transitions:

- `createCardForChoice` handles Start/Switch and optional incoming EntryDraft. The draft is validated, its note is preserved and the ViewModel consumes its captured ID only after success.
- `createCardForFocus`, `createCardForLabel` and `createCardForEarlierSwitch` retain the existing Focus defaults, Label session/note semantics and retrospective boundary rules.
- Undo for Switch/Label/Earlier includes the new card as part of the same user action.

Form-only `createCard` is intentionally different. The picker action explicitly creates an activity and returns its selection to an Entry/Plan/Goal form; it does not start a timer or record time. The catalog card remains if that separate form is later cancelled or refused. A stale token rejects the single save without creating it.

`App.kt` and `PickerSheet.kt` retain the original SurfacePickerRoute and assignmentId when opening the full catalog. Existing choices go through `chooseSurface(expectedRoute = route)`; new cards go through `createSurfaceCard` → `SurfaceChoices.createAndChoose` → the core atomic action. A stopped assignment never becomes a generic Start. Cancel preserves the recorded session. The original PickerSheet signature remains available as an overload; the surface overload requires an explicit route.

## Android surface settings

Settings are persisted on `Settings.surfaces` and changed through `setSurfaces { it.copy(...) }`:

| Field | Default and validation | Applied behavior |
|---|---|---|
| notificationPrivacy: NotificationPrivacy? | null; enum PRIVATE/FULL/HIDDEN | Null and PRIVATE use PRIVATE with a redacted publicVersion. FULL requests PUBLIC content; HIDDEN requests SECRET. Explicit PRIVATE remains distinct from the legacy null preference in backup. |
| widgetBackgroundAlphaPercent: Int | 100, range 0..100 | Alpha applies to the dedicated background ImageView; foreground text/buttons remain opaque. |
| overlayChooserBackgroundAlphaPercent: Int | 100, range 0..100 | Alpha applies only to the chooser's raised background, not the WindowManager window or foreground. |

Bindings are in SurfaceViews.kt, SurfaceBackend.kt, TimerSurfaces.kt, FloatingTimerService.kt and res/layout/timer_widget.xml. Reset only these fields so unrelated opt-ins remain unchanged.

SurfaceSettingsBackend exposes observed notification-channel importance, status-bar request and lock-screen visibility. OPEN_TIMER_CHANNEL_SETTINGS opens the existing control channel. MIN requests drawer-only presentation and can reduce lock-screen prominence; system privacy, notification permission, channel settings and OEM policy remain authoritative. The separate overlay foreground-service indicator cannot be suppressed. SystemUI owns the outer notification card/header, including its overall transparency.

Dynamic timer_action and timer_choose use immutable timer targets and existing permission gates. Opt-out/recovery withdraw actions; Focus and launcher-count limits also withdraw retained chooser copies. Stale actions do not retarget a new timer. Ordinary application tap remains unchanged. The full notification chooser remains available when compact suggestions are empty.

`FloatingTimerService.openAssignmentChooser()` is available, but its visible overlay action still needs a UI binding. Preserve the route when adding that button.

## Interface work and acceptance cases

- Add privacy controls, background-alpha sliders, preview/reset and actual channel capability/settings access.
- Render the session summary projection and connect favorite actions/order, parent editors, correction step and Focus editor.
- Theme.kt currently provides SYSTEM/LIGHT/DARK with Onest. AMOLED, accent/text size, font choices, density, compactness and section order remain interface work. Existing theme SharedPreferences are outside Dataset backup; portable new UI preferences need a defined storage contract.
- Review catalog titles/subtitles and category placement by stable ID. Do not merge records by label or rewrite historical meaning. A short movement break can use the visible term «Короткая разминка» with a plain explanation.
- Check long labels on Home, picker, widget, overlay and collapsed/expanded notifications, including narrow widths, large fonts and translations. Remove only a redundant glyph inside custom notification content; preserve the system small icon/header.
- An idle Start pulse remains unimplemented. It must respect reduced motion and system animation settings and must not require a background timer.
- Validate keyboard/TalkBack alternatives to gestures, text contrast, expanded mixed sessions and day-boundary totals. Widget rounding and overlay touch/security restrictions remain platform-controlled.

## Backup compatibility

Full backup writers use `CURRENT_BACKUP_FORMAT = 15`. Both existing readers support versions 1–14 with default presentation settings and reject unsupported future versions or non-default new preferences mislabeled as an old format. Older APKs do not read JSON15. Existing archive bytes, CSV and selective-transfer format 4 are unchanged.

The SAF flow writes a user-selected external JSON document, distinct from CSV and private staging/rollback. Export records timestamp, bytes and readback verification where supported. Restore requires preview, explicit replacement confirmation, a verified rollback and an atomic replacement. Restoring preferences does not grant Android permissions or rewrite system channels/privacy. An external document survives uninstall only while its user/provider retains it; private app data does not. Auto Backup remains disabled. Automatic cloud backup, synchronization and accounts are not implemented.

## Verification

Evidence is under `evidence/wave-a-20261020/`:

| Scope | Result |
|---|---|
| Previous full core checkpoint | 221 passed, no failures/skips; core-full-atomic.log and corresponding summary/JUnit archive. |
| Previous targeted Android checkpoint | 40 distinct Room/Robolectric/Compose tests passed after targeted fixture/selector corrections. android-summary.json retains all run outcomes and android-junit.zip contains initial and repeat XML. |
| Current ordinary-create change | 3 core + 3 Android checks passed; one new shared scenario plus existing history, surface atomicity and Compose assignment regressions. ordinary-create-validation.log, ordinary-create-summary.json and ordinary-create-junit.zip. The full suites were not repeated for this change. |
| Android compilation and unsigned APK |Passed with `trackerUnsigned=true`; current artifact filename, source commit, size and SHA256 are in apk-delivery.json. No INTERNET permission was present in the APK. |
| Lint, device/OEM and completed interface acceptance |Not run. |

The initial three Android test failures were fixed without weakening the production contract:

1. The favorites persistence test reopened an already closed original store a second time, causing an ownership-map NPE. Using the current `t.store` preserves both real Room close/open operations and every ID/variant/order/stale/Undo assertion. Room and Memory confirmations passed.
2. The Compose assignment test matched «Все занятия» on both Home and its dialog before the first action. Restricting the selector to `hasAnyAncestor(isDialog())` exercises the intended real dialog button. Existing/new activity, cancel, session identity/count, classification, IDLE and preserved-history assertions remain.
3. Robolectric 4.17 keeps a pinned dynamic shortcut in its pinned map and models disable through a disabled registry rather than ShortcutInfo flags. The test reads the fresh retained route from pinnedShortcuts and checks the public requestPinShortcut rejection for a disabled entry, following the existing opt-out test. Publication, stale replay, preference opt-out, Focus withdrawal/gating and unchanged Dataset assertions remain. The last launcher rerun passed; android-launcher-confirmation.log and the corresponding XML retain the result. OEM rendering/isEnabled behavior is still outside this shadow verification.

## Build requirements

Use JDK 21 with JNI headers, Gradle Wrapper 8.13, Android SDK Platform 36 revision2, Build Tools 35.0.0 and NDK 30.0.16248370. Platform-Tools 37.0.1 was installed as an AGP SDK dependency. Kotlin 2.1.20, AGP 8.11.1 and Room 2.7.2 are pinned in Gradle files. Set JAVA_HOME and ANDROID_HOME for the selected installation; do not commit local.properties or signing keys.

```sh
bash scripts/build-cleanup-host.sh "$JAVA_HOME" "$PWD/build/libtracker_cleanup.so"
./gradlew :core:test -PtrackerCleanupLibrary="$PWD/build/libtracker_cleanup.so"
./gradlew :app:compileDebugKotlin :app:assembleDebug -PtrackerUnsigned=true
```

The SDK package metadata, expected archive checksums, installed source.properties and exact package-license text are retained in the technical evidence. Selected stable packages declare android-sdk-license in the official repository metadata. Bundled third-party notices remain separate from that package-manager agreement. Device tests and signed distribution builds are separate steps.
