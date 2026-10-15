package dev.notai.tracker

import kotlinx.serialization.Serializable
import java.time.*
import java.time.temporal.ChronoUnit

@Serializable enum class ScheduleState { ACTIVE, DISABLED, ARCHIVED }
@Serializable enum class ScheduleZonePolicy { DEVICE_LOCAL, FIXED }
@Serializable data class ScheduleRule(val selection: Classification,val startDate: String,val weekdays: Set<Int> = emptySet(),
    val extraDates: Set<String> = emptySet(),val localTime: String? = null,val zonePolicy: ScheduleZonePolicy = ScheduleZonePolicy.DEVICE_LOCAL,
    val zoneId: String? = null,val until: String? = null,val alertsEnabled: Boolean = false,val reminderTime: String? = null)
@Serializable data class ScheduleRevision(val effectiveFrom: String,val rule: ScheduleRule)
@Serializable data class ScheduleSeries(val id: String = newId(),val revisions: List<ScheduleRevision>,val revision: Long = 1,val state: ScheduleState = ScheduleState.ACTIVE)
@Serializable data class OccurrenceKey(val seriesId: String,val originalDate: String)
@Serializable enum class OverrideOperation { SKIP, MOVE, REPLACE }
/** Null time with replaceTime=true explicitly removes a time. Reminder has the same tri-state. */
@Serializable data class OccurrenceOverride(val key: OccurrenceKey,val operation: OverrideOperation,val revision: Long = 1,
    val date: String? = null,val selection: Classification? = null,val replaceTime: Boolean = false,val localTime: String? = null,
    val replaceReminder: Boolean = false,val reminderTime: String? = null,val allowCollision: Boolean = false)
@Serializable data class ScheduleStartLink(val key: OccurrenceKey,val sessionId: String,val createdAt: Long,val seriesRevision: Long,val commandKey: String,val payloadHash: String)
@Serializable data class ScheduleCommandResult(val datasetRevision: Long,val seriesId: String,val occurrence: OccurrenceKey? = null,val sessionId: String? = null)
@Serializable data class ScheduleReceipt(val key: String,val hash: String,val result: ScheduleCommandResult)
@Serializable data class ScheduleData(val series: List<ScheduleSeries> = emptyList(),val overrides: List<OccurrenceOverride> = emptyList(),
    val links: List<ScheduleStartLink> = emptyList(),val receipts: List<ScheduleReceipt> = emptyList())
enum class DstResolution { EXACT, GAP_FIRST_VALID, OVERLAP_EARLIER }
enum class OccurrenceState { SCHEDULED, TODAY, OVERDUE, SKIPPED, MOVED, STARTED }
data class ScheduleProjection(val key: OccurrenceKey,val seriesRevision: Long,val overrideRevision: Long?,val originalDate: LocalDate,
    val date: LocalDate,val localTime: LocalTime?,val selection: Classification,val zone: String,val instant: Instant?,val dstResolution: DstResolution?,
    val reminderInstant: Instant?,val startedSessionId: String?,val state: OccurrenceState)
object ScheduleDayResolver {
    fun date(now: Instant,deviceZone: ZoneId,boundary: LocalTime = LocalTime.MIDNIGHT): LocalDate {
        val local = now.atZone(deviceZone);return local.toLocalDate().minusDays(if(local.toLocalTime() < boundary) 1 else 0)
    }
}
internal fun scheduleDate(raw: String): LocalDate = try { LocalDate.parse(raw).also { ensure(it.year in 1..9999 && it.toString() == raw,"Invalid date") } }
    catch(e: CoreError) { throw e } catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid ISO date") }
internal fun scheduleTime(raw: String): LocalTime = try { LocalTime.parse(raw).also { ensure(it.nano == 0,"Subsecond schedule unsupported") } }
    catch(e: CoreError) { throw e } catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid local time") }
internal fun scheduleZone(raw: String): ZoneId = try { ZoneId.of(raw).also { ensure(raw in ZoneId.getAvailableZoneIds(),"IANA zone required") } }
    catch(e: CoreError) { throw e } catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid IANA zone") }
internal fun ScheduleSeries.rule(date: LocalDate): ScheduleRule? = revisions.lastOrNull { scheduleDate(it.effectiveFrom) <= date }?.rule
internal fun ScheduleRule.includes(date: LocalDate): Boolean = date >= scheduleDate(startDate) && (until == null || date <= scheduleDate(until)) &&
    (date.dayOfWeek.value in weekdays || date.toString() in extraDates)
internal fun resolveScheduleTime(date: LocalDate,time: LocalTime,zone: ZoneId): Pair<Instant,DstResolution> {
    val local = date.atTime(time);val offsets = zone.rules.getValidOffsets(local)
    return when(offsets.size) {
        0 -> zone.rules.getTransition(local).dateTimeAfter.atZone(zone).toInstant() to DstResolution.GAP_FIRST_VALID
        1 -> local.toInstant(offsets[0]) to DstResolution.EXACT
        else -> local.toInstant(offsets[0]) to DstResolution.OVERLAP_EARLIER
    }
}
object Schedules {
    private fun occurrence(series: ScheduleSeries,original: LocalDate,o: OccurrenceOverride?,link: ScheduleStartLink?,now: Instant,deviceZone: ZoneId): ScheduleProjection? {
        val key = OccurrenceKey(series.id,original.toString())
        val rule = series.rule(original) ?: return null
        if(!rule.includes(original) && o == null && link == null) return null
        val date = o?.date?.let(::scheduleDate) ?: original
        val time = (if(o?.replaceTime == true) o.localTime else rule.localTime)?.let(::scheduleTime)
        val zone = if(rule.zonePolicy == ScheduleZonePolicy.FIXED) scheduleZone(rule.zoneId!!) else deviceZone
        val resolved = time?.let { resolveScheduleTime(date,it,zone) }
        val reminder = (if(o?.replaceReminder == true) o.reminderTime else rule.reminderTime)?.let(::scheduleTime)
        val today = ScheduleDayResolver.date(now,deviceZone)
        val state = when {
            link != null -> OccurrenceState.STARTED
            o?.operation == OverrideOperation.SKIP -> OccurrenceState.SKIPPED
            o?.operation == OverrideOperation.MOVE -> OccurrenceState.MOVED
            date < today -> OccurrenceState.OVERDUE
            date == today -> OccurrenceState.TODAY
            else -> OccurrenceState.SCHEDULED
        }
        return ScheduleProjection(key,series.revision,o?.revision,original,date,time,o?.selection ?: rule.selection,zone.id,resolved?.first,resolved?.second,
            if(rule.alertsEnabled && time != null && reminder != null && state !in setOf(OccurrenceState.SKIPPED,OccurrenceState.STARTED)) resolveScheduleTime(date,reminder,zone).first else null,
            link?.sessionId,state)
    }
    fun occurrence(data: Dataset,key: OccurrenceKey,now: Instant,deviceZone: ZoneId): ScheduleProjection? {
        val series = data.schedule.series.singleOrNull { it.id == key.seriesId && it.state == ScheduleState.ACTIVE } ?: return null
        return occurrence(series,scheduleDate(key.originalDate),data.schedule.overrides.singleOrNull { it.key == key },data.schedule.links.singleOrNull { it.key == key },now,deviceZone)
    }
    fun project(data: Dataset,first: LocalDate,last: LocalDate,now: Instant,deviceZone: ZoneId,includeInactive: Boolean = false): List<ScheduleProjection> {
        ensure(!last.isBefore(first) && ChronoUnit.DAYS.between(first,last) <= 3660,"Projection range exceeds 3661 days")
        val schedule = data.schedule
        val overrides = schedule.overrides.associateBy { it.key };val links = schedule.links.associateBy { it.key }
        return schedule.series.filter { includeInactive || it.state == ScheduleState.ACTIVE }.flatMap { series ->
            val dates = generateSequence(first) { if(it < last) it.plusDays(1) else null }.toMutableSet()
            schedule.overrides.filter { it.key.seriesId == series.id && it.date?.let(::scheduleDate)?.let { d -> d in first..last } == true }.forEach { dates += scheduleDate(it.key.originalDate) }
            dates.mapNotNull { original ->
                val key = OccurrenceKey(series.id,original.toString())
                occurrence(series,original,overrides[key],links[key],now,deviceZone)?.takeIf { it.date in first..last }
            }
        }.sortedWith(compareBy<ScheduleProjection> { it.date }.thenBy { it.localTime ?: LocalTime.MAX }.thenBy { it.key.seriesId }.thenBy { it.key.originalDate })
    }
    /** Closest future reminder(s), including distant explicit dates and moved occurrences.
     * Weekly candidates jump by weeks; no expanding years of recurrence or arbitrary horizon. */
    fun nextReminders(data: Dataset,now: Instant,deviceZone: ZoneId,excluded: Set<OccurrenceKey> = emptySet()): List<ScheduleProjection> {
        val schedule = data.schedule;val overrides = schedule.overrides.associateBy { it.key };val links = schedule.links.associateBy { it.key }
        val blocked = overrides.keys+links.keys+excluded
        val candidates = mutableMapOf<OccurrenceKey,ScheduleProjection>()
        fun add(series: ScheduleSeries,date: LocalDate) {
            val key = OccurrenceKey(series.id,date.toString());if(key in excluded) return
            occurrence(series,date,overrides[key],links[key],now,deviceZone)?.takeIf { it.reminderInstant?.isAfter(now) == true }?.let { candidates[key] = it }
        }
        for(series in schedule.series.filter { it.state == ScheduleState.ACTIVE }) {
            schedule.overrides.filter { it.key.seriesId == series.id }.forEach { add(series,scheduleDate(it.key.originalDate)) }
            series.revisions.forEachIndexed { index,version ->
                val rule = version.rule
                if(!rule.alertsEnabled || rule.localTime == null || rule.reminderTime == null) return@forEachIndexed
                val zone = if(rule.zonePolicy == ScheduleZonePolicy.FIXED) scheduleZone(rule.zoneId!!) else deviceZone
                val first = maxOf(scheduleDate(rule.startDate),scheduleDate(version.effectiveFrom),now.atZone(zone).toLocalDate())
                val until = rule.until?.let(::scheduleDate)
                val endExclusive = series.revisions.getOrNull(index+1)?.effectiveFrom?.let(::scheduleDate)
                fun within(date: LocalDate) = date.year in 1..9999 && (until == null || date <= until) && (endExclusive == null || date < endExclusive)
                rule.extraDates.map(::scheduleDate).filter { it >= first && within(it) && OccurrenceKey(series.id,it.toString()) !in blocked }.forEach { add(series,it) }
                for(day in rule.weekdays) {
                    var date = first.plusDays(((day-first.dayOfWeek.value+7)%7).toLong())
                    while(within(date)) {
                        val key = OccurrenceKey(series.id,date.toString())
                        if(key !in blocked) { add(series,date);if(key in candidates) break }
                        date = date.plusWeeks(1)
                    }
                }
            }
        }
        val next = candidates.values.minOfOrNull { it.reminderInstant!! } ?: return emptyList()
        return candidates.values.filter { it.reminderInstant == next }.sortedWith(compareBy<ScheduleProjection> { it.key.seriesId }.thenBy { it.key.originalDate })
    }
}

internal fun validateSchedule(data: Dataset) {
    val s = data.schedule
    ensure(s.series.map { it.id }.distinct().size == s.series.size && s.series.all { it.id.isNotBlank() && it.revision > 0 },"Invalid/duplicate series")
    fun selection(c: Classification) = validateSelection(data,c,data.types.map { it.id }.toSet(),data.cards.map { it.id }.toSet())
    for(series in s.series) {
        ensure(series.revisions.isNotEmpty(),"Empty series revisions")
        val dates = series.revisions.map { scheduleDate(it.effectiveFrom) };ensure(dates == dates.sorted(),"Invalid schedule revision dates")
        series.revisions.forEach { version ->
            val r = version.rule;selection(r.selection)
            val start = scheduleDate(r.startDate)
            ensure(r.weekdays.all { it in 1..7 } && (r.weekdays.isNotEmpty() || r.extraDates.isNotEmpty()),"Empty/invalid recurrence")
            ensure(r.until == null || scheduleDate(r.until) >= start,"Invalid until")
            r.extraDates.forEach { ensure(scheduleDate(it) >= start && (r.until == null || scheduleDate(it) <= scheduleDate(r.until)),"Extra date outside series") }
            r.localTime?.let(::scheduleTime);r.reminderTime?.let(::scheduleTime)
            ensure(r.zonePolicy == ScheduleZonePolicy.FIXED || r.zoneId == null,"Device-local rule has fixed zone")
            if(r.zonePolicy == ScheduleZonePolicy.FIXED) scheduleZone(r.zoneId ?: fail(ErrorCode.INVALID_INPUT,"Fixed zone missing"))
        }
    }
    ensure(s.overrides.map { it.key }.distinct().size == s.overrides.size,"Duplicate occurrence override")
    fun original(key: OccurrenceKey): ScheduleSeries {
        val series = s.series.find { it.id == key.seriesId } ?: fail(ErrorCode.INVALID_INPUT,"Missing series")
        val date = scheduleDate(key.originalDate)
        ensure(series.rule(date) != null && series.revisions.any { it.rule.includes(date) },"Unknown original occurrence")
        return series
    }
    s.overrides.forEach { o ->
        original(o.key);ensure(o.revision > 0,"Invalid override revision");o.date?.let(::scheduleDate);o.localTime?.let(::scheduleTime);o.reminderTime?.let(::scheduleTime);o.selection?.let(::selection)
        ensure(o.operation != OverrideOperation.MOVE || o.date != null,"Move requires date")
        ensure(o.operation != OverrideOperation.SKIP || (o.date == null && o.selection == null && !o.replaceTime && !o.replaceReminder),"Skip has replacement fields")
        ensure(o.replaceTime || o.localTime == null,"Time replacement not enabled");ensure(o.replaceReminder || o.reminderTime == null,"Reminder replacement not enabled")
    }
    ensure(s.links.map { it.key }.distinct().size == s.links.size && s.links.map { it.sessionId }.distinct().size == s.links.size,"Duplicate start link")
    s.links.forEach { l -> original(l.key);ensure(data.sessions.any { it.id == l.sessionId } && l.createdAt in 0..MAX_TIME && l.seriesRevision > 0 && l.commandKey.isNotBlank() && l.payloadHash.matches(Regex("[0-9a-f]{64}")),"Invalid start link") }
    ensure(s.receipts.map { it.key }.distinct().size == s.receipts.size,"Duplicate receipt")
    s.receipts.forEach { ensure(it.key.isNotBlank() && it.hash.matches(Regex("[0-9a-f]{64}")) && it.result.datasetRevision > 0,"Invalid schedule receipt") }
}
@Serializable enum class ScheduleAction { CREATE, UPDATE_FUTURE, SET_STATE, OVERRIDE, START }
@Serializable data class ScheduleCommand(val key: String,val expected: Token,val action: ScheduleAction,val seriesId: String,
    val expectedSeriesRevision: Long? = null,val rule: ScheduleRule? = null,val effectiveFrom: String? = null,val state: ScheduleState? = null,
    val occurrence: OccurrenceKey? = null,val override: OccurrenceOverride? = null,val expectedOverrideRevision: Long? = null,val deviceZone: String = "UTC")
/** One transaction and receipt, including the existing timer transition. No second timer. */
fun Tracker.scheduleCommand(command: ScheduleCommand): ScheduleCommandResult = storage {
    // Capture caller-owned nested collections before hashing and applying one immutable request.
    val captured = codec.decodeFromString<ScheduleCommand>(codec.encodeToString(command))
    fun time(value: String?) = value?.let { scheduleTime(it).toString() }
    val prepared = captured.copy(rule = captured.rule?.let { r -> r.copy(localTime = time(r.localTime),reminderTime = time(r.reminderTime)) },
        override = captured.override?.let { o -> o.copy(localTime = time(o.localTime),reminderTime = time(o.reminderTime)) })
    val hash = payloadHash(prepared)
    store.checkedTransaction { checked ->
        val d = checked.data;val c = prepared;val s = d.schedule
        ensure(c.key.isNotBlank() && c.key.length <= 200,"Invalid command key")
        val receipt = s.receipts.find { it.key == c.key }
        if(receipt != null) {
            ensureReason(receipt.hash == hash,ErrorReason.IDEMPOTENCY_CONFLICT,ErrorCode.STALE,mapOf("commandKey" to ErrorArgument.Identifier(c.key)))
            return@checkedTransaction checked.replace(d) { receipt.result }
        }
        val now = clock.now();checkToken(d,c.expected,now);val zone = scheduleZone(c.deviceZone)
        var next = d;var resultSession: String? = null
        val series = s.series.find { it.id == c.seriesId }
        if(c.action != ScheduleAction.CREATE) {
            ensureReason(series != null,ErrorReason.SERIES_NOT_FOUND,arguments = mapOf("seriesId" to ErrorArgument.Identifier(c.seriesId)))
            ensure(series!!.revision == c.expectedSeriesRevision,"Series revision changed",ErrorCode.STALE)
        }
        when(c.action) {
            ScheduleAction.CREATE -> {
                ensure(series == null && c.seriesId.isNotBlank(),"Series already exists")
                val rule = c.rule ?: fail(ErrorCode.INVALID_INPUT,"Missing rule");checkStartable(d,rule.selection)
                val row = ScheduleSeries(c.seriesId,listOf(ScheduleRevision(rule.startDate,rule.copy(selection = d.classify(rule.selection)))))
                next = d.copy(schedule = s.copy(series = s.series+row))
            }
            ScheduleAction.UPDATE_FUTURE -> {
                val from = scheduleDate(c.effectiveFrom ?: fail(ErrorCode.INVALID_INPUT,"Effective date required"))
                ensure(from >= ScheduleDayResolver.date(Instant.ofEpochMilli(now.wall),zone),"Cannot rewrite past schedule")
                ensure(from >= scheduleDate(series!!.revisions.last().effectiveFrom),"Effective date cannot precede prior revision")
                val rule = c.rule ?: fail(ErrorCode.INVALID_INPUT,"Missing rule");checkStartable(d,rule.selection)
                val row = series.copy(revision = Math.addExact(series.revision,1),revisions = series.revisions+ScheduleRevision(from.toString(),rule.copy(selection = d.classify(rule.selection))))
                next = d.copy(schedule = s.copy(series = s.series.map { if(it.id == row.id) row else it }))
            }
            ScheduleAction.SET_STATE -> {
                val row = series!!.copy(revision = Math.addExact(series.revision,1),state = c.state ?: fail(ErrorCode.INVALID_INPUT,"Missing state"))
                next = d.copy(schedule = s.copy(series = s.series.map { if(it.id == row.id) row else it }))
            }
            ScheduleAction.OVERRIDE,ScheduleAction.START -> {
                val key = c.occurrence ?: fail(ErrorCode.INVALID_INPUT,"Missing occurrence")
                ensure(key.seriesId == series!!.id && series.state == ScheduleState.ACTIVE,"Series unavailable")
                val date = scheduleDate(key.originalDate);val rule = series.rule(date) ?: fail(ErrorCode.INVALID_INPUT,"OCCURRENCE_NOT_FOUND")
                val old = s.overrides.find { it.key == key }
                ensureReason(rule.includes(date) || old != null,ErrorReason.OCCURRENCE_NOT_FOUND,arguments = mapOf("originalDate" to ErrorArgument.Date(key.originalDate)))
                ensure(old?.revision == c.expectedOverrideRevision,"Occurrence revision changed",ErrorCode.STALE)
                if(c.action == ScheduleAction.OVERRIDE) {
                    val o = c.override ?: fail(ErrorCode.INVALID_INPUT,"Missing override")
                    ensure(o.key == key,"Wrong override key");ensure(s.links.none { it.key == key },"Started occurrence is immutable")
                    val row = o.copy(revision = Math.addExact(old?.revision ?: 0,1),selection = o.selection?.let { checkStartable(d,it);d.classify(it) })
                    next = d.copy(schedule = s.copy(overrides = s.overrides.filterNot { it.key == key }+row))
                    validateSchedule(next)
                    if(row.operation == OverrideOperation.MOVE && !row.allowCollision) {
                        val target = scheduleDate(row.date!!)
                        ensureReason(Schedules.project(next,target,target,Instant.ofEpochMilli(now.wall),zone).none { it.key != key && it.key.seriesId == key.seriesId && it.state != OccurrenceState.SKIPPED },ErrorReason.OCCURRENCE_COLLISION,arguments = mapOf("targetDate" to ErrorArgument.Date(target.toString())))
                    }
                } else {
                    ensure(old?.operation != OverrideOperation.SKIP && s.links.none { it.key == key },"Occurrence skipped or already started")
                    val selected = old?.selection ?: rule.selection
                    val started = checked.timer(c.expected,TimerAction.START,now,selected,null) { state,_ -> state.data }.state
                    resultSession = started.snapshot(now).session!!.id
                    val link = ScheduleStartLink(key,resultSession!!,now.wall,series.revision,c.key,hash)
                    next = started.data.copy(schedule = s.copy(links = s.links+link))
                }
            }
        }
        val revision = Math.addExact(d.revision,1)
        val result = ScheduleCommandResult(revision,c.seriesId,c.occurrence,resultSession)
        next = next.copy(revision = revision,schedule = next.schedule.copy(receipts = s.receipts+ScheduleReceipt(c.key,hash,result)))
        checked.replace(next) { result }
    }
}
fun Tracker.listOccurrences(first: LocalDate,last: LocalDate,deviceZone: ZoneId): List<ScheduleProjection> =
    Schedules.project(data(),first,last,Instant.ofEpochMilli(clock.now().wall),deviceZone)
