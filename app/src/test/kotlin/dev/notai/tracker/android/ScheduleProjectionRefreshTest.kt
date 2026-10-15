package dev.notai.tracker.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import dev.notai.tracker.*
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** One changed Android boundary: a failed wake must disarm without rewriting authority/facts.
 * Uses only the fresh Robolectric application's own journal and platform shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScheduleProjectionRefreshTest {
    @Test fun corruptOrFutureWakeJournalDisarmsWithoutReplacingItOrChangingFacts() {
        val context = RuntimeEnvironment.getApplication()
        val sync = DirectoryDurability.sync
        val rename = DirectoryDurability.rename
        val oldConsumers = ScheduleProjectionBinding.consumers
        try {
            DirectoryDurability.sync = { directory ->
                java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            }
            DirectoryDurability.rename = { source,target ->
                java.nio.file.Files.move(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);Unit
            }
            val store = object: StateStore {
                private var value = Dataset()
                override val changes = MutableStateFlow(value)
                override fun read() = value
                @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
                    val (next,result) = block(value);validate(next);value = next;changes.value = next;return result
                }
            }
            val tracker = Tracker(store,FakeClock())
            tracker.configureSchedule(tracker.snapshot().token,ScheduleSettings(quietShadeEnabled = true))
            val channelId = "qa-silent-projection"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId,"Synthetic projection",NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null,null);enableVibration(false)
                })
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            var invalidations = 0
            ScheduleProjectionBinding.install(listOf(object: ScheduleProjectionConsumer {
                override val id = "qa-shade"
                override val surface = ScheduleDataSurface.QUIET_SHADE
                override val channelId = "qa-silent-projection"
                override fun available(context: android.content.Context) = true
                override fun invalidate(value: ScheduleSurfaceState,projectionKey: String) { invalidations++ }
            }))
            val projection = ScheduleProjectionRefresh(context,tracker,ScheduleWorkspace(context,tracker) { ZoneId.of("UTC") })
            val journal = PrivateFileMaintenance.metadata(File(context.noBackupFilesDir,"schedule-projection-refresh.json"))
            val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
            val component = ComponentName(context,ScheduleProjectionReceiver::class.java)
            for(bad in listOf("{\"format\":2}","{malformed")) {
                journal.writeDurably("{\"format\":1}") // reset only this synthetic authority between cases
                val ready = projection.synchronize()
                assertNotNull(ready.nextRefreshAt);assertEquals(1,alarms.scheduledAlarms.size)
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,context.packageManager.getComponentEnabledSetting(component))
                val before = tracker.data();val published = invalidations
                journal.writeDurably(bad);assertEquals(bad,journal.read())
                assertThrows(Exception::class.java) { projection.onWake(ready.projectionKey) }
                assertTrue("Failed wake must cancel its existing alarm",alarms.scheduledAlarms.isEmpty())
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,context.packageManager.getComponentEnabledSetting(component))
                assertEquals(bad,journal.read());assertEquals(before,tracker.data());assertEquals(published,invalidations)
            }
        } finally {
            ScheduleProjectionRefresh.disarm(context)
            ScheduleProjectionBinding.install(oldConsumers)
            DirectoryDurability.sync = sync;DirectoryDurability.rename = rename
        }
    }
}
