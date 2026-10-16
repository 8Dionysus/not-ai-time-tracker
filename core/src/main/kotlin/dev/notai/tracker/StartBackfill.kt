package dev.notai.tracker

data class PreviousStoppedEnd(val sessionId: String,val segmentId: String,val end: Long)
enum class StartBackfillWarning { USER_CONFIRMED_TIME_ADDED }

/** A proposal, never evidence that the device observed the earlier interval. */
class StartBackfillPreview internal constructor(internal val owner: Tracker,val expected: Token,
    val sessionId: String,val originalStart: Long,val proposedSegment: Segment,
    val previousStoppedEnd: PreviousStoppedEnd?,val asOf: Moment) {
    val addedMs get() = proposedSegment.durationMs
    val requiredWarning get() = StartBackfillWarning.USER_CONFIRMED_TIME_ADDED
}

internal object StartBackfill {
    private fun first(d: Dataset,n: Moment,sessionId: String): Segment {
        validateMoment(n)
        val active = current(d) ?: fail(ErrorCode.INVALID_INPUT,"No current session")
        ensure(active.id == sessionId,"Current session changed",ErrorCode.STALE)
        ensure(d.focus == null,"Finish Focus before filling a missing start")
        val state = snapshot(d,n)
        ensure(active.status != SessionStatus.RECOVERY && state.mode != Mode.RECOVERY && !state.calendarUncertain,
            "Resolve uncertain time before filling a missing start",ErrorCode.RECOVERY_REQUIRED)
        ensure(active.status in setOf(SessionStatus.RUNNING,SessionStatus.PAUSED),"Only an ordinary current session can have its start filled")
        return d.segments.filter { it.sessionId == sessionId }.minWithOrNull(compareBy<Segment> { it.start }.thenBy { it.id })
            ?: fail(ErrorCode.INVALID_INPUT,"Current session has no interval")
    }
    fun previous(d: Dataset,n: Moment,sessionId: String): PreviousStoppedEnd {
        val before = first(d,n,sessionId).start
        val stopped = d.sessions.filter { it.status == SessionStatus.STOPPED }.map { it.id }.toSet()
        val segment = d.segments.filter { it.sessionId in stopped && !it.uncertain && it.end != null && it.end <= before }
            .maxWithOrNull(compareBy<Segment> { it.end }.thenBy { it.id })
            ?: fail(ErrorCode.INVALID_INPUT,"No previous certain stopped interval")
        return PreviousStoppedEnd(segment.sessionId,segment.id,segment.end!!)
    }
    fun segment(d: Dataset,n: Moment,sessionId: String,start: Long,id: String = newId()): Segment {
        val first = first(d,n,sessionId)
        ensure(start >= 0 && start < first.start && first.start <= n.wall,"Choose a positive past gap before the current session",ErrorCode.INVALID_INTERVAL)
        val conflict = d.segments.firstOrNull { it.sessionId != sessionId && it.start < first.start &&
            (it.end ?: it.observedUntil ?: it.start) > start }
        if(conflict != null) throw CoreError(if(conflict.uncertain) ErrorCode.RECOVERY_REQUIRED else ErrorCode.OVERLAP,
            "The proposed start crosses an existing interval",arguments = mapOf("segmentId" to ErrorArgument.Identifier(conflict.id)),fieldPath = "startBackfill.start")
        return Segment(id,sessionId,start,first.start,first.start-start,first.selection.frozen(),
            manualConfirmation = ManualTimeConfirmation(n.wall))
    }
}

/** Also inspect retained transfer payloads before accepting a legacy storage/archive version. */
fun Dataset.hasManualTimeConfirmation(): Boolean = manualTimeConfirmations().any()
internal fun Dataset.manualTimeConfirmations(): Sequence<ManualTimeConfirmation> = sequence {
    suspend fun SequenceScope<ManualTimeConfirmation>.content(c: TransferContent?) {
        c?.segments?.forEach { it.manualConfirmation?.let { marker -> yield(marker) } }
    }
    segments.forEach { it.manualConfirmation?.let { marker -> yield(marker) } }
    transfer.origins.forEach { content(it.localGroup) }
    transfer.operations.forEach { op -> op.groups.forEach {
        content(it.group.content);content(it.inserted);content(it.previousOrigin?.localGroup)
    } }
}
