package dev.notai.tracker.android

import android.app.*
import android.content.res.Configuration
import dev.notai.tracker.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProductionReminderNoticesTest {
    @Test fun minimalCardUsesResolvedPlannedTimeAndPrivateExplicitOpenWithoutTimerActions() {
        val app=RuntimeEnvironment.getApplication();val date=LocalDate.of(2026,10,17)
        val planned=date.atTime(10,0).atZone(ZoneId.of("Europe/Moscow")).toInstant()
        val key=OccurrenceKey("fixture","2026-10-17")
        val projection=ScheduleProjection(key,1,null,date,date,LocalTime.of(10,0),Classification(name="private classification"),"Europe/Moscow",planned,DstResolution.EXACT,planned.minusSeconds(60),null,OccurrenceState.SCHEDULED)
        val ticket=ScheduleReminderTicket("fixture","barrier",key,1,null,projection.zone,projection.reminderInstant!!.toEpochMilli())
        val notice=ProductionReminderNotices.schedule.create(app,ticket,projection)
        assertEquals(AndroidScheduleDeliveryPlatform.CHANNEL,notice.channelId)
        assertEquals(app.getString(R.string.app_name),notice.extras.getCharSequence(Notification.EXTRA_TITLE))
        val text=notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("10:00"));assertTrue(text.contains("+03:00"));assertFalse(text.contains("private classification"))
        assertEquals(Notification.VISIBILITY_PRIVATE,notice.visibility);assertNotNull(notice.publicVersion)
        assertNull(notice.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT));assertTrue(notice.actions.isNullOrEmpty())
        assertTrue(notice.contentIntent.isImmutable)
        val intent=shadowOf(notice.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name,intent.component?.className)
        assertTrue(intent.extras==null || intent.extras!!.isEmpty)
    }
    @Test fun longRecordConfigurationRefreshPreservesActualBlockedChannelPolicy() {
        val app=RuntimeEnvironment.getApplication() as TrackerApplication
        val manager=app.getSystemService(NotificationManager::class.java)
        val channel=manager.getNotificationChannel(AndroidLongRecordPlatform.CHANNEL)
        channel.importance=NotificationManager.IMPORTANCE_NONE;channel.setSound(null,null);channel.enableVibration(false);channel.setShowBadge(false)
        manager.deleteNotificationChannel(channel.id);manager.createNotificationChannel(channel)
        val binding=LongRecordNoticeBinding.capture()
        app.onConfigurationChanged(Configuration(app.resources.configuration))
        val after=manager.getNotificationChannel(channel.id)
        assertEquals(NotificationManager.IMPORTANCE_NONE,after.importance);assertNull(after.sound)
        assertFalse(after.shouldVibrate());assertFalse(after.canShowBadge());assertSame(binding,LongRecordNoticeBinding.capture())
        assertEquals(app.getString(R.string.app_name),after.name)
    }
}
