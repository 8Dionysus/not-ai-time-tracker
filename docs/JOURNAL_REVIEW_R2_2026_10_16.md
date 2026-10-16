# Four-path integration review correction R2

Prior runtime8bbd19a / head3ff1086 is a preserved, unaccepted checkpoint. The
coordinator verified its337 payload hashes,947 source files and execution records,
then found four reachable integration defects. Its Library files remain unchanged:
ZIP libfile_97da5dcc56688191ae6a00ce1c5d5fc2 and text libfile_12c25cec8dd48191b2c01383ee81d5eb.
Those checks do not establish correctness of the four paths corrected here.

Runtime correction source: **cea1bc973ef78e8b76ff3d9df34e43f533ab59d1**. All changes
are in the existing core and shared tests. No frontend, environment or extra model
executor was added. Room12/full14/read1–14/selective1–4 are unchanged: the new window
identity is a read projection, not persisted data.

| Review path | Correction | Shared Memory/Room regression in ReviewR2Scenarios.kt |
| --- | --- | --- |
| Retained change causal order | Noncompacted origin.localGroup and previousOrigin receive full content validation. Partial inserted changes use the same basis rule, resolving actual observations from their source group and full retained snapshots with the same origin ID AND source hash. Duplicate-ID conflicting bases, missing/unrelated bases and an early change fail closed. Live data is never a fallback; hash-only capsules supply no invented values. | retainedChangeCausality: asOf1000, bases900/950 and change1; whole reader, streaming reader/writer and atomic Store rejection. Valid partial source basis, rewritten COPY IDs via current/previous origin, conflicting/unrelated basis refusal and positive hash-only compaction. |
| Exact origin skipped after stale candidate | Export gathers all membership candidates, chooses the first exact unchanged origin, then uses the existing deterministic fallback only if none is exact. | exactOriginPrecedesStaleCandidate: O1/A→localB→O2/B, O2 provenance/header/replay before and after compaction, reopen and full restore; no-exact fallback stays deterministic. |
| Goal/query dependency split | The existing selected-root graph also joins complete dependency identities from the actual exported contents. Shared type/ancestor/axis/unit identities need no separately selected root. Names never join records; unselected session roots are never enrolled. Incoming format4 split dependencies are rejected before apply; legacy1–3 group admission remains supported. | sharedQueryDependenciesCloseWithoutExplicitRoot: type conflict/COPY and facet/grouping-axis conflict/COPY, one shared local ID, unrelated group independent, hidden session absent, compaction/replay/re-export and malformed split4 rejection plus legacy1 admission. |
| Incompatible quantity windows silently union | QuantityWindow.selection distinguishes Stored(sessionIds), without audit cutoff, from Reported(asOf,filter,dayPolicy). combine requires the same token AND selection identity. Window bounds stay separate so compatible adjacent periods still union by IDs. | quantityWindowsRequireCompatibleObservation: E0=10/Monday, E1=12/Tuesday, clock rollback11:00→10:30→11:00 at unchanged token. Different cutoffs reject; consistent cutoff totals12, never22. Different filter, stored/report mode and null/empty session selection reject. |

Callers must not overwrite selection to bypass compatibility. For different report
cutoffs, recompute the intended union range in one quantityReport request. Existing
SubjectRead.window has explicitly different latest-stored semantics and cannot be
combined as though it had a report audit cutoff. UI wiring remains Opus-owned.

## Fresh evidence

`:core:test :app:testDebugUnitTest --max-workers=1 -PtrackerUnsigned=true
-PtrackerCleanupLibrary=<isolated-host-fixture> -Pkotlin.compiler.execution.strategy=in-process`
executes **167 core +135 app**, zero failures/errors/skips, BUILD SUCCESSFUL4m45s
on cea1bc9. This includes all earlier shared cases and the four new review cases.
A first new fixture had constructor syntax errors; it never reached test execution
and is preserved separately. The earlier684938c4/4 and91e92ef167/135 results are
also separate, rather than attributed to the final partial-COPY context fix.

Updated public build, private11 checks, APK hashes and raw exact XML/logs are bound
to their actual commits in the R2 delivery receipt. Earlier source8bbd19a163/131 and
private5/5 remain historical records. No device/OEM/TalkBack/RTL, frontend completion,
publication, signing or independent runtime model/effort attestation is implied.

## Corrective artifact and private validation

Public source cea1bc9: debug/release unsigned APKs, lint and releaseInventory build
successfully in2m48s. Lint0errors/0fatal/68warnings;66 runtime artifacts,65 external
hashes match retained license declarations. zipalign-P16 and compile/target36,
min26 checks pass. Manifest remains byte-identical to accepted C949. Actual HTTPS
disabled recipe is pinned to cea1bc9; scanner/lint exit0 with one retained gzip warning.
This is not an independent server build or a reproducibility comparison.

Private source a27f991661a7c6da16e5b1b26effd9701e92200f has274 common source/test/build/schema
files equal to cea1bc9. Private real11 + four earlier combined + four review cases
execute9core/9app, no failures/errors/skips; unsigned release build succeeds5m18s.
All24 locale sidecars still match previous accepted sidecars and the private APK.
Both DEX entries match public release; native ELF sections differ only in build-id.
AGP worktree VCS metadata remains explicitly NO_VALID_GIT_FOUND; it is not forged.
All prior source8bbd19a artifacts and Library files remain unchanged.

| Artifact | Bytes | SHA256 |
| --- | --- | --- |
| Public debug | 15452501 | `4cab1ae978b5e64ffb2d6557d415a9ec80475ac5081d70628af80f485ff5710c` |
| Public release | 9196222 | `238a2a8ef7024e9585de551f6d2ee9af7bec80f90f1a662096b7e3dd50ec6e9c` |
| Private11 release | 11170333 | `2521dfdbaae0d1112e802567fabf2515becd1a881803e77ae42481054c6b89ed` |

Raw exact XML/logs and the integration diff are in the new private R2 ZIP and text
appendix. The earlier fixture compilation error is preserved separately. Frontend/
OEM/device/TalkBack/RTL, legacy cleanup-owner witness, native publisher provenance,
store/signing and independent reproducibility conditions remain unchanged. No real
user data was imported/deleted and no external publication was performed. This is
the corrected package for the coordinator's acceptance, not a claim of that acceptance.
