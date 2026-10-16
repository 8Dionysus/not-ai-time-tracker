package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import java.time.*
import java.time.temporal.TemporalAdjusters

@Serializable enum class GoalMetric { DURATION_MS, SESSION_COUNT, DISTINCT_RECORDED_DAYS }
@Serializable enum class GoalDirection { MINIMUM, LIMIT }
@Serializable enum class GoalPeriod { DAY, WEEK, MONTH, FIXED }
/** Optional personal target. No stored progress, streak, notification or reward state. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class Goal(@Required val id: String = newId(),val name: String,val metric: GoalMetric,
    val target: Long,val direction: GoalDirection = GoalDirection.MINIMUM,val period: GoalPeriod = GoalPeriod.DAY,
    val zone: String = "UTC",val filter: Filter = Filter(),val enabled: Boolean = true,
    val startDate: String? = null,val endDateExclusive: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val dayStartMinute: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val revisions: List<GoalRevision> = emptyList())
@Serializable enum class GoalEditMode { NEXT_PERIOD, CORRECT_CURRENT }
/** Ordered append-only history; equal effective dates resolve to the last explicit correction.
 * Definitions inside a revision never contain further revisions. Legacy unversioned goals
 * retain their original meaning from 1970-01-01 when first edited. */
@Serializable data class GoalRevision(val id: String,val effectiveFrom: String,val recordedAt: Long,
    val mode: GoalEditMode,val definition: Goal)
val Goal.dayPolicy get() = ReportingDayPolicy(zone,dayStartMinute)
enum class GoalPosition { BELOW, AT, ABOVE }
enum class GoalStatus { MINIMUM_PENDING, MINIMUM_REACHED, WITHIN_LIMIT, LIMIT_EXCEEDED }
data class GoalWindow(val start: Long,val end: Long,val zone: String)
data class GoalProgress(val goal: Goal,val window: GoalWindow,val asOf: Long,val actual: Long,
    val position: GoalPosition,val recordedStatus: GoalStatus,val remaining: Long,val excess: Long,val incompletePeriod: Boolean,
    val completedSessionIds: Set<String>,val unfinishedSessionIds: Set<String>,val unresolvedSegmentIds: Set<String>,
    val hasRecords: Boolean) {
    /** Recorded evidence only; unknown time is not silently counted as zero activity. */
    val uncertain get() = unresolvedSegmentIds.isNotEmpty()
}
object Goals {
    /** DAY: local day; WEEK: ISO Monday; MONTH: first day. FIXED uses [startDate,endDateExclusive). */
    fun window(goal: Goal,date: LocalDate): GoalWindow = try {
        val zone = ZoneId.of(goal.zone)
        val first = when(goal.period) {
            GoalPeriod.DAY -> date
            GoalPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            GoalPeriod.MONTH -> date.withDayOfMonth(1)
            GoalPeriod.FIXED -> LocalDate.parse(goal.startDate)
        }
        val last = when(goal.period) {
            GoalPeriod.DAY -> first.plusDays(1)
            GoalPeriod.WEEK -> first.plusWeeks(1)
            GoalPeriod.MONTH -> first.plusMonths(1)
            GoalPeriod.FIXED -> LocalDate.parse(goal.endDateExclusive)
        }
        val start = goal.dayPolicy.boundary(first)
        val end = goal.dayPolicy.boundary(last)
        ensure(start >= 0 && end > start && end <= MAX_TIME,"Invalid goal period",ErrorCode.INVALID_INTERVAL)
        GoalWindow(start,end,goal.zone)
    } catch(e: CoreError) { throw e }
      catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid goal zone/date") }

    fun effective(stored: Goal,date: LocalDate): Goal = stored.revisions.withIndex().filter { LocalDate.parse(it.value.effectiveFrom) <= date }
        .maxWithOrNull(compareBy({ LocalDate.parse(it.value.effectiveFrom) },{ it.index }))?.value?.definition ?: if(stored.revisions.isEmpty()) stored else fail(ErrorCode.INVALID_INPUT,"Goal has no effective version on this date")
    internal fun save(data: Dataset,now: Moment,value: Goal,mode: GoalEditMode): Dataset {
        val old = data.goals.singleOrNull { it.id == value.id }
        ensure(value.revisions.isEmpty() || value.revisions == old?.revisions,"Goal history is command-owned")
        val definition = value.copy(revisions = emptyList())
        validateGoals(data.copy(goals = data.goals.filterNot { it.id == value.id }+definition))
        if(old == null) return data.copy(goals = data.goals+definition)
        // A cadence change cannot be spliced into existing periods. Use a new goal ID for
        // a different calendar; thresholds, filters, metric, name and enabled state are versioned.
        ensure(listOf(old.period,old.zone,old.dayStartMinute,old.startDate,old.endDateExclusive) ==
            listOf(value.period,value.zone,value.dayStartMinute,value.startDate,value.endDateExclusive),
            "A different goal calendar requires a new goal ID")
        val date = old.dayPolicy.dateAt(now.wall)
        val current = window(old,date)
        ensure(old.period != GoalPeriod.FIXED || mode == GoalEditMode.CORRECT_CURRENT,"Fixed goals require an explicit correction")
        ensure(old.period != GoalPeriod.FIXED || now.wall in current.start until current.end,"Fixed goal correction requires its current period")
        val effective = old.dayPolicy.dateAt(if(mode == GoalEditMode.NEXT_PERIOD) current.end else current.start)
        val history = old.revisions.ifEmpty { listOf(GoalRevision(newId(),"1970-01-01",now.wall,GoalEditMode.CORRECT_CURRENT,old.copy(revisions = emptyList()))) }
        val next = definition.copy(revisions = history+GoalRevision(newId(),effective.toString(),now.wall,mode,definition))
        return data.copy(goals = data.goals.map { if(it.id == next.id) next else it })
    }
    /** One captured now, same matching/clipping/uncertainty semantics as Reports and CSV.
     * A session counts once in EACH period in which it has positive matching recorded time.
     * Pauses/segments never multiply it. Unfinished counts are exposed separately and included in actual.
     */
    fun progress(data: Dataset,now: Moment,goalId: String,date: LocalDate? = null): GoalProgress {
        validateMoment(now)
        val stored = data.goals.singleOrNull { it.id == goalId } ?: fail(ErrorCode.INVALID_INPUT,"Missing goal")
        val day = date ?: stored.dayPolicy.dateAt(now.wall)
        val goal = effective(stored,day)
        val window = window(goal,day)
        val report = Reports.report(data,now,window.start,window.end,goal.filter,goal.zone,goal.dayStartMinute)
        val ids = report.rows.filter { it.durationMs > 0 }.map { it.sessionId }.toSet()
        val completed = data.sessions.filter { it.id in ids && it.status == SessionStatus.STOPPED }.map { it.id }.toSet()
        val unfinished = ids-completed
        val actual = when(goal.metric) {
            GoalMetric.DURATION_MS -> report.recordedMs
            GoalMetric.SESSION_COUNT -> ids.size.toLong()
            GoalMetric.DISTINCT_RECORDED_DAYS -> report.recordedDays.size.toLong()
        }
        return GoalProgress(goal,window,now.wall,actual,when {
            actual < goal.target -> GoalPosition.BELOW; actual == goal.target -> GoalPosition.AT; else -> GoalPosition.ABOVE
        },when(goal.direction) {
            GoalDirection.MINIMUM -> if(actual >= goal.target) GoalStatus.MINIMUM_REACHED else GoalStatus.MINIMUM_PENDING
            GoalDirection.LIMIT -> if(actual > goal.target) GoalStatus.LIMIT_EXCEEDED else GoalStatus.WITHIN_LIMIT
        },maxOf(0,goal.target-actual),maxOf(0,actual-goal.target),report.incomplete,
            completed,unfinished,report.unresolved.map { it.id }.toSet(),report.hasRecords)
    }
}
internal fun validateGoals(data: Dataset) {
    ensure(data.goals.map { it.id }.distinct().size == data.goals.size,"Duplicate goal ID")
    for(stored in data.goals) {
        ensure(stored.revisions.map { it.id }.distinct().size == stored.revisions.size,"Duplicate goal revision")
        ensure(stored.revisions.isEmpty() || stored.copy(revisions = emptyList()) == stored.revisions.last().definition,"Goal head differs from latest edit")
        stored.revisions.forEach { r ->
            ensure(r.id.isNotBlank() && r.recordedAt in 0..MAX_TIME && r.definition.id == stored.id && r.definition.revisions.isEmpty(),"Invalid goal revision")
            val date = try { LocalDate.parse(r.effectiveFrom) } catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid goal effective date") }
            ensure(date >= LocalDate.of(1970,1,1),"Invalid goal effective date")
            val v = r.definition
            ensure(listOf(v.period,v.zone,v.dayStartMinute,v.startDate,v.endDateExclusive) == listOf(stored.period,stored.zone,stored.dayStartMinute,stored.startDate,stored.endDateExclusive),"Goal revision calendar mismatch")
            // Except the legacy baseline, revisions start at a complete period boundary.
            ensure(r.effectiveFrom == "1970-01-01" || windowStartDate(v,date) == date,"Goal revision splits a period")
        }
        for(goal in listOf(stored)+stored.revisions.map { it.definition }) {
        ensure(goal.id.isNotBlank() && goal.name.isNotBlank() && goal.target > 0,"Goal needs ID/name/positive target")
        ensure(goal.metric != GoalMetric.DURATION_MS || goal.target <= MAX_TIME,"Goal duration out of range")
        goal.dayPolicy.validate()
        ensure(goal.period == GoalPeriod.FIXED || (goal.startDate == null && goal.endDateExclusive == null),"Dates only apply to fixed goals")
        if(goal.period == GoalPeriod.FIXED) Goals.window(goal,LocalDate.ofEpochDay(0))
        val f = goal.filter
        ensure(f.cardId == null || data.cards.any { it.id == f.cardId },"Missing goal card")
        ensure(f.activityTypeId == null || data.types.any { it.id == f.activityTypeId },"Missing goal type")
        ensure(f.details.all { it.key.isNotBlank() && it.value.isNotBlank() } && f.tags.all { it.isNotBlank() },"Invalid goal filter")
        ensure(f.facets.distinct().size == f.facets.size,"Duplicate goal facet")
        // ANY may legitimately contain alternative values of a single-valued axis.
        f.facets.forEach { validateFacets(data,listOf(it)) }
    }
    }
}

private fun windowStartDate(goal: Goal,date: LocalDate): LocalDate = when(goal.period) {
    GoalPeriod.DAY -> date
    GoalPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    GoalPeriod.MONTH -> date.withDayOfMonth(1)
    GoalPeriod.FIXED -> LocalDate.parse(goal.startDate)
}
internal fun Goal.frozenGoal(): Goal = copy(filter = filter.frozenFilter(),revisions = frozenList(revisions.map { it.copy(definition = it.definition.frozenGoal()) }))
internal fun Goal.definitions() = listOf(copy(revisions = emptyList()))+revisions.map { it.definition }
