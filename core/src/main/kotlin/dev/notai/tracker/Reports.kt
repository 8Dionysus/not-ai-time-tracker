package dev.notai.tracker

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@kotlinx.serialization.Serializable enum class FacetMatch { ALL, ANY }

@kotlinx.serialization.Serializable data class Filter(val cardId: String? = null, val activityTypeId: String? = null,
    val details: Map<String,String> = emptyMap(), val tags: Set<String> = emptySet(), val facets: List<Facet> = emptyList(),
    val facetMatch: FacetMatch = FacetMatch.ALL) {
    fun matches(c: Classification) = (cardId == null || c.cardId == cardId) &&
        (activityTypeId == null || activityTypeId in c.ancestorPathAtWrite) && details.all { c.details[it.key] == it.value } && c.tags.containsAll(tags) &&
        (facets.isEmpty() || if (facetMatch == FacetMatch.ALL) facets.all { it in c.facets } else facets.any { it in c.facets })
}
data class ReportRow(val segmentId: String, val sessionId: String, val start: Long, val end: Long,
    val durationMs: Long, val selection: Classification, val note: String?)
data class QuantityKey(val fieldId: String,val meaningVersion: Int,val unit: String,val scope: Scope)
data class Report(val start: Long, val end: Long, val asOf: Long, val zone: String,
    val rows: List<ReportRow>, val unresolved: List<Segment>, val measurements: List<Measurement>,
    val recordedMs: Long, val calendarDays: Long, val recordedDays: Set<LocalDate>, val incomplete: Boolean, val metricDefinitions: List<Metric> = emptyList()) {
    /** Whole-target quantities, not apportioned to this report window. Scope stays separate. */
    fun quantityTotals(): Map<QuantityKey,Decimal> = measurements.distinctBy { it.id }.filter { m ->
        metricDefinitions.find { it.id == m.fieldId && it.meaningVersion == m.meaningVersion }?.aggregation != Aggregation.BY_TARGET
    }
        .groupBy { QuantityKey(it.fieldId,it.meaningVersion,it.unit,it.scope) }
        .mapValues { (_,values) -> values.fold(Decimal.ZERO) { total,m -> total + m.value } }
    val hasRecords get() = rows.isNotEmpty()
    fun byCard(): Map<String?, Long> = rows.groupBy { it.selection.cardId }.mapValues { (_,v) -> v.sumExact { it.durationMs } }
    /** Parent totals overlap with their children: do not sum this map into an overall total. */
    fun byType(): Map<String?,Long> = rows.distinctBy { it.segmentId }.flatMap { row ->
        row.selection.ancestorPathAtWrite.ifEmpty { listOf(null) }.map { it to row }
    }.groupBy({ it.first },{ it.second }).mapValues { (_,segments) -> segments.sumExact { it.durationMs } }
    fun byFacet(axisId: String): Map<Facet?,Long> = rows.distinctBy { it.segmentId }.flatMap { row ->
        row.selection.facets.filter { it.axisId == axisId }.ifEmpty { listOf(null) }.map { it to row }
    }.groupBy({ it.first },{ it.second }).mapValues { (_,segments) -> segments.sumExact { it.durationMs } }
    fun byDetail(key: String): Map<String?, Long> = rows.groupBy { it.selection.details[key] }.mapValues { (_,v) -> v.sumExact { it.durationMs } }
}
data class Comparison(val current: Report, val previous: Report, val absoluteDifferenceMs: Long, val percent: Double?)
object Reports {
    internal fun indexed(d: Dataset,index: ReportIndex,now: Moment,start: Long,end: Long,filter: Filter,zone: String): Report {
        validateMoment(now);ensure(start >= 0 && end > start && end <= MAX_TIME,"Invalid report window",ErrorCode.INVALID_INTERVAL)
        return observedReport(d,now,start,end,ZoneId.of(zone),index.observed(now,start,end,filter),index.notes,index)
    }
    fun report(d: Dataset, now: Moment, start: Long, end: Long, filter: Filter = Filter(), zone: String = d.settings.reportZone): Report {
        validateMoment(now)
        ensure(start >= 0 && end > start && end <= MAX_TIME, "Invalid report window", ErrorCode.INVALID_INTERVAL)
        val zoneId = ZoneId.of(zone)
        val observed = d.segments.distinctBy { it.id }.filter { filter.matches(it.selection) }.map { materialize(it,now) }
        return observedReport(d,now,start,end,zoneId,observed)
    }
    private fun observedReport(d: Dataset,now: Moment,start: Long,end: Long,zoneId: ZoneId,observed: List<Segment>,
        capturedNotes: Map<String,String?>? = null,index: ReportIndex? = null): Report {
        val zone = zoneId.id
        val notes = capturedNotes ?: d.sessions.associate { it.id to it.note }
        val rows = windowRows(observed,now,start,end,notes)
        val sessions = rows.map { it.sessionId }.toSet(); val segments = rows.map { it.segmentId }.toSet()
        val measurements = index?.measurements(sessions,segments) ?: d.measurements.filter { if (it.scope == Scope.SESSION) it.targetId in sessions else it.targetId in segments }
        val days = mutableSetOf<LocalDate>()
        rows.forEach { row ->
            var day = Instant.ofEpochMilli(row.start).atZone(zoneId).toLocalDate()
            val last = Instant.ofEpochMilli(row.end-1).atZone(zoneId).toLocalDate()
            while (day <= last) { days += day; day = day.plusDays(1) }
        }
        val first = Instant.ofEpochMilli(start).atZone(zoneId).toLocalDate()
        val last = Instant.ofEpochMilli(end-1).atZone(zoneId).toLocalDate()
        return Report(start,end,now.wall,zone,frozenList(rows.map { it.copy(selection = it.selection.frozen()) }),
            frozenList(observed.filter { it.uncertain }.map { it.copy(selection = it.selection.frozen()) }),frozenList(measurements),
            rows.sumExact { it.durationMs },java.time.temporal.ChronoUnit.DAYS.between(first,last)+1,frozenSet(days),end > now.wall,
            frozenList(d.metrics.map { it.copy(scopes = frozenSet(it.scopes),researchOrigins = frozenList(it.researchOrigins)) }))
    }
    private fun windowRows(observed: List<Segment>,now: Moment,start: Long,end: Long,notes: Map<String,String?>): List<ReportRow> =
        observed.filterNot { it.uncertain }.mapNotNull { s ->
            val finish = s.end ?: s.observedUntil ?: now.wall
            val from = maxOf(start,s.start); val to = minOf(end,finish,now.wall)
            if (to <= from || finish <= s.start) null else ReportRow(s.id,s.sessionId,from,to,
                proportional(s.durationMs,to-s.start,finish-s.start)-proportional(s.durationMs,from-s.start,finish-s.start),
                s.selection,notes[s.sessionId])
        }.sortedWith(compareBy({it.start},{it.segmentId}))
    /** One physical observation, bounded calendar page; DST boundaries come from the explicit zone. */
    fun calendar(d: Dataset,now: Moment,first: LocalDate,days: Int,filter: Filter = Filter(),zone: String = d.settings.reportZone): CalendarHistory {
        ensure(days in 1..62,"Calendar page must contain 1..62 days",ErrorCode.INVALID_INTERVAL)
        validateMoment(now)
        val (start,end) = calendarBounds(first,days,zone)
        ensure(start >= 0 && end > start && end <= MAX_TIME,"Calendar window is empty or out of range",ErrorCode.INVALID_INTERVAL)
        val zoneId = ZoneId.of(zone)
        val observed = d.segments.distinctBy { it.id }.filter { filter.matches(it.selection) }.map { materialize(it,now) }
        return calendarObserved(d,now,first,days,zone,start,end,zoneId,observed)
    }
    internal fun indexedCalendar(d: Dataset,index: ReportIndex,now: Moment,first: LocalDate,days: Int,filter: Filter,zone: String): CalendarHistory {
        ensure(days in 1..62,"Calendar page must contain 1..62 days",ErrorCode.INVALID_INTERVAL)
        validateMoment(now)
        val (start,end) = calendarBounds(first,days,zone)
        ensure(start >= 0 && end > start && end <= MAX_TIME,"Calendar window is empty or out of range",ErrorCode.INVALID_INTERVAL)
        return calendarObserved(d,now,first,days,zone,start,end,ZoneId.of(zone),index.observed(now,start,end,filter),index)
    }
    private fun calendarObserved(d: Dataset,now: Moment,first: LocalDate,days: Int,zone: String,start: Long,end: Long,
        zoneId: ZoneId,observed: List<Segment>,index: ReportIndex? = null): CalendarHistory {
        val whole = observedReport(d,now,start,end,zoneId,observed,index?.notes,index)
        val notes = index?.notes ?: d.sessions.associate { it.id to it.note }
        val pages = (0 until days).map { offset ->
            val date = first.plusDays(offset.toLong()); val (a,b) = calendarBounds(date,1,zone)
            ensure(b > a,"Calendar page contains a skipped local date: $date in $zone",ErrorCode.INVALID_INTERVAL)
            val rows = windowRows(observed,now,a,b,notes)
            // Uncertain attribution and whole-target quantities are exposed once at the window level.
            CalendarDay(date,a,b,frozenList(rows),rows.sumExact { it.durationMs },b > now.wall)
        }
        return CalendarHistory(token(d),whole,frozenList(pages))
    }
    fun day(d: Dataset,now: Moment,date: LocalDate,filter: Filter = Filter(),zone: String = d.settings.reportZone): Report {
        val (start,end) = calendarBounds(date,1,zone)
        return report(d,now,start,end,filter,zone)
    }
    /** Explicit equal elapsed-time windows. Calendar-day windows have a separate DST-aware operation. */
    fun compare(d: Dataset,now: Moment,currentStart: Long,currentEnd: Long,previousStart: Long,previousEnd: Long,filter: Filter = Filter()): Comparison {
        val a = report(d,now,currentStart,currentEnd,filter); val b = report(d,now,previousStart,previousEnd,filter)
        ensure(currentEnd-currentStart == previousEnd-previousStart, "Comparison windows must have equal physical duration")
        return comparison(a,b)
    }
    /** Same number of local calendar days, even when the windows contain 23/25-hour DST days.
     * Incomplete windows are labeled; values are never extrapolated or silently scaled.
     */
    fun compareCalendarDays(d: Dataset,now: Moment,currentStart: LocalDate,previousStart: LocalDate,days: Int,
        filter: Filter = Filter(),zone: String = d.settings.reportZone): Comparison {
        val (a0,a1) = calendarBounds(currentStart,days,zone)
        val (b0,b1) = calendarBounds(previousStart,days,zone)
        return comparison(report(d,now,a0,a1,filter,zone),report(d,now,b0,b1,filter,zone))
    }
    internal fun calendarBounds(start: LocalDate,days: Int,zone: String): Pair<Long,Long> {
        ensure(days > 0,"Calendar day count must be positive",ErrorCode.INVALID_INTERVAL)
        return try {
            val z = ZoneId.of(zone)
            start.atStartOfDay(z).toInstant().toEpochMilli() to start.plusDays(days.toLong()).atStartOfDay(z).toInstant().toEpochMilli()
        } catch (_: java.time.DateTimeException) { fail(ErrorCode.INVALID_INTERVAL,"Calendar window out of range") }
        catch (_: ArithmeticException) { fail(ErrorCode.INVALID_INTERVAL,"Calendar window out of range") }
    }
    private fun comparison(a: Report,b: Report): Comparison {
        val delta = a.recordedMs-b.recordedMs // both nonnegative, so the difference fits Long
        return Comparison(a,b,delta,if (b.recordedMs == 0L) null else 100.0*delta/b.recordedMs)
    }
    private fun proportional(total: Long,numerator: Long,denominator: Long): Long =
        BigInteger.valueOf(total).multiply(BigInteger.valueOf(numerator)).divide(BigInteger.valueOf(denominator)).longValueExact()
}

/** Constructed only for a validated immutable revision. Raw Dataset APIs retain their full oracle
 * path; no mutable/global cache can make a caller's edits disappear. */
internal class ReportIndex(private val data: Dataset) {
    val notes = data.sessions.associate { it.id to it.note }
    // Validated certain intervals do not overlap, so end order equals start order.
    private val closed = data.segments.mapIndexed { i,s -> i to s }.filter { it.second.end != null && !it.second.uncertain }.sortedBy { it.second.start }
    private val other = data.segments.mapIndexed { i,s -> i to s }.filter { it.second.end == null || it.second.uncertain }
    private val sessionMeasurements = data.measurements.mapIndexed { i,m -> i to m }.filter { it.second.scope == Scope.SESSION }.groupBy { it.second.targetId }
    private val segmentMeasurements = data.measurements.mapIndexed { i,m -> i to m }.filter { it.second.scope == Scope.SEGMENT }.groupBy { it.second.targetId }
    fun observed(now: Moment,start: Long,end: Long,filter: Filter): List<Segment> {
        var lo = 0;var hi = closed.size
        while(lo < hi) { val mid = (lo+hi)/2;if(closed[mid].second.end!! <= start) lo = mid+1 else hi = mid }
        val chosen = ArrayList<Pair<Int,Segment>>()
        while(lo < closed.size && closed[lo].second.start < end) { val row = closed[lo++];if(filter.matches(row.second.selection)) chosen += row }
        chosen += other.filter { filter.matches(it.second.selection) }
        return chosen.sortedBy { it.first }.map { materialize(it.second,now) }
    }
    fun measurements(sessions: Set<String>,segments: Set<String>): List<Measurement> =
        (sessions.flatMap { sessionMeasurements[it].orEmpty() } + segments.flatMap { segmentMeasurements[it].orEmpty() })
            .sortedBy { it.first }.map { it.second }
}
internal fun <T> Iterable<T>.sumExact(errorCode: ErrorCode = ErrorCode.INVALID_INTERVAL,value: (T)->Long): Long = try {
    fold(0L) { a,b -> Math.addExact(a,value(b)) }
} catch (_: ArithmeticException) { fail(errorCode,"Total exceeds supported integer range") }

enum class CsvMode { EXACT, SPREADSHEET_SAFE }
object Csv {
    /** Segment rows use exactly Report's totals. Quantities get separate rows, once per target, never per day/minute. */
    fun export(report: Report,mode: CsvMode = CsvMode.SPREADSHEET_SAFE): String {
        fun cell(value: Any?): String {
            var s = value?.toString() ?: ""
            if (mode == CsvMode.SPREADSHEET_SAFE && s.trimStart().firstOrNull() in listOf('=','+','-','@')) s = "'"+s
            return "\"" + s.replace("\"","\"\"") + "\""
        }
        val rows = mutableListOf(listOf("type","id","session_or_target_id","start_ms","end_ms","duration_ms","card_id","name","activity_type_id","details","tags","note","field_id","meaning_version","unit","value","scope","zone","ancestor_path","facets"))
        report.rows.forEach { r -> rows += listOf("segment",r.segmentId,r.sessionId,r.start.toString(),r.end.toString(),r.durationMs.toString(),
            r.selection.cardId ?: "",r.selection.name ?: "",r.selection.activityTypeId ?: "",codec.encodeToString(r.selection.details),
            codec.encodeToString(r.selection.tags),r.note ?: "","","","","","",report.zone,codec.encodeToString(r.selection.ancestorPathAtWrite),codec.encodeToString(r.selection.facets)) }
        report.measurements.forEach { m -> rows += listOf("measurement",m.id,m.targetId,"","","","","","","","","",m.fieldId,m.meaningVersion.toString(),m.unit,m.value.toString(),m.scope.name,report.zone,"","") }
        // Explicitly expose excluded uncertain data without inventing period attribution or adding it to totals.
        report.unresolved.forEach { s -> rows += listOf("unresolved",s.id,s.sessionId,"","","","",s.selection.name ?: "","","","","","","","","","",report.zone,codec.encodeToString(s.selection.ancestorPathAtWrite),codec.encodeToString(s.selection.facets)) }
        return rows.joinToString("\r\n",postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    }
}
