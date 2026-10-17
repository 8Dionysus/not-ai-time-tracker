package dev.notai.tracker.android

import android.app.PendingIntent
import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetPlacementFailureTest {
    @Test fun unsupportedDoesNotWriteOrRequestPin() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("widget-placement",0);prefs.edit().clear().commit()
        val platform = object: WidgetPlacementPlatform {
            override val supported = false
            override fun ids(provider: ComponentName) = intArrayOf()
            override fun request(provider: ComponentName,callback: PendingIntent): Boolean = error("unsupported must not request")
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val result = WidgetPlacementController(context,platform).request(activity.get())
            assertEquals(WidgetPlacement.UNSUPPORTED,result.placement)
            assertTrue(prefs.all.isEmpty())
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun pendingReceiptPrecedesFailuresAndRetryKeepsCallbackIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("widget-placement",0);prefs.edit().clear().commit()
        var behavior = "throw";var calls = 0
        val platform = object: WidgetPlacementPlatform {
            override val supported = true
            override fun ids(provider: ComponentName) = intArrayOf()
            override fun request(provider: ComponentName,callback: PendingIntent): Boolean {
                calls++
                assertEquals("PENDING",prefs.getString("state",null))
                assertFalse(prefs.getString("request",null).isNullOrBlank())
                if(behavior == "throw") throw IllegalStateException("native platform failure fixture")
                return behavior == "accept"
            }
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val controller = WidgetPlacementController(context,platform)
            assertEquals(SurfaceProblem.PLATFORM_FAILURE,controller.request(activity.get()).problem)
            assertEquals("FAILED",prefs.getString("state",null))
            behavior = "refuse"
            assertEquals(SurfaceProblem.LAUNCHER_UNSUPPORTED,controller.request(activity.get()).problem)
            assertEquals("UNCONFIRMED",prefs.getString("state",null))
            behavior = "accept"
            val first = controller.request(activity.get()).requestId!!;val count = calls
            controller.request(activity.get());assertEquals(count,calls)
            controller.read(true)
            val second = controller.request(activity.get()).requestId!!;assertNotEquals(first,second)
            val before = prefs.all.toMap();controller.callback(first);assertEquals(before,prefs.all)
            controller.callback(second);assertEquals("UNCONFIRMED",prefs.getString("state",null))
        } finally { activity.pause().stop().destroy() }
    }
}
