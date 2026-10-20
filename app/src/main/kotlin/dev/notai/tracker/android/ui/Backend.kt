package dev.notai.tracker.android.ui

import android.content.Context
import dev.notai.tracker.Backups
import dev.notai.tracker.CatalogPreview
import dev.notai.tracker.Tracker
import dev.notai.tracker.android.FocusDueSignals
import dev.notai.tracker.android.SurfaceAction
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
    /** Optional ordinary-timer check-in. Exists only with the real runtime unless a test supplies one. */
    val longRecord: dev.notai.tracker.LongRecordReminder? get() = runtime?.attention?.ordinary?.controller
    /** The catalog this build ships with; null where no bundled catalog exists (screen tests). */
    val bundledCatalogVersion: String? get() = runtime?.bundledCatalogVersion
    fun prepareCatalog(): CatalogPreview
    fun applyCatalog(preview: CatalogPreview): Tracker.Change
    fun submitShortcut(action: SurfaceAction): Deferred<Result<Tracker.Change>>
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
