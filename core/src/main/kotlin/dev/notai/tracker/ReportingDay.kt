package dev.notai.tracker

import kotlinx.serialization.Serializable
import java.time.*

/** Civil report-day labels, independent of ScheduleDayPolicy. Never edits stored timestamps.
 * Gaps shift forward by the gap duration, overlaps use the earlier offset.
 * Boundaries are resolved separately each day; elapsed days need not be 24 hours. */
@Serializable data class ReportingDayPolicy(val zone: String = "UTC",val startMinute: Int = 0) {
    fun validate() {
        ensure(startMinute in 0..1439,"Report day start must be 0..1439")
        try { ZoneId.of(zone) } catch(_: DateTimeException) { fail(ErrorCode.INVALID_INPUT,"Invalid report zone") }
    }
    fun boundary(date: LocalDate): Long {
        validate()
        return try { date.atTime(LocalTime.of(startMinute/60,startMinute%60)).atZone(ZoneId.of(zone)).toInstant().toEpochMilli() }
        catch(_: Exception) { fail(ErrorCode.INVALID_INTERVAL,"Report boundary out of range") }
    }
    fun dateAt(epochMs: Long): LocalDate {
        validate()
        val civil = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zone)).toLocalDate()
        // Compare instants, not local clock times: DST folds can cross the local start twice.
        return if(epochMs < boundary(civil)) civil.minusDays(1) else civil
    }
    fun bounds(first: LocalDate,days: Int): Pair<Long,Long> {
        ensure(days > 0,"Calendar day count must be positive",ErrorCode.INVALID_INTERVAL)
        return try { boundary(first) to boundary(first.plusDays(days.toLong())) }
        catch(e: CoreError) { throw e }
        catch(_: Exception) { fail(ErrorCode.INVALID_INTERVAL,"Report window out of range") }
    }
}
val Settings.reportDayPolicy get() = ReportingDayPolicy(reportZone,reportDayStartMinute)
