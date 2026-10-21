package dev.notai.tracker.android

import android.app.Application

/** Process/lifetime composition using existing quiet Android system cards.
 * No notification post, permission prompt, feature opt-in or DB creation at startup. */
open class TrackerApplication: Application() {
    /** Application-owned stateless rendering; a subclass can explicitly opt out or replace it. */
    protected open fun scheduleNoticeConfiguration(): ScheduleNoticeConfiguration? = ProductionReminderNotices.scheduleConfiguration()
    override fun onCreate() { super.onCreate();ScheduleNoticeBinding.initialize(this,::scheduleNoticeConfiguration);LongRecordNoticeBinding.initialize(this);CopyLifetimes.install(this);PlanSurfaces.install(this);FocusEffects.install(this) }
    override fun onTerminate() { CopyLifetimes.get(this).shutdown();super.onTerminate() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig);ScheduleNoticeBinding.refreshChannelMetadata(this)
        try { ProductionReminderNotices.longRecordChannel(this) } catch(_: Exception) { android.util.Log.w("LongRecordReminder","Channel metadata refresh unavailable; user policy retained") }
        TrackerRuntime.refreshExistingPlatform(this)
    }
}
