# Optional attention data and capability boundary

Source review of LongRecordReminder, FocusEnvironment, ObservationFacts and their
three Android adapters; verify the built manifest in the accompanying artifact record.
No UI, settings grant or user data operation is implied by this document.

| Data/effect | Destination and authority | Portable? |
| --- | --- | --- |
| Ordinary reminder enabled/afterMs/snoozeMs | Existing Tracker.settings transaction, Dataset settings | Full JSON13; nondefault values reject in older formats |
| Ordinary activation/ticket/receipt/revision | App-private noBackupFilesDir/long-record-reminder.json; existing durable metadata helper | No; restore requires explicit local confirm |
| Focus request/own-rule lease/creation intent | App-private noBackupFilesDir/focus-environment.json | No; remains tied to this device and own rule |
| DND condition | Android NotificationManager, only a checked own AutomaticZenRule; policy access and actual configuration Activity required | OS capability, no grant by manifest or backup |
| Selected local audio bytes | One no-follow/nonblocking opened regular FD, app-owned MediaPlayer after audio-focus acceptance and foreground check | No URI/network/account; no external player/volume control |
| Ordinary notice | NotificationManager through Application-owned quiet system-card composition; exact binding, permission/channel independently checked | Receipt records attempted outcome, not human display |
| Weekly observations | Existing captured Reports/Goals projection in memory with explicit zone | No new history, award ledger, external transmission or notifications |

The manifest declares ACCESS_NOTIFICATION_POLICY for the optional DND port. The
adapter never invokes global policy setters and cleanup parks its checked rule
condition without deleting configuration. Permission loss, incompatible build
fingerprint or unknown creation outcome retains an explicit cleanup/review state.

No HTTP client, analytics SDK, endpoint or additional dependency was introduced
by this delta. The release merged manifest/APK checks establish permission and
packaging evidence; this source review is not a runtime traffic capture. Existing
SAF and Calendar interactions keep their separate permission/provider boundaries.
Device/OEM, permission flow, actual notification/DND/playback and accessibility
acceptance remain open. No store privacy draft was published.

Local music supports API26 through the existing native module's compiled ABI O_CLOEXEC getter (745c7ed); API27+ uses the public constant. One atomic Os.open retains O_RDONLY/O_CLOEXEC/O_NOFOLLOW/O_NONBLOCK, followed by regular/nonempty fstat and retained descriptor. Missing native capability fails closed. This has host JNI/fixture and four-ABI build evidence; actual Android Os/audio/device execution remains unverified. See [current verification](BACKEND_FOLLOWUP_VERIFICATION_2026_10_16.md).
