package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import java.time.ZoneId

/** Device-local intent. Backups retain the explicit zone that grouped their history (JSON15). */
class ReportZonePreference(context: Context,private val tracker: Tracker,newStore: Boolean,
    private val deviceZone: () -> String = { ZoneId.systemDefault().id }) {
    private val prefs = context.applicationContext.getSharedPreferences("report-zone",Context.MODE_PRIVATE)
    init {
        // An existing dataset without this preference keeps its fixed report zone until an explicit AUTO choice.
        if(!prefs.contains("automatic")) save(newStore)
    }
    val automatic: Boolean get() = prefs.getBoolean("automatic",false)
    private fun save(value: Boolean) {
        ensure(prefs.edit().putBoolean("automatic",value).commit(),"Report zone preference could not be saved",ErrorCode.STORAGE)
    }
    @Synchronized fun setAutomatic(expected: Token,value: Boolean): Tracker.Change {
        val current = tracker.data().settings
        val zone = if(value) deviceZone() else current.reportZone
        val changed = tracker.configureReportingDay(expected,ReportingDayPolicy(zone,current.reportDayStartMinute))
        save(value)
        return changed
    }
    @Synchronized fun setFixed(expected: Token,zone: String): Tracker.Change {
        val current = tracker.data().settings
        val changed = tracker.configureReportingDay(expected,ReportingDayPolicy(zone,current.reportDayStartMinute))
        save(false)
        return changed
    }
    /** Startup/resume/timezone event: no revision when the explicit zone already matches the device. */
    @Synchronized fun reconcile(): Tracker.Change? {
        if(!automatic) return null
        val view = tracker.view()
        val data = view.data
        val zone = deviceZone()
        if(data.settings.reportZone == zone) return null
        return tracker.configureReportingDay(view.snapshot().token,ReportingDayPolicy(zone,data.settings.reportDayStartMinute))
    }
}
