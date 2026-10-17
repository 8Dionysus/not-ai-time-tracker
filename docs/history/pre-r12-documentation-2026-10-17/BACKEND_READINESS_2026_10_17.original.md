# Backend / pre-UI continuation — 2026-10-17

## Current result and evidence boundaries

Accepted Library v10 is retained in full. This continuation closes the remaining actual notification/overlay gesture paths on the **unchanged installed APK** and adds two concrete backend fixes. No new UI, dependency update, installation, release signing, GitHub publication or additional agent was used.

Accepted base: `841448b8a3ff26111fce9bdf8418c170dbc7a622`. Branch: `prep/native-performance-lint-2026-10-16`.

| Source | Change / proof |
|---|---|
| `a5ae96f8162de9d08f5e8d35b86a6094abf44af8` | Backend owns Application/channel startup; final publication checks the exact factory registration. Red/green schedule regression and four lifecycle tests. |
| `1c97d72f2516e8b9b480c4abd635804db6695668` | Optional terminal export catalog registration moves to existing process IO queue after synchronous receipt/chooser/writer handoff. Red/green blocked-registration regression. |
| Installed native reference: production `3fc9920443ed9d89d5341857d060c528361133b2` | APK SHA256 `988ab6f8a3f316974014c85704482ef048f511400cf8a9daf5cfe151d317acfb`; this installed version supplied the native UI evidence. **The new candidate has not been installed or natively accepted.** |

Targeted verification: **21 tests, 0 failures/errors/skips**, five raw XML files in `evidence/backend-r11-targeted-green2/`. The first targeted attempt had a fixture compilation error (Robolectric4.17 has a non-generic getApplication API), executed no tests and is separately classified; its leftover XML is explicitly marked preexisting. Full candidate verification succeeded: **203core+249app=452 tests,52XML,0failures/errors/skips**; core used UP-TO-DATE results for unchanged inputs and the full app suite executed. Debug/debugAndroidTest APK and lintRelease succeeded offline. Raw: `evidence/backend-r11-full/summary.json`, `backend-r11-full-build.log`. Lint75warnings+1hint; all76 file/line/issue identities matched the new XML and remain individually classified52retain/24futureUI. Room12, full JSON14/read1–14 and selective4/read1–4 are unchanged.

## Remaining actual UI gestures

On the same isolated AOSP API35 x86_64 COW, serial `emulator-5590`, guest boot6:

- Notification: physical double taps on the observed Start/Pause/Resume/Stop controls. Four pairs, revisions64→68, one session/two segments.
- Overlay: physical double taps on the existing Start/Stop puck. Two pairs, revisions68→70, one session/one segment. Pause/Resume controls are not offered by this puck.
- Every successful pair gives one transition. SQL transactions and streamed hashes retain the old rows. These are actual input events, independently of earlier receiver-boundary duplicate tests.
- Overlay Stop creates its ordinary durable assignment receipt. The exact own receipt was then opened through the existing SurfacePickerRoute and consumed by a real “Оставить без занятия” tap. That was cleanup, not substitute gesture evidence. Saved time and every recorded row remain exact.

Current synthetic Dataset after cleanup: **73,013 sessions / 73,018 segments, revision71, IDLE, no Focus, no pending assignment**. The extra session from the earlier driver-settle attempt remains as recorded QA data; nothing was deleted or restored. Raw failed attempts and their exact outcomes are retained. Attempt r11c used an insufficient host settle predicate before asynchronous Pause completed; the later transition and real cleanup Stop are recorded. Successful r11d waits for the expected notification state.

`timer_controls` was actually blocked (`importance=0`) at entry. Within the explicitly approved window it was enabled through the real Android Settings switch and restored to0. **Every Android permission grant/flag line is exact before/after** (six rows); original POST denial/flags, POST_NOTIFICATION ignore and SYSTEM_ALERT_WINDOW default are restored. Selected channel policy fields also match, including mUserLockedFields4. No Calendar/DND/notification-policy grant was changed. Whole-window original73010/73014 prefix hashes and metadata except revision/timerEpoch were finally rechecked read-only in boot7; no app UI/receiver was invoked for that check. The own AVD is stopped; original checkout remains main841448b and clean; selected original AVD backing metadata remains exact.

Evidence: `backend-r11-ui-completion.json`, `backend-r11d-surface-ui-result.json`, `backend-r11d-ui/`, `native-ui/backend-r11d-*.xml`, `backend-r11-assignment-cleanup-result.json`, `backend-r11-permission-restoration-verification.json`, `backend-r11-final-state-before-shutdown.json`, `backend-r11-own-avd-shutdown.json`; final read-only stage `backend-r11-whole-window-integrity.json`, `backend-r11-integrity-final-state-before-shutdown.json`, `backend-r11-integrity-own-avd-shutdown.json`.

Environment correction: v10 stated guest RAM3584MiB; that was the resource demand including emulator host overhead. The unchanged launch command is **-memory2560 -cores2**; guest MemTotal2532896KiB. See `backend-r11-environment-correction.json`. No timed comparison relied on the mistaken functional-QA label; all original v10 bytes remain in history.

## N22: completed backend work and exact production input

### Confirmed defect and fix

Before the fix, a renderer replaced by another factory while preparing a notification still posted its old prepared content. The regression obtained **POSTED instead of NOT_POSTED**. New production binding captures an exact registration token, including reinstalling the same factory instance. Final validity check and NotificationManager.notify run under the same registration gate; replacement cannot pass an old prepared notification. Renderer execution remains outside that gate. An invalidated prepared notice follows the existing NOT_POSTED/at-most-once receipt policy; no timer or schedule facts are fabricated.

Red: `backend-r11-binding-red/TEST-dev.notai.tracker.android.ScheduleDeliveryTest.xml`; before source and patch retained. Green: the corresponding test in targeted/full candidate XML. This is a deterministic application-factory replacement regression in Robolectric, not an observed OS failure.

### Working application/channel lifecycle

`TrackerApplication.onCreate` invokes `ScheduleNoticeBinding.initialize` **before activity callbacks/runtime/receivers**. The backend performs the lifecycle work:

1. Resolve an application-owned `ScheduleNoticeConfiguration`.
2. Resolve its existing string-resource labels and create the stable `schedule_reminders` channel before exposing the factory.
3. Reuse existing channel importance/sound/vibration/badge policy; configuration changes refresh channel text without resetting a blocked user channel or replacing the factory registration.
4. Fail closed on absent or failing composition. Startup does not open/create the Dataset, acquire a runtime, request a permission, post a notification or arm an alarm.
5. Warm explicit `install(factory)` remains compatible, and is explicitly process-local. Its technical fixture evidence is never relabeled as production cold binding.

The four lifecycle tests exercise the real TrackerApplication hook in Robolectric with a clearly labeled fixture configuration: configured/unconfigured startup before runtime acquisition, preserved blocked channel policy, configuration-text refresh, failing provider and same-instance reinstallation. No production renderer was introduced by a test subclass.

**Exact remaining input:** the production `TrackerApplication.scheduleNoticeConfiguration()` still returns null. Fill that one composition hook with an approved application-owned `ScheduleNoticeFactory`, an existing/approved channel-name string resource (optional description), and explicit initial channel importance. New channels otherwise use Android channel defaults. Rendering uses the application context; it must not retain an Activity. No screen needs to implement startup, channel lifecycle, delivery scheduling, durable receipts or receiver wiring. This is a production composition/presentation decision in this repository, not a blanket backend handoff to Opus.

Cold production POSTED acceptance remains pending until that input exists and the resulting candidate is authorized for installation. A process-local technical renderer does not close it. The previous default-unbound native proof is preserved with its original source version.

## Main-thread durable IO

Historical n7: widget reconcile0.757–5.038ms; export-result cancel53.147–107.445ms, median63.253ms. The native `main-io` fixture did **begin+awaitResult without prepare/staged bytes**, then repeated receiveResult(id,true). It is not a large-file export callback benchmark, and the existing timings do not split fsync and catalog cost.

The confirmed causal issue is narrower: terminal result consumption synchronously invoked best-effort catalog registration. A blocked registrar prevented the callback returning. The fix preserves synchronous operation metadata fsync, exact chooser-reference release and writer-reference release. **Only after the guarded write has fully returned** does it enqueue terminal catalog work. Enqueuing while the callback still needs the maintenance gate would retain the blocking risk, so the strengthened regression lets the optional worker hold that gate and verifies the callback has already returned and both durable references have been released.

If the process dies before optional work, the changed registration remains invalidated and bytes are unregistered/protected; no file is removed or copied. Existing non-destructive reconciliation can register them later. The queue is process-owned, independent of ViewModel cancellation. Ordinary successful destination writes keep their existing IO registration path.

Red: `backend-r11-export-red/TEST-dev.notai.tracker.android.ExportCompletionDispatchTest.xml`. Green and durable-file checks: targeted/full XML. The blocked port is explicit injection, not an OS disk failure or a latency sample. **No native millisecond/percentage improvement is claimed for the uninstalled candidate.** Required receipt/reference fsync still costs time on the caller; seven SharedPreferences commit sites remain justified, and commit→apply was not performed. Real widget request timing also includes Binder/launcher work and is not attributed solely to commit.

Evidence: `backend-r11-main-io-attribution.json`, old `native-main-io.json`, red/green XML and source patches.

## Cold startup and accepted performance work

All seven existing ba8 cold samples were reanalyzed; no new benchmark series ran. Medians:

| Observed stage | ms | Meaning |
|---|---:|---|
| Activity create→store-open gap |1785.215| Unsplit bootstrap/UI/class loading/dispatch gap; no single backend function attribution. |
| Store-open→store-initialized |1177.622| Includes preservation/Room setup. Reused archive phase has6 samples, median787.775ms; first archive creation remains its separate1 sample. |
| Store-initialized→checked history ready |2193.953| Backend decode/freeze/validation/index on an IO thread, not Android main. |
| History-ready→action first frame |336.310| Later UI/publication stage. |
| Activity create→action first frame |5538.192| Full existing cold observation. Medians are not summed. |

Core freeze260.547ms, validation648.740ms and checked-index46.375ms are separate measured methods. Full validation, corruption refusal, frozen data and archival hash checks protect data accuracy; existing evidence does not justify skipping them, trusting file timestamps or imposing a data limit. A native candidate trace after an authorized install is the next narrow attribution step if startup remains slow. No speedup or memory reduction is inferred from unchanged/historical samples.

Accepted performance improvements remain: scanner n7 **29.664203702→21.410060489s (−27.8253%)**, archive registration paired n7 **30.821839462→1.890261639s (−93.8671%)**, with exact input/output or forest/registry proof. These were accepted earlier, not rerun here. Large fixture:73,000 sessions/segments,3650days×20, input66,840,051B SHA256 `93e5f709bc899fd1845af15748acb1484a9138ee594d4b4d3572387b59918a1d`. Prior cold/warm/history/search/report/restore and memory stage samples remain in the retained v10 report and raw evidence; no additional large DB copies were made.

Evidence: `backend-r11-cold-start-attribution.json` includes each raw sample SHA, thread IDs, method sample counts, stage definitions and attribution limits; `current-performance-summary.json` preserves the complete earlier observations.

## Lint and remaining backend inventory

Risk classification remains per warning; the new full candidate XML has75warnings+1hint and every76 finding is matched individually,52retain/24futureUI. No new finding was introduced. Durable commit ordering is retained; dependencies and suppressions are unchanged. Optional catalog dispatch is an addressed backend latency cause, not justification to replace commit with apply. N16 unsupported/refuse/throw and Editor commit=false remain **explicit fault injections**; actual AppWidgetManager supported/accepted requests/dialogs/reconcile are separate positive OS proof. Actual OS/storage failures remain unobserved.

| Remaining target | Exact state / next action |
|---|---|
| Production N22 configuration | Pending input for the one hook above; backend startup/channel/receipt/publication contract implemented and tested. |
| Native acceptance of new candidate | New APK installation is not authorized. Existing APK native evidence cannot accept new bytes. Candidate build/unsigned APK/source are delivered for concrete review; no installation was attempted. |
| Candidate callback timing/process death | Pending authorized QA signing/install, then focused native checks of the changed boundary under a valid resource window. Existing native receipts retain their old code provenance. |
| Main-thread mandatory durability / unexplained startup gap | Measured risk remains. No blind async conversion, cache shortcut, limit, or unsupported causal claim. |
| Nonexistent UI actions | Overlay exposes Start/Stop; shortcut has primary Start/Pause/Resume and no Stop. No controls were invented. |
| Future schedule surface consumers/resources/language/editor UI | Existing backend APIs/consumer contract retained; production presentation connections need their own UI implementation. They do not move the completed backend lifecycle work to a UI owner. |
| Actual N16 OS failure / physical OEM / power-loss / TalkBack/RTL | Outside present native evidence. No actual failure is inferred from a fault port; no emulator result is presented as a phone/OEM test. |
| Clean-room/release/store | Exact source bundle, offline commands and toolchain provenance are supplied. No new environment installs, bit-for-bit release reproducibility, release signing or publication claimed. |

Current blockers have known unexecuted/pending states. There was no new automatic-approval rejection or unknown mutation outcome. Original source checkout/data and earlier archives are preserved. All previous v10 payload hashes must be retained by the new archive mapping, with changed old bytes under history; manifest/CRC/payload and upload receipts establish the delivered version.
