# Backend follow-up verification, 16 October 2026

Current production/core/app/build source: `6fcc4c212a854f4da320e4911605a7beb5c6db52`.
`89cc405830003a5a82ecffac459f9a9b1c0c32cb` updates only the preparation helper's
readiness report; later documentation/evidence do not change APK inputs.
Room11/full JSON13/read1–13/selective1–3 remain independent unchanged formats.
Requested gpt-6.1-sol/high has no independent runtime model/effort attestation.

| Source | Executed scope | Outcome |
| --- | --- | --- |
| c949a8f202e143151e216a8932956a7a93b001bc | Full130core/99app; both unsigned APKs/lint/inventory | Successful5m31s; historical accepted regression |
| 745c7ed709bd8aa7e0cf9562eb7b6d76aefed06b | Affected16core/5app; both APKs/lint/inventory | Successful7m24s; actual host JNI flag, API26 audio fixture, cleanup |
| 6fcc4c212a854f4da320e4911605a7beb5c6db52 | Affected7core/2Room; both APKs/lint/inventory | Successful4m56s; reviewed readiness, translated ChoiceView search |
| 89cc405830003a5a82ecffac459f9a9b1c0c32cb | Preparation helper5Python cases | Passed; no APK production change |
| private ab0d1a5d92c96beb7e4748d302ade4278f1106ba | Real11packs:1core/1Android asset-Robolectric; debug build | Successful3m22s; not a new full regression or actual device run |

Evidence: [follow-up source-bound results](../evidence/backend-followup-2026-10-16/6fcc4c2/artifact-verification.json),
[API26 results](../evidence/backend-followup-2026-10-16/745c7ed/artifact-verification.json),
[private metadata only](../evidence/backend-followup-2026-10-16/private11-functional-metadata.json).
Original logs are retained outside public Git; any local SDK path normalization is
explicitly recorded with raw hashes. Prior raw C949 full evidence remains intact.

```text
/workspace/tracker-tools/run-gradle :core:test --tests '*DescriptorOpenFlagsTest*' --tests '*DescriptorCleanupTest*' :app:testDebugUnitTest --tests '*AttentionPlatformTest*' :app:assembleDebug :app:assembleRelease :app:lintRelease :app:releaseInventory -PtrackerUnsigned=true -PtrackerCleanupLibrary=/workspace/tracker-tools/libtracker-copy-audio-flags-host.so -Pkotlin.compiler.execution.strategy=in-process
/workspace/tracker-tools/run-gradle :core:test --tests '*LocaleContractTest*' --tests '*ChoiceLocalizationTest*' :app:testDebugUnitTest --tests '*RoomChoiceLocalizationTest*' :app:assembleDebug :app:assembleRelease :app:lintRelease :app:releaseInventory -PtrackerUnsigned=true -PtrackerCleanupLibrary=/workspace/tracker-tools/libtracker-copy-audio-flags-host.so -Pkotlin.compiler.execution.strategy=in-process
python3 -B -m unittest discover -s scripts -p 'test_catalog_content_pack.py' -v
```

The first line records the affected selection; raw gradle/XML evidence records
the concrete cases/counts. Shared ChoiceLocalization scenarios run Memory/Room.
HostFocusAudioOs uses host NIO NOFOLLOW and descriptor metadata because Robolectric's
Linux shadow cannot execute this Android contract. It is not Android Os/O_NONBLOCK
or actual audio playback acceptance. Fresh host-target Clang21 at745 has0diagnostics;
6fcc retains the exact same C blob. Android native builds cover four ABIs.

| Current unsigned APK | Bytes | SHA256 |
| --- | ---: | --- |
| Debug |13894998|47339f1fd51c299df9ce0c97cb725f44d6d0e4feb18ab0283690354411832b56|
| Release |9065150|cd784587c4c15a8c09245eacddfae39ced3aeeff0099e406bc380c6de38d1449|

Both: ZIP CRC,16KiB alignment, expected unsigned-signature refusal,min26/target36,
no INTERNET; release VCS equals6fcc. Eight native entries/four ABIs. Lint0errors,
68warnings,1hint; release dependency inventory remains65external artifacts.
Own-code Apache2, Onest OFL, third-party/content rights remain distinct. No keys/store
upload or real user data import/deletion. Disabled F-Droid recipe/source scan is
exactly C949 (0errors/1gzip warning), not this newer source; no independent server
build/reproducibility. Prior Android fixture is exactly1ecd9a8, not current acceptance.

## Search acceptance and Opus integration

| Behavior | Status |
| --- | --- |
| Locale TYPE labels and aliases | Implemented in existing ChoiceView.search for TYPE and linked current type of Card/Favorite/History; canonical references unchanged |
| Original/source fields | Retained alongside translated search fields |
| Missing/DRAFT/incomplete pack fallback | Source or compatible ready parent; Hans/Hant never cross; damaged replacement keeps prior loaded pack |
| Personal titles and type overrides | Literal names preserved in display/search |
| Localized ancestor path display and ID subtree scope | Implemented |
| Query matching ancestor words/context | NOT_IMPLEMENTED; current matcher indexes own/current-linked-type fields |
| View/cursor lifetime | Immutable locale overlay bound to Dataset token/barrier/type snapshot; foreign/stale overlays and cross-view cursors refuse |

Opus captures the Dataset and obtains `catalogContent.choiceLabels(locale, data)`,
then calls `view.withCatalogLabels(labels)`. Rebuild after confirmed locale/Dataset
changes. See [full content contract](CATALOG_CONTENT_PACKS.md). No Compose screen,
TechnicalActivity, RemoteViews renderer or UI resource production edit was made.
Reviewed+complete determines display readiness, independent of offline availability
and preserved rights metadata. This does not authorize public text publication.

## Remaining backend functions and independent acceptance

Distinct-days goals/effective goal versions, a dedicated reviewed selected-record
text-summary builder, scale min/max/nested measurement identities/weighted tempo,
and generic owner-specific historical UNKNOWN handoff are absent. Existing known
lifetimes hand off exact leases safely; missing historical origin/census cannot be
inferred from age, bytes or another new owner's completion. Retain protection.
These are concrete backend gaps/conditional journal extensions, not UI-only issues.
The coordinator separately audits14extensions; this delivery does not implement all14.
Do not reopen existing Report.groupSimilar or CalendarOccurrences.list/nearest/readSelected.

Frontend schedule/cleanup/selective flows, notification renderers and actual Android
DND/audio/permission/OEM/RTL/TalkBack/overlay P2/goal-edit remain separate. Native
publisher/static-runtime provenance and independent reproducibility remain release
conditions. See [journal audit](JOURNAL_BACKEND_REMAINDERS_2026_10_16.md).
