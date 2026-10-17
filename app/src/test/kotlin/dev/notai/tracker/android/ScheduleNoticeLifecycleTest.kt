package dev.notai.tracker.android

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Application lifecycle and channel policy in Robolectric; not production renderer/OS delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScheduleNoticeLifecycleTest {
    class ConfiguredApplication: TrackerApplication() {
        override fun scheduleNoticeConfiguration() = fixtureConfiguration()
    }
    companion object {
        private val renderer = ScheduleNoticeFactory { c,_,_ ->
            Notification.Builder(c,AndroidScheduleDeliveryPlatform.CHANNEL).setSmallIcon(android.R.drawable.ic_menu_today).build()
        }
        private fun fixtureConfiguration() = ScheduleNoticeConfiguration(renderer,R.string.app_name,NotificationManager.IMPORTANCE_DEFAULT)
        private fun runtimeAbsent() = TrackerRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null) == null
    }
    @Test @Config(application = TrackerApplication::class)
    fun defaultApplicationBindsQuietProductionCardsWithoutOpeningOrCreatingStore() {
        val app = (RuntimeEnvironment.getApplication() as TrackerApplication)
        assertEquals(ScheduleNoticeBindingState.BOUND,ScheduleNoticeBinding.state)
        assertSame(ProductionReminderNotices.schedule,ScheduleNoticeBinding.factory)
        assertSame(ProductionReminderNotices.longRecord,LongRecordNoticeBinding.factory)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW,manager.getNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW,manager.getNotificationChannel(AndroidLongRecordPlatform.CHANNEL).importance)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.checkSelfPermission("android.permission.POST_NOTIFICATIONS"))
        assertFalse(DatabaseFiles.exists(app));assertTrue(runtimeAbsent())
    }
    @Test @Config(application = ConfiguredApplication::class)
    fun configuredApplicationBindsChannelAndFactoryBeforeAnyRuntimeAcquisition() {
        val app = (RuntimeEnvironment.getApplication() as ConfiguredApplication)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL)
        assertNotNull(channel);assertEquals(app.getString(R.string.app_name),channel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,channel.importance)
        assertEquals(ScheduleNoticeBindingState.BOUND,ScheduleNoticeBinding.state);assertSame(renderer,ScheduleNoticeBinding.factory)
        assertFalse(DatabaseFiles.exists(app));assertTrue(runtimeAbsent())
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.checkSelfPermission("android.permission.POST_NOTIFICATIONS"))
    }
    @Test @Config(application = ConfiguredApplication::class)
    fun repeatedStartupAndConfigurationChangeKeepBlockedChannelPolicyAndRefreshOnlyItsText() {
        val app = (RuntimeEnvironment.getApplication() as ConfiguredApplication);val manager = app.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL)
        // Explicit shadow-state fixture, not a claim of actual user/OS blocking.
        channel.importance = NotificationManager.IMPORTANCE_NONE;channel.setSound(null,null);channel.enableVibration(false);channel.setShowBadge(false)
        manager.deleteNotificationChannel(channel.id);manager.createNotificationChannel(channel)
        assertEquals(ScheduleNoticeBindingState.BOUND,ScheduleNoticeBinding.initialize(app) { fixtureConfiguration() })
        val current = ScheduleNoticeBinding.capture()
        manager.getNotificationChannel(channel.id).name = "Outdated fixture text"
        app.onConfigurationChanged(Configuration(app.resources.configuration))
        val after = manager.getNotificationChannel(channel.id)
        assertEquals(NotificationManager.IMPORTANCE_NONE,after.importance);assertNull(after.sound);assertFalse(after.shouldVibrate());assertFalse(after.canShowBadge())
        assertEquals(app.getString(R.string.app_name),after.name);assertSame(current,ScheduleNoticeBinding.capture())
        assertTrue(dev.notai.tracker.ScheduleDeliveryBlocker.CHANNEL in AndroidScheduleDeliveryPlatform(app).availability().blockers)
        assertFalse(DatabaseFiles.exists(app));assertTrue(runtimeAbsent())
    }
    @Test @Config(application = TrackerApplication::class)
    fun failedCompositionFailsClosedAndSameFactoryReinstallInvalidatesPreparedRegistration() {
        val app = (RuntimeEnvironment.getApplication() as TrackerApplication)
        assertEquals(ScheduleNoticeBindingState.UNAVAILABLE,ScheduleNoticeBinding.initialize(app) { error("Injected configuration failure") })
        assertNull(ScheduleNoticeBinding.factory);assertFalse(DatabaseFiles.exists(app));assertTrue(runtimeAbsent())
        try {
            ScheduleNoticeBinding.install(renderer);val prior = ScheduleNoticeBinding.capture()
            ScheduleNoticeBinding.install(renderer);var posts = 0
            assertFalse(ScheduleNoticeBinding.publishIfCurrent(prior) { posts++;true });assertEquals(0,posts)
            val current = ScheduleNoticeBinding.capture()
            assertTrue(ScheduleNoticeBinding.publishIfCurrent(current) { posts++;true });assertEquals(1,posts)
        } finally { ScheduleNoticeBinding.install(null) }
    }
}
