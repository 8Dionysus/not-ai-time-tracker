# Functional backend coverage, 15 October 2026

Sources: complete main Page snapshot seq207 (SHA256 ff33e062b2292e872d82a85eb21469302b09dca26d75026cc7b7911fc82acd82), including the 21-family matrix block71f20de7-2cef-47c3-9445-7f47a4f6b61d, and complete localization Page (SHA256889e96f944e6109f3a0fc6ae64615e11b4db7094ae4ab10f81e0b01d9e441630). Source snapshots stay outside the public repository/package. This matrix concerns backend scope; API availability is not user-visible product acceptance. UI remains with Opus by direct user instruction15:10UTC.

| Family | Backend/result boundary | Exact remaining acceptance/owner |
|---|---|---|
| One timer/start without card | Existing Tracker/Room transaction, unchanged | Process/device/OEM testing |
| Pause/resume/stop/recovery | Existing bounded anchors and uncertain tail; recovery journal3 durable completion before lease release | Physical reboot/time/process scenarios; no invented elapsed time |
| Home/history/day/quick choice | Existing captured views/reports; new single-moment schedule state | UI date-change presentation and accessibility/device |
| Recents/favorites | Existing ChoiceView/selection stable IDs; no tap telemetry | Same-name/recreation/device UX |
| Calendar/statistics | Existing day/range/filter/comparison APIs | Opus date selection/drill-down/filter wiring |
| Own categories/subcategories/cards | Existing parentId/Card APIs; custom reading→philosophy→Заратустра in shared/file/Room fixtures | Opus authoring forms; no parallel task model |
| Details/facets/measurements | Existing exact definitions/values/scopes, transfer closure and filters | Opus contextual fields and primary UI |
| Manual corrections/conflicts/Undo | Existing preview/apply/revision/Undo; imported dependencies retained after later mutation | Device forms; mass repair is not approved scope |
| Seven frontend integrations/delete | Existing accepted rendering left intact; linked deletion now typed SCHEDULE_LINKED_SESSION with IDs/date, preserving start receipt/link | Opus typed refusal display; tombstone/unlink needs explicit domain decision, OEM/placement/accessibility |
| Focus/signal | Atomic configureFocusSignal delta; typed receipt-first delivery, retained unknown attempt; backend receiver migrated | Opus deprecated Boolean callers and delta toggle; permission/device acceptance |
| Schedule | Existing Series/Override/StartLink/idempotent commands; saved ScheduleWorkspace exact keys/hashes/results | Opus forms and explicit Start wiring |
| Schedule lifecycle/notification | Committed-view and application/platform refresh; independent quiet projection journal/alarm and connected-consumer port | Opus renderer/factory/channel binding; OEM punctuality and permission callbacks |
| Day/home zone/quiet settings | Persisted settings; one captured day projection and next civil boundary, DST policy; no reportZone mutation | Opus controls/three surfaces |
| Goals | Existing CRUD/period/filter/calculation, stable export/transfer | Opus create/edit/disable/delete forms; no streak promise |
| JSON/CSV | Room9/JSON11 legacy migration; full replacement+rollback; unresolved CSV retains literal classification/note without unknown time totals; serializable mapping/duration draft fields | Opus sourceLocale and error-draft caller migration; CSV version marker remains proposal |
| Selective transfer | Existing engine plus saved file stage→inspect→review→apply/resume/Undo/export controller, exact operation ID/base/hash and source leases | Opus SAF picker and dependencies/losses/conflicts/partial-result UI |
| Selected text | Existing atomic switchDraft preserves text and classification | Opus replaces old switch+label caller; saved separate patch unaccepted |
| Localization | Error contract and single preference API; catalog schema1 integrity/coverage loader, batch original/display/status/provenance/override; exact parent-qualified values; nullable source-rights pending metadata and lazy requested-locale payloads | Editorial full content delivery/review and source-rights clearance; Opus UI resources/LocaleManager/RTL/TalkBack. No uninstalled/draft locale support claim |
| Shortcut toggle | Default-true setting Room8→9/JSON11; mutable shortcut withdrawal and retained action gate | Opus toggle caller and static immutable app-opening shortcut resource |
| Calendar sync/research extensions | Not silently accepted as schedule; no extra engine/sensors/AI/network scope | Separate user selection required |
| Local/offline/resource cost | API36 manifest without INTERNET; no new poll loop/FGS/permission prompt; historical indexed receipt profile kept at exact69d926f | Full graph remains RAM-linear; phone RAM/battery and OEM delivery unmeasured |

| A–F | Implemented and reviewable | Open boundary |
|---|---|---|
| A localization/catalog | Stable typed errors; separate app preference; exact catalog ID/version/hash sidecars, full coverage checks and batch fallback/override; source unchanged | Editorial packs awaited/validated separately; UI/resource/device/native-language QA |
| B schedule | Commands/receipts/series/exceptions/links, saved controller, one projection, no automatic fact/Start, separate silent invalidation and interrupting alert | Opus consumers/factory/UI and device delivery |
| C persistence/archive | Room5→…→9, JSON1–11 reader/current11; complete domain snapshots; shared Memory/Room migration/rollback/failure invariants; recovery completion reconciliation | Real device interruption/reboot outside synthetic harness scope |
| D private copies | Trusted Context root, descriptor/JNI no-follow graph, sealed archives, inventory/confirmation/warnings/quarantine/PARTIAL/explicit resume, exact writers/chooser/lifetimes/source leases, legacy cutover preserving unknown tokens | Opus presentation/explicit consumer lifetimes beyond built-in flows; malicious/noncooperating same-UID writer exclusion unproved; physical devices/other ABI runtime |
| E selective | One existing engine with closure/origin/version/hash, groups/conflicts/partial/resume/Undo and real saved file adapter | Opus SAF/user review flow; no claim CSV equals selective archive |
| F release engineering | Apache-2.0 own code, Onest OFL and notices retained; API36, unsigned release build/manifest/dependency/ELF checks and isolated recipe lint | Exact AndroidX native build/static-runtime binding, catalog rights, independent F-Droid server build/reproducibility, stores/legal/owner decisions, signing not done |

Current test/artifact counts and exact source SHA are in INDEXED_BACKEND_VERIFICATION.md and delivery artifact-verification.json. Historical profiles are not rerun for reporting. Broader UI P2 overlay, goal-edit, OEM/TalkBack/RTL checks are not closed by JVM tests. Requested gpt-6.1-sol/high has no independent runtime attestation.

Sequential A+B refinements: complete catalog packs retain every source alias structurally; selective excluded overrides must exist in selected series; malformed/future projection wakes disarm platform wake sources without rewriting authority. These are covered by exact-source combined regression evidence. Legacy unknown-owner cleanup eligibility remains unresolved where historical lineage/census is absent; metadata-only evidence confirms preserved tokens and bytes, not completed migration. See LEGACY_OWNER_MIGRATION_REVIEW.md.

EN and ES complete source-bound candidates are now independently reproduced and JVM-tested under catalog/editorial. EN retains coordinator-reported REVIEWED provenance; ES retains DRAFT. Both remain rights-pending/null-license/nativeSpeakerQA=false, excluded from Android assets/index. Other languages and rights clearance remain external; FR input is incomplete after reported publication rejection, with no reroute. Current exact core101/app80 attribution and APK checks are in INDEXED_BACKEND_VERIFICATION.md.
