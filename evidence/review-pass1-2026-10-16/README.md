# First parent review: source-bound interim evidence, 2026-10-16

Parent baseline is main59d0ba60; this directory does not claim completion of the
entire P1-001…011 packet or a second review. Exact source, raw JUnit XML, counts
and hashes are in each verification.json. Full unmodified Gradle logs remain
with the task artifact delivery; they are not copied into public Git with local SDK paths.

- P1-00173921a6 reproduces five failures in Memory and Room. Initial b7ee1df
  passed178core/146app but lacked the subsequently identified rollback-clock
  coverage; it is a historical passing checkpoint, not final acceptance.
  38cfc9c reproduces the remaining checked-index issue2/2 failures in both stores.
- P1-0023777584 reproduces bounded metric/unit failures2/3 in both stores;
  49dc82f passes3/3 in both, including COPY, replay, Undo, retention, re-export
  and legacy format2. Dependency-only personalization was already passing.
- P1-003c1b0659 reproduces four stale-post race tests; its absent-store case
  stopped at a Robolectric directory-fsync limitation. Actual empty-store
  bootstrap is separately reproduced by242269d after the host-fsync/boot-count
  fixture was configured. c5a34b4 passes6app tests, including40 race variants
  across Memory/Room, capture/preparation, result/refresh and five mutations.
- P1-009881ec54 returns STORAGE after readback confirms the new interval,
  then fails the old unconditional unchanged message.446767f passes1app test.
- P1-01036a4814 Python fixture fails1/3 because confirmation-only changes
  collide;95a271d passes3/3 (SQLite fixtures only, no adb/device invoked).

All failures and positive results refer to their actual source. No physical-device,
OEM, TalkBack/RTL, fresh linguistic expertise or independent model/effort claim.

Additional first-pass checkpoints (same scope; no second-review claim):

- P1-0018b4be66 fixes the remaining empty-index/clock-rollback archive path;
  11core/11Room pass. The original b7 checkpoint remains historical.
- UI3ce21de reproduces7/8 failures;4af4ab6 has one test-only leading-zero
  expectation failure. c6140ba passes1core/11app including10 ViewModel cases.
- P1-006f369b6e passes16app:5 ownership scenarios,10 VM regressions and1 export
  lifecycle. Actual durable lease references/source bytes are checked on fixtures.
- P1-011c341e23 reproduces4 semantic receipt collisions in both stores. Its fifth
  failure is a test-fixture legacy hash encoded without the production pretty-print
  setting, not a product failure; corrected separately in3921354. The same invocation
  passes the new whole-area guarded-start regression (1app).

P1-0113921354 passes6core/7app: the five new CSV cases, existing COPY-origin
replay in each store, and whole-area guarded start. Corrected legacy hash is also
checked against the frozen original algorithm; original receipts remain unchanged.

Final aggregate runtime source0ed5b253e65cf4fec375f1a93c7b48f0e47cb0e4 passes
189core +180app,0failures/errors/skips (2m29s). Python fingerprint3/3 passes.
Raw original aggregate XML are stored alongside verification.json. Checkpoint
integrity checks5050 source-file references at18 exact commits plus XML hashes/
counts against Git objects; this is artifact integrity, not another code review.

Final unsigned build0ed5b25 succeeds3m24s: lint0errors/0fatal/68warnings,
66 runtime artifacts (65 external exact documented hashes), min26/target36,
zipalign-P16, both APKs24 locale assets equal source and baseline59d.
Exact APK bytes/SHA256 and command semantics are in final-result.json.
Full original Gradle/check logs and APKs are in the task archive; public evidence
keeps metadata/XML without local SDK paths.
