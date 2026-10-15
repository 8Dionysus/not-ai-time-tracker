package dev.notai.tracker.android

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.notai.tracker.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

class AndroidClock(context: Context): Clock {
    private val resolver = context.applicationContext.contentResolver
    override fun now() = Moment(System.currentTimeMillis(),SystemClock.elapsedRealtime(),
        "boot:" + Settings.Global.getInt(resolver,Settings.Global.BOOT_COUNT).toString(),PROCESS_ID)
    companion object { private val PROCESS_ID = newId() }
}
class PrivateRollbackStore(private val context: Context): RollbackStore {
    private val directory = File(context.noBackupFilesDir,"rollback").apply { mkdirs() }
    override fun <T> protecting(block: ()->T): T = PrivateFileMaintenance.writing(context,"rollback-consumer",CopyBlocker.REQUIRED_BY_PENDING_RECOVERY,block)
    override fun save(json: String): String = PrivateFileMaintenance.writing(context,"rollback-save") { saveUnsafe(json) }
    private fun saveUnsafe(json: String): String {
        val file = File(directory,"rollback-${newId()}.json")
        FileOutputStream(file).use { it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        syncDirectory(directory)
        PrivateCopyBackend.registerRollback(context,file.name)
        return file.name
    }
    override fun read(reference: String): String {
        require(File(reference).name == reference)
        return File(directory,reference).readText(Charsets.UTF_8)
    }
    fun files(): List<File> = directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("rollback-") && it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }
    override fun saveSnapshot(backup: Backup): String = PrivateFileMaintenance.writing(context,"rollback-snapshot") { saveSnapshotUnsafe(backup) }
    private fun saveSnapshotUnsafe(backup: Backup): String {
        val file = File(directory,"rollback-${newId()}.json")
        FileOutputStream(file).use { StreamingBackup.write(backup,it);it.fd.sync() }
        syncDirectory(directory)
        PrivateCopyBackend.registerRollback(context,file.name)
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
    val backups = Backups(tracker,rollback,CopyLifetimes.get(context))
    val historyTransfer = HistoryTransfer(tracker,rollback)
    val selectiveTransfer = SelectiveTransfer(tracker)
    val selectiveDocuments = SelectiveDocuments(context.applicationContext,tracker,selectiveTransfer)
    val schedule = ScheduleWorkspace(context.applicationContext,tracker)
    val scheduleProjection = ScheduleProjectionRefresh(context.applicationContext,tracker,schedule)
    val catalogContent by lazy { CatalogContentAdapter(context.applicationContext,readyCatalog) }
    val catalogImports = CatalogImports(tracker) { readyCatalog }
    /** Prepared vocabulary preview: no source parsing or research archiving on the phone. */
    fun prepareBundledCatalog(): CatalogPreview = catalogImports.prepare()
    val calendarOccurrences = CalendarOccurrences(context.applicationContext)
    val focusDueSignals = FocusDueSignals()
    val scheduleDelivery = AndroidScheduleDelivery(context.applicationContext,tracker)
    private val surfaceScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    internal val shortcuts = ShortcutCommands(tracker,surfaceScope)
    private val platformContext = context.applicationContext
    private val platformReceiver = object: android.content.BroadcastReceiver() {
        override fun onReceive(context: Context,intent: android.content.Intent) { refreshExistingPlatform(context) }
    }
    private var platformRegistered = false
    private fun platformChanges() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_DATE_CHANGED);addAction(android.content.Intent.ACTION_TIME_CHANGED);addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.app.NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(android.app.NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            addAction(android.app.NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED)
        }
        if(android.os.Build.VERSION.SDK_INT >= 33) platformContext.registerReceiver(platformReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else platformContext.registerReceiver(platformReceiver,filter)
        platformRegistered = true
    }
    private fun detachPlatform() { if(platformRegistered) { platformContext.unregisterReceiver(platformReceiver);platformRegistered = false } }
    init {
        StartupTrace.mark(context,"store-initialized")
        tracker.store.readChecked() // fail before publishing a runtime or starting observers
        StartupTrace.mark(context,"history-graph-ready");platformChanges()
        surfaceScope.launch {
            var previous: SurfaceSettings? = null
            var previousSchedule: ScheduleDeliveryReport? = null
            tracker.views.catch { e ->
                if(e is CancellationException) throw e
                android.util.Log.w("TimerSurfaces","Store observation unavailable; open recovery controls")
            }.collect { view ->
                try { scheduleProjection.synchronize() } catch(e: Exception) { if(e is CancellationException) throw e;android.util.Log.w("ScheduleProjection","Projection refresh pending") }
                try { TimerSurfaces.refresh(context,view,previous);FocusSignals.refresh(context,tracker) }
                catch(e: Exception) { if(e is CancellationException) throw e; android.util.Log.w("TimerSurfaces","Surface refresh unavailable") }
                previous = view.data.settings.surfaces
                val delivered = previousSchedule
                if(delivered == null || delivered.sourceDatasetId != view.data.datasetId || delivered.sourceBarrier != view.data.controlBarrier || delivered.sourceSchedule != view.data.schedule || delivered.sourcePreferences != view.data.settings.schedule) {
                    try { previousSchedule = scheduleDelivery.synchronize() }
                    catch(e: Exception) { if(e is CancellationException) throw e;android.util.Log.w("ScheduleDelivery","Schedule delivery refresh unavailable") }
                }
            }
        }
    }
    private fun refreshPlatform(focusContext: Context?) {
        val actions = listOf<() -> Unit>({ if(focusContext != null) FocusSignals.refresh(focusContext,tracker) },{ scheduleProjection.synchronize();Unit },{ scheduleDelivery.synchronize();Unit })
        for(action in actions) try { action() } catch(e: Exception) { if(e is CancellationException) throw e;android.util.Log.w("TrackerPlatform","Independent refresh pending; no facts changed") }
    }
    companion object {
        @Volatile private var instance: TrackerRuntime? = null
        @Volatile private var recovering = false
        internal fun refreshExistingPlatform(context: Context) {
            val runtime = instance ?: return
            runtime.surfaceScope.launch {
                runtime.refreshPlatform(context.applicationContext)
            }
        }
        internal fun refreshExistingSchedule() {
            val runtime = instance ?: return
            runtime.surfaceScope.launch {
                runtime.refreshPlatform(null)
            }
        }
        internal fun refreshExistingFocus(context: Context) {
            val runtime = instance ?: return
            runtime.surfaceScope.launch {
                try { FocusSignals.refresh(context.applicationContext,runtime.tracker) }
                catch(e: Exception) {
                    if(e is CancellationException) throw e
                    android.util.Log.w("FocusSignals","Current signal refresh unavailable")
                }
            }
        }
        /** Initialize on IO. All future surfaces share this boundary and expected-revision tokens. */
        fun get(context: Context): TrackerRuntime = PrivateFileMaintenance.forContext(context).locked { instance ?: synchronized(this) {
            if(recovering || DatabaseRecovery.needsAttention(context)) throw CoreError(ErrorCode.STORAGE,"A recovery operation needs completion; open recovery controls")
            instance ?: run {
                val newStore = !DatabaseFiles.exists(context)
                StartupTrace.mark(context,"store-open-start")
                val database = TrackerDatabase.open(context)
                try { TrackerRuntime(context.applicationContext,database,newStore).also { instance = it } }
                catch(e: Exception) { database.close();throw e }
            }
        } }
        internal fun beginRecovery() = synchronized(this) {
            ensure(!recovering,"Recovery already in progress",ErrorCode.STORAGE)
            recovering = true;instance?.detachPlatform();instance?.surfaceScope?.cancel();(instance?.tracker?.store as? RoomStore)?.retire();instance?.database?.close();instance = null
        }
        internal fun endRecovery() = synchronized(this) { recovering = false }
    }
}

internal fun newInstallData(catalog: ReadyCatalog,zone: String = java.time.ZoneId.systemDefault().id) =
    Dataset(types = catalog.vocabulary.types,axes = catalog.vocabulary.axes,metrics = catalog.vocabulary.metrics,
        units = catalog.vocabulary.units,catalogVersion = catalog.manifest.catalogVersion,settings = dev.notai.tracker.Settings(zone))
