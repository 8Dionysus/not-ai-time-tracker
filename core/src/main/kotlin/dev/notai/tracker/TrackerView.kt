package dev.notai.tracker

/** One immutable committed revision. UI may retain this until the next emission.
 * Display ticks materialize only the current session; no storage access or history scan.
 * Reports are explicit operations over this revision, at one captured moment per call.
 */
class TrackerView internal constructor(private val checked: CheckedState,private val clock: Clock,private val choiceCache: ChoiceCache) {
    private val choiceView by lazy { ChoiceView(data,choiceCache,checked.historyKey,::snapshot) }
    fun choices(): ChoiceView = choiceView
    val data get() = checked.data
    fun snapshot(): Snapshot = checked.snapshot(clock.now())
    fun scheduleOccurrences(first: java.time.LocalDate,last: java.time.LocalDate,deviceZone: java.time.ZoneId): List<ScheduleProjection> =
        Schedules.project(data,first,last,java.time.Instant.ofEpochMilli(clock.now().wall),deviceZone)
    fun scheduleDay(deviceZone: java.time.ZoneId): ScheduleDayView =
        data.settings.schedule.resolveDay(java.time.Instant.ofEpochMilli(clock.now().wall),deviceZone)
    /** One revision and ONE captured moment even when a civil boundary is crossed. */
    fun scheduleState(deviceZone: java.time.ZoneId): ScheduleViewState {
        val now = clock.now();validateMoment(now);val instant = java.time.Instant.ofEpochMilli(now.wall)
        val day = data.settings.schedule.resolveDay(instant,deviceZone)
        return ScheduleViewState(checked.snapshot(now).token,data.controlBarrier,now.wall,day,data.settings.schedule,data.settings.surfaces,deviceZone.id,
            frozenList(Schedules.project(data,day.date.minusDays(7),day.date.plusDays(14),instant,deviceZone)),data.settings.schedule.nextDayBoundary(instant,deviceZone).toEpochMilli())
    }
    fun goalProgress(id: String,date: java.time.LocalDate? = null): GoalProgress = Goals.progress(data,clock.now(),id,date)
    fun historyGaps(start: Long,end: Long): HistoryGapReview = HistoryGaps.inspect(data,clock.now(),start,end)
    fun report(start: Long,end: Long,filter: Filter = Filter()): Report = checked.report(clock.now(),start,end,filter,data.settings.reportZone)
    fun calendar(first: java.time.LocalDate,days: Int,filter: Filter = Filter(),zone: String = data.settings.reportZone): CalendarHistory =
        checked.calendar(clock.now(),first,days,filter,zone)
    fun day(date: java.time.LocalDate,filter: Filter = Filter()): Report {
        val (start,end) = Reports.calendarBounds(date,1,data.settings.reportZone)
        return report(start,end,filter)
    }
}
