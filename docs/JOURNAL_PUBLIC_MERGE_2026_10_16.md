# Public combined journal R2 and eleven catalog packs — 2026-10-16

Fresh `origin/main` was `70a7155b3ef548be4638cf71849d3b05dbbef7a6`.
Accepted public backend `b03f5400c03b1455546bce08248c30895b141220` and accepted
catalog branch `a27f991661a7c6da16e5b1b26effd9701e92200f` were merged normally,
without conflicts, into source **`a09d21e3aec6248371bb425ef92c5369c0c1bbd2`**.
This is the exact source revision for the new tests and APKs below. Later delivery
commits update documentation, receipts and the disabled recipe only; they do not
change the tested runtime, tests, build configuration or bundled catalog files.

Current formats: **Room12; full JSON14/read1–14; selective4/read1–4**.
[Coverage 1–14](JOURNAL_14_COVERAGE_2026_10_16.md),
[combined API](JOURNAL_COMBINED_CONTRACT_2026_10_16.md),
[four R2 fixes](JOURNAL_REVIEW_R2_2026_10_16.md) and
[Opus integration](JOURNAL_OPUS_INTEGRATION_2026_10_16.md) remain the detailed contracts.

Dionysus authorized the public merge at 07:09 UTC and explicitly included all eleven
checked catalog translations at 07:11:09 UTC. The 23 index/per-locale files retain
exact accepted bytes. The generic 24th manifest now lists the bundled eleven packs.
Original rights/license/QA provenance, Apache-2.0 boundaries, Onest OFL and NOTICE
remain intact. [Catalog delivery provenance](../PRIVATE_CATALOG11.md) records the
superseded private-only preparation boundary. No input ZIP, unrelated private files,
signing keys, credentials or real user data were added.

The production core, Android code and Room schema equal accepted R2. Compared with
fresh main, the only production Android change is `TrackerDatabase.kt`; Compose,
ViewModel, resources, TechnicalActivity and RemoteViews were preserved. Translation
assets provide catalog display/search, separately from Android UI resource migration.

## Fresh post-merge regression

```
./gradlew :core:test :app:testDebugUnitTest --rerun-tasks --max-workers=1 \
  -PtrackerUnsigned=true -PtrackerCleanupLibrary=<isolated-host-fixture.so> \
  -Pkotlin.compiler.execution.strategy=in-process
```

JDK21/Gradle8.13/API36, BUILD SUCCESSFUL in **6m54s**, all **37 tasks executed**:
**168 core +136 app**, zero failures, errors or skips. This includes all shared
Memory/Room R2 scenarios and both real eleven-pack catalog fixtures. The host JNI
fixture was built from this repository; these are JVM/Robolectric checks, not an
Android device run. Raw exact JUnit XML and suite/hash/source manifests are retained
in [public evidence](../evidence/journal-public-merge-2026-10-16/tests.json).
The unmodified full Gradle test log is retained with the artifact delivery; SHA256
`0a33239b7d0f9bb6f72c911c861674d87ca1de7f357ed8afd93d883b0a7ef370`.

## APK and release checks

```
./gradlew :app:assembleDebug :app:assembleRelease :app:lintRelease :app:releaseInventory \
  --max-workers=1 -PtrackerUnsigned=true \
  -PtrackerCleanupLibrary=<isolated-host-fixture.so> \
  -Pkotlin.compiler.execution.strategy=in-process
```

BUILD SUCCESSFUL in **2m29s**, 113 tasks:30 executed,83 up-to-date. Lint:
**0 errors/0 fatal/68 warnings**. Runtime inventory:66 artifacts, including65 external
artifacts whose hashes equal the retained license inventory and the own Apache-2.0
core artifact. Both APKs are unsigned and pass zipalign-P16; min26/target36. The
release embeds source revision `a09d21e`; debug has no embedded VCS metadata.
All **24 locale assets in both APKs** equal the corresponding source files. The
merged release manifest equals the accepted premerge manifest and has no INTERNET
permission. [Build receipts](../evidence/journal-public-merge-2026-10-16/build.json)
include exact hashes, native files, asset hashes and manifest checks.

| APK | Bytes | SHA256 |
| --- | ---: | --- |
| debug unsigned | 16074956 | `4acd9acafbe7316143d25aa68997313bffd22a57828bdf096e86bd82ef68f0d4` |
| release unsigned | 11170429 | `2e779a7630b50b967f2374e73d86514617e393d1abdcadb4d6c6480122818315` |

F-Droid scanner2.4.5 on a disposable exact-source checkout exits0 with0 errors and
one warning for the retained gzip test fixture. Metadata lint exits0 with repository
category configuration present. The initial empty-category-config attempt exited1;
this was not an app source failure. The minimal configured `Time Tracker` key matches
[official fdroiddata categories](https://gitlab.com/fdroid/fdroiddata/-/raw/master/config/categories.yml).
The scan fixture uses a local clone; the delivered disabled recipe retains HTTPS and
pins source `a09d21e`. This is not an independent F-Droid server build, admission or
reproducibility comparison. [Scanner receipt](../evidence/journal-public-merge-2026-10-16/fdroid-verification.json).

## Retained limits and history

Earlier cea1bc9/R2 and C949 reports remain bound to their own SHA. Historical AOSP
API35 QA from 2026-10-13 belongs to `a589f56779163aef07952f1696a97593d0e09b6b`;
it is not acceptance of this merged source on a device. No new AVD, physical/OEM,
TalkBack/RTL, permission/delivery or power-loss checks are claimed here.

Frontend integration of the fourteen APIs, language authority/resources, schedule
surfaces, review/share, quantity displays, overlay P2 and goal editing remain open.
Legacy cleanup-owner witness, native publisher provenance and independent
reproducibility remain separate. No store publication, release signing or runtime
model/effort attestation is implied. The three subsequent parent code-review passes
have not been counted as performed by this integration.
