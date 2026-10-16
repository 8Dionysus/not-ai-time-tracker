# Attention backend integration verification, 16 October 2026

Historical checkpoint. Current readiness/search/API26 behavior and scoped verification supersede the corresponding older claims below: [follow-up verification](BACKEND_FOLLOWUP_VERIFICATION_2026_10_16.md). Original test/source evidence is retained.

Runtime, core tests, app tests and both unsigned APKs: `c949a8f202e143151e216a8932956a7a93b001bc`.
Published integration baseline: `7e240e45990fdcda2b938464f2f7f8697c3c7be1`.
C v3 source: `c403bc6216519aed817245830b19a3e81bc7aa7d`; exact delta transport,
author-preserving local commits and owner corrections are in
[source provenance](../evidence/backend-attention-integration-2026-10-16/source-provenance.json).
The v3 ZIP was not independently downloaded here; its three exact patch inputs
were independently decoded and size/SHA verified through private Library text transport.

```text
/workspace/tracker-tools/run-gradle :core:test --rerun :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleRelease :app:lintRelease :app:releaseInventory -PtrackerUnsigned=true -PtrackerCleanupLibrary=/workspace/tracker-tools/libtracker-copy-android-root.so -Pkotlin.compiler.execution.strategy=in-process
```

**BUILD SUCCESSFUL in 5m 31s: 130core / 99app, zero failures/errors/skips.** Both test tasks
executed; these counts are not inferred from UP-TO-DATE. Raw XML and the full log
are preserved in [evidence](../evidence/backend-attention-integration-2026-10-16/gradle.log).
The 34 Attention-specific executions include the original C contract/safety/audit
cases and owner-added backfill/Undo, timebase, own-rule, archive and migration checks.
Memory and Room run the same six Attention scenarios. The lifecycle fixture now
waits for restored backend readiness before requesting another export; UI production
code was unchanged. The first full run exposed that fixture race (isolated old case
passed); a later full run passed all tests but exposed the API27 constant on min26.
Those failed executions are preserved privately and are not counted as acceptance.

Current **Room11 / fullJSON13/read1–13 / selective1–3**. Generated schema11 differs
from schema10 only by version. Migration9→10/manualConfirmation stays intact;
10→11 preserves all SessionStatus/anchors/manual provenance and refuses a future
nondefault ordinary preference with reached-migration/transaction rollback proof.
String and streaming current13 round-trips jointly retain confirmed manual time
and the preference; genuine legacy12 manual-time data remains readable. Old selective
hashes and CSV behavior remain covered. Restore requires separate device confirmation;
unchanged restored forms cannot silently acknowledge delivery or replay UNKNOWN.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Unsigned debug APK | 13894709 | `ef3c56cc3d287c0db061138fbf4bb48b35256ba2056b53599908feeed1e496e4` |
| Unsigned release APK | 9065022 | `2096ee8eda498243a3b151fca00922dd81a83980c34e00b803f1b10fbe6532b4` |

Both APKs pass16KiB zip alignment, CRC and expected unsigned signature checks;
min26/target36/noINTERNET are verified from merged manifest/APKs. Release VCS metadata
contains the exact source SHA. Eight native entries/four ABIs are packaged. No fresh
Android/OEM VM execution is inferred: the retained AOSP fixture is historical at
1ecd9a8. Native C source is unchanged; the preserved zero-diagnostic host static
analysis is source-equivalence evidence, not a new Android runtime analysis.

Lint: **0 errors, 68 warnings, 1 hint**. The resolved graph has65 external artifacts,
0unknown POM declarations, and27 retained notice members. Regenerated JSON/notices
exactly match both APK assets. Own-code Apache2, Onest OFL and third-party boundaries
remain distinct. ACCESS_NOTIFICATION_POLICY is the optional DND capability declaration,
not a grant; see [data flows](ATTENTION_DATA_FLOWS.md). Music is explicitly unsupported
on API26 rather than opening without the selected descriptor contract.

The disabled F-Droid recipe pins this runtime. Actual source scan reports0errors and
one retained gzip-fixture warning, with nonempty processing evidence; metadata lint0.
Only the disposable checkout omits the documentation editorial ZIP via the existing
recipe rm rule; the original archive bytes/hash remain intact. See
[F-Droid verification](../evidence/backend-attention-integration-2026-10-16/fdroid-verification.json).
No independent server build, reproducibility comparison, signing key or store upload.

No new translated language texts are in these public APKs, source commits or evidence.
The separately authorized private eleven-language technical preparation has its own
input hashes, source-record checks, loader/asset results and delivery boundary.
Frontend renderer/channel/actions, real DND configuration/permission flow, local source
chooser/lifetime, observations presentation, physical/OEM/audio/delivery/TalkBack/RTL
and overlay P2/goal-edit remain unaccepted. Full latest-journal audit and conditional
advanced goal/quantitative/text-summary proposals remain distinct;229 tests do not
claim completion of the entire product journal.

Requested gpt-6.1-sol/high is recorded without independent runtime model/effort attestation.

Reference owner patch is a zero-context diff from46128a3 toC949; isolated-index `git apply --cached --check --unidiff-zero` passed. It is already integrated and must not be reapplied to current main.
