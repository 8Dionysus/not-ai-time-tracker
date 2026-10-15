# Schedule delivery coordination: backend and caller contract

15 October 2026. Initial delivery coordination used Room7/portable JSON9; the current
[functional extension](FUNCTIONAL_CUSTOMIZATION_HANDOFF.md) uses Room8/JSON10. Delivery state
is separate operational state, not a fact/completion mark. The current
[indexed journal](INDEXED_SCHEDULE_RECEIPTS.md) uses SQLite schema1 and a format2
authority marker, with crash-safe import of the old format1 JSON. The v6 ceiling
section below is historical; normal Android sync no longer scans all receipts.

## Core and platform boundary

`ScheduleDelivery.synchronize()` computes the next eligible reminder from the same
occurrence projection used by `Tracker.listOccurrences`. `Schedules.occurrence`
projects one original key, and `Schedules.nextReminders` chooses the earliest future
time plus ties. Weekly candidates jump by weeks; explicit dates, future revisions,
SKIP/MOVE/REPLACE, start links, fixed/device zones and DST use the common projector.
There is no arbitrary look-ahead horizon or expanded recurrence history. Tests
include 600 skipped weeks and an explicit date in year9999.

`ScheduleRule.alertsEnabled` must be true and both occurrence time and an explicit
reminder time must resolve. Date-only plans acquire no midnight instant. Quiet
projection remains a read API, independent of reminder opt-in, notification
permission, the ordinary timer notification preference and rendering capability.
No schedule delivery operation starts a timer, writes segments or marks completion.

The Android adapter uses one replaceable inexact RTC_WAKEUP alarm for the whole
next tie batch. The immutable PendingIntent carries only its canonical hash. The
callback reads durable pending state, captures fresh Tracker data and rechecks
original key, revisions, exception/link, zone, barrier, time and capability. Old or
early callbacks cannot post. Repeated/concurrent callbacks share the delivery gate.

Before each post, a RESERVED receipt is durably written. After that write, the
current plan and capability are checked again. POSTED means NotificationManager
accepted notify, not that Android/user displayed it. NOT_POSTED is a known refusal;
UNKNOWN includes an ambiguous platform outcome. A RESERVED receipt surviving an
interruption is exposed as UNKNOWN by `attempts()`/`attemptsPage()` and is never automatically
replayed. The identity is datasetId + original occurrence key, so MOVE/edit/Undo
cannot cause another attempt for the same occurrence. A post-result write failure
leaves the previous durable reservation protecting against replay. Receipts are
local operational state; restoring domain JSON invalidates old pending tickets via
the control barrier and does not roll back local attempt history.

New startup planning does not backfill historical occurrences. A previously durable
pending reminder can be delivered late after interruption/capability recovery; its
original time stays explicit. There is no wall-clock punctuality or OEM guarantee.
No age purge or silent receipt eviction exists. Indexed storage removes the old
16MiB lifetime snapshot ceiling; per-row/control and finite disk bounds still
refuse before unsafe posts. Legacy snapshot adapters keep their bound. A capacity/
storage failure is a delivery failure, not a reason to discard history. A failed post/scheduling operation cannot be interpreted as successful UI
acknowledgement. Domain data remains untouched even when an external post outcome
is uncertain.

## Historical v6 snapshot ceiling and failure coordination (`df10261`)

This records the prior full-file implementation, retained as baseline evidence.
The current Android index/migration/paged contract is in INDEXED_SCHEDULE_RECEIPTS.md.

The format1 operational file now writes compact JSON whitespace. Its strict reader
accepts prior pretty JSON without rewriting on read; no receipt, field, outcome,
identity or canonical callback hash is pruned or changed. Each immutable ticket
caches only its derived identity in a transient field; cold decode and copy compute
their own identity. The cache is not accepted from stored JSON.

The bound is 16,777,216 UTF-8 bytes for the entire operational state, including
pending batch and all attempts. It is not a guaranteed number of reminders or
years: opaque IDs, zones, numeric widths and other fields change record size.
Reservation overflow refuses before post and preserves the prior readable file and
every receipt. Storage failures independently attempt alarm cancellation and wake
hook disable before propagating STORAGE. Platform refusal of either cleanup action
remains possible; no new reminder post follows the failed reservation.

Notice inspection/cancellation failure no longer skips alarm cancellation or hook
disable. These platform operations are attempted independently. Failure is reported
as PLATFORM_UNAVAILABLE, scheduling is not acknowledged when it did not succeed,
and no receipt is consumed by a failed synchronization. Capability-query failure
also blocks a post before reserve instead of masquerading as successful availability.

The bounded host experiment is `:core:scheduleDeliveryProfile` with
`-PtrackerCleanupLibrary=<host JNI library>`. It records three sequential syncs for
0,1000,10000 and a state filled to the byte ceiling, checks zero writes for unchanged
synchronizations, then proves that a new reservation at the ceiling posts nothing,
retains all existing receipts/bytes and disarms best-effort. Raw evidence is delivered
with the exact runtime SHA. JVM heap samples include transient garbage and the
fixture; they are not retained heap, phone PSS or battery measurements.

**Historical pre-index long-lived limit (superseded for Android indexed storage):** compact whitespace and a transient
hash cache reduce bytes/repeated hashing; they do not bound receipt count or make
the store indexed. Each cold synchronization still decodes/validates all attempts
and builds sets in O(n) time/memory. A changed state serializes and atomically
replaces the whole file. Successful delivery normally writes reserve, outcome and
the next pending batch (two writes per tied item plus a batch update). Near the
ceiling this can mean tens of MiB of logical writes per occurrence, before filesystem
amplification. There is no unlimited lifetime, low-RAM or battery acceptance claim.
At the ceiling delivery stops safely; it does not silently forget receipts. Any
future indexed journal/explicit retention must preserve exact dataset+original-key
tombstones across old callbacks, MOVE/edit and restore, disclose loss of detailed
attempt outcomes, and get its own migration/crash verification. This pass does not
invent that retention policy or silently evict history.

## Rendering and final post gate (`d0888a5`)

The backend platform contract is now `prepare(ticket, projection): PreparedScheduleNotice?`.
Preparation invokes the renderer outside the domain transaction. After it returns,
core enters the same StateStore checked-transaction gate used by commands, rechecks
current occurrence/revisions/opt-in/link/barrier/zone/time/capability, and invokes
only the prepared terminal post. Memory/Room reuse the unchanged CheckedState;
no Dataset revision, session, link or Room generation is written by the post guard.
Delivery receipts are persisted before and after this domain gate, not while it is held.

A renderer that archives/opts out/starts the current occurrence can therefore cause
NOT_POSTED before notify; cancellation in the trailing synchronize is not used as
evidence that no stale sound was submitted. A concurrent cooperating domain command
commits either before the final match (post refused) or after terminal notify returns.
The shared Memory/Room tests exercise both orders. The Android API36 adapter test
checks NOT_POSTED for current-series opt-out during render, rather than merely an
empty notification list after possible post-and-cancel.

PreparedScheduleNotice is an internal backend protocol, not an independently usable
UI capability. Terminal post must be short and must not render, mutate Tracker,
read delivery state or reenter the coordinator. Only OS submission runs under the
domain gate; a slow Binder can delay concurrent commands and needs device QA.
ScheduleNoticeFactory and its Opus-owned rendering signature are unchanged.
The complete fresh projection must still equal the rendered projection. A changed
day policy/relative-day caption therefore refuses post even without a series revision change.
Factories must still avoid side effects; the guard handles the reviewed regression.
This serializes submission against domain commits. It cannot retract sound already
accepted by Android before a later opt-out, nor make OS display/permission/clock
changes part of the application transaction. POSTED remains submission acceptance.

## Required Opus integration

1. At **every process startup**, install an application-scoped
   `ScheduleNoticeBinding.install(factory)`. The factory implements
   `ScheduleNoticeFactory.create(context,ticket,projection)` and must not capture an
   Activity or mutate Tracker. Without it the capability is RENDERER_UNAVAILABLE;
   backend alarms/wake hooks stay disabled. Existing production UI has no factory.
2. On contextual explicit opt-in, create the localized channel with stable ID
   `AndroidScheduleDeliveryPlatform.CHANNEL`. Permission/channel denial does not
   rewrite alertsEnabled or remove a plan. The factory owns localized notification
   content, privacy/public-version handling, accessibility and a direct explicit
   Activity route; tapping a notice must not start time automatically. Backend owns
   ticket extras, unique notification tags and notify/cancel calls. DND is reported
   as a system capability and remains Android-controlled.
3. Call `runtime.scheduleDelivery.synchronize()` on IO after foreground return,
   permission/channel changes and binding changes. Runtime observation compares
   persisted schedule day/quiet preferences too. Runtime observation already
   refreshes committed schedule/barrier changes; ordinary timer commits with the
   same schedule do not resynchronize. UI can show the typed availability and
   attempt result. `alarmAccepted` acknowledges the scheduling call only.
4. Use one `Tracker.listOccurrences` projection/key for home, quiet shade and widget.
   Those renderer/layout changes remain Opus-owned and absent from this pass.
   Import/restore preview must show enabled incoming reminder choices; no hidden
   permission request, automatic start or unrelated global setting import.

The new SchedulePlatformReceiver is disabled by default. A usable opted-in pending
alarm enables it for BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED and the app's own
package replacement. No usable alarm disables it again. RECEIVE_BOOT_COMPLETED is
therefore a new manifest permission, while ordinary timer-only use keeps this
receiver disabled. There is no timer FGS, periodic poll or per-second DB write.
Background receivers refuse to initialize an empty replacement Dataset when its
SQLite files are absent; retained delivery state remains for foreground review.
Cold receivers still need the factory installed at Application startup. Force-stop,
OS delivery limits, broadcast budgets and OEM behavior need real device QA.
The platform event choice follows [Android broadcast exceptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions);
alarms use the documented best-effort [setAndAllowWhileIdle API](https://developer.android.com/reference/android/app/AlarmManager#setAndAllowWhileIdle(int,%20long,%20android.app.PendingIntent)).

## Focus compatibility: caller migration is mandatory

The existing snapshot `FocusSignals.deliver(context,focus):Boolean` is deprecated.
It queues fresh IO work and returns a previous receipt only; existing ViewModel and
TechnicalActivity callers still treat the Boolean as synchronous delivery and can
show duplicate/fallback cues. Backend compatibility cannot make that interpretation
truthful without changing the callers. This app chain remains OPEN.

Use `FocusSignals.deliverResult(context,tracker)` on IO. Its typed status is
POST_ACCEPTED, PREVIOUS_RECEIPT, BLOCKED_BEFORE_POST, NOT_DUE or OUTCOME_UNKNOWN.
PREVIOUS_RECEIPT and OUTCOME_UNKNOWN do not acknowledge delivery and must not cause
an automatic duplicate fallback. A deliberate visible fallback for BLOCKED_BEFORE_POST
must use the returned fresh focus and verify the current signalKey/phase again after
awaiting IO, then consume it once via FocusDueSignals. NOT_DUE never shows a due cue.
The old tracker Boolean API remains source compatible; new code should use the
result. No Compose/TechnicalActivity caller was changed.

Other mandatory callers: TrackerViewModel.applyChoice still does switch then
labelCurrent in two transactions; use atomic `Tracker.switchDraft(expected,draft)`
for incoming text. Technical CSV mapping still does not pass sourceLocale; connect
the explicit source date locale and preserve mapping inputs on errors. The 65th
import issue contract is [backend-typed-delta.json](localization/backend-typed-delta.json).
