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
        "boot:" + Settings.Global.getInt(resolver,Settings.Global.BOOT_COUNT).toString(),PROCESS_ID)
    companion object { private val PROCESS_ID = newId() }
}
class PrivateRollbackStore(context: Context): RollbackStore {
    private val directory = File(context.noBackupFilesDir,"rollback").apply { mkdirs() }
    override fun save(json: String): String {
        val file = File(directory,"rollback-${newId()}.json")
        FileOutputStream(file).use { it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        syncDirectory(directory)
        return file.name
    }
    override fun read(reference: String): String {
        require(File(reference).name == reference)
        return File(directory,reference).readText(Charsets.UTF_8)
    }
    fun files(): List<File> = directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("rollback-") && it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }
    override fun saveSnapshot(backup: Backup): String {
        val file = File(directory,"rollback-${newId()}.json")
        FileOutputStream(file).use { StreamingBackup.write(backup,it);it.fd.sync() }
        syncDirectory(directory)
        return file.name
    }
    override fun readSnapshot(reference: String): Backup {
        require(File(reference).name == reference)
        val file = File(directory,reference)
        return StreamingBackup.read { file.inputStream() }
    }
}
class TrackerRuntime private constructor(context: Context,val database: TrackerDatabase,newStore: Boolean) {
    private val readyCatalog by lazy { ReadyCatalog.read { context.applicationContext.assets.open("catalog/$it") } }
    val tracker = Tracker(RoomStore(database,initial = {
        val catalog = readyCatalog
        newInstallData(catalog)
    },allowInitialize = newStore,onLoadPhase = StartupTrace.loadObserver(context)),AndroidClock(context))
    private val rollback = PrivateRollbackStore(context)
    val backups = Backups(tracker,rollback)
    val historyTransfer = HistoryTransfer(tracker,rollback)
    val catalogImports = CatalogImports(tracker) { readyCatalog }
    /** Prepared vocabulary preview: no source parsing or research archiving on the phone. */
    fun prepareBundledCatalog(): CatalogPreview = catalogImports.prepare()
    val calendarOccurrences = CalendarOccurrences(context.applicationContext)
    val focusDueSignals = FocusDueSignals()
    private val surfaceScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    internal val shortcuts = ShortcutCommands(tracker,surfaceScope)
    init {
        StartupTrace.mark(context,"store-initialized")
        tracker.store.readChecked() // fail before publishing a runtime or starting observers
        StartupTrace.mark(context,"history-graph-ready")
        surfaceScope.launch {
            var previous: SurfaceSettings? = null
            tracker.views.collect { view ->
                try { TimerSurfaces.refresh(context,view,previous);FocusSignals.refresh(context,view) }
                catch(e: Exception) { if(e is CancellationException) throw e; android.util.Log.w("TimerSurfaces","Surface refresh unavailable") }
                previous = view.data.settings.surfaces
            }
        }
    }
    companion object {
        @Volatile private var instance: TrackerRuntime? = null
        @Volatile private var recovering = false
        /** Initialize on IO. All future surfaces share this boundary and expected-revision tokens. */
        fun get(context: Context): TrackerRuntime = instance ?: synchronized(this) {
            if(recovering || DatabaseRecovery.needsAttention(context)) throw CoreError(ErrorCode.STORAGE,"A recovery operation needs completion; open recovery controls")
            instance ?: run {
                val newStore = !DatabaseFiles.exists(context)
                StartupTrace.mark(context,"store-open-start")
                val database = TrackerDatabase.open(context)
                try { TrackerRuntime(context.applicationContext,database,newStore).also { instance = it } }
                catch(e: Exception) { database.close();throw e }
            }
        }
        internal fun beginRecovery() = synchronized(this) {
            ensure(!recovering,"Recovery already in progress",ErrorCode.STORAGE)
            recovering = true;instance?.surfaceScope?.cancel();(instance?.tracker?.store as? RoomStore)?.retire();instance?.database?.close();instance = null
        }
        internal fun endRecovery() = synchronized(this) { recovering = false }
    }
}

internal fun newInstallData(catalog: ReadyCatalog,zone: String = java.time.ZoneId.systemDefault().id) =
    Dataset(types = catalog.vocabulary.types,axes = catalog.vocabulary.axes,metrics = catalog.vocabulary.metrics,
        units = catalog.vocabulary.units,catalogVersion = catalog.manifest.catalogVersion,settings = dev.notai.tracker.Settings(zone))
