# Recovery, gestures, Neon and language — review candidate

Base: `6681056604dda748b52013b373e84990c992d718`. Candidate app: **0.16.0 / code 18**; Room: **13** (from 12); full backup: **JSON16**, with readers for previous versions including JSON15. This is a review candidate, without signing, release or merge approval.

## Records and recovery

Deletion now archives completed records and their related facts in `Dataset.deletedRecords` in the same StateStore transaction that removes them from live history. It retains session/segment/measurement identities, classifications, notes, source provenance, timestamps, subject links, sets and correction rows, quantities and paired distance/time inputs. There is no permanent-delete action, expiry or automatic cleanup. Ephemeral picker requests are intentionally not resurrected. Active, Focus, schedule-linked, unknown and stale targets retain their existing refusal guards.

`restoreDeleted(expected, sessionIds)` restores only those records through normal Tracker validation. It never restores an old whole-database snapshot over later independent edits. Intersections with newer live intervals or identity conflicts refuse without removing the saved record. Referenced dictionaries must remain valid; edits cannot silently orphan archived facts. Archives can overlap live intervals while retained; their independent validation does not reserve deleted time against new history entries.

Snackbar Undo carries the committed deletion batch and dataset replacement barrier. It restores that specific batch against a freshly captured current token, preserving unrelated later edits. A backup replacement, including replacement of the same dataset, invalidates that old UI action. Generic core snapshot Undo and its strict expected-token checks are unchanged. `requestDelete` acknowledges `Applied` only after the VM-owned commit succeeds. Failure returns `Failed`; caller disposal does not turn an already claimed write into a false result.

Changing the UI language keeps an already displayed Undo receipt alive. Its short-lived message retains the original language; subsequent notices use the new resource context. The collector is not restarted just because the locale changes.

“Deleted” is directly accessible near the bottom of Settings. Each retained record can be restored. The page renders records in bounded batches and has no purge control. Its “Show more” action uses a dedicated resource for all twelve supported locales. A Compose → VM → Room regression covers 51 archived records, a locale change while the pagination button is visible, and identity-preserving restoration beyond the first page. Old records physically deleted by earlier releases cannot be invented by this migration; recovery of those needs a pre-existing backup.

Room 12→13 changes the version guard, not the physical table layout or existing history rows. The optional archive lives in the existing transactional metadata payload, with the existing chunked metadata reader. JSON16 carries this archive through full backup/restore and verified rollback. Old JSON15 remains readable. JSON15 containing a smuggled archive field is rejected rather than treated as a compatible old file. Older app versions do not understand JSON16/Room13; downgrading is not an acceptance path.

## Gestures and Neon

The saved Material dismiss state and delayed reset are removed. A scrolling gesture is not consumed before a predominantly horizontal leftward intention passes touch slop. Vertical/diagonal intention exits to the parent scroller. Deletion requires a deliberate drag of at least 120 dp and 60% of the row width, without a velocity shortcut. The row menu remains an accessible alternative. Restored rows with the same day/session LazyColumn key have no old dismissed state to replay.

Neon keeps the established geometry and neutral surfaces. Bracket/icon halos are slightly stronger. The hero halo follows the chosen accent, with a slow, narrow intensity variation; the secondary wash no longer masks it with a fixed blue. The existing pulse preference and reduced-motion boundary stop the new halo motion. Other theme palettes are unchanged.

## Language boundary

The picker is directly near the bottom of Settings. System is the default and can be explicitly selected again. Twelve existing prepared locales are available: ru, en, es, fr, pt-BR, de, it, ar, zh-Hans, ja, ko and hi. This uses the existing `AppLocales` / `AppLocalePreferences` model and Android resource resolution. It does not rewrite user names, classifications, notes, report timezone or any Dataset field, and does not call `Locale.setDefault`.

The pinned reviewed editorial ZIP is verified against its SHA256 and every member digest. 349 existing static variants are materialized as explicit Android resource IDs; no runtime substring translation or reverse lookup of personal text exists. Seven ambiguous equal-Russian phrases are not mapped automatically. The source-to-resource map is in `localization/android-static-resource-map.json` and the generator is reproducible. Rights-pending catalog packs are not added. Nineteen new critical resource keys cover language choice, deletion/confirmation/counts, Undo, Deleted, restoration, pagination and their errors for all twelve locales; these new small source translations have no native-speaker acceptance claim.

The existing seven shortcut/widget/technical resource IDs now alias their reviewed editorial translations in each supported locale. Five existing launcher/favorite resource IDs receive localized labels; the actual controller catalog, IDs and image semantics remain unchanged. The two product brand names are intentionally not translatable. These platform labels use Android resources and do not translate personal favorite names.

**Coverage remains partial.** Existing appearance option names/help, plan and Focus sections, many backup/surface status lines, dynamic composed captions, date/duration units, and errors outside the changed flows still include Russian. The locale sheet says so. Visible navigation, connected static controls and every new critical flow really switch; the partial label is not a substitute for that behavior. Android's separate system app-language settings UI is not advertised as a new integration. Unsupported system languages resolve to the existing Russian fallback. RTL uses the chosen Android resource configuration. Existing personal/catalog labels remain their original stored values.

## Evidence and acceptance limits

Raw host logs and XML are included in the delivery, with failed fixture rounds retained as history. Full existing core and Android host suites plus Debug/Release lint are required by the workflow; exact-head results and any failures are recorded in the companion. No device/OEM, real installation/update on a personal phone, measured latency, native-speaker QA or signed release is claimed. Source/runtime decisions about the reported old swipe/Undo defect began as a source hypothesis, not an asserted reproduction on the user's phone. The new regression verifies the corrected behavior end to end on a synthetic Compose → VM → Room fixture, including the identical LazyColumn key and immediate restoration.

The full-suite follow-up makes Russian scenario fixtures explicitly Russian, including method-level screen-size overrides; their data, recreation, stale-save and timing assertions remain. The multi-segment delete test uses the new confirmation title and additionally asserts no mutation before confirmation and exact archived session/segments afterward. Settings observes the language StateFlow through Compose. The existing debug-only immutability probe explicitly requires API31 instead of calling that method on API26–30. No lint errors are hidden with a baseline or global suppression.

Animated frame capture is **NOT VERIFIED** in this host: Compose `captureToImage`/PixelCopy `forceRedraw` timed out, and the manual Window renderer did not produce reliable changed frames under a controlled animation clock. Those failed attempts are retained, not reported as product PASS. Static native host frames validate all four accents with reduced motion enabled; the pulse/reduced-motion branch is additionally source-reviewed. Actual animated pulse and dynamic OS animation-scale changes still need device/emulator acceptance. No production animation settings were weakened to make this host assertion pass.

All generated screenshots contain synthetic repository fixtures. The requirement text was sufficient for this stage; the new user screenshot was not represented as a local viewed file. The previous nine image references are not republished here.

### Controlled baseline comparison

An isolated checkout of production `6681056` ran the same Compose/VM/Room paths with only a nonbehavioral row test tag and test instrumentation. The diagonal drag (`0.9w→0.2w`, vertical movement `−0.35w`, 400 ms) deleted a record: expected RED, with commit trace `(33, false)` for that target's presence. In the new implementation the identical drag preserves data, and an ordinary vertical gesture additionally proves that history scroll position changes.

Undo after a later independent card edit was RED on `6681056`: target presence stayed false at revisions 33 and 34. The new end-to-end test restores the same record while preserving that later card edit. Simple immediate Undo without a later mutation PASSED on baseline (trace false→true); saved-dismiss re-deletion was not reproduced in this host. Therefore this delivery does not claim that the original phone's precise failure mechanism has been identified. Baseline sources, measurement-only patches, expected failed XML and logs are retained in the delivery.

## Synthetic native host images

- Deleted: [dark](ui/screens/recovery-deleted-dark.png), [light](ui/screens/recovery-deleted-light.png).
- Restored history: [dark](ui/screens/recovery-restored-history-dark.png), [light](ui/screens/recovery-restored-history-light.png).
- Language controls and partial coverage: [English](ui/screens/recovery-language-en.png), [Japanese](ui/screens/recovery-language-ja.png).
- Neon timer under reduced motion: [amber](ui/screens/recovery-neon-running-amber.png), [teal](ui/screens/recovery-neon-running-teal.png), [lime](ui/screens/recovery-neon-running-lime.png), [graphite](ui/screens/recovery-neon-running-graphite.png).

These were generated by the existing native Robolectric renderer and personally viewed as pixels. They are not phone captures. The detailed remaining source-literal inventory is [here](localization/remaining-ui-copy-2026-10-24.json); its count is not a runtime coverage percentage.
