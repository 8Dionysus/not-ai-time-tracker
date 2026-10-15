# Production audit ledger

Baseline: `f14fa3c` (2026-10-15). Current branch: `backend/production-audit`.
Scope: all tracked source, tests, resources, build/release configuration, schemas,
catalog adapters and bundled data. Prior APK evidence at `0980c50` is historical.
No new model executor, store publication or signing key. Actual runtime model/effort
has not been independently attested.

This is a developing ledger, not a declaration of production readiness. `pending`
means no completed review claim. Structural resource checks do not establish visual,
translation, TalkBack or OEM correctness. Device/store/signing boundaries stay separate.

## Findings and current boundary

Backend-only branch: `backend/production-audit`, based on `f14fa3c`. The user
withdrew UI work on 15 October at 15:10 UTC. UI source/layout, TechnicalActivity,
FloatingTimerService and RemoteViews changes are absent from this branch.
Unaccepted work is preserved locally on `archive/unaccepted-ui-production-audit`
at `e8aa417`; it is not production acceptance or an instruction to merge it.

| ID | Backend disposition | Evidence / remaining work |
|---|---|---|
| C01 | Durable registry/gate and descriptor-relative JNI storage implemented; four Android ABIs compile. | Host filesystem fault tests. Production deletion still unbound: Android descriptor QA, frontend durable-reference adoption, legacy registration and sealed archive hardlink graph remain required. |
| C02/C03 | Reconcile interrupted rename/restore; inventory/reconciliation never unlinks. Only explicit resume finalizes empty quarantine. | Actual filesystem crash/failure fixtures. |
| C04/R02 | Maintenance-before-runtime lock order; acquire durable writer refs under gate, stream ordinary I/O outside it. Rescue remains exclusive. | Long-writer fixture; backend writer wrappers. |
| C07 | Count only selectable, unreferenced recovery copies for the separate last-unused-copy warning; an active recovery source cannot mask it. | Real-filesystem two-copy reference fixture. |
| C06 | Unknown unsafe/non-ASCII filenames produce non-addressable opaque inventory markers, leaving safe siblings inspectable. They remain blocked and never become paths. | Unicode sibling fixture. |
| C05 | Reject metadata UTF-8 overflow before replacing the previous readable journal. | Metadata overflow/reopen fixture. |
| F01 | Focus side effects serialize capture of the current Tracker; cancel obsolete notification 43. | Room test covers Next/Stop/disable/revocation and delayed old callback. Legacy UI overloads queue a fresh runtime-owned IO refresh. |
| U01 | `Tracker.switchDraft` commits switch and note atomically. | Shared Memory/Room injected-failure fixture. Existing UI caller migration is OPEN for Opus. |
| T01 | Selective resume chooses the next pending group inside its transaction. | Deterministic concurrent resume fixture preserves APPLIED receipt and Undo. |
| B01 | Streaming export enforces its reader's 16 MiB character limit per record. | Oversized-record regression; total file still streams. Large individual metadata records remain a declared capacity limit. |
| S01 | Cold Room loads compare payload entity IDs and positions against row identity/range. | Corrupt fixture rejects without repair or byte mutation. |
| R01 | Core `Snapshot.isCounting()` gives Focus mode precedence. | Frontend indicator/ticker adoption remains OPEN. |
| R04 | Proven unchanged completed history reuses choice indexes during START/PAUSE/RESUME; bounded queries visit only intersecting history. | Shared Memory/Room scan counters; bounded-query full-scan oracle; current scale measurement recorded separately. |
| L01 | CSV source date parsing uses explicit source BCP-47 locale or ROOT, independent of UI/system locale. | RU/FR/TR system locale invariance and explicit French month fixture. New typed issue SOURCE_LOCALE_INVALID needs editorial mapping. |
| X01 | Archived Repeat/Favorite are intentionally permitted by existing saved-snapshot contract. | Candidate behavior change rejected; existing semantics retained. |

Open frontend findings are recorded in `BACKEND_AUDIT_HANDOFF.md`. They include
stale correction previews, premature form dismissal, optimistic favorite success,
incoming draft switching, overlay bounds, counters/Focus DUE refresh, picker cache,
process/configuration draft preservation and accessible localized error presentation.
No UI-owned issue is marked fixed by this backend branch.

## Validation

The earlier mixed backend/UI checkpoint `c83dc29` passed core 35/35 and app 55/55.
It is historical only. The later mixed checkpoint `e8aa417` had one failing new UI
fixture and is explicitly unaccepted. Neither result accepts this backend-only tree.
Final follow-up runtime source `5111df4` (build started at documentation/config
commit `eefbecd`) passed core 41/41 and app 52/52, with zero failures/errors/skips.
Unsigned debug/release APKs and release lint completed successfully; lint has
0 errors, 60 warnings, 1 hint. Both APKs pass zipalign with 16 KiB page alignment.
The corrected scale command ran RoomScenariosTest.timerPath at `12fea29`; the
first storageWorkload filter matched no standalone JUnit test and is not evidence
of a measured workload. fdroidserver 2.4.5 metadata lint passed with official
category context, including an enabled isolated fixture. No full F-Droid build.
Current commands, artifact hashes, raw logs and exact source SHA are in delivery evidence.

No Android device/resource measurement is claimed. `adb devices -l` failed trying
to create read-only `/home/agent/.android`; this adb binary does not recognize
ANDROID_USER_HOME. No HOME override, device or sandbox workaround was used.
Synthetic JVM timings/heap samples do not establish phone PSS/mAh. Full history,
report and choice indexes still scale with history; that is not automatically a leak.
Idle overlay FGS remains available for the user's requested always-available button.

## File coverage

Initial hashes identify the reviewed starting content; changed files need follow-up review.

| Path | SHA-256 prefix | Review |
|---|---|---|
| `.gitattributes` | `cd5feef90447` | pending |
| `.gitignore` | `bf361ac33caa` | pending |
| `AGENTS.md` | `9cc7dec737bd` | pending |
| `LICENSE` | `b1d2870f1a00` | pending |
| `NOTICE` | `ae46ab41f736` | pending |
| `README.md` | `88e23be8cc35` | pending |
| `app/build.gradle.kts` | `762683c0354d` | pending |
| `app/schemas/dev.notai.tracker.android.TrackerDatabase/1.json` | `d2ce971d7d97` | structural parse only; semantic/device review not claimed |
| `app/schemas/dev.notai.tracker.android.TrackerDatabase/2.json` | `f8b482f96bc9` | structural parse only; semantic/device review not claimed |
| `app/schemas/dev.notai.tracker.android.TrackerDatabase/3.json` | `aacce33a2aa0` | structural parse only; semantic/device review not claimed |
| `app/schemas/dev.notai.tracker.android.TrackerDatabase/4.json` | `227eaa277713` | structural parse only; semantic/device review not claimed |
| `app/schemas/dev.notai.tracker.android.TrackerDatabase/5.json` | `b3bd6649eaf1` | structural parse only; semantic/device review not claimed |
| `app/schemas/dev.notai.tracker.android.TrackerDatabase/6.json` | `e8d5ad43f09a` | structural parse only; semantic/device review not claimed |
| `app/schemas/dev.notai.tracker.android.TrackerDatabase/7.json` | `d2cded285f7c` | structural parse only; semantic/device review not claimed |
| `app/src/androidTest/kotlin/dev/notai/tracker/android/ArchiveInstrumentation.kt` | `b64442089e47` | pending |
| `app/src/androidTest/kotlin/dev/notai/tracker/android/BackendNativeChecks.kt` | `d8cd2a23dfaa` | pending |
| `app/src/main/AndroidManifest.xml` | `523dc2774dcb` | structural parse only; semantic/device review not claimed |
| `app/src/main/assets/licenses/onest-OFL.txt` | `1b10b77bbd51` | pending |
| `app/src/main/assets/licenses/project-Apache-2.0.txt` | `b1d2870f1a00` | pending |
| `app/src/main/assets/licenses/release-runtime-index.json` | `3ba3773b53ce` | pending |
| `app/src/main/assets/licenses/third-party-notices.txt` | `f0be537fa248` | pending |
| `app/src/main/kotlin/dev/notai/tracker/android/AndroidRuntime.kt` | `2d6656a0f31d` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/AppLocalePreferences.kt` | `bdacd55ce5e6` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/BackupDocuments.kt` | `b901af0305ff` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/DatabaseRecovery.kt` | `2c36b7c6d396` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/EntryInputs.kt` | `e9cf05dbe12f` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/FloatingTimerService.kt` | `40018ada0036` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/FocusDueSignals.kt` | `41c4bf89597b` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/FocusSignals.kt` | `4357815d5831` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/MainActivity.kt` | `68c6415a6be8` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/NormalizedHistory.kt` | `09e375a5d80f` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/PrivateCopyAudit.kt` | `b2e97b2e3ec7` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/PrivateFileMaintenance.kt` | `2b1e29919f2c` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/PrivateFiles.kt` | `84be1a3930ca` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/StartupTrace.kt` | `dd83cc28c721` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/SurfaceBackend.kt` | `d72f4f69cc16` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/SurfaceViews.kt` | `23657b6db8db` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/TechnicalActivity.kt` | `04488d3fca3b` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/TimerSurfaces.kt` | `bb75fbcf4f2e` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/TrackerDatabase.kt` | `8904b6c92c89` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/ActivityGlyphs.kt` | `f1dfb3ee3379` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/App.kt` | `cc1d5831e13c` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/Backend.kt` | `4cd526a642a4` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/Components.kt` | `d4fb172fe3f2` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/Forms.kt` | `293f7c5ac7eb` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/Icons.kt` | `d9194282a4ab` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/Interval.kt` | `c7444bfdfd24` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/OverviewScreen.kt` | `1ab957d86eeb` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/PickerSheet.kt` | `92a31729756d` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/Presentation.kt` | `db976fb08e7a` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/SettingsScreen.kt` | `0a223c5e2ca9` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/Theme.kt` | `c95f93a55d10` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/TimerScreen.kt` | `62ad715b1e66` | source read; follow-up/validation pending |
| `app/src/main/kotlin/dev/notai/tracker/android/ui/TrackerViewModel.kt` | `a425b113ffe6` | source read; follow-up/validation pending |
| `app/src/main/res/drawable/act_article.xml` | `7326f9e019c4` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_bag.xml` | `2131f415e0ff` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_ball.xml` | `6a0cd16b5f25` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_bike.xml` | `f691522bc861` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_bolt.xml` | `0a0cc7d37f86` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_book.xml` | `c3a49514cef7` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_bus.xml` | `8c08e64e7b9c` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_camera.xml` | `55b36ca9b8d0` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_candle.xml` | `da68ed6cd750` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_card.xml` | `3d410d07c0af` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_cardio.xml` | `ec08cde7b661` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_cards.xml` | `4229778843eb` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_chart.xml` | `b283fb47996d` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_clean.xml` | `6e52e91c5f45` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_code.xml` | `557f9a2263c4` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_columns.xml` | `31a08adb237e` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_cook.xml` | `1f4bc23f2cd1` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_cube.xml` | `65cb6c66da98` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_cup.xml` | `865c200f98a0` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_custom.xml` | `e0c69dda6b83` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_dice.xml` | `b29bef6a2dff` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_drop.xml` | `9619615a4bc2` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_family.xml` | `6949764d46b5` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_feed.xml` | `afd674342eaa` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_flask.xml` | `46e5830ff8a0` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_focus.xml` | `42773adae993` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_food.xml` | `bdf592bcac5d` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_gamepad.xml` | `9aa036255916` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_headphones.xml` | `b518174007db` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_heart.xml` | `1ab26056ff5c` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_home.xml` | `71dee4fd4063` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_hourglass.xml` | `e3b66da220f0` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_language.xml` | `a41463b03a60` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_leaf.xml` | `236cdc5e5dc2` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_lotus.xml` | `ba40571320e8` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_mail.xml` | `6f0fbd398e73` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_medical.xml` | `e65b50f27287` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_meeting.xml` | `178f43aaf216` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_mic.xml` | `0083f08450a6` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_mountain.xml` | `59c7e2cd25cc` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_music.xml` | `ae0c16193055` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_palette.xml` | `cfa6add7695e` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_papers.xml` | `969c08052ff4` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_paw.xml` | `c5fd80bace09` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_pen.xml` | `7dbca2fcb1f9` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_people.xml` | `a7f638b335fc` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_phone.xml` | `c4d0b7205d49` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_run.xml` | `43c6fe3032b9` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_scissors.xml` | `906dfafddf1b` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_screen.xml` | `4fd937354955` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_server.xml` | `a127604ffafd` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_shield.xml` | `29d32bc30736` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_shirt.xml` | `440e95301b4b` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_sleep.xml` | `e09e808ab563` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_snow.xml` | `94ca101cc8ef` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_sofa.xml` | `74aaec8d8b85` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_steps.xml` | `e32cd2388679` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_strength.xml` | `51c927d185e3` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_study.xml` | `8a3ff5fd7b6a` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_teach.xml` | `521ef1b720a1` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_ticket.xml` | `fe6ddf4d6ea5` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_tools.xml` | `2fbcd1f5850b` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_unclassified.xml` | `ebddd47f87bc` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_water.xml` | `60055918efc5` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/act_work.xml` | `71d31f5743d9` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | `5aea1cda053b` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | `765f8380edbb` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/ic_pause.xml` | `4049293135cc` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/ic_play.xml` | `41ecae89645b` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/ic_shortcut.xml` | `ecb422afdce3` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/ic_stat_notai.xml` | `5d30dc885277` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/ic_stop.xml` | `71e1edf267a5` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/surface_button_primary.xml` | `193636b6b812` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/surface_button_tonal.xml` | `8c270f0fd1bc` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/surface_chip.xml` | `b8ff07ec637e` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/drawable/widget_background.xml` | `71d7f8e1093f` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/font/onest_200.ttf` | `7b7124f6823e` | pending |
| `app/src/main/res/font/onest_300.ttf` | `3ccb4ee42d34` | pending |
| `app/src/main/res/font/onest_400.ttf` | `5d27bd7fe4b7` | pending |
| `app/src/main/res/font/onest_500.ttf` | `24744bba5ddd` | pending |
| `app/src/main/res/font/onest_600.ttf` | `030d1bae9b38` | pending |
| `app/src/main/res/layout/notification_timer.xml` | `f74de1928768` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/layout/notification_timer_big.xml` | `3773b6a1f625` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/layout/surface_timer_row.xml` | `4bdaeb4a741d` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/layout/timer_widget.xml` | `6b14012b780c` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/mipmap-anydpi/ic_launcher.xml` | `ae92c429e7ef` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml` | `ae92c429e7ef` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/values-night/colors.xml` | `98fab277b753` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/values-night/themes.xml` | `eee73f8257ec` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/values/colors.xml` | `319a0e149764` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/values/ids.xml` | `773a1a3bff33` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/values/strings.xml` | `2ed2185b3146` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/values/themes.xml` | `753f2d6667d0` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/xml/data_extraction_rules.xml` | `a0d81ccff94f` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/xml/shortcuts.xml` | `90c4d28810de` | structural parse only; semantic/device review not claimed |
| `app/src/main/res/xml/timer_widget_info.xml` | `159b6bebd6b4` | structural parse only; semantic/device review not claimed |
| `app/src/test/kotlin/dev/notai/tracker/android/RoomScenariosTest.kt` | `dad20602b12c` | pending |
| `app/src/test/kotlin/dev/notai/tracker/android/ui/UiExportLifecycleTest.kt` | `e21efe41edf0` | pending |
| `app/src/test/kotlin/dev/notai/tracker/android/ui/UiScreensTest.kt` | `6591f6a3618e` | pending |
| `build.gradle.kts` | `69bae8fb385b` | pending |
| `catalog/compatibility.json` | `40f9a284bea8` | pending |
| `catalog/locales/manifest.json` | `5ebc714bcace` | pending |
| `catalog/manifest.json` | `136b030c6370` | pending |
| `catalog/runtime-catalog.json` | `9efaea5313b4` | pending |
| `core/build.gradle.kts` | `4ea1cc3a82aa` | pending |
| `core/src/main/c/private_copy_io.c` | `4e63804401ee` | pending |
| `core/src/main/kotlin/dev/notai/tracker/Backup.kt` | `e663ce19aa42` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/CheckedState.kt` | `88728e2fdaf9` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Choices.kt` | `fbd23167e32e` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/CopyMaintenance.kt` | `c4b999bd8447` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/DescriptorCopyFiles.kt` | `f77f33cb93f1` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Focus.kt` | `d87505802677` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Goals.kt` | `042ab78a6ee3` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/HistoryTransfer.kt` | `c33f69605b25` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/ImmutableData.kt` | `35e75b4c2046` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/ImportInspection.kt` | `7416d03402bc` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/ImportIssues.kt` | `501eaac2e12f` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/LocalEntries.kt` | `7e7cb8888c32` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Localization.kt` | `929965766975` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Migration.kt` | `c96e1980c0f4` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Model.kt` | `b8487e2e9b33` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/PrivateCopyCleanup.kt` | `281985d06c74` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/ReadyCatalog.kt` | `f5a88bb9fbaa` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/RegisteredCopyStorage.kt` | `fc49a6309d29` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Reports.kt` | `9189072eb4f2` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Schedule.kt` | `c4f2ab90aaff` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/SelectiveTransfer.kt` | `4ae6dde03566` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/StreamingBackup.kt` | `0c518f0c0edd` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/SurfaceContracts.kt` | `9728184daf1f` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Tracker.kt` | `6440a0d39736` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/TrackerView.kt` | `015673b6d781` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/TransferIdentity.kt` | `1ad721d7b284` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/TransferRetention.kt` | `f8c0d1244dd6` | source read; follow-up/validation pending |
| `core/src/main/kotlin/dev/notai/tracker/Vocabulary.kt` | `c59eefef9487` | source read; follow-up/validation pending |
| `core/src/main/resources/catalog-bootstrap.json` | `16a9ad141518` | pending |
| `core/src/test/kotlin/dev/notai/tracker/CleanupProtocolTest.kt` | `834ef1e11ed2` | pending |
| `core/src/test/kotlin/dev/notai/tracker/CoreScenariosTest.kt` | `8704e5952ec1` | pending |
| `core/src/test/kotlin/dev/notai/tracker/DescriptorCleanupTest.kt` | `f2e7f450d7b4` | pending |
| `core/src/test/kotlin/dev/notai/tracker/ImportIssuesTest.kt` | `485cb7aea0c9` | pending |
| `core/src/test/kotlin/dev/notai/tracker/LocaleContractTest.kt` | `19014f3db3ea` | pending |
| `core/src/testFixtures/kotlin/dev/notai/tracker/ScenarioContract.kt` | `c00016d0c7d2` | pending |
| `core/src/testFixtures/resources/previous-runtime-catalog.json.gz` | `d8212d4a4041` | pending |
| `docs/ACCEPTANCE_GAPS.md` | `5112dbe51b02` | pending |
| `docs/BACKEND_PASS_2026_10.md` | `99fb7619f9cf` | pending |
| `docs/BACKEND_V10.md` | `a50480959053` | pending |
| `docs/BACKEND_V13.md` | `6686ef7fead8` | pending |
| `docs/CATALOG_IMPACT.md` | `d5b46e4e9cad` | pending |
| `docs/CHOICES.md` | `dfabdc4269db` | pending |
| `docs/FILE_IMPORT.md` | `1d881e45b2d6` | pending |
| `docs/FOCUS.md` | `e54c54de9106` | pending |
| `docs/FRONTEND_HANDOFF.md` | `90442d9e577a` | pending |
| `docs/GOALS.md` | `7de962fe7ce8` | pending |
| `docs/IMPORT_ISSUES.md` | `c67e6772c8ee` | pending |
| `docs/LOCAL_ENTRIES.md` | `a9ee029aa9a4` | pending |
| `docs/NATIVE_LICENSE_EVIDENCE.md` | `2e4ccf997d62` | pending |
| `docs/PERFORMANCE_QA.md` | `085f9b5ba29a` | pending |
| `docs/PORTABILITY.md` | `21dffc1398bc` | pending |
| `docs/RUNTIME_CATALOG.md` | `560c15e642e1` | pending |
| `docs/TRANSFER_RETENTION.md` | `01245bf56241` | pending |
| `docs/UI.md` | `1d7134f95efd` | pending |
| `docs/UI_CONTRACT.md` | `8a4e13ce7bbf` | pending |
| `docs/VERIFICATION.md` | `66f6a48b00de` | pending |
| `docs/backend/release-runtime-licenses.json` | `3ba3773b53ce` | pending |
| `docs/history/ACCEPTANCE_GAPS_through_v15.md` | `8aae3fc418bf` | pending |
| `docs/history/backend-assignment-fix-20261014.json` | `5b60d3762264` | pending |
| `docs/history/backend-contracts-20261014.json` | `fdd187c1ab1e` | pending |
| `docs/localization/README.md` | `6ee2a1fd0c3e` | pending |
| `docs/localization/tracker-localization-12-locales-editorial.zip` | `eca102490bb8` | pending |
| `docs/release/release-native-provenance-review.json` | `ed87c24d267b` | pending |
| `docs/release/release-native-provenance-review.md` | `da80dc7768a8` | pending |
| `docs/ui/screens/00-timer-idle-dark.png` | `8bfcda8cdd08` | pending |
| `docs/ui/screens/00-timer-idle-light.png` | `3bb74fb187ad` | pending |
| `docs/ui/screens/01-timer-running-dark.png` | `f4c2911b35bf` | pending |
| `docs/ui/screens/01-timer-running-light.png` | `16cc66a3cc8f` | pending |
| `docs/ui/screens/02-picker-dark.png` | `2deaa0150e5a` | pending |
| `docs/ui/screens/02-picker-light.png` | `f67b17361229` | pending |
| `docs/ui/screens/03-correction-dark.png` | `6a8b1d221ca8` | pending |
| `docs/ui/screens/03-correction-light.png` | `bc59faefe268` | pending |
| `docs/ui/screens/04-focus-dark.png` | `1a248c933d2c` | pending |
| `docs/ui/screens/04-focus-light.png` | `998fa0cc1d5d` | pending |
| `docs/ui/screens/05-overview-dark.png` | `6d5c76e011f1` | pending |
| `docs/ui/screens/05-overview-light.png` | `e9cca0998589` | pending |
| `docs/ui/screens/06-settings-dark.png` | `ab386173557b` | pending |
| `docs/ui/screens/06-settings-light.png` | `cca285993532` | pending |
| `docs/ui/screens/06b-settings-full-dark.png` | `2427e66ac90c` | pending |
| `docs/ui/screens/06b-settings-full-light.png` | `c82c9794041b` | pending |
| `docs/ui/screens/07-recovery-dark.png` | `2b4426e3e630` | pending |
| `docs/ui/screens/07-recovery-light.png` | `b4f3f2a1f1c6` | pending |
| `docs/ui/screens/08-entry-menu-dark.png` | `af62438a0508` | pending |
| `docs/ui/screens/08-entry-menu-light.png` | `3dde390a30a1` | pending |
| `docs/ui/screens/09-delete-confirm-dark.png` | `ff38b90acda6` | pending |
| `docs/ui/screens/09-delete-confirm-light.png` | `94fb5385773d` | pending |
| `docs/ui/screens/10-quick-pick-dark.png` | `aedc275ae357` | pending |
| `docs/ui/screens/10-quick-pick-light.png` | `36b1b9a7eff9` | pending |
| `docs/ui/screens/11-surfaces-dark.png` | `f03a16de553a` | pending |
| `docs/ui/screens/11-surfaces-light.png` | `c99a1880b955` | pending |
| `docs/ui/screens/12-floating-dark.png` | `a2b4f2f0b980` | pending |
| `docs/ui/screens/12-floating-light.png` | `937fd858ca48` | pending |
| `docs/ui/screens/13-first-launch-dark.png` | `fc5226dae1ab` | pending |
| `docs/ui/screens/13-first-launch-light.png` | `1f3f18fa0943` | pending |
| `docs/ui/screens/14-first-overview-dark.png` | `dbe793183e9d` | pending |
| `docs/ui/screens/14-first-overview-light.png` | `ff3f73812b88` | pending |
| `docs/ui/screens/15-first-picked-dark.png` | `b91efdcc6249` | pending |
| `docs/ui/screens/15-first-picked-light.png` | `7bef771a69bf` | pending |
| `evidence/acceptance-followup/verification.json` | `6e6d7da2fd07` | pending |
| `evidence/acceptance-v11/core-tests.xml` | `fc8ae0764d6f` | structural parse only; semantic/device review not claimed |
| `evidence/acceptance-v11/lint-results-debug.txt` | `7767fda272a0` | pending |
| `evidence/acceptance-v11/room-tests.xml` | `0f57f8009fa5` | structural parse only; semantic/device review not claimed |
| `evidence/acceptance-v11/summary.json` | `0bf0be599205` | pending |
| `evidence/acceptance-v11/verification.log` | `538a935e51a3` | pending |
| `evidence/adb-devices.txt` | `66dc3881f2be` | pending |
| `evidence/android-build-blocked.log` | `ac06b5da3d31` | pending |
| `evidence/android-build.log` | `0024b792965e` | pending |
| `evidence/apk-manifest.txt` | `5be11951caa2` | pending |
| `evidence/apk-signature.txt` | `b0a1af67205e` | pending |
| `evidence/audit-updates/publication.json` | `18363ff3e520` | pending |
| `evidence/audit-updates/scale-comparison.json` | `90b8a8e81afc` | pending |
| `evidence/audit-updates/verification.json` | `8c111d9dbfc0` | pending |
| `evidence/backend-integration/verification.json` | `4614a62e2369` | pending |
| `evidence/backend-v10/before-verification.log` | `cc22b42073bc` | pending |
| `evidence/backend-v10/core-tests.xml` | `bd0f4c46ea48` | structural parse only; semantic/device review not claimed |
| `evidence/backend-v10/lint-results-debug.txt` | `7767fda272a0` | pending |
| `evidence/backend-v10/room-tests.xml` | `00ff6965a2e8` | structural parse only; semantic/device review not claimed |
| `evidence/backend-v10/storage-after.txt` | `28e0b5e03aa9` | pending |
| `evidence/backend-v10/storage-before.txt` | `d24c6250e672` | pending |
| `evidence/backend-v10/summary.json` | `460657b04a9d` | pending |
| `evidence/backend-v10/verification.log` | `c7aca593f2c8` | pending |
| `evidence/backend-v13/apk-badging.txt` | `0b9fea2fe053` | pending |
| `evidence/backend-v13/build.log` | `32088681f72d` | pending |
| `evidence/backend-v13/checked-path-benchmark.txt` | `f36a8b702463` | pending |
| `evidence/backend-v13/core-tests.xml` | `09c343c8d799` | structural parse only; semantic/device review not claimed |
| `evidence/backend-v13/lint.txt` | `47e6741b62cf` | pending |
| `evidence/backend-v13/room-tests.xml` | `66ea32c807ea` | structural parse only; semantic/device review not claimed |
| `evidence/backend-v13/storage-workload.txt` | `ce1aa7221f8f` | pending |
| `evidence/backend-v13/verification.json` | `a91a16405285` | pending |
| `evidence/choices-v16/apk-badging.txt` | `dc2b064e7463` | pending |
| `evidence/choices-v16/build.log` | `8ced58dc5534` | pending |
| `evidence/choices-v16/choices-summary.json` | `77194040e8e0` | pending |
| `evidence/choices-v16/core-tests.xml` | `b479e03e0b98` | structural parse only; semantic/device review not claimed |
| `evidence/choices-v16/lint.txt` | `2ecfb35f20f0` | pending |
| `evidence/choices-v16/room-tests.xml` | `7f923cb16759` | structural parse only; semantic/device review not claimed |
| `evidence/choices-v16/verification.json` | `62a084e10130` | pending |
| `evidence/classification-hash-guard/verification.json` | `5bc44835d76f` | pending |
| `evidence/cold-start-sql/android-baseline.json` | `666cd172ff5a` | pending |
| `evidence/cold-start-sql/android-migration3to4.json` | `9fab0cd16893` | pending |
| `evidence/cold-start-sql/android-optimized.json` | `967b863c8869` | pending |
| `evidence/cold-start-sql/jvm-baseline.json` | `223b2af2a4b0` | pending |
| `evidence/cold-start-sql/jvm-keyset-index.json` | `dbf29c766ce2` | pending |
| `evidence/cold-start-sql/jvm-position-index.json` | `f70e4b0c578a` | pending |
| `evidence/cold-start-sql/verification.json` | `72aec853b1bc` | pending |
| `evidence/compose-current/android-action-matrix.json` | `79365cff31f0` | pending |
| `evidence/compose-current/verification.json` | `5673da49bcc3` | pending |
| `evidence/core-test.log` | `ab1d6ed57fe4` | pending |
| `evidence/core-tests.xml` | `0df898e76250` | structural parse only; semantic/device review not claimed |
| `evidence/draft-validation-v18/apk-badging.txt` | `cc3682ba6bc7` | pending |
| `evidence/draft-validation-v18/build.log` | `29f0c68c3c78` | pending |
| `evidence/draft-validation-v18/core-tests.xml` | `c340956853e8` | structural parse only; semantic/device review not claimed |
| `evidence/draft-validation-v18/lint.txt` | `1adf6df34503` | pending |
| `evidence/draft-validation-v18/room-tests.xml` | `f76055fcf6c1` | structural parse only; semantic/device review not claimed |
| `evidence/draft-validation-v18/verification.json` | `6a2e195400fc` | pending |
| `evidence/focus-v15/apk-badging.txt` | `94af95b554cb` | pending |
| `evidence/focus-v15/build.log` | `8a94e1917173` | pending |
| `evidence/focus-v15/checked-path-benchmark.txt` | `adfb00353f2f` | pending |
| `evidence/focus-v15/core-tests.xml` | `376839077eae` | structural parse only; semantic/device review not claimed |
| `evidence/focus-v15/focus-example.json` | `afcacfaff783` | pending |
| `evidence/focus-v15/lint.txt` | `f2c8a44a8c02` | pending |
| `evidence/focus-v15/room-tests.xml` | `cd9365b296bf` | structural parse only; semantic/device review not claimed |
| `evidence/focus-v15/storage-workload.txt` | `20eb79e91703` | pending |
| `evidence/focus-v15/verification.json` | `b481f25852af` | pending |
| `evidence/format-import-v12/core-tests.xml` | `7244d54580b8` | structural parse only; semantic/device review not claimed |
| `evidence/format-import-v12/lint-results-debug.txt` | `7767fda272a0` | pending |
| `evidence/format-import-v12/room-tests.xml` | `23afd869caa1` | structural parse only; semantic/device review not claimed |
| `evidence/format-import-v12/summary.json` | `a118547038be` | pending |
| `evidence/format-import-v12/verification.log` | `731b57341ed1` | pending |
| `evidence/goals-v14/apk-badging.txt` | `4abb49c3c558` | pending |
| `evidence/goals-v14/build.log` | `1d9dbec43677` | pending |
| `evidence/goals-v14/checked-path-benchmark.txt` | `fca8c5e63235` | pending |
| `evidence/goals-v14/core-tests.xml` | `8d77fd90447f` | structural parse only; semantic/device review not claimed |
| `evidence/goals-v14/goals-example.json` | `9b27e857cfcc` | pending |
| `evidence/goals-v14/lint.txt` | `a125e12d95a8` | pending |
| `evidence/goals-v14/room-tests.xml` | `daa8ea84931b` | structural parse only; semantic/device review not claimed |
| `evidence/goals-v14/storage-workload.txt` | `058d73694161` | pending |
| `evidence/goals-v14/verification.json` | `4d6a3308940f` | pending |
| `evidence/lint-results-debug.txt` | `0adf6a075adb` | pending |
| `evidence/local-entries-v17/apk-badging.txt` | `facfe421519e` | pending |
| `evidence/local-entries-v17/build.log` | `c4a76a4330ec` | pending |
| `evidence/local-entries-v17/core-tests.xml` | `a1df178e833f` | structural parse only; semantic/device review not claimed |
| `evidence/local-entries-v17/lint.txt` | `642c5e775cdf` | pending |
| `evidence/local-entries-v17/room-tests.xml` | `1ca805a5204a` | structural parse only; semantic/device review not claimed |
| `evidence/local-entries-v17/verification.json` | `6c75082b1b97` | pending |
| `evidence/normalized-history/after-compact.json` | `9d29fa060c50` | pending |
| `evidence/normalized-history/after-normalized.json` | `d86cfc39cb3c` | pending |
| `evidence/normalized-history/after-saf.json` | `775cd0a807f9` | pending |
| `evidence/normalized-history/android-archive-comparison.json` | `a5940bdc7107` | pending |
| `evidence/normalized-history/android-normalized-migration4to5.json` | `a4a586601b55` | pending |
| `evidence/normalized-history/android-normalized5.json` | `63ec401e57b1` | pending |
| `evidence/normalized-history/android-sql4-compact-json.json` | `516468b3cecf` | pending |
| `evidence/normalized-history/android-sql4-precompact.json` | `2c56474809ee` | pending |
| `evidence/normalized-history/before-compact.json` | `775cd0a807f9` | pending |
| `evidence/normalized-history/compact-experiment.json` | `e1031acc68c7` | pending |
| `evidence/normalized-history/jvm-normalized.json` | `a2ad892cacab` | pending |
| `evidence/normalized-history/verification.json` | `ab33ed270a3a` | pending |
| `evidence/room-tests.xml` | `9faad9c7168a` | structural parse only; semantic/device review not claimed |
| `evidence/ui-merge/checkpoint.json` | `0949c80696dd` | pending |
| `gradle.properties` | `66eb28b6c7a2` | pending |
| `gradle/wrapper/gradle-wrapper.jar` | `81a82aaea5ab` | pending |
| `gradle/wrapper/gradle-wrapper.properties` | `1bb6d43ff8af` | pending |
| `gradlew` | `734b3879d350` | pending |
| `gradlew.bat` | `57931b17dd22` | pending |
| `metadata/dev.notai.tracker.yml` | `70d88ddac706` | pending |
| `samples/history-v1.json` | `b8cd5312ae6d` | pending |
| `samples/history-v2.json` | `3f3dc6000415` | pending |
| `samples/open-tail-v1.json` | `1ce4c9ff12d6` | pending |
| `samples/transfer/README.md` | `e5230cfc70ce` | pending |
| `samples/transfer/schema-a.csv` | `59dbeca4aa0a` | pending |
| `samples/transfer/schema-b.csv` | `8a3b5ab48440` | pending |
| `samples/transfer/toggl-supported.csv` | `a67e57ca297c` | pending |
| `scripts/activity_icons.py` | `aba5363a1102` | structural parse only; semantic/device review not claimed |
| `scripts/android_startup_samples.py` | `56b76d9875c4` | structural parse only; semantic/device review not claimed |
| `scripts/android_store_fingerprint.py` | `135fbe486887` | structural parse only; semantic/device review not claimed |
| `scripts/android_ui.py` | `6fafd280837f` | structural parse only; semantic/device review not claimed |
| `scripts/app_icon.py` | `59c4723452dd` | structural parse only; semantic/device review not claimed |
| `scripts/build-cleanup-host.sh` | `26f8ad7cd94d` | pending |
| `scripts/dependency_license_inventory.py` | `0266baac0859` | structural parse only; semantic/device review not claimed |
| `settings.gradle.kts` | `95ab85802c94` | pending |
