package dev.notai.tracker.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.notai.tracker.*
import java.nio.file.StandardCopyOption
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.Assert.*

/** Android SDK simulation only. No real permissions, DND, music, account or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttentionPlatformTest {
    @Test fun ordinaryAlarmAndReceiptPersistWithoutTouchingTheDomain() {
        val context = RuntimeEnvironment.getApplication()
        val oldSync = DirectoryDurability.sync;val oldRename = DirectoryDurability.rename
        val oldFactory = LongRecordNoticeBinding.factory
        try {
            DirectoryDurability.sync = { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { fd -> fd.force(true) } }
            DirectoryDurability.rename = { a,b -> java.nio.file.Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
            val data = object: StateStore {
                private var value = Dataset();private val flow = kotlinx.coroutines.flow.MutableStateFlow(value)
                override fun read() = value
                override val changes get() = flow
                @Synchronized override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
                    val (next,result) = block(value);validate(next);value = next;flow.value = next;return result
                }
            }
            val clock = FakeClock();val tracker = Tracker(data,clock);tracker.start(tracker.snapshot().token)
            val notifications = context.getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(AndroidLongRecordPlatform.CHANNEL,"Fixture",NotificationManager.IMPORTANCE_DEFAULT))
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            LongRecordNoticeBinding.factory = null
            val platform = AndroidLongRecordPlatform(context)
            assertTrue(ScheduleDeliveryBlocker.RENDERER_UNAVAILABLE in platform.availability().blockers)
            LongRecordNoticeBinding.factory = LongRecordNoticeFactory { c,_,_ -> Notification.Builder(c,AndroidLongRecordPlatform.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_pause).setContentTitle("Fixture check-in").build() }
            val store = AndroidLongRecordStore(context);val controller = LongRecordReminder(tracker,store,platform)
            val before = tracker.data();val ticket = controller.configure(tracker.snapshot().token,LongRecordPreference(true,100,20)).ticket!!
            val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
            assertEquals(1,alarms.scheduledAlarms.size)
            val scheduled = shadowOf(alarms.scheduledAlarms.single().operation).savedIntent
            assertEquals(AndroidLongRecordPlatform.ACTION,scheduled.action)
            assertEquals(ticket.hash,scheduled.getStringExtra(AndroidLongRecordPlatform.HASH))
            clock.advance(100);assertEquals(LongRecordOutcome.POST_ACCEPTED,controller.onAlarm(ticket.hash).outcome)
            val reopened = LongRecordReminder(tracker,AndroidLongRecordStore(context),platform)
            assertEquals(LongRecordOutcome.PREVIOUS_ATTEMPT,reopened.onAlarm(ticket.hash).outcome)
            assertEquals(ScheduleAttemptStatus.POSTED,AndroidLongRecordStore(context).read().attempt!!.status)
            assertEquals(before,tracker.data())
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val denied = reopened.synchronize();assertTrue(denied.preference.enabled);assertFalse(denied.alarmAccepted)
        } finally {
            LongRecordNoticeBinding.factory = oldFactory;DirectoryDurability.sync = oldSync;DirectoryDurability.rename = oldRename
        }
    }
    @Test @Config(sdk = [28]) fun unsupportedDndHasNoGlobalFallbackAndMusicHasNoDefaultSource() {
        val context = RuntimeEnvironment.getApplication()
        val before = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        assertEquals(FocusEffectStatus.UNSUPPORTED,AndroidFocusDistractions(context).availability())
        FocusEnvironmentBinding.foreground = false
        val music = AndroidFocusMusic(context)
        assertEquals(FocusEffectStatus.FOREGROUND_REQUIRED,music.availability(null))
        assertNull(music.prepare(newId(),"unbound"))
        assertEquals(before,context.getSystemService(NotificationManager::class.java).currentInterruptionFilter)
    }
}
