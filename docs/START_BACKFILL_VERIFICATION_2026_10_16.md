# Verified start backfill and B fixes, 16 October 2026

Full runtime/build/test source: **0def529a610232f557530f7f74a2facf3507136f**.
Separate reached-interruption test source: **e4435919948a361f2b023f750134d765b8d379ee**.
The latter changes tests/docs only; core/app production sources and build inputs
are identical to the full runtime. No new device execution is inferred.

The accepted “start at the previous record's end” gap is implemented through
[the explicit preview/apply contract](START_BACKFILL_CONTRACT.md). Room10 adds nullable
manual confirmation, full archives use12/read1–12, selective format3 is used for
annotated segments while unannotated1/2 hashes remain stable. Source-specific journal
remainders, including proposals versus selected functions, are recorded separately in
[JOURNAL_BACKEND_REMAINDERS_2026_10_16.md](JOURNAL_BACKEND_REMAINDERS_2026_10_16.md).

B commits are sequentially integrated as e46d25588391be2fede892c5d1c746e0f77171d7
(strict prepared-catalog UTF8) and60d15ee0b1d63b85f6b280c4e3b2dfcb3b68a0e1
(CSV retained-origin dedupe after selective COPY). Exact input patches and their
5core+1Room isolated test evidence are preserved in b-prerequisites. Those changes
also participate in the full execution below.

```text
/workspace/tracker-tools/run-gradle :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease :app:releaseInventory -PtrackerUnsigned=true -PtrackerCleanupLibrary=/workspace/tracker-tools/libtracker-copy-android-root.so -Pkotlin.compiler.execution.strategy=in-process
```

**BUILD SUCCESSFUL in7m39s**. Core109/app86, zero failures, errors or skips. Both
actual test tasks executed. Release lint:0errors/64warnings/1hint. Inventory:
65external artifacts,0unknown POM declarations,27notice members; generated license
index/notices and project Apache/Onest OFL assets byte-match both APKs. This verifies
resolved declarations and packaged bytes, not the remaining publisher native
source/link/static-runtime rights attestation.

| Unsigned APK | Bytes | SHA256 |
| --- | ---: | --- |
| Debug | 14171882 | ba6c7f49bd620a542a3ddaa903453fc4388a1b59e78da20bb754bf271cf14f30 |
| Release | 8999338 | 7e3478d96cc5fbd53c9dfa673353e8a37e35cbc242fc6c27aa86af16b8997d39 |

Both pass16KiB zip alignment and parse with aapt; signature verification reports the
expected unsigned state. Release embedded VCS SHA is0def529. Merged manifest:
min26/target36, noINTERNET. Eight native entries/four ABIs are packaged. Editorial
EN/ES payloads remain excluded. No signing key or store publication was created.

```text
/workspace/tracker-tools/run-gradle :app:testDebugUnitTest --tests '*RoomScenariosTest.v9Migration*' --tests '*RoomScenariosTest.normalizedMigrationRollsBackAfterReplacementFailure' -PtrackerCleanupLibrary=/workspace/tracker-tools/libtracker-copy-android-root.so -Pkotlin.compiler.execution.strategy=in-process
```

At e443591: **BUILD SUCCESSFUL in56s**,3Room tests, zero failures/errors/skips. The
9→10 and historical4→5 fault tests now assert that the injected failure was reached;
the historical fixture registers its complete path to schema10. They verify rollback
and explicit successful retry. The historical test's previous exception-only check
could pass on a missing migration path, so the new witness is reported independently
from the full0def529 run.

A separate Java serializer probe against the previously compiled8aaa890 core captured
the legacy segment-containing selective hash
`d6d96de142abc2d24549c36758d5e4a1757e6b9c6bdf46e2212a4ee03c9634bd`.
The new core preserves it; probe source, old runtime JAR hash and exact output are
retained. Full XML/logs and artifact metadata live under
evidence/backend-start-backfill-2026-10-16.

The disabled F-Droid recipe now pins the tested0def529 runtime. Scanner2.4.5
actually processed that source with0errors/1retained gzip-fixture warning; metadata
lint passed. An initial empty JSON result was rejected after identifying an unwritable
default cache; the successful disposable-checkout run used a workspace cache. No
server build or reproducibility is claimed, and the real editorial ZIP remains intact.

C artifact f6fcadf was supplied by the coordinator but its ZIP could not be materialized
by the normal Library helper in this executor (`download failed`). Its15prepared
scenarios are not counted here. Source compilation/runtime binding is pending exact
source transfer. Ordinary long-timer reminder, extended Focus environment/music and
game-layer contracts are not declared complete by these A+B checks.

Opus UI integration, concrete schedule surfaces, resource/LocaleManager migration,
reviewed installed translations, physical OEM/reboot/TalkBack/RTL/overlay P2/goal-edit,
legacy owner/census proof and independent F-Droid/reproducibility/native rights
conditions remain open. Requested gpt-6.1-sol/high has no independent runtime
model/effort attestation.
