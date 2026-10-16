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
