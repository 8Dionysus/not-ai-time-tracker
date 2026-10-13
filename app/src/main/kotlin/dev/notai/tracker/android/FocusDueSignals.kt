package dev.notai.tracker.android

import dev.notai.tracker.FocusMode
import dev.notai.tracker.FocusSnapshot

/** Runtime-owned visible-signal delivery. Retain two runs so a one-step Undo does not replay a cue.
 * A failed callback remains retryable. No scheduler, sound, history writes or phase transition.
 */
class FocusDueSignals {
    private val delivered = linkedMapOf<String,MutableSet<String>>()
    @Synchronized fun consume(snapshot: FocusSnapshot?,show: (FocusSnapshot)->Unit): Boolean {
        val focus = snapshot?.takeIf { it.mode == FocusMode.DUE } ?: return false
        val key = focus.signalKey ?: return false
        if(delivered[focus.id]?.contains(key) == true) return false
        val keys = delivered.getOrPut(focus.id) { linkedSetOf() }
        keys.add(key)
        try { show(focus) } catch(e: Exception) { keys.remove(key); throw e }
        while(delivered.size > 2) delivered.remove(delivered.keys.first())
        return true
    }
}
