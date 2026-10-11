package dev.notai.tracker

/** One immutable committed revision. UI may retain this until the next emission.
 * Display ticks materialize only the current session; no storage access or history scan.
 * Reports are explicit operations over this revision, at one captured moment per call.
 */
class TrackerView internal constructor(private val checked: CheckedState,private val clock: Clock,private val choiceCache: ChoiceCache) {
    private val choiceView by lazy { ChoiceView(data,choiceCache,::snapshot) }
    fun choices(): ChoiceView = choiceView
    val data get() = checked.data
    fun snapshot(): Snapshot = checked.snapshot(clock.now())
    fun goalProgress(id: String,date: java.time.LocalDate? = null): GoalProgress = Goals.progress(data,clock.now(),id,date)
    fun report(start: Long,end: Long,filter: Filter = Filter()): Report = Reports.report(data,clock.now(),start,end,filter)
    fun calendar(first: java.time.LocalDate,days: Int,filter: Filter = Filter(),zone: String = data.settings.reportZone): CalendarHistory =
        Reports.calendar(data,clock.now(),first,days,filter,zone)
    fun day(date: java.time.LocalDate,filter: Filter = Filter()): Report = Reports.day(data,clock.now(),date,filter)
}
