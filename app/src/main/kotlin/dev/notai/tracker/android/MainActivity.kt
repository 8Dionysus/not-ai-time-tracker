package dev.notai.tracker.android

import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dev.notai.tracker.CoreError
import dev.notai.tracker.android.ui.TrackerApp
import dev.notai.tracker.android.ui.TrackerViewModel

/**
 * Product interface. All data access goes through [TrackerViewModel] → Tracker; this Activity only
 * routes system entry points (launcher shortcut, selected text) and refreshes user-enabled surfaces.
 */
class MainActivity : ComponentActivity() {
    private val vm: TrackerViewModel by viewModels()
    private var shortcutPending: SurfaceAction? = null
    private var tracedReady = false
    private var launchedDocumentExport: String? = null
    private var launchedDocumentExportOwner: String? = null
    private val saveExport = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        launchedDocumentExport = null
        launchedDocumentExportOwner = null
        vm.documentExportId()?.let { vm.writeExport(it,uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        StartupTrace.mark(this, "activity-create")
        launchedDocumentExport = savedInstanceState?.getString("launchedDocumentExport")
        launchedDocumentExportOwner = savedInstanceState?.getString("launchedDocumentExportOwner")
        vm.recoverDocumentExport(launchedDocumentExport,launchedDocumentExportOwner)
        shortcutPending = TimerSurfaces.restoredShortcut(savedInstanceState, intent)
        if (savedInstanceState == null) receiveSelectedText(intent)
        intent.action = Intent.ACTION_MAIN
        consumeShortcut()
        setContent { TrackerApp(vm, openTechnical = { startActivity(Intent(this, TechnicalActivity::class.java)) }, onActionReady = ::traceActionReady) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.documentExport.collect { id ->
                    val owner = dev.notai.tracker.newId()
                    if(id != null) try {
                        if(vm.claimDocumentExport(id,owner)) {
                            saveExport.launch(vm.exportTitle(id))
                            launchedDocumentExport = id
                            launchedDocumentExportOwner = owner
                        }
                    } catch(e: Exception) {
                        vm.releaseDocumentExport(id,owner,e)
                        if(e is kotlinx.coroutines.CancellationException) throw e
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveSelectedText(intent)
        TimerSurfaces.parse(intent)?.takeIf { it.source == Surface.SHORTCUT }?.let { shortcutPending = it }
        intent.action = Intent.ACTION_MAIN; setIntent(intent)
        consumeShortcut()
    }

    override fun onResume() {
        super.onResume()
        // Overlay may only start from a visible Activity; widget/notification are redrawn on explicit events.
        vm.refreshSurfaces { runtime, view ->
            TimerSurfaces.refresh(this, view, forceWidget = true)
            FocusSignals.refresh(this, view)
            runOnUiThread { if (!isFinishing) FloatingTimerService.fromVisibleActivity(this, view.data.settings.surfaces) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        TimerSurfaces.saveShortcut(this, outState, shortcutPending)
        launchedDocumentExport?.let { outState.putString("launchedDocumentExport",it) }
        launchedDocumentExportOwner?.let { outState.putString("launchedDocumentExportOwner",it) }
        super.onSaveInstanceState(outState)
    }

    /** Debug-only startup phases used by scripts/android_startup_samples.py: first composition with live timer controls, then its frame. */
    private fun traceActionReady() {
        if (tracedReady) return
        tracedReady = true
        StartupTrace.mark(this, "action-ready")
        val root = window.decorView
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                StartupTrace.mark(this@MainActivity, "action-first-frame")
                return true
            }
        })
    }

    /** The shortcut carries its original token; the process runtime executes it once, never replays it with a newer one. */
    private fun consumeShortcut() {
        val action = shortcutPending ?: return
        shortcutPending = null
        vm.submitShortcut(action)
    }

    /** Only text another app explicitly sent. It becomes an editable note; nothing is saved without a choice. */
    private fun receiveSelectedText(incoming: Intent) {
        try { ProcessTextDrafts.read(incoming)?.let(vm::receiveDraft) }
        catch (_: CoreError) { /* invalid share: ignored, history untouched */ }
        if (incoming.action == Intent.ACTION_PROCESS_TEXT) {
            incoming.removeExtra(Intent.EXTRA_PROCESS_TEXT)
            incoming.action = Intent.ACTION_MAIN
        }
    }
}
