package dev.notai.tracker.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.notai.tracker.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Quiet existing system-card template. Creating channel metadata grants no opt-in or permission.
 * LOW follows timer_controls: no new sound, vibration, heads-up or automatic timer action. */
internal object ProductionReminderNotices {
    private fun notice(context: Context,channel: String,text: String?): Notification {
        val title = context.getString(R.string.app_name)
        val public = Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_stat_notai)
            .setContentTitle(title).setContentIntent(TimerSurfaces.open(context)).setShowWhen(false).build()
        return Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_stat_notai)
            .setColor(context.getColor(R.color.widget_amber)).setContentTitle(title).setContentText(text)
            .setContentIntent(TimerSurfaces.open(context)).setShowWhen(false).setAutoCancel(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }
    val schedule = ScheduleNoticeFactory { context,_,projection ->
        val planned = requireNotNull(projection.instant).atZone(ZoneId.of(projection.zone))
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale).format(planned)
        notice(context,AndroidScheduleDeliveryPlatform.CHANNEL,"$time (${planned.offset})")
    }
    /** Same quiet card, worded as a question, with the three answers that need no screen. The token captured
     * at post time goes with each action, so a press after the timer changed is refused, never re-targeted. */
    val longRecord = LongRecordNoticeFactory { context,ticket,snapshot ->
        val minutes = snapshot.trustedDurationMs/60_000L
        val elapsed = if(minutes >= 60) L.s(R.string.t_60e27c596a, minutes/60, (minutes%60).toString().padStart(2,'0')) else L.s(R.string.t_455a50db4a, minutes)
        val builder = Notification.Builder(context,AndroidLongRecordPlatform.CHANNEL).setSmallIcon(R.drawable.ic_stat_notai)
            .setColor(context.getColor(R.color.widget_amber)).setContentTitle(L.s(R.string.t_a683d51495))
            .setContentText(L.s(R.string.t_43f3c7181c, elapsed))
            .setContentIntent(TimerSurfaces.open(context)).setShowWhen(false).setAutoCancel(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context,AndroidLongRecordPlatform.CHANNEL).setSmallIcon(R.drawable.ic_stat_notai)
                .setContentTitle(context.getString(R.string.app_name)).setContentIntent(TimerSurfaces.open(context)).setShowWhen(false).build())
        listOf(LongRecordAction.CONTINUE to L.s(R.string.t_3f75368ab9),LongRecordAction.SNOOZE to L.s(R.string.t_fd042c9a3a),LongRecordAction.STOP to L.s(R.string.t_d4f447c130)).forEach { (action,title) ->
            builder.addAction(Notification.Action.Builder(null,title,LongRecordActions.pending(context,ticket.hash,snapshot.token,action)).build())
        }
        builder.build()
    }
    fun scheduleConfiguration() = ScheduleNoticeConfiguration(schedule,R.string.app_name,NotificationManager.IMPORTANCE_LOW)
    fun longRecordChannel(context: Context) {
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        val channel = manager.getNotificationChannel(AndroidLongRecordPlatform.CHANNEL)
            ?: NotificationChannel(AndroidLongRecordPlatform.CHANNEL,context.getString(R.string.app_name),NotificationManager.IMPORTANCE_LOW)
        // Reuse the actual channel, including user-blocked importance, sound and vibration.
        channel.name = context.getString(R.string.app_name)
        manager.createNotificationChannel(channel)
        check(manager.getNotificationChannel(channel.id) != null)
    }
}
