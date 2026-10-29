package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ru-rRU")
class SurfaceStorageOutcomeTest {
    private fun check(source: Surface) {
        val app = RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);TimerSurfaces.channels(app)
        val base = object: StateStore {
            var data = Dataset(settings = Settings(surfaces = SurfaceSettings(widget = true,notification = true)))
            override val changes = MutableStateFlow(data)
            override fun read() = data
            @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T { val (next,result)=block(data);validate(next);data=next;changes.value=next;return result }
        }
        val clock=FakeClock();val healthy=Tracker(base,clock);healthy.start(healthy.snapshot().token);clock.advance(1000)
        val uncertain = object: StateStore {
            override val changes = base.changes
            override fun read() = base.read()
            override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T { var wrote=false;val result=base.transaction { old -> block(old).also { wrote=it.first!=old } };if(wrote) throw IOException("Synthetic post-commit failure");return result }
        }
        val t=Tracker(uncertain,clock);val shown=t.snapshot()
        kotlinx.coroutines.runBlocking { TimerActionReceiver().handleCurrent(app,t,SurfaceAction(source,TimerCommand.STOP,shown.token,shown.timerTarget)) }
        assertEquals(SessionStatus.STOPPED,base.read().sessions.single().status);assertEquals(1000L,base.read().segments.single().durationMs)
        val prefs=app.getSharedPreferences("surface-results",Context.MODE_PRIVATE);val message=requireNotNull(prefs.getString("last",null))
        assertTrue(message,message.contains("STORAGE"));assertFalse(message,message.contains("история не изменена"));assertTrue(message,message.contains("проверь",ignoreCase=true))
        assertEquals("STORAGE",prefs.getString("lastCode",null))
    }
    @Test fun widgetCommitThenStorageDoesNotPromiseUnchangedHistory() = check(Surface.WIDGET)
    @Test fun notificationCommitThenStorageDoesNotPromiseUnchangedHistory() = check(Surface.NOTIFICATION)
}
