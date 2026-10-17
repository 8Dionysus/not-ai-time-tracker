package dev.notai.tracker.android

import android.Manifest
import android.app.*
import dev.notai.tracker.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LongRecordBindingTest {
    @Test fun sameFactoryReinstallRejectsAnAlreadyPreparedNotice() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(AndroidLongRecordPlatform.CHANNEL,"Fixture",2))
        val renderer = LongRecordNoticeFactory { c,_,_ -> Notification.Builder(c,AndroidLongRecordPlatform.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notai).build() }
        val original = LongRecordNoticeBinding.factory
        try {
            LongRecordNoticeBinding.factory = renderer
            val snap = snapshot(Dataset(),FakeClock().now())
            val ticket = LongRecordTicket(TimerTarget("fixture","fixture",0,"fixture"),"fixture","boot",0,0,1)
            val prepared = requireNotNull(AndroidLongRecordPlatform(app).prepare(ticket,snap))
            LongRecordNoticeBinding.factory = renderer
            assertFalse("Reinstall is a new registration even for the same renderer instance",prepared.post())
            assertTrue(manager.activeNotifications.isEmpty())
        } finally { LongRecordNoticeBinding.factory = original }
    }
}
