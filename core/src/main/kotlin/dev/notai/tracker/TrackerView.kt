package dev.notai.tracker

/** One immutable committed revision. UI may retain this until the next emission.
 * Display ticks materialize only the current session; no storage access or history scan.
 * Reports are explicit operations over this revision, at one captured moment per call.
 */
class TrackerView internal constructor(val data: Dataset,private val clock: Clock) {
    private val currentSession = data.sessions.singleOrNull { it.status != SessionStatus.STOPPED }
    private val timer = data.copy(sessions = listOfNotNull(currentSession),
        segments = if(currentSession == null) emptyList() else data.segments.filter { it.sessionId == currentSession.id })
    fun snapshot(): Snapshot = snapshot(timer,clock.now())
    fun report(start: Long,end: Long,filter: Filter = Filter()): Report = Reports.report(data,clock.now(),start,end,filter)
    fun day(date: java.time.LocalDate,filter: Filter = Filter()): Report = Reports.day(data,clock.now(),date,filter)
}
