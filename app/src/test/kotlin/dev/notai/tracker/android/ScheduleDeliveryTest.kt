package dev.notai.tracker.android

import android.Manifest
import android.app.*
import dev.notai.tracker.*
import java.time.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.Assert.*

/** API36 platform simulation; not a physical-device/Android-filesystem acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScheduleDeliveryTest {
    private fun fixture(block: (android.app.Application,Tracker,FakeClock)->Unit) {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(36,RuntimeEnvironment.getApiLevel())
        val sync = DirectoryDurability.sync;val rename = DirectoryDurability.rename
        try {
            DirectoryDurability.sync = { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { fd -> fd.force(true) } }
            DirectoryDurability.rename = { a,b -> java.nio.file.Files.move(a.toPath(),b.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);Unit }
            val clock = FakeClock(wall = Instant.parse("2026-10-10T09:00:00Z").toEpochMilli())
            val t = Tracker(testFixturesStore(),clock)
            t.scheduleCommand(ScheduleCommand("fixture",t.snapshot().token,ScheduleAction.CREATE,"a",rule = ScheduleRule(Classification(),"2026-10-10",
                extraDates = setOf("2026-10-10","2026-10-11"),localTime = "10:00",alertsEnabled = true,reminderTime = "09:30")))
            block(context,t,clock)
        } finally { DirectoryDurability.sync = sync;DirectoryDurability.rename = rename }
    }
    // Memory domain + real private delivery file; Room domain parity runs in ScenarioContract.
    private fun testFixturesStore(): StateStore = object: StateStore {
        private var value = Dataset();private val flow = kotlinx.coroutines.flow.MutableStateFlow(value)
        override fun read() = value
        @Synchronized override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
            val (next,result) = block(value);validate(next);value = next;flow.value = next;return result
        }
        override val changes get() = flow
    }
    private fun channel(context: android.content.Context) = context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL,"Fixture only",NotificationManager.IMPORTANCE_DEFAULT))
    private val renderer = ScheduleNoticeFactory { context,_,_ -> Notification.Builder(context,AndroidScheduleDeliveryPlatform.CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_today).setContentTitle("Fixture reminder").setVisibility(Notification.VISIBILITY_PRIVATE).build() }
    @Test fun unboundRendererAndPermissionNeverArmOrConsumeAnOccurrence() = fixture { context,t,_ ->
        val platform = AndroidScheduleDeliveryPlatform(context) { null };channel(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = AndroidScheduleDeliveryStore(context)
        val delivery = ScheduleDelivery(t,store,platform) { ZoneId.of("UTC") }
        val report = delivery.synchronize()
        assertFalse(report.alarmAccepted);assertTrue(ScheduleDeliveryBlocker.RENDERER_UNAVAILABLE in report.availability.blockers)
        assertNull(store.locked { store.read().pending });assertTrue(delivery.attempts().isEmpty())
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertTrue(context.packageManager.getComponentEnabledSetting(android.content.ComponentName(context,SchedulePlatformReceiver::class.java)) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }
    @Test fun oneAlarmPostsOnceAndReopenKeepsReceiptAndRevocationCancelsNotice() = fixture { context,t,clock ->
        channel(context);shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val platform = AndroidScheduleDeliveryPlatform(context) { renderer };val store = AndroidScheduleDeliveryStore(context)
        val delivery = ScheduleDelivery(t,store,platform) { ZoneId.of("UTC") };val before = t.data()
        val batch = delivery.synchronize().next!!;delivery.synchronize()
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,context.packageManager.getComponentEnabledSetting(android.content.ComponentName(context,SchedulePlatformReceiver::class.java)))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1,alarms.scheduledAlarms.size)
        val alarm = alarms.scheduledAlarms.single()
        val intent = shadowOf(alarm.operation!!).savedIntent
        assertEquals(AndroidScheduleDeliveryPlatform.ACTION,intent.action);assertEquals(batch.hash,intent.getStringExtra(AndroidScheduleDeliveryPlatform.HASH))
        assertTrue(android.os.Build.VERSION.SDK_INT >= 31 && alarm.operation!!.isImmutable)
        clock.advance(30*60_000)
        assertEquals(ScheduleAttemptStatus.POSTED,delivery.onAlarm(batch.hash).attempted.single().status)
        assertEquals(1,platform.visible().size)
        val reopened = ScheduleDelivery(t,AndroidScheduleDeliveryStore(context),platform) { ZoneId.of("UTC") }
        reopened.onAlarm(batch.hash);assertEquals(1,platform.visible().size);assertEquals(1,reopened.attempts().size)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val blocked = reopened.synchronize();assertFalse(blocked.alarmAccepted);assertTrue(platform.visible().isEmpty());assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,context.packageManager.getComponentEnabledSetting(android.content.ComponentName(context,SchedulePlatformReceiver::class.java)))
        assertEquals(before,t.data());assertTrue(t.data().schedule.series.single().revisions.single().rule.alertsEnabled)
    }
    @Test fun startedOccurrenceCancelsPendingWithoutInventingAnotherSession() = fixture { context,t,_ ->
        channel(context);shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val platform = AndroidScheduleDeliveryPlatform(context) { renderer }
        val delivery = ScheduleDelivery(t,AndroidScheduleDeliveryStore(context),platform) { ZoneId.of("UTC") }
        val original = delivery.synchronize().next!!
        t.scheduleCommand(ScheduleCommand("start",t.snapshot().token,ScheduleAction.START,"a",1,occurrence = OccurrenceKey("a","2026-10-10")))
        assertEquals(ScheduleDeliveryOutcome.STALE_CALLBACK,delivery.onAlarm(original.hash).outcome)
        assertEquals(1,t.data().sessions.size);assertEquals(1,t.data().schedule.links.size);assertTrue(platform.visible().isEmpty())
        assertEquals("2026-10-11",delivery.synchronize().next!!.tickets.single().key.originalDate)
    }
    @Test fun rendererOptOutOfCurrentSeriesIsRejectedBeforeNotifyInsteadOfCancellingAfterPost() = fixture { context,t,clock ->
        channel(context);shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val mutating = ScheduleNoticeFactory { c,ticket,projection ->
            val rule = t.data().schedule.series.single().revisions.last().rule.copy(alertsEnabled = false)
            t.scheduleCommand(ScheduleCommand("render-opt-out",t.snapshot().token,ScheduleAction.UPDATE_FUTURE,"a",1,
                rule = rule,effectiveFrom = "2026-10-10"))
            renderer.create(c,ticket,projection)
        }
        val platform = AndroidScheduleDeliveryPlatform(context) { mutating }
        val delivery = ScheduleDelivery(t,AndroidScheduleDeliveryStore(context),platform) { ZoneId.of("UTC") }
        val batch = delivery.synchronize().next!!;clock.advance(30*60_000)
        assertEquals(ScheduleAttemptStatus.NOT_POSTED,delivery.onAlarm(batch.hash).attempted.single().status)
        assertEquals(ScheduleAttemptStatus.NOT_POSTED,delivery.attempts().single().status)
        assertTrue(platform.visible().isEmpty());assertTrue(t.data().sessions.isEmpty())
        assertFalse(t.data().schedule.series.single().revisions.last().rule.alertsEnabled)
    }
    @Test fun backgroundWakeCannotInitializeAMissingDataset() = fixture { context,t,_ ->
        channel(context);shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val delivery = ScheduleDelivery(t,AndroidScheduleDeliveryStore(context),AndroidScheduleDeliveryPlatform(context) { renderer }) { ZoneId.of("UTC") }
        delivery.synchronize()
        assertFalse(DatabaseFiles.exists(context));assertFalse(hasScheduleWakeSource(context));assertFalse(DatabaseFiles.exists(context))
    }
}
