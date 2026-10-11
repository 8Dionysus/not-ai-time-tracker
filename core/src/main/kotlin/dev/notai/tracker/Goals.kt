package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import java.time.*
import java.time.temporal.TemporalAdjusters

@Serializable enum class GoalMetric { DURATION_MS, SESSION_COUNT }
@Serializable enum class GoalDirection { MINIMUM, LIMIT }
@Serializable enum class GoalPeriod { DAY, WEEK, MONTH, FIXED }
/** Optional personal target. No stored progress, streak, notification or reward state. */
@Serializable data class Goal(@Required val id: String = newId(),val name: String,val metric: GoalMetric,
    val target: Long,val direction: GoalDirection = GoalDirection.MINIMUM,val period: GoalPeriod = GoalPeriod.DAY,
    val zone: String = "UTC",val filter: Filter = Filter(),val enabled: Boolean = true,
    val startDate: String? = null,val endDateExclusive: String? = null)
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
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = last.atStartOfDay(zone).toInstant().toEpochMilli()
        ensure(start >= 0 && end > start && end <= MAX_TIME,"Invalid goal period",ErrorCode.INVALID_INTERVAL)
        GoalWindow(start,end,goal.zone)
    } catch(e: CoreError) { throw e }
      catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid goal zone/date") }

    /** One captured now, same matching/clipping/uncertainty semantics as Reports and CSV.
     * A session counts once in EACH period in which it has positive matching recorded time.
     * Pauses/segments never multiply it. Unfinished counts are exposed separately and included in actual.
     */
    fun progress(data: Dataset,now: Moment,goalId: String,date: LocalDate? = null): GoalProgress {
        validateMoment(now)
        val goal = data.goals.singleOrNull { it.id == goalId } ?: fail(ErrorCode.INVALID_INPUT,"Missing goal")
        val day = date ?: Instant.ofEpochMilli(now.wall).atZone(ZoneId.of(goal.zone)).toLocalDate()
        val window = window(goal,day)
        val report = Reports.report(data,now,window.start,window.end,goal.filter,goal.zone)
        val ids = report.rows.filter { it.durationMs > 0 }.map { it.sessionId }.toSet()
        val completed = data.sessions.filter { it.id in ids && it.status == SessionStatus.STOPPED }.map { it.id }.toSet()
        val unfinished = ids-completed
        val actual = if(goal.metric == GoalMetric.DURATION_MS) report.recordedMs else ids.size.toLong()
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
    for(goal in data.goals) {
        ensure(goal.id.isNotBlank() && goal.name.isNotBlank() && goal.target > 0,"Goal needs ID/name/positive target")
        ensure(goal.metric != GoalMetric.DURATION_MS || goal.target <= MAX_TIME,"Goal duration out of range")
        try { ZoneId.of(goal.zone) } catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid goal zone") }
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
