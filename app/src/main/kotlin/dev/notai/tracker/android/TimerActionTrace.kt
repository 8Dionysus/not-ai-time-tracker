package dev.notai.tracker.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only scalar timings. No activity names, intents, tokens, or history content. */
internal class TimerActionTrace private constructor(private val id: Int) {
    private val start = SystemClock.elapsedRealtimeNanos()
    fun mark(stage: String) {
        Log.d("TimerActionTiming", "id=$id stage=$stage elapsedMs=${(SystemClock.elapsedRealtimeNanos()-start)/1_000_000}")
    }
    companion object {
        private val sequence = AtomicInteger()
        fun begin(context: Context): TimerActionTrace? =
            if(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                TimerActionTrace(sequence.incrementAndGet()).also { it.mark("received") }
            else null
    }
}
