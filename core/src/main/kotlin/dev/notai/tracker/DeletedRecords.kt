package dev.notai.tracker

import kotlinx.serialization.Serializable

/** Recoverable facts, stored in the same transaction as removal from live history.
 * No expiry, purge command or ephemeral picker requests are retained here. */
@Serializable data class DeletedRecord(val batchId: String,val deletedAt: Long,val deletedRevision: Long,
    val session: Positioned<Session>,val segments: List<Positioned<Segment>>,
    val measurements: List<Positioned<Measurement>>,val links: List<Positioned<SubjectSessionLink>>,
    val sets: List<Positioned<ExerciseSet>>,val quantities: List<Positioned<EventQuantity>>,
    val distanceTimes: List<Positioned<DistanceTimeInput>>) {
    val sessionId get() = session.value.id
    val recordedMs get() = segments.sumExact { it.value.durationMs }
}

internal fun <T> retainedRows(rows: List<T>,keep: (T)->Boolean) = rows.mapIndexedNotNull { i,v -> if(keep(v)) Positioned(i,v) else null }
internal fun <T> restoreRows(live: List<T>,saved: List<Positioned<T>>): List<T> = live.toMutableList().apply {
    saved.sortedBy { it.position }.forEach { add(it.position.coerceAtMost(size),it.value) }
}
internal fun DeletedRecord.frozen(capture: (Classification)->Classification) = copy(
    session = session.copy(value = session.value.copy(selection = capture(session.value.selection),importOrigin = session.value.importOrigin?.frozen())),
    segments = frozenList(segments.map { it.copy(value = it.value.copy(selection = capture(it.value.selection))) }),
    measurements = frozenList(measurements),links = frozenList(links),sets = frozenList(sets),
    quantities = frozenList(quantities),distanceTimes = frozenList(distanceTimes))

/** Archive intervals may now overlap live history. Validate archived graphs independently;
 * restoration still goes through ordinary live validation and refuses a collision losslessly. */
internal fun validateDeletedRecords(d: Dataset) {
    if(d.deletedRecords.isEmpty()) return
    fun ids(live: List<String>,saved: List<String>) = ensure((live+saved).distinct().size == live.size+saved.size,"Deleted identity collides with live or archived facts")
    ids(d.sessions.map { it.id },d.deletedRecords.map { it.sessionId })
    ids(d.segments.map { it.id },d.deletedRecords.flatMap { it.segments.map { r -> r.value.id } })
    ids(d.measurements.map { it.id },d.deletedRecords.flatMap { it.measurements.map { r -> r.value.id } })
    ids(d.subjects.links.map { it.id },d.deletedRecords.flatMap { it.links.map { r -> r.value.id } })
    ids(d.subjects.sets.map { it.id },d.deletedRecords.flatMap { it.sets.map { r -> r.value.id } })
    ids(d.subjects.quantities.map { it.id },d.deletedRecords.flatMap { it.quantities.map { r -> r.value.id } })
    ids(d.subjects.distanceTimes.map { it.id },d.deletedRecords.flatMap { it.distanceTimes.map { r -> r.value.id } })
    for(r in d.deletedRecords) {
        ensure(r.batchId.isNotBlank() && r.deletedAt in 0..MAX_TIME && r.deletedRevision >= 1,"Invalid deletion metadata")
        ensure(r.session.value.status == SessionStatus.STOPPED,"Only completed records belong in Deleted")
        ensure((listOf(r.session.position)+r.segments.map { it.position }+r.measurements.map { it.position }+
            r.links.map { it.position }+r.sets.map { it.position }+r.quantities.map { it.position }+r.distanceTimes.map { it.position }).all { it >= 0 },"Invalid deleted row position")
        validate(Dataset(types = d.types,cards = d.cards,axes = d.axes,metrics = d.metrics,units = d.units,settings = d.settings,
            sessions = listOf(r.session.value),segments = r.segments.map { it.value },measurements = r.measurements.map { it.value },
            subjects = d.subjects.copy(links = r.links.map { it.value },sets = r.sets.map { it.value },
                quantities = r.quantities.map { it.value },distanceTimes = r.distanceTimes.map { it.value })))
    }
}

internal fun Dataset.validateDeletedSnapshot(asOf: Long) {
    for(r in deletedRecords) {
        ensure(r.deletedAt <= asOf && r.segments.all { row -> row.value.let { s -> s.anchor == null && s.end != null &&
            (s.uncertain || s.isEmptyInterval() || (s.start <= asOf && s.end <= asOf)) &&
            (s.observedUntil == null || s.observedUntil <= asOf || s.uncertain) &&
            (s.manualConfirmation == null || s.manualConfirmation.confirmedAt <= asOf) } } &&
            r.sets.all { it.value.recordedAt <= asOf } && r.quantities.all { it.value.recordedAt <= asOf },
            "Deleted record after snapshot",ErrorCode.UNSUPPORTED_IMPORT)
    }
}
