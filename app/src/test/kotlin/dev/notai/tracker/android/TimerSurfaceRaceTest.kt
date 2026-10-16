package dev.notai.tracker.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutManager
import android.widget.TextView
import androidx.room.Room
import dev.notai.tracker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual notification/widget/launcher effects, isolated stores and deterministic preparation barriers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimerSurfaceRaceTest {
    private val factory = TimerSurfaces.notificationFactory
    @After fun reset() { TimerSurfaces.notificationFactory=factory;TimerSurfaces.refreshPrepared=null }
    private fun memory(): StateStore = object: StateStore {
        private var value=Dataset();override val changes=MutableStateFlow(value)
        override fun read()=value
        @Synchronized override fun <T> transaction(block:(Dataset)->Pair<Dataset,T>):T {val(n,r)=block(value);validate(n);value=n;changes.value=n;return r}
    }
    private fun refresh(c: Context,t: Tracker,v: TrackerView,previous: SurfaceSettings? = null) { kotlinx.coroutines.runBlocking { TimerSurfaces.refresh(c,t,v,previous,forceWidget=true) } }
    private fun race(action: String,afterNotification: Boolean=false) {
        val c=RuntimeEnvironment.getApplication();shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);TimerSurfaces.channels(c)
        val notifications=c.getSystemService(NotificationManager::class.java);val shortcuts=c.getSystemService(ShortcutManager::class.java)
        val widgets=shadowOf(AppWidgetManager.getInstance(c));widgets.bindAppWidgetId(807,ComponentName(c,TimerWidget::class.java))
        for(room in listOf(false,true)) {
            val db=if(room) Room.inMemoryDatabaseBuilder(c,TrackerDatabase::class.java).allowMainThreadQueries().build() else null
            try {
                val t=Tracker(db?.let(::RoomStore) ?: memory(),FakeClock())
                t.settings(t.snapshot().token,t.data().settings.copy(surfaces=SurfaceSettings(widget=true,notification=true,shortcut=true)))
                t.start(t.snapshot().token,Classification(name="Shown timer"));val captured=t.view();val original=captured.snapshot()
                var expected: Dataset?=null;var newest: Notification?=null;var expectedShortcut: SurfaceAction?=null;var calls=0
                val change={
                    calls++;TimerSurfaces.refreshPrepared=null;TimerSurfaces.notificationFactory=factory
                    when(action) {
                        "notification" -> t.settings(t.snapshot().token,t.data().settings.copy(surfaces=t.data().settings.surfaces.copy(notification=false)))
                        "widget" -> t.settings(t.snapshot().token,t.data().settings.copy(surfaces=t.data().settings.surfaces.copy(widget=false)))
                        "shortcut" -> t.settings(t.snapshot().token,t.data().settings.copy(surfaces=t.data().settings.surfaces.copy(shortcut=false)))
                        "barrier" -> t.store.transaction { it.copy(controlBarrier=newId()) to Unit }
                        else -> t.pause(t.snapshot().token)
                    }
                    refresh(c,t,t.view(),captured.data.settings.surfaces);expected=t.data()
                    newest=notifications.activeNotifications.singleOrNull { it.id==TimerSurfaces.CONTROL_ID }?.notification
                    expectedShortcut=shortcuts.dynamicShortcuts.singleOrNull()?.intent?.let(TimerSurfaces::parse)
                    Unit
                }
                if(afterNotification) TimerSurfaces.notificationFactory={ context,view -> factory(context,view).also { change() } }
                else TimerSurfaces.refreshPrepared={ _,_ -> change() }
                refresh(c,t,captured)
                assertEquals(1,calls);assertEquals(expected,t.data())
                if(action=="notification") assertTrue("Disabled shade was resurrected: $room",notifications.activeNotifications.none { it.id==TimerSurfaces.CONTROL_ID })
                else assertSame("New timer notification replaced by captured view: $room/$action",newest,notifications.activeNotifications.single { it.id==TimerSurfaces.CONTROL_ID }.notification)
                val widget=widgets.getViewFor(807)
                assertEquals(if(action=="widget") "Виджет выключен" else "Shown timer",widget.findViewById<TextView>(R.id.surface_title).text.toString())
                if(action=="pause") assertEquals("Продолжить",widget.findViewById<android.view.View>(R.id.surface_secondary).contentDescription)
                assertEquals(expectedShortcut,shortcuts.dynamicShortcuts.singleOrNull()?.intent?.let(TimerSurfaces::parse))
                if(action=="shortcut") assertTrue(shortcuts.dynamicShortcuts.isEmpty())
                // Every shown PendingIntent keeps its original immutable revision/target.
                assertEquals(SurfaceAction(Surface.WIDGET,TimerCommand.PAUSE,original.token,original.timerTarget),
                    TimerSurfaces.parse(TimerSurfaces.intent(c,SurfaceAction(Surface.WIDGET,TimerCommand.PAUSE,original.token,original.timerTarget))))
            } finally { TimerSurfaces.refreshPrepared=null;TimerSurfaces.notificationFactory=factory;db?.close() }
        }
    }
    @Test fun notificationOptOutWinsAfterCapturedView()=race("notification")
    @Test fun widgetOptOutWinsWithoutDisablingOtherSurfaces()=race("widget")
    @Test fun shortcutOptOutWinsWithoutDisablingOtherSurfaces()=race("shortcut")
    @Test fun newestTimerControlsWinAfterCapturedView()=race("pause")
    @Test fun replacementBarrierWinsAfterCapturedView()=race("barrier")
    @Test fun notificationOptOutWinsAfterActualNotificationRendering()=race("notification",true)
}
