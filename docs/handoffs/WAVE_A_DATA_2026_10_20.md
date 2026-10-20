# Wave A data and integration checkpoint

Base: `652a63ad332ccd64c82013bbb2a7f09c06c700b7`. Branch: `wave/a-data-20261020`.

The old A's patch was transferred as exact UTF-8 text by the coordinator after a Library signed download returned HTTP403 in this executor. Verified patch: 23734 bytes, SHA256 `a4663707b49fb5a7565c104d63f90f4c7d7fca6d2f2dd016f77c9136df118b16`. It applied cleanly to the exact base. All five resulting files matched the delivered manifest sizes and hashes before further fixes. Original five-file base archive and incoming patch/manifest are retained at `/workspace/handoff-a` in this Cloud executor.

## Frozen source contract for B

`ChoiceView.quick(window: HistoryWindow, limit: Int = 6, excludeSelection: Classification? = null): List<QuickChoice>` excludes the supplied semantic selection before taking the limit; shared order remains favorites then recorded frequency. Pass the current selection for SWITCH, null for START and stopped-assignment. No second ranking or label-based identity. Blank unclassified choices do not occupy compact slots. Full favorites remain available through existing search pagination.

Persisted fields on existing `Settings.surfaces: SurfaceSettings`:

* `notificationPrivacy: NotificationPrivacy? = null`; enum `NotificationPrivacy { PRIVATE, FULL, HIDDEN }` in `dev.notai.tracker`. Null means legacy PRIVATE with no explicit choice. Explicit PRIVATE remains distinct from null in the archive.
* `widgetBackgroundAlphaPercent: Int = 100`.
* `overlayChooserBackgroundAlphaPercent: Int = 100`.

Both alpha values are validated in 0..100 by core settings validation. Render as `percent / 100f` on owned backgrounds only; never fade text or the whole overlay window. Reset with default field values. Existing `TrackerViewModel.setSurfaces { ... }` uses the existing Tracker transaction; B must not write Model or ViewModel. No separate status-icon preference: observe the existing channel and open its settings. OS permissions/channel privacy remain authoritative.

Full archive is explicitly JSON15 (`CURRENT_BACKUP_FORMAT`), reading old versions 1–14 with missing presentation fields defaulted. Both string and streaming readers reject new non-default preferences labeled as an older format. Settings travel with full backup/restore; restore cannot grant Android permissions or rewrite system channels. Older APKs cannot read JSON15. CSV, selective transfer and user-owned old archive bytes are unchanged.

## A behavior and Opus bindings

* `Tracker.addFavorite` is an idempotent user-add boundary by selectionIdentity/effective favorite note policy; saveFavorite remains ID-based editing/import. Existing IDs/duplicates/variants are retained. Remove is undoable.
* `orderFavorites(expected, ids, completed)`, `editFavorite(expected, favorite, completed)`, `saveCard(expected, card, completed)` and `saveType(expected, type, completed)` reuse Tracker mutations and typed completion. IDs for order are raw favorite IDs, not `favorite:` choice IDs. Save preserves existing personal profiles; edit those with existing personalizeChoice.
* `createCard` and `createAndChoose` accept optional activityTypeId. New/reparented Card/Type links reject archived or missing parents; core vocabulary validation rejects cycles. Historical paths and totals are not rewritten. Existing descendants remain node-local and can keep their prior archived-parent links.
* `DayUi.sessions` projects saved entries into `SessionDayRow(sessionId, rows, recordedMs, pauses)` per reporting day. Original entries remain available for existing editor/delete callbacks. start/end are clipped row bounds, work is summed report duration; pauses are separate and uncertain edges are not inferred as pauses. mixedSelection compares saved semantic variants; different session IDs never merge.
* `focusEditor`, openFocusEditor/closeFocusEditor expose a draft for defaults/next phase. Existing configureFocus preserves the current phase's duration.
* correctionStepMinutes defaults to 1 and accepts 1/5/15; stepCorrectionTime changes only draft values. Existing preview/apply/conflict/Undo remain the apply boundary. Opus must bind the existing editor controls; no visual redesign was performed.
* Full surface catalog keeps the original SurfacePickerRoute, including assignmentId. PickerSheet surfaceRoute bypasses generic START/SWITCH, createAndChoose and startBlank. Stable references and newly created cards submit via SurfaceChoices.choose. Only successful commands close selection; cancel never deletes saved sessions. There is no ASSIGN enum value: assignmentId selects that backend branch. Opus owns appearance; preserve this routing.

## Checks and environment

Original unreviewed tests used nonexistent Tracker.report calls; corrected to Tracker.view().report. Added shared Memory/Room scenarios for duplicate add/variants/order/Undo; session grouping/days/mixed/uncertainty; invalid parents; JSON14 defaults/JSON15 both-reader roundtrip/invalid alpha/future-version refusal. Updated current-writer test expectations to the explicit new format; historical sample files were not rewritten.

Core Kotlin, test fixtures and tests compile. Initial targeted run: 110 tests, 109 passed, one failure in an existing archived-parent reparent expectation. Updated that expectation to the new contract, then reran the failed case plus JournalATest successfully. No Android/Room/Compose/APK/OEM claim. A narrow Compose stopped-assignment -> all activities -> existing choice/new card/cancel regression is added but NOT RUN.

SDK-independent core check uses a temporary external settings file including only :core (repository settings are unchanged). Gradle 8.13 downloaded successfully using the environment proxy explicitly passed to Java. compileSdk 36, AGP8.11.1, NDK30.0.16248370 are required by the repository; buildToolsVersion is not pinned; downloaded com.android.SdkConstants.CURRENT_BUILD_TOOLS_VERSION is 35.0.0. No SDK/sdkmanager/adb found in inspected standard paths. Java21 runtime and GCC are present; JDK JNI headers are absent. Host cleanup library could not compile, and no JNI result is claimed. Normal project configuration fails with SDK location not found. No existing .github workflow was found. No new services, permissions or network feature were introduced.

## Integrated B source checkpoint

Cherry-picked B commits from its base through `d6a26ac6d7a1b8398b3befd4572c0dccb882580e`, after reading their source diffs. Integration head before this evidence-only update: `84e20e5`. B still owns final bindings to frozen A fields/quick and related surface files; no concurrent writer edits were made there. Source integration is not Android acceptance.

Evidence: `evidence/wave-a-20261020/core-initial.log` records 110 tests with one old expectation failure; `core-confirmation.log` plus XML record the corrected failed scenario and all 14 JournalA tests passing. These include five new wave-specific shared scenarios. The SDK-independent check uses a temporary external settings file with normal plugin/dependency repositories and only :core included. No test framework or project configuration was added for this check.

B integration advisory: existing RoomScenariosTest surfaceActions picks the current selection from a one-item quick list during SWITCH. Once exclusion is bound correctly, that compact list is empty; an explicit same-activity switch test must resolve its reference from the full catalog instead. Assignment-after-stop still receives null exclusion.

## Full core acceptance and remaining Android dependency

The missing-JNI host blocker was resolved with the official Debian `openjdk-21-jdk-headless` package, version `21.0.12.1+1-1~deb13u1`, matching the installed Java runtime. Package size/SHA256 were verified against official Debian Packages metadata. It was extracted only in `/workspace/toolchains`; existing GCC built the project-owned host JNI. No system install or SDK agreement acceptance occurred.

A full core run with JNI passed 219 tests, zero failures/skips. Added one shared regression comparing both existing readers and restore-preview non-mutation for every old archive version 1–14 (v1/v2 retained fixtures and required fields for later versions). The final full run after this addition passed **220 tests, 0 failures, 0 errors, 0 skipped**. JSON15 roundtrip/new preferences/invalid alpha/future-format refusal remain covered by the shared JournalA scenarios. Exact summary, final log and all JUnit XML in `evidence/wave-a-20261020/core-full-*`; earlier failed logs remain historical evidence. This supersedes the earlier unavailable-JNI status and limited core run. Room/Compose/Android/APK remain unrun.

Fresh official Google repository metadata was inspected without installing SDK packages. Platform36 revision2, Build Tools35.0.0, NDK30.0.16248370 and current Linux command-line tools all reference `android-sdk-license`, SHA256 `1f8729233617b193fd619213792ae16a41b95d2bbbf525dfe66998252ba68b16`. Exact package URLs, sizes/checksums and license digest are in `android-toolchain-requirements.json`. The same digest appears in historical `evidence/android-runtime-preflight/verification.json`, but `docs/ANDROID_RUNTIME_PREFLIGHT.md` explicitly limits that authorization to a single old Emulator+AOSP attempt. No current-scope SDK license acceptance has been inferred from that history. No emulator was launched, no SDK license markers created and no security/host/network settings changed. The historical workspace SDK paths are absent in this executor.

Remaining install/build action is pending an established authorization basis for this exact SDK agreement or provision of an already-authorized toolchain in this Cloud workspace; it was not attempted or denied by auto-review. B's final bindings remain its file ownership; last fetched B head is `d6a26ac6d7a1b8398b3befd4572c0dccb882580e`. After B delivers its final delta, integrate only that delta and perform normal Android compilation, Room/Compose routing checks and APK build when the SDK dependency is resolved.

## Final source integration and atomic surface creation

Integrated B bindings `bed7aab26703a763df9abaf971ad127a481dfc57` as `24abd8822cb844612bf5fb36f69f91d9f0aac5cd`. Persisted privacy, widget/overlay alpha and current-selection exclusion are connected; B corrected the existing same-activity-switch test to resolve from the full catalog. No Model/archive/ViewModel delta came from B.

Review found that the initial createSurfaceCard implementation saved the card before applying a choice; that earlier path was **not atomic**. It is superseded by `Tracker.createCardForChoice(expected, target, card, application, assignmentId, allowed)` and the narrow `SurfaceChoices.createAndChoose` adapter. Existing apply/assign logic is shared through two private helpers; there is no separate timer implementation. Card creation, route/permission/receipt checks, classification and choice application commit together. Refusal leaves no card, session or classification change. A retry after refusal therefore does not create a duplicate; a delivered successful assignment cannot be replayed with a new card ID. Assignment/SWITCH Undo includes the created card and selection in one change. General form createCard/createAndChoose APIs retain their earlier behavior outside this surface path.

One shared regression checks denied permission, expired receipt, invalid parent, successful retry with the same card, replay refusal, Undo, new-timer stale target and normal START. Final full core run **221 tests, 0 failures, 0 errors, 0 skipped**, with real project-owned host JNI. New evidence is `core-full-atomic.log`, `core-atomic-summary.json` and `core-atomic-junit.zip`. Core source and fixture compilation passed; Android source/tests remain uncompiled.

PickerSheet's original signature `(vm, mode, active, startClock, boundaryClock, boundary, onDismiss, onReturn = null)` is retained. A separate overload requires explicit surfaceRoute. There are exactly three call sites in App.kt: general picker uses named onDismiss, form picker uses named onDismiss/onReturn, and the surface picker uses named onDismiss/surfaceRoute. Source inspection establishes those bindings, not Android compiler acceptance.

Compact overlay AssignmentCard still lacks its visible full-chooser action; `FloatingTimerService.openAssignmentChooser()` is ready but unbound. That visible button remains explicitly Opus scope per coordinator instruction. Notification/launcher/app full choosers retain the original route and receipt; no visual redesign was added. Opus must preserve the routing and add the remaining controls/preview/reset and overlay button before full UI acceptance.

Android install/build remains pending the exact SDK license authorization basis described above. No SDK binaries or accepted-license markers were installed in this executor. GitHub push's default HTTPS helper later lacked credentials; an existing GH_TOKEN credential helper was used for the successful push without printing the token or changing network/security settings. Main/release/merge remain untouched.
