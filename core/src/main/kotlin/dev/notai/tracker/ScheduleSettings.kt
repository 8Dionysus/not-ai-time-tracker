package dev.notai.tracker

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** Grouping preferences, independent of fact reportZone and each series' civil-time policy. */
@Serializable data class ScheduleDayPolicy(val zonePolicy: ScheduleZonePolicy = ScheduleZonePolicy.DEVICE_LOCAL,
    val zoneId: String? = null,val boundary: String = "00:00")
@Serializable data class ScheduleSettings(val day: ScheduleDayPolicy = ScheduleDayPolicy(),val quietShadeEnabled: Boolean = false)
data class ScheduleDayView(val date: LocalDate,val zone: String,val boundary: String)
data class ScheduleViewState(val token: Token,val controlBarrier: String,val asOf: Long,val day: ScheduleDayView,
    val settings: ScheduleSettings,val surfaces: SurfaceSettings,val deviceZone: String,val occurrences: List<ScheduleProjection>,val nextDayBoundary: Long)

internal fun ScheduleSettings.canonical(): ScheduleSettings {
    validateScheduleSettings(this)
    return copy(day = day.copy(boundary = scheduleTime(day.boundary).toString()))
}
internal fun validateScheduleSettings(value: ScheduleSettings) {
    fun invalid(field: String,raw: String,rule: String): Nothing = throw CoreError(ErrorCode.INVALID_INPUT,"Invalid schedule day policy",
        arguments = mapOf("value" to ErrorArgument.Text(raw),"rule" to ErrorArgument.Text(rule)),
        fieldPath = "settings.schedule.day.$field",reason = ErrorReason.SCHEDULE_DAY_INVALID)
    val day = value.day
    try { scheduleTime(day.boundary) } catch(_: CoreError) { invalid("boundary",day.boundary,"INVALID_LOCAL_TIME") }
    if(day.zonePolicy == ScheduleZonePolicy.FIXED) {
        val zone = day.zoneId ?: invalid("zoneId","","FIXED_ZONE_REQUIRED")
        try { scheduleZone(zone) } catch(_: CoreError) { invalid("zoneId",zone,"INVALID_IANA_ZONE") }
    } else if(day.zoneId != null) invalid("zoneId",day.zoneId,"DEVICE_ZONE_NOT_ALLOWED")
}
internal fun ScheduleSettings.resolveDay(now: Instant,deviceZone: ZoneId): ScheduleDayView {
    validateScheduleSettings(this)
    val zone = if(day.zonePolicy == ScheduleZonePolicy.FIXED) scheduleZone(day.zoneId!!) else deviceZone
    return ScheduleDayView(ScheduleDayResolver.date(now,zone,scheduleTime(day.boundary)),zone.id,scheduleTime(day.boundary).toString())
}
fun Tracker.scheduleDay(deviceZone: ZoneId): ScheduleDayView = data().settings.schedule.resolveDay(Instant.ofEpochMilli(clock.now().also(::validateMoment).wall),deviceZone)
/** Civil boundary, never now+24h; same gap/fold policy as day grouping. */
fun ScheduleSettings.nextDayBoundary(now: Instant,deviceZone: ZoneId): Instant {
    val day = resolveDay(now,deviceZone)
    return resolveScheduleTime(day.date.plusDays(1),scheduleTime(day.boundary),scheduleZone(day.zone)).first
}
