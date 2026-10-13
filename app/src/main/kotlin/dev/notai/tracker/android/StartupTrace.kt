package dev.notai.tracker.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.Log

/** Debug-only scalar timings. Never logs payloads, names, paths or user content. */
internal object StartupTrace {
    fun loadObserver(context: Context): ((String,Long)->Unit)? =
        if(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) null
        else { phase,ns -> mark(context,"load-$phase",ns) }
    fun mark(context: Context,phase: String,durationNs: Long? = null) {
        if(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        Log.i("TrackerStartup","phase=$phase elapsedNs=${SystemClock.elapsedRealtimeNanos()} processStartMs=${Process.getStartElapsedRealtime()} pid=${Process.myPid()}" +
            (durationNs?.let { " durationNs=$it" } ?: ""))
    }
}
