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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
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
                if(action=="focus-due") t.startFocus(t.snapshot().token,plan=FocusPlan(1000,1000)) else t.start(t.snapshot().token,Classification(name="Shown timer"))
                val captured=t.view();val original=captured.snapshot()
                val oldAction=SurfaceAction(Surface.WIDGET,TimerCommand.PAUSE,original.token,original.timerTarget);val oldPending=TimerSurfaces.pending(c,oldAction)
                var expected: Dataset?=null;var newest: Notification?=null;var expectedShortcut: SurfaceAction?=null;var calls=0
                val change={
                    calls++;TimerSurfaces.refreshPrepared=null;TimerSurfaces.notificationFactory=factory
                    when(action) {
                        "notification" -> t.settings(t.snapshot().token,t.data().settings.copy(surfaces=t.data().settings.surfaces.copy(notification=false)))
                        "widget" -> t.settings(t.snapshot().token,t.data().settings.copy(surfaces=t.data().settings.surfaces.copy(widget=false)))
                        "shortcut" -> t.settings(t.snapshot().token,t.data().settings.copy(surfaces=t.data().settings.surfaces.copy(shortcut=false)))
                        "barrier" -> t.store.transaction { it.copy(controlBarrier=newId()) to Unit }
                        "focus-due" -> (t.clock as FakeClock).advance(1000)
                        else -> t.pause(t.snapshot().token)
                    }
                    refresh(c,t,t.view(),captured.data.settings.surfaces);expected=t.data()
                    newest=notifications.activeNotifications.singleOrNull { it.id==TimerSurfaces.CONTROL_ID }?.notification
                    expectedShortcut=shortcuts.dynamicShortcuts.singleOrNull { it.id == "timer_action" }?.intent?.let(TimerSurfaces::parse)
                    Unit
                }
                if(afterNotification) TimerSurfaces.notificationFactory={ context,view -> factory(context,view).also { change() } }
                else TimerSurfaces.refreshPrepared={ _,_ -> change() }
                refresh(c,t,captured)
                assertEquals(1,calls);assertEquals(expected,t.data())
                if(action=="notification") assertTrue("Disabled shade was resurrected: $room",notifications.activeNotifications.none { it.id==TimerSurfaces.CONTROL_ID })
                else assertSame("New timer notification replaced by captured view: $room/$action",newest,notifications.activeNotifications.single { it.id==TimerSurfaces.CONTROL_ID }.notification)
                val widget=widgets.getViewFor(807)
                if(action=="focus-due") {
                    assertEquals(android.view.View.GONE,widget.findViewById<android.view.View>(R.id.surface_secondary).visibility)
                    assertTrue(widget.findViewById<TextView>(R.id.surface_status).text.toString().contains("закончилась"))
                } else assertEquals(if(action=="widget") "Виджет выключен" else "Shown timer",widget.findViewById<TextView>(R.id.surface_title).text.toString())
                if(action=="pause") assertEquals("Продолжить",widget.findViewById<android.view.View>(R.id.surface_secondary).contentDescription)
                assertEquals(expectedShortcut,shortcuts.dynamicShortcuts.singleOrNull { it.id == "timer_action" }?.intent?.let(TimerSurfaces::parse))
                if(action=="shortcut") assertTrue(shortcuts.dynamicShortcuts.isEmpty())
                // Every shown PendingIntent keeps its original immutable revision/target.
                assertEquals(oldAction,TimerSurfaces.parse(shadowOf(oldPending).savedIntent))
                if(action=="focus-due") { assertEquals(FocusMode.DUE,t.snapshot().focus!!.mode);assertTrue(shortcuts.dynamicShortcuts.isEmpty()) }
            } finally { TimerSurfaces.refreshPrepared=null;TimerSurfaces.notificationFactory=factory;db?.close() }
        }
    }
    @Test fun notificationOptOutWinsAfterCapturedView()=race("notification")
    @Test fun widgetOptOutWinsWithoutDisablingOtherSurfaces()=race("widget")
    @Test fun shortcutOptOutWinsWithoutDisablingOtherSurfaces()=race("shortcut")
    @Test fun newestTimerControlsWinAfterCapturedView()=race("pause")
    @Test fun replacementBarrierWinsAfterCapturedView()=race("barrier")
    @Test fun notificationOptOutWinsAfterActualNotificationRendering()=race("notification",true)
    @Test fun duePhaseWithoutRevisionChangeWinsAfterPreparation()=race("focus-due")
    @Test fun preparationFailureLeavesIndependentWidgetAndShortcutUsable() {
        val c=RuntimeEnvironment.getApplication();shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);TimerSurfaces.channels(c)
        val t=Tracker(memory(),FakeClock());t.settings(t.snapshot().token,t.data().settings.copy(surfaces=SurfaceSettings(widget=true,notification=true)))
        t.start(t.snapshot().token,Classification(name="Independent current"));val before=t.data()
        val widgets=shadowOf(AppWidgetManager.getInstance(c));widgets.bindAppWidgetId(808,ComponentName(c,TimerWidget::class.java))
        TimerSurfaces.notificationFactory={ _,_ -> throw IllegalStateException("Synthetic notification preparation failure") }
        refresh(c,t,t.view())
        assertEquals(before,t.data());assertEquals("Independent current",widgets.getViewFor(808).findViewById<TextView>(R.id.surface_title).text.toString())
        assertEquals(t.snapshot().token,TimerSurfaces.parse(c.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single { it.id == "timer_action" }.intent!!)?.token)
        assertTrue(c.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id==TimerSurfaces.CONTROL_ID })
    }
    @Test fun defaultViewReadAndTerminalStoreGateRunAwayFromCallerMainThread() {
        val c=RuntimeEnvironment.getApplication();val caller=Thread.currentThread();val source=memory()
        val reads=java.util.concurrent.atomic.AtomicInteger();val writes=java.util.concurrent.atomic.AtomicInteger()
        val workerStore=object:StateStore {
            override val changes=source.changes
            override fun read():Dataset { assertNotSame(caller,Thread.currentThread());reads.incrementAndGet();return source.read() }
            override fun <T> transaction(block:(Dataset)->Pair<Dataset,T>):T {assertNotSame(caller,Thread.currentThread());writes.incrementAndGet();return source.transaction(block)}
        }
        kotlinx.coroutines.runBlocking { TimerSurfaces.refresh(c,Tracker(workerStore,FakeClock())) }
        assertTrue(reads.get()>0);assertTrue(writes.get()>0)
    }

    private fun skippedDisableInRuntimeObserver(overlay: Boolean,roomOnly: Boolean? = null) = runBlocking {
        val c = RuntimeEnvironment.getApplication()
        val widgets = shadowOf(AppWidgetManager.getInstance(c));widgets.bindAppWidgetId(809,ComponentName(c,TimerWidget::class.java))
        for(room in roomOnly?.let { listOf(it) } ?: listOf(false,true)) {
            val db = if(room) Room.inMemoryDatabaseBuilder(c,TrackerDatabase::class.java).allowMainThreadQueries().build() else null
            try {
                while(shadowOf(c).nextStoppedService != null) { /* discard only fixture service requests */ }
                val t = Tracker(db?.let(::RoomStore) ?: memory(),FakeClock())
                t.settings(t.snapshot().token,t.data().settings.copy(surfaces = SurfaceSettings(widget = true,overlay = overlay)))
                val observer = TimerSurfaceObserver(c,t);val published = CompletableDeferred<Unit>();val observed = mutableListOf<Long>()
                val job = launch(Dispatchers.IO) {
                    t.views.take(3).collect { view ->
                        observed += view.data.revision;observer.refresh(view)
                        if(observed.size == 1) published.complete(Unit)
                    }
                }
                try {
                    withTimeout(10000) { published.await() }
                    assertNotEquals("Виджет выключен",widgets.getViewFor(809).findViewById<TextView>(R.id.surface_title).text.toString())
                    val oldSnapshot = t.snapshot();val oldAction = SurfaceAction(Surface.WIDGET,TimerCommand.START,oldSnapshot.token,oldSnapshot.timerTarget)
                    val oldPending = TimerSurfaces.pending(c,oldAction)
                    TimerSurfaces.refreshPrepared = { _,view ->
                        if(!view.data.settings.surfaces.widget) {
                            TimerSurfaces.refreshPrepared = null
                            t.saveFavorite(t.snapshot().token,Favorite("p3-independent","Independent mutation",Classification()))
                        }
                    }
                    t.settings(t.snapshot().token,t.data().settings.copy(surfaces = t.data().settings.surfaces.copy(widget = false,overlay = false)))
                    withTimeout(10000) { job.join() }
                    assertEquals(3,observed.size);assertEquals(3,observed.distinct().size)
                    assertEquals("Skipped opt-out lost after next collector emission; room=$room", "Виджет выключен",widgets.getViewFor(809).findViewById<TextView>(R.id.surface_title).text.toString())
                    assertEquals(oldAction,TimerSurfaces.parse(shadowOf(oldPending).savedIntent))
                    if(overlay) assertEquals(FloatingTimerService::class.java.name,shadowOf(c).nextStoppedService?.component?.className)
                    assertFalse(t.data().settings.surfaces.widget);assertEquals(Mode.IDLE,t.snapshot().mode)
                } finally { job.cancelAndJoin() }
            } finally { TimerSurfaces.refreshPrepared = null;db?.close() }
        }
    }
    @Test fun reviewP3RuntimeCollectorRetriesSkippedWidgetDisableWithoutForce() = skippedDisableInRuntimeObserver(false)
    @Test fun reviewP3RuntimeObserverRetainsOverlayStopRequestAfterSkippedTransition() = skippedDisableInRuntimeObserver(true)
    @Test fun reviewP3RoomCollectorRetriesSkippedWidgetDisableWithoutForce() = skippedDisableInRuntimeObserver(false,true)
    @Test fun reviewP3RoomObserverRetainsOverlayStopRequestAfterSkippedTransition() = skippedDisableInRuntimeObserver(true,true)

}
