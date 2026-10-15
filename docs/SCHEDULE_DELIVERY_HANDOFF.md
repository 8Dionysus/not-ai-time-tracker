# Schedule delivery coordination: backend and caller contract

15 October 2026. Domain persistence remains Room7 / portable JSON9. Delivery state
is a separate bounded operational sidecar (format1), not a fact or a completion mark.

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
interruption is exposed as UNKNOWN by `attempts()` and is never automatically
replayed. The identity is datasetId + original occurrence key, so MOVE/edit/Undo
cannot cause another attempt for the same occurrence. A post-result write failure
leaves the previous durable reservation protecting against replay. Receipts are
local operational state; restoring domain JSON invalidates old pending tickets via
the control barrier and does not roll back local attempt history.

New startup planning does not backfill historical occurrences. A previously durable
pending reminder can be delivered late after interruption/capability recovery; its
original time stays explicit. There is no wall-clock punctuality or OEM guarantee.
No age purge or silent receipt eviction exists. Metadata refuses overflow at16MiB
UTF-8; a capacity/storage failure is a delivery failure, not a reason to discard
history. A failed post/scheduling operation cannot be interpreted as successful UI
acknowledgement. Domain data remains untouched even when an external post outcome
is uncertain.

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
   permission/channel changes and binding changes. Runtime observation already
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
