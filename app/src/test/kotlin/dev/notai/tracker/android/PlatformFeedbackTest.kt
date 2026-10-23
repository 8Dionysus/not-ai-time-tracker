package dev.notai.tracker.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.provider.Settings
import dev.notai.tracker.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformFeedbackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @After fun reset() {
        TimerSurfaces.ancillaryPreparing = null;TimerSurfaces.ancillaryPrepared = null
    }
    private fun memory(): StateStore = object: StateStore {
        private var value = Dataset()
        override val changes = kotlinx.coroutines.flow.MutableStateFlow(value)
        override fun read() = value
        @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
            val (next,result) = block(value);validate(next);value = next;changes.value = next;return result
        }
    }
    private fun tracker(): Tracker {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TimerSurfaces.channels(context)
        return Tracker(memory(),FakeClock()).also { tracker ->
            tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(surfaces = SurfaceSettings(notification = true,shortcut = true)))
        }
    }

    @Test fun commandNotificationIsPublishedBeforeAncillaryPreparation() = runBlocking {
        val tracker = tracker();val shown = tracker.snapshot()
        val action = SurfaceAction(Surface.NOTIFICATION,TimerCommand.START,shown.token,shown.timerTarget)
        var beforeAncillary = false
        val startedNs = System.nanoTime();var publishedNs = 0L
        TimerSurfaces.ancillaryPreparing = { _,view ->
            beforeAncillary = true
            assertEquals(Mode.RUNNING,view.snapshot().mode)
            val published = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == TimerSurfaces.CONTROL_ID }.notification
            assertEquals("Без занятия",published.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            // The launcher still has no new timer shortcut at this boundary.
            assertTrue(context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.isEmpty())
            publishedNs = System.nanoTime()
            Thread.sleep(80) // Synthetic ancillary stall, never a device-latency estimate.
        }
        TimerActionReceiver().handleCurrent(context,tracker,action)
        val finishedNs = System.nanoTime()
        assertTrue(beforeAncillary)
        assertEquals(Mode.RUNNING,tracker.snapshot().mode)
        println("HOST_SYNTHETIC notificationBeforeAncillaryMs=${(publishedNs-startedNs)/1_000_000} totalMs=${(finishedNs-startedNs)/1_000_000} injectedAncillaryStallMs=80")
    }

    @Test fun secondGateCannotPublishAncillaryAfterOptOutAndNewerView() = runBlocking {
        val tracker = tracker();tracker.start(tracker.snapshot().token)
        var nested = false
        TimerSurfaces.ancillaryPrepared = { _,_ ->
            TimerSurfaces.ancillaryPrepared = null;nested = true
            tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(surfaces = tracker.data().settings.surfaces.copy(notification = false,shortcut = false)))
            runBlocking { TimerSurfaces.refresh(context,tracker) }
        }
        assertFalse(TimerSurfaces.refresh(context,tracker))
        assertTrue(nested)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == TimerSurfaces.CONTROL_ID })
        assertTrue(context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.isEmpty())
    }

    @Test fun publishedPrivacyMappingAndExactChannelSettings() = runBlocking {
        val tracker = tracker()
        for((policy,visibility) in listOf(NotificationPrivacy.PRIVATE to Notification.VISIBILITY_PRIVATE,
            NotificationPrivacy.FULL to Notification.VISIBILITY_PUBLIC,NotificationPrivacy.HIDDEN to Notification.VISIBILITY_SECRET)) {
            tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(surfaces = tracker.data().settings.surfaces.copy(notificationPrivacy = policy)))
            TimerSurfaces.refresh(context,tracker)
            val capability = SurfaceSettingsBackend.read(context,tracker.data().settings.surfaces).timerNotification!!
            assertEquals(visibility,capability.requestedVisibility);assertEquals(visibility,capability.publishedVisibility)
            assertEquals(policy == NotificationPrivacy.PRIVATE,capability.publishedHasPublicVersion)
            val published = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == TimerSurfaces.CONTROL_ID }.notification
            if(policy == NotificationPrivacy.PRIVATE) {
                assertNull(published.publicVersion.contentView)
                assertNull(published.publicVersion.actions)
                assertEquals("(not)AI Time",published.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            }
        }
        val intent = SurfaceSettingsBackend.settingsIntent(context,SurfaceSettingsAction.OPEN_TIMER_CHANNEL_SETTINGS)!!
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,intent.action)
        assertEquals(context.packageName,intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(TimerSurfaces.CONTROL_CHANNEL,intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_LOW,context.getSystemService(NotificationManager::class.java).getNotificationChannel(TimerSurfaces.CONTROL_CHANNEL).importance)
    }

    private fun selectLauncher() = runBlocking {
        val tracker = tracker();TimerSurfaces.refresh(context,tracker)
        val manager = context.getSystemService(ShortcutManager::class.java)
        var original = manager.dynamicShortcuts.associate { it.id to it.intent!!.toUri(0) }
        val controller = LauncherIconController(context)
        for(key in LauncherIconController.options.map { it.key }) {
            val result = controller.select(key)
            assertEquals(key,result.state.selected);assertEquals(setOf(key),result.state.enabled)
            assertTrue(result.shortcutsRebound)
            assertEquals(original,manager.dynamicShortcuts.associate { it.id to it.intent!!.toUri(0) })
            assertTrue(manager.dynamicShortcuts.all { it.activity == controller.component() })
            TimerSurfaces.refresh(context,tracker)
            assertEquals(original.keys,manager.dynamicShortcuts.map { it.id }.toSet())
            assertEquals(tracker.snapshot().token,TimerSurfaces.parse(manager.dynamicShortcuts.single { it.id == "timer_action" }.intent!!)?.token)
            assertEquals(tracker.snapshot().timerTarget,SurfaceChoices.parse(manager.dynamicShortcuts.single { it.id == "timer_choose" }.intent!!)?.target)
            assertTrue(manager.dynamicShortcuts.all { it.activity == controller.component() })
            // An ordinary refresh intentionally creates a new chooser route ID; selection itself
            // must preserve the exact payload that existed immediately before the selection.
            original = manager.dynamicShortcuts.associate { it.id to it.intent!!.toUri(0) }
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,context.packageManager.getComponentEnabledSetting(android.content.ComponentName(context,MainActivity::class.java)))
            assertEquals(MainActivity::class.java.name,TimerSurfaces.open(context).let { shadowOf(it).savedIntent.component!!.className })
            val processText = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
            assertTrue(context.packageManager.queryIntentActivities(processText,0).any { it.activityInfo.name == MainActivity::class.java.name })
        }
    }
    @Test fun launcherAliasesPreserveShortcutsAndMainActivityOnApi33Plus() = selectLauncher()
    @Test @Config(sdk = [32]) fun launcherAliasesPreserveShortcutsAndMainActivityOnOldApi() = selectLauncher()
}
