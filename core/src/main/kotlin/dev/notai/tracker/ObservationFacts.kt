package dev.notai.tracker

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Optional factual projection, not a score, streak or durable achievement ledger.
 * Inputs stay with the caller until the product chooses a saved visual layer. */
data class ObservationRequest(val enabled: Boolean = false,val filter: Filter = Filter(),
    val recordedMilestonesMs: Set<Long> = emptySet(),val goalIds: Set<String> = emptySet())
data class ObservationDay(val date: LocalDate,val recordedMs: Long,val hasPositiveRecord: Boolean)
data class RecordedMilestone(val thresholdMs: Long,val reached: Boolean)
data class ObservationGoal(val id: String,val progress: GoalProgress,
    /** Eligible for an optional quiet acknowledgement; never automatically emits a cue. */
    val acknowledgementEligible: Boolean)
data class ObservationWeek(val source: Token,val asOf: Long,val firstDate: LocalDate,val zone: String,
    val recordedMs: Long,val days: List<ObservationDay>,val positiveDays: Int,
    val unresolvedSegmentIds: Set<String>,val incompletePeriod: Boolean,
    val milestones: List<RecordedMilestone>,val goals: List<ObservationGoal>)

object ObservationFacts {
    /** ISO Monday week. Recompute on EVERY revision/correction and time observation; consumers
     * must discard an old source token. A lower corrected total retracts a milestone immediately.
     * Session fragmentation cannot change recorded duration or distinct positive calendar days. */
    fun week(data: Dataset,now: Moment,date: LocalDate,zone: String,request: ObservationRequest): ObservationWeek? {
        if(!request.enabled) return null
        validateMoment(now)
        val thresholds = frozenSet(request.recordedMilestonesMs);val selectedGoals = frozenSet(request.goalIds)
        val filter = request.filter.copy(details = frozenMap(request.filter.details),tags = frozenSet(request.filter.tags),facets = frozenList(request.filter.facets))
        ensure(thresholds.size <= 64 && thresholds.all { it in 1..MAX_TIME },"Invalid observation milestone")
        ensure(selectedGoals.all { id -> data.goals.any { it.id == id } },"Missing observation goal")
        val zoneId = try { ZoneId.of(zone) } catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid observation zone") }
        val first = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val calendar = Reports.calendar(data,now,first,7,filter,zoneId.id)
        val report = calendar.window
        val days = calendar.days.map { ObservationDay(it.date,it.recordedMs,it.recordedMs > 0) }
        val goals = selectedGoals.sorted().map { id ->
            val goal = data.goals.single { it.id == id }
            val progress = Goals.progress(data,now,id,date)
            ObservationGoal(id,progress,goal.enabled && goal.metric == GoalMetric.DURATION_MS && goal.direction == GoalDirection.MINIMUM &&
                progress.recordedStatus == GoalStatus.MINIMUM_REACHED && !progress.uncertain && progress.hasRecords)
        }
        return ObservationWeek(token(data),now.wall,first,zone,report.recordedMs,frozenList(days),days.count { it.hasPositiveRecord },
            frozenSet(report.unresolved.map { it.id }),report.incomplete,
            frozenList(thresholds.sorted().map { RecordedMilestone(it,report.recordedMs >= it) }),frozenList(goals))
    }
}
