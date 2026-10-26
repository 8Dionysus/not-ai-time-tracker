package dev.notai.tracker.android.ui

import android.content.Context
import dev.notai.tracker.Backups
import dev.notai.tracker.CatalogPreview
import dev.notai.tracker.Tracker
import dev.notai.tracker.android.FocusDueSignals
import dev.notai.tracker.android.SurfaceAction
import dev.notai.tracker.android.AutomaticBackupState
import dev.notai.tracker.android.BackupPolicy
import dev.notai.tracker.android.TrackerRuntime
import kotlinx.coroutines.Deferred

/** What the interface needs from the process runtime. Production uses [TrackerRuntime];
 * Robolectric screen tests supply an in-memory Tracker with a fixed clock. */
interface UiBackend {
    val tracker: Tracker
    val backups: Backups
    val focusDue: FocusDueSignals
    /** Android surfaces (widget, notification, overlay) exist only with the real runtime. */
    val runtime: TrackerRuntime?
    val reportZone: dev.notai.tracker.android.ReportZonePreference? get() = runtime?.reportZone
    /** Optional ordinary-timer check-in. Exists only with the real runtime unless a test supplies one. */
    val longRecord: dev.notai.tracker.LongRecordReminder? get() = runtime?.attention?.ordinary?.controller
    /** The catalog this build ships with; null where no bundled catalog exists (screen tests). */
    val bundledCatalogVersion: String? get() = runtime?.bundledCatalogVersion
    /** Reviewed preset translations bundled with the APK; display only. */
    val catalogContent: dev.notai.tracker.android.CatalogContentAdapter? get() = runtime?.catalogContent
    fun prepareCatalog(): CatalogPreview
    fun applyCatalog(preview: CatalogPreview): Tracker.Change
    fun submitShortcut(action: SurfaceAction): Deferred<Result<Tracker.Change>>
    /** Events of the phone's calendar, read only after an explicit tap. Real runtime only. */
    val calendar: dev.notai.tracker.android.CalendarOccurrences? get() = runtime?.calendarOccurrences
    /** CSV history append with preview. Real runtime only, unless a test supplies one. */
    val historyTransfer: dev.notai.tracker.HistoryTransfer? get() = runtime?.historyTransfer
    /** Automatic copies into a folder the user picked. Real runtime only, unless a test supplies one. */
    val autoBackup: AutoBackupBackend? get() = runtime?.automaticBackups?.let { RuntimeAutoBackup(it) }
}

/** A CSV file being imported: the inspection and the name shown in the sheet. */
data class CsvImportUi(val inspection: dev.notai.tracker.ImportInspection, val fileName: String?)

/** Automatic copy state for Settings, with the chosen folder's readable name resolved once. */
data class AutoBackupUi(val state: AutomaticBackupState, val folder: String?)

/** What Settings needs from [dev.notai.tracker.android.AutomaticBackups]. Every call runs on IO. */
interface AutoBackupBackend {
    fun status(): AutomaticBackupState
    /** A short human name for the chosen folder: provider app and folder, never the raw URI. */
    fun folderName(tree: String): String?
    fun selectionIntent(): android.content.Intent
    fun select(uri: android.net.Uri, returnedFlags: Int, policy: BackupPolicy): AutomaticBackupState
    fun enable(tree: String, policy: BackupPolicy): AutomaticBackupState
    fun disable(): AutomaticBackupState
    fun retry()
    fun requestBackup()
    fun abandonPending(operationId: String)
    fun prepareRestore(operationId: String): dev.notai.tracker.RestorePreview
}

private class RuntimeAutoBackup(private val backups: dev.notai.tracker.android.AutomaticBackups) : AutoBackupBackend {
    override fun status() = backups.status()
    override fun folderName(tree: String): String? = runCatching {
        val uri = android.net.Uri.parse(tree)
        val app = backups.context
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri))
        val name = app.contentResolver.query(root, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val provider = uri.authority?.let { a -> app.packageManager.resolveContentProvider(a, 0)?.loadLabel(app.packageManager)?.toString() }
            ?.takeUnless { uri.authority == "com.android.externalstorage.documents" }
        listOfNotNull(provider, name).joinToString(" · ").ifBlank { null }
    }.getOrNull()
    override fun selectionIntent() = dev.notai.tracker.android.SafBackupDestination.selectionIntent()
    override fun select(uri: android.net.Uri, returnedFlags: Int, policy: BackupPolicy) = backups.select(uri, returnedFlags, policy)
    override fun enable(tree: String, policy: BackupPolicy) = backups.enable(tree, policy)
    override fun disable() = backups.disable()
    override fun retry() = backups.retry()
    override fun requestBackup() = backups.requestBackup()
    override fun abandonPending(operationId: String) = backups.abandonPending(operationId)
    override fun prepareRestore(operationId: String) = backups.prepareRestore(operationId)
}

class RuntimeBackend(override val runtime: TrackerRuntime) : UiBackend {
    override val tracker get() = runtime.tracker
    override val backups get() = runtime.backups
    override val focusDue get() = runtime.focusDueSignals
    override fun prepareCatalog() = runtime.prepareBundledCatalog()
    override fun applyCatalog(preview: CatalogPreview) = runtime.catalogImports.apply(preview)
    override fun submitShortcut(action: SurfaceAction) = runtime.shortcuts.submit(action)
}

object UiBackends {
    /** Opened on Dispatchers.IO. Replaced only by tests. */
    @Volatile var open: (Context) -> UiBackend = { RuntimeBackend(TrackerRuntime.get(it)) }
}
