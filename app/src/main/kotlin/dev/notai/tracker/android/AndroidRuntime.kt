package dev.notai.tracker.android

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.notai.tracker.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*

class AndroidClock(context: Context): Clock {
    private val resolver = context.applicationContext.contentResolver
    override fun now() = Moment(System.currentTimeMillis(),SystemClock.elapsedRealtime(),
        "boot:" + Settings.Global.getInt(resolver,Settings.Global.BOOT_COUNT).toString())
}
class PrivateRollbackStore(context: Context): RollbackStore {
    private val directory = File(context.noBackupFilesDir,"rollback").apply { mkdirs() }
    override fun save(json: String): String {
        val file = File(directory,"rollback-${newId()}.json")
        FileOutputStream(file).use { it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        val dirFd = android.system.Os.open(directory.absolutePath,android.system.OsConstants.O_RDONLY,0)
        try { android.system.Os.fsync(dirFd) } finally { android.system.Os.close(dirFd) }
        return file.name
    }
    override fun read(reference: String): String {
        require(File(reference).name == reference)
        return File(directory,reference).readText(Charsets.UTF_8)
    }
}
class TrackerRuntime private constructor(context: Context) {
    val database = TrackerDatabase.open(context)
    val tracker = Tracker(RoomStore(database),AndroidClock(context))
    private val rollback = PrivateRollbackStore(context)
    val backups = Backups(tracker,rollback)
    val historyTransfer = HistoryTransfer(tracker,rollback)
    private val surfaceScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    internal val shortcuts = ShortcutCommands(tracker,surfaceScope)
    init {
        surfaceScope.launch {
            var previous: SurfaceSettings? = null
            tracker.views.collect { view ->
                try { TimerSurfaces.refresh(context,view,previous) }
                catch(e: Exception) { if(e is CancellationException) throw e; android.util.Log.w("TimerSurfaces","Surface refresh unavailable") }
                previous = view.data.settings.surfaces
            }
        }
    }
    companion object {
        @Volatile private var instance: TrackerRuntime? = null
        /** Initialize on IO. All future surfaces share this boundary and expected-revision tokens. */
        fun get(context: Context): TrackerRuntime = instance ?: synchronized(this) {
            instance ?: TrackerRuntime(context.applicationContext).also { instance = it }
        }
    }
}
