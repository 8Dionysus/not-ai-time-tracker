# Backend verification and evidence boundaries

Current product source: `2d620140d411b2856da4bed81502b17b93f68cf8`. Full prior test/Android fixture source: `1ecd9a84238e44c06487a8eac4eae924a86eced6`. dff31cf adds missing-installed-DB completion refusal and its Room-fixture assertions, with78app tests passing and unsigned builds/lint successful in4m15s. 2d62014 adds nullable catalog rights provenance, explicit pending/cleared status and lazy requested-locale loading; it changes no domain persistence, timer/receipt/native/fixture paths. Core98 is rerun for that content change; app78 is retained at dff31cf for unchanged relevant paths. Each exact test/build source is recorded in artifact-verification.json.
The full build finished successfully in7m49s:98core/78app tests, zero failures/errors/skips, debug/release APKs, release lint and dependency inventory. Lint:0errors/64warnings/1hint. Exact commands, artifact hashes and separately scoped Android results are recorded in the delivery artifact-verification.json. Room9,
full JSON11/read1–11, selective1–2, maintenance/recovery operational format3 and
catalog sidecar schema1 are independent version boundaries.

Full workspaces build command at1ecd9a8:

```text
/workspace/tracker-tools/run-gradle :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintRelease :app:releaseInventory -PtrackerCleanupLibrary=/workspace/tracker-tools/libtracker-copy-android-root.so -Pkotlin.compiler.execution.strategy=in-process
```

Final recovery guard at dff31cf ran :app:testDebugUnitTest plus unsigned debug/release,
lint and releaseInventory successfully in4m15s (78app,0failures/errors/skips).
Final content-only delta at2d62014 completed successfully in4m28s: :core:test plus unsigned debug/release,
lint and releaseInventory with the same JNI property;98core,0failures/errors/skips.
No repeated app UI suite or load profile is inferred from that final content build.

Signed debug app/test APKs are preserved only for the explicit isolated emulator QA.
Delivery debug/release APKs are unsigned; debug is separately assembled with
-PtrackerUnsigned=true after preserving the signed QA files. No release key exists.
Artifact checks cover16-KiB zip alignment, unsigned signature state, aapt badging,
min26/target36, embedded release VCS SHA, eight native entries/four ABIs, ELF
headers/hashes and no INTERNET permission in the merged release manifest.
These are not an assurance against every OS/provider-mediated data flow.

The host JNI was built from unchanged private_copy_io.c and used by the explicit
-PtrackerCleanupLibrary property; omitted-property assumption skips do not count as
acceptance. Clang21 host-target analysis has0 diagnostics. Android's four native
ABI builds are separate from runtime execution. License inventory reads resolved
release artifacts and cached POM declarations; generated third-party notices are
compared with the APK asset. This does not close native static-runtime/source or
catalog/content rights. F-Droid disabled recipe and isolated enabled fixture pass
metadata lint only; no server build/reproducibility is claimed.

## Preserved intermediate failures

Historical5213335 full build passed96core/73app with lint0errors/60warnings/1hint.
That earlier result does not accept this new version. The c5ffa34 attempt failed
because a test-only migration builder omitted8→9 and a Robolectric pinned shortcut
assertion assumed a dynamic entry. Both fixture defects were corrected. The first
workspace fixture incorrectly expected Undo after a later schedule mutation; it
now verifies STALE and unchanged dependencies. Exact49f30f3 passed98core and failed
one of78app tests: its new draft validator expected64hex while the existing engine
uses a composite128hex operation hash. 1ecd9a8 fixes the adapter, preserving archive/
engine hashes, and finishes source leases after leaving the maintenance gate.
Raw failed logs/XML remain labeled as failed; they are not silently overwritten
with final results in the delivery history.

## Indexed journal evidence

The matched isolated profile is an exact measurement of `69d926f`, compared with
v7 runtime `fe0e490`. Receipt engine, SQL adapter, indexed delivery and profile blobs
remain identical in1ecd9a8. Schedule.kt canonical capture extraction and
ScheduleSettings.kt next-civil-boundary/readmodel helpers changed; source equivalence
records those exceptions explicitly. There is no fresh load run or blanket claim
that all scheduler sources match. Raw logs, three samples, SQL counts and old receipt
fingerprints are retained with69d's source SHA. The host median1017.449→2.616ms at
47,392receipts/16,777,216old-JSON-bytes and cold reopen100,001receipts are not phone,
PSS, battery, OEM or statistical latency acceptance. No arbitrary receipt eviction
or whole-file16MiB receipt ceiling was added. Other metadata bounds and RAM-linear
whole-domain graph remain.

## Android runtime evidence

First historical600s preflight did not reach boot_completed. The later authorized
repeat used official Emulator37.2.12 and AOSP API36 x86_64 with TCG/noKVM. Actual adb
37.0.1-15733141 was verified; writable HOME applies only to SDK child processes,
without changing the parent HOME or host permissions. Boot completed572.130s; both
signed521 APK installs returned Success. Two instrumentation launches then ended
Process crashed before fixture creation. System logs show startup ANRs including
Tracker/Phone/Media/Contacts, without an identified Tracker exception/native crash.
They are failures, not runtime acceptance.

An authorized same-AVD repair boot completed431.459s, PID29338. The requested
ro.hw_timeout_multiplier=20 was NOT reflected in getprop; its application is not
claimed. The same running VM was kept for subsequent backend work/QA by extending
only task-local supervision, without another reboot. Final synthetic instrumentation uses a small BackendInstrumentation entry point and exact current debug app/test APKs. An initial1ecd test launch failed before any fixture: AGP overrode the custom test-manifest runner name with ArchiveInstrumentation. The QA-only backend-runner.init.gradle explicitly sets defaultConfig.testInstrumentationRunner; it changes no production code. Its exact build command/bytes/hash are retained. See evidence/android-qa/verification.json for actual outcome/commands, APK
hashes, process IDs and reports. No success is inferred from install or boot alone.

Create/reopen modes exercise actual Context→descriptor binding, native SQLite legacy
receipt migration/duplicate refusal, custom reading→philosophy→Заратустра and Room
timer/shortcut settings/date-only schedule, saved selective file review/apply/replay,
full replacement with rollback, protected synthetic staging, interruption after first
unlink, cold process reopen without automatic delete, explicit resume and idempotent
completion. Only fresh UUID-owned databases/directories change; an unselected sentinel
and tracker.db before/after identity are checked. Other native ABIs are built only.
Physical OEM/reboot/notification delivery/permissions/TalkBack/RTL/overlay P2/goal-edit
acceptance remains open regardless of the synthetic result.

The successful1ecd create/reopen fixture used processes5081→5135 with identical
Dataset hash3d85ae72d47a0a7401ca84e269029b658985b7795edaa9c00012de83200e9001,
33 indexed receipts, preserved legacy bytes, no automatic deletion on reopen and
explicit PARTIAL→DONE resume. Context private root mode40771 was observed. Both
instrumentation codes were-1 and the driver independently compared PIDs/hash.
After fixture success an explicit stop was requested; the resumed supervisor recorded QEMU exit-11 during shutdown. This is preserved separately and is not attributed to a Tracker Java/JNI crash. No further boot was performed. This
is scoped Android evidence for1ecd, not execution of the additional recovery guard or catalog sidecar changes, other ABIs/OEMs or frontend rendering.

## Scope and handoff

See BACKEND_WORKSPACES_HANDOFF.md, CATALOG_CONTENT_PACKS.md,
PRIVATE_COPY_BACKEND_BINDING.md and FUNCTIONAL_COVERAGE_2026_10_15.md. Protected
UI/layout/MainActivity/TechnicalActivity/ViewModel/RemoteViews/resources are unchanged
from audit f14fa3c. The separately retained unaccepted caller patch is excluded from
main/runtime/source bundle. Editorial v2 bytes are not claimed locally verified
while cloud download is blocked. Requested gpt-6.1-sol/high has no independent runtime
model/effort attestation. No store publication or production clearance is claimed.

Final2d62014 unsigned APK checks passed:16-KiB zip alignment, apksigner confirms unsigned state, min26/target36/no INTERNET, embedded release VCS exact2d62014, eight native entries/four ABIs. DEX presence checks confirm the new rights enum, pending-rights token, saved controllers and missing-installed-DB guard (presence is not runtime acceptance). Debug SHA25627893353ce0abc2ea106a7058f8fab721ce10baa42e8e185306a39e851b80a61; release SHA256fe9735effde1cece8670c6864c844ec8aa634ac846a940570b6e490fe17b02cc. Final license inventory:65external/0unknown POM declarations,27notice members; generated notice bytes exactly match the APK. Final recipe lint passes in both isolated fixtures. Public summary evidence: [artifact-verification.json](../evidence/backend-workspaces-2026-10-15/artifact-verification.json); raw XML/logs/APKs/source bundle are retained in the full Library delivery.
