package dev.notai.tracker.android

import android.app.Activity
import android.app.Application
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.*
import android.os.Build
import android.os.Bundle
import dev.notai.tracker.Tracker
import kotlinx.coroutines.*

/** Owner-applied composition root for the two optional controllers. No display/UI callers,
 * periodic loop, new service, schema or alternate timer. synchronize is blocking, IO only.
 * The existing TrackerRuntime observer calls it after each committed domain view.
 */
class AttentionRuntime(context: Context,tracker: Tracker) {
    private val context = context.applicationContext
    @Volatile private var closed = false
    private val visibility = Any()
    val ordinary = AndroidLongRecordReminders(this.context,tracker) { !closed }
    val focus = AndroidFocusEnvironment(this.context,tracker) { !closed }
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var resumed = 0
    private val lifecycle = object: Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            synchronized(visibility) {
                if(closed) return
                resumed++;focus.foregroundHint(true)
            }
            refresh()
        }
        override fun onActivityPaused(activity: Activity) {
            synchronized(visibility) {
                if(closed) return
                resumed = maxOf(0,resumed-1)
                focus.foregroundHint(resumed > 0) // prevent starts now; IO refresh releases our player
            }
            refresh()
        }
        override fun onActivityCreated(activity: Activity,savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity,outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    private val receiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) { refresh() }
    }
    init {
        // Runtime may be initialized after the host's first onResume callback. This is only a
        // conservative local hint; Android still decides the actual audio-focus request.
        val importance = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(importance)
        focus.foregroundHint(importance.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        (this.context as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED);addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
        }
        try {
            if(Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
            else this.context.registerReceiver(receiver,filter)
        } catch(e: Exception) {
            (this.context as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
            focus.foregroundHint(false);scope.cancel();throw e
        }
    }
    fun refresh() { if(!closed) scope.launch { synchronize() } }
    fun synchronize() {
        for(action in listOf<()->Unit>({ ordinary.synchronize();Unit },{ focus.synchronize();Unit })) try {
            if(closed) return
            action()
        }
        catch(e: Exception) {
            if(e is CancellationException) throw e
            android.util.Log.w("AttentionRuntime","Optional controller unavailable; facts unchanged")
        }
    }
    /** Call on IO before retiring/closing TrackerRuntime's database during recovery. */
    fun close() {
        synchronized(visibility) {
            closed = true // Final gates reject a prepared post/start even if IO is already in flight.
            focus.foregroundHint(false)
        }
        scope.cancel();runCatching { context.unregisterReceiver(receiver) }
        runCatching { (context as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle) }
        focus.foregroundChanged(false)
        try { ordinary.controller.suspendDelivery() }
        catch(_: Exception) { android.util.Log.w("AttentionRuntime","Ordinary reminder revocation remains pending") }
        try { AndroidFocusEnvironment.releaseOwned(context) }
        catch(_: Exception) { android.util.Log.w("AttentionRuntime","Owned-rule cleanup remains pending during recovery") }
    }
}
