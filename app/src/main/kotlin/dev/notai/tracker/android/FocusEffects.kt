package dev.notai.tracker.android

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import dev.notai.tracker.FocusEnvironmentChoice
import dev.notai.tracker.FocusEnvironmentState
import dev.notai.tracker.FocusMode
import dev.notai.tracker.FocusPhase
import dev.notai.tracker.Tracker
import java.io.File
import java.util.UUID

/**
 * The person's choice of what a Focus work interval does to the phone: «Не беспокоить» through the app's own
 * Do Not Disturb rule, and optionally a local audio file played while the app is open. Device-local; nothing
 * here travels in backups, and the core controller still decides and releases every effect.
 */
object FocusEffects {
    private const val PREFS = "focus-effects"
    data class Choice(val dnd: Boolean = false, val music: Boolean = false, val musicId: String? = null, val musicName: String? = null)

    fun read(c: Context): Choice = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p ->
        Choice(p.getBoolean("dnd", false), p.getBoolean("music", false), p.getString("musicId", null), p.getString("musicName", null))
    }
    fun write(c: Context, v: Choice) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("dnd", v.dnd).putBoolean("music", v.music)
            .putString("musicId", v.musicId).putString("musicName", v.musicName).apply()
    }
    private fun dir(c: Context) = File(c.noBackupFilesDir, "focus-audio")
    fun file(c: Context, id: String): File? = File(dir(c), id).takeIf { id.matches(Regex("[0-9a-f-]{36}")) && it.isFile }

    /** Copies the chosen sound into private storage once, so playback never depends on a revocable URI. IO only. */
    fun importAudio(c: Context, uri: Uri, name: String?): Choice {
        val id = UUID.randomUUID().toString()
        val target = File(dir(c).apply { mkdirs() }, id)
        c.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                    if (total > 64L * 1024 * 1024) { target.delete(); throw java.io.IOException("Audio over 64 MiB") }
                    out.write(buffer, 0, n) }
            }
        } ?: throw java.io.IOException("Audio source unavailable")
        val old = read(c)
        val next = old.copy(music = true, musicId = id, musicName = name)
        write(c, next)
        old.musicId?.let { prev -> if (prev != id) file(c, prev)?.delete() }
        return next
    }

    fun dndAccess(c: Context): Boolean = Build.VERSION.SDK_INT >= 29 &&
        c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    fun dndSupported(): Boolean = Build.VERSION.SDK_INT >= 29

    /** Process-wide bindings the Android adapters read. Set once from the Application. */
    fun install(c: Context) {
        val app = c.applicationContext
        FocusEnvironmentBinding.rule = FocusRuleConfiguration(ComponentName(app, FocusRuleConfigurationActivity::class.java),
            L.s(R.string.t_13b7c69527), L.s(R.string.t_f4ca346393))
        FocusEnvironmentBinding.localFile = { id -> file(app, id) }
    }

    @Volatile private var begun: Pair<String, Long>? = null
    /** After each committed view: a fresh work interval with an opted-in effect gets one begin, at most once per phase. */
    @Synchronized internal fun maybeBegin(c: Context, tracker: Tracker, focus: dev.notai.tracker.FocusEnvironment, state: FocusEnvironmentState) {
        val choice = read(c)
        if (!choice.dnd && !(choice.music && choice.musicId != null)) return
        val snapshot = tracker.snapshot(); val f = snapshot.focus ?: return
        if (f.phase != FocusPhase.WORK || f.mode != FocusMode.RUNNING) return
        val key = f.id to f.phaseIndex
        val phase = "${snapshot.token.datasetId}:${f.id}:${f.phaseIndex}"
        val attempts = c.getSharedPreferences("focus-effect-attempts",Context.MODE_PRIVATE)
        if (begun == key || attempts.getString("phase",null) == phase) return
        // Durable before begin: process recreation is cleanup-only, never an implicit retry in the same phase.
        check(attempts.edit().putString("phase",phase).commit()) { "Focus effect intent could not be saved" }
        begun = key
        if(state.request?.let { it.focusId == f.id && it.phaseIndex == f.phaseIndex } == true) return
        focus.begin(snapshot.token, UUID.randomUUID().toString(),
            FocusEnvironmentChoice(distractions = choice.dnd, music = choice.music && choice.musicId != null, localMusicId = choice.musicId.takeIf { choice.music }),
            state.controlRevision)
    }
}
