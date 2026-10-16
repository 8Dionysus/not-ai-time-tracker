# Journal extension A: search, personal choices and history review

Assigned requirements4/5/8/9 from the approved14-item report. Integration owner A
will merge B/C migration fragments at one final Room/archive/selective floor.
The merged floor is Room12/full JSON14/read1–14/selective1–4. The original
Room11/full13/selective1–3 remain supported. This is a new work branch after delivered
public70a7155/runtime6fcc; it does not replace the previous checkpoint or its evidence.
No Compose/ViewModel/resources/TechnicalActivity/RemoteViews production path is edited.

## 4. Existing search with ancestor context

Use the same `choiceLabels(locale,capturedDataset)` + `withCatalogLabels` interfaces.
Current ancestors contribute original and reviewed localized names/aliases across
TYPE/Card/Favorite/History, normalized once per immutable view. The historical path
snapshot remains untouched. `MatchEvidence.sourceTypeId` identifies a matched
ancestor; `field` is ANCESTOR_NAME/ANCESTOR_ALIAS and `localeTag` identifies translated
provenance. Multiword queries can combine own/current fields and ancestor context.
No name-based identity merge. Literal personal names have priority, then exact,
prefix and contains within the applicable field class; ancestor context is lower.
Explicit personal ordering below is authoritative for pinned matching choices.

## 5. Profiles owned by stable Card/Favorite IDs

`Card.personal` and `Favorite.personal`: `ChoicePersonalization(aliases,order,notePolicy)`.
Default profiles are omitted from serialization so historical selective hashes stay
unchanged. No second catalog/profile engine. Ordinary saveCard/saveFavorite preserve
an existing profile; `personalizeChoice(expectedToken,kind,id,value)` is the explicit
profile boundary, including clearing it. Aliases are literal user strings, not preset
translations. Existing search includes them and current linked-card aliases.

`orderChoices(expectedToken,kind,ids)` pins the supplied distinct-ID prefix; omitted
IDs become unpinned. No positional identity. Search filters first, then keeps pinned
relative order across query strengths. Card and Favorite orders are independent;
Favorites precede Cards when both have pins. Stable IDs break imported order ties.
`quick()` respects Favorite order before recorded-history suggestions. Catalog-only
updates and renaming do not erase the profile. copyCard copies aliases/policy and
clears order for its new ID. Profile operations use Tracker transactions and Undo.

Default Repeat retains source note; Favorite/Card Start retains the old empty-note
default. `CLEAR` explicitly suppresses note copying. `COPY_SOURCE` uses the source
session note for History/Repeat; Card/Favorite explicitly opting in use the latest
positive completed certain recorded segment with the exact selection identity.
A latest null note remains null; names alone never match notes. Measurements are
never copied into a start. startChoice and legacy repeat/startFavorite share this
policy; legacy repeat also accepts an explicit per-action notePolicy, including
for unclassified history without a Card profile. Profiles travel with selected Card/Favorite dependencies through the existing
selective engine, origin hashes, COPY ID rewriting and full replacement. No new
importer. Legacy archive/selective versions refuse nondefault new profiles.

## 8. Recording gaps

`historyGaps(start,end)` captures one Dataset token and Moment. End is clipped to
asOf; future time is excluded. The projection uses absolute half-open intervals;
callers obtain a civil day's bounds from the shared reporting policy. It is not
an inactivity detector and does not assign quantities to gaps.

Trusted recorded intervals are occupied. Remaining spans distinguish PAUSE (known
within-session pause/current paused tail), RECOVERY_UNCERTAIN and UNRECORDED.
Recovery uncertainty takes priority over pause. Unplaceable uncertain segment IDs
are explicit, not fabricated zero-duration records. An event sweep avoids quadratic
comparison of all interval pairs. Adjacent equivalent spans coalesce. IDs and
witness session/segment IDs are stable for the exact projection.

`gapDraft(review,gapId,selection,note)` checks the current token and recomputes the
captured projection. It returns only an ordinary EntryDraft with HISTORY_GAP source;
it writes no fact. Uncertainty requires the existing recovery resolution, not a
manual replacement over it. UI reviews/edits and explicitly calls recordDraft with
its expected token. Existing interval validation rejects repeat fill/overlap. Date,
zone, DST and custom reporting-day policy only determine the supplied window, never
rewrite fact timestamps. Saved manual facts use existing archive/selective paths.

## 9. Batch metadata correction

`previewMetadata(BatchMetadataRequest(sessionIds,selection,selectionPolicy,note))`
accepts distinct stable IDs (up to10000). All must exist and be STOPPED; one unsuitable
record rejects the whole preview/apply. EMPTY_ONLY fills missing name/Card/type fields,
missing detail keys, and empty tags/facets while retaining existing values; assigning
a previously absent type captures its explicit current path. REPLACE explicitly
replaces the reviewed classification aggregate, including historical name/path.
Preview exposes stable MetadataChangedField values as well as before/after values.
A separate note edit defaults to EMPTY_ONLY;
explicit REPLACE with null clears it.

Preview lists exact Session/Segment field changes with before/after values and a
Dataset token. Discarding it has no effect. `applyMetadata(preview)` revalidates the
same request and commits once through Tracker/StateStore; a competing revision
refuses STALE, with no partial write. Existing Undo restores the previous Dataset
within its existing validity/lifetime boundary. Times, anchors, durations, segment
IDs, measurements and provenance are unchanged by this command. Corrected ordinary
rows already persist and travel in full/selected session groups; no separate batch
export entity or hidden timer/import engine is introduced.

## Acceptance

A4/5/8/9 source3d26c07 passes17core/14app (shared Memory/Room, selective COPY/replay,
retained legacy hash vectors and reached migration guard/interruption rollback),
build4m12s. Source3f7cb53 earlier passed3Memory/3Room search scenarios. Private real11
ancestor-context coverage passes1core atc751fb5 and1Android asset-Robolectric at26de40a;
the latter final invocation's core task is UP-TO-DATE, with actual execution retained
from the earlier attempt. Relevant search/model/archive/adapter source bytes match
public3d26c07; this is component equivalence, not an assertion of whole private/public
source equality. No device execution. Private texts/assets remain excluded from public Git.

Earlier failures are explicit: a current-format fixture expected13 instead of14;
a new Android migration test used unavailable core-test-only APIs. Both were fixed;
failed logs/results are retained separately. No successful old C949 result is
re-attributed or rerun as acceptance of these new operations.

Earlier A-only XML/log/source evidence is under evidence/journal-a-2026-10-16.
The combined source8bbd19a now passes163core/131app, including the old-data migration
comparison and mixed A/B/C COPY/Undo/compaction and origin reconstruction. Final
private11/APK evidence is source-bound in JOURNAL_14_VERIFICATION_2026_10_16.md.
JVM/Robolectric evidence does not close device/UI.
