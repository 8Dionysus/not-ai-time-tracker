# C: notification, launcher, platform feedback — 2026-10-23

Base: `21f1a82ba83a3c393eff503ea6fe045ba99248a9`, tree `f30f9fecb5a91bc0900fe358ed975d6b3bd798d3`, 0.15.1/code 17.
Isolated branch `feedback/c-platform`; no push, merge, signing, APK release, device changes or emulator.

## Integration contract

- C owns TimerSurfaces, SurfaceViews, notification-only layouts, SurfaceBackend, launcher controller and manifest. AndroidRuntime / TrackerViewModel remain A-owned.
- `LauncherIconController.options`: stable keys `default`, `amber`, `mint`, `violet`; aliases `LauncherDefault`, `LauncherAmber`, `LauncherMint`, `LauncherViolet` in `dev.notai.tracker.android`.
- `read()` returns observed component state, including duplicates/zero enabled aliases as `selected == null`. `select(key)` is an IO operation; result contains observed state and independent `shortcutsRebound` status. The B picker must surface failure/uncertain state honestly and refresh capabilities on foreground.
- After the visual continuation, C supplies amber/mint/violet adaptive launcher palette assets using unchanged bracket/clock vector geometry. Default remains the original icon; all use the original monochrome themed layer. Existing option keys, aliases and label resource IDs are retained. The typed descriptor exposes `labelRes` / `option.label(context)`; labels now correspond to actual palette assets. No mapping to B theme names is invented. MainActivity stays enabled and receives notification, PROCESS_TEXT, picker and shortcut payload intents.
- Shortcuts keep IDs `timer_action`, `timer_choose`, `timer_favorite_0`, `timer_favorite_1`, original immutable payloads and ranking. Their `activity` is rebound to the selected launcher alias, while their intent still targets MainActivity. Launcher icon switching and shortcut publication share only a narrow alias lock; no Tracker mutation is performed by the controller.
- `SettingsCapabilities.timerNotification` now contains `requestedVisibility`, `publishedVisibility`, `publishedHasPublicVersion`, channel importance, requested status-bar-icon eligibility and channel lock-screen policy. Null published fields mean no observed current timer notification. These are app/platform facts, not a claim that an OEM showed the row on its lock screen.
- B should use `SurfaceSettingsBackend.settingsIntent(context, OPEN_TIMER_CHANNEL_SETTINGS)`: exact channel intent with app package and the unchanged `timer_controls` ID. Do not recreate the channel or override its user policy.

## Notification latency

Before: receiver IO -> synchronous runtime initialization/validation -> core command/commit -> refresh preparing shortcuts, widget, then notification -> store gate publishing shortcuts, widget, then notification. The collector also ran backup/attention/schedule before surfaces. No measured device delay is attributed to a particular phase yet.

Now: notification is prepared and published in the first full captured-view gate, before shortcut/widget preparation or publication. Ancillary publication uses a second full gate. Both verify dataset/revision/barrier/token/timerTarget/mode/focus id/phase/mode and honor current permission/opt-out. Any stale second stage cannot overwrite newer platform surfaces. Preparation/publication failures remain independent. Store validation is retained.

Debug-only `TimerActionTiming` logs scalar elapsed milliseconds per invocation: `received`, `runtime-ready`, `command-committed` (or `command-rejected`), `notification-published` / `notification-withdrawn` / `notification-skipped`, `ancillary-published` / `ancillary-skipped`. No titles, tokens, intents or history are logged. Notification milestone means the manager call completed, not verified screen display. Runtime cost and commit cost remain distinct from post-commit publication cost. Existing `StartupTrace` stays available for startup phases; no broad profiling project or validation bypass was added.

## A-owned proposed hunk

The delivery includes `runtime-proposed.patch`, not applied to the checkout. It moves surface refresh before ancillary collector work and exposes a system-zone StateFlow from the existing registered receiver. AUTO/UI should consume that signal and resolve the report zone without moving historic instants or changing JSON 15. If A/B choose a different AUTO controller, retain the same narrow TIMEZONE_CHANGED reconcile point. A must integrate this with its own runtime changes and verify the combined tree.

## Visual continuation: original pixels viewed and implementation completed

The earlier Library shell-transfer HTTP 403 remains historical. A newly authorized Page reference read returned actual PNG image content for every one of the nine originals; all nine were viewed directly before visual implementation. Original user screenshots are not copied into this delivery. The private delivery records their byte sizes/hashes and view status without exposing the screenshots' contents.

Collapsed idle notification now uses its own `notification_timer_row.xml`: the idle caption is hidden and up to four favorite icons use the same core order, references and immutable PendingIntent route as existing quick choices. Visible labels are absent in collapsed; accessibility labels say “Начать: …”. Expanded labeled quick choices and shared widget XML remain unchanged. The core bounded quick projection is shared by collapsed/expanded rendering, avoiding an extra history query. Active, paused, recovery and Focus retain their chronometer/control paths.

The compact row uses a conservative display-width budget (96dp system decoration + 48dp main control + 40dp per favorite slot), capped at four. Android does not expose an OEM notification host's actual measured width to the app publisher, so this is an estimate, not a claim of universal OEM fit. Host fixtures measure the actual inflated content at 124/184/224/304dp and require every visible icon to fit; default-text collapsed height must fit 48dp. With fontScale > 1.15, the compact extras are hidden to preserve the title/control row. Unusually constrained OEM cards and small targets still require device QA; no tall row is promised.

Three launcher palettes keep the exact original vector paths: amber on warm light background, mint on dark green, violet on dark violet. The original default resource is unchanged. Adaptive masks/themed icon presentation remain launcher/system policy; all variants deliberately share the original monochrome layer. This completes the previously missing color assets and resolves the earlier placeholder-label contradiction without translating B's light/dark/accent/neon keys.

Host-rendered synthetic PNGs show default/amber/mint/violet icons and light/dark notification idle/expanded/running/paused/recovery states. They were visually inspected. These previews are JVM renderings of the real Android resources, without system decoration or personal user data; they are not device screenshots.

## Verification status

Host: Kotlin production/test compilation and resource/manifest processing passed in the targeted Gradle tasks. Eighteen unique targeted checks accepted across runs: all 13 existing TimerSurfaceRaceTest cases; 3 new order/second-gate/privacy cases in the initial run; both launcher cases (API 32/36) after the payload fix. Initial launcher failures were preserved in delivery evidence: an intent-less partial update in the host model was replaced with explicit public-field copying; a fixture comparing newly generated chooser route IDs after ordinary refresh was corrected to distinguish selection-preserved bytes from a fresh route. Independent accepted cases were not rerun.

One priority case was then rerun with a clearly synthetic 80 ms ancillary stall. It confirmed an already published notification before entering ancillary preparation: host boundary 296 ms, complete call 405 ms, injected stall 80 ms. This is an ordering witness, not a device benchmark or a measured before/after speedup. No cold-start performance gain is claimed.

Static manifest contract and `git diff --check` passed. The proposed A hunk passes `git apply --check` against the base runtime and remains unapplied. No broad independent suites, APK assembly, lint, signing or device/emulator actions were run. The visual continuation adds three targeted native-graphics host checks: compact width/height/accessibility/payloads, active/paused/recovery/enlarged-text rendering, and launcher palette labels/rendering. The launcher test was accepted independently; only the two affected notification tests were rerun after resource fixes. Initial fixture qualifier ordering and an unsupported RemoteViews reflective method were corrected; final binding uses static notification-only XML attributes.

Device/OEM, real permissions, actual launcher cache behavior, failed-platform rollback on a device, physical cold-start/action latency, and system-hosted notification layout are unmeasured. The old-API rollback implementation retains the newly enabled alias if restoring every former entry fails, rather than disabling the last known working entry.

## Primary Android API references

- [Atomic component settings, API 33+](https://developer.android.com/reference/android/content/pm/PackageManager#setComponentEnabledSettings(java.util.List))
- [Shortcut target activity binding](https://developer.android.com/reference/android/content/pm/ShortcutInfo.Builder#setActivity(android.content.ComponentName))
