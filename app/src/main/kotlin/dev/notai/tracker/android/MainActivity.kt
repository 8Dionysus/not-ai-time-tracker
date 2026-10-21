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
        val id = launchedDocumentExport
        val owner = launchedDocumentExportOwner
        launchedDocumentExport = null
        launchedDocumentExportOwner = null
        vm.receiveDocumentExportResult(id,owner,uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        StartupTrace.mark(this, "activity-create")
        launchedDocumentExport = savedInstanceState?.getString("launchedDocumentExport")
        launchedDocumentExportOwner = savedInstanceState?.getString("launchedDocumentExportOwner")
        vm.recoverDocumentExport(launchedDocumentExport,launchedDocumentExportOwner)
        if(savedInstanceState == null && receiveShortcutChoice(intent)) vm.receiveSurfacePicker(null)
        else vm.receiveSurfacePicker(SurfaceChoices.restore(savedInstanceState,intent))
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
        if(!receiveShortcutChoice(intent)) SurfaceChoices.parse(intent)?.let(vm::receiveSurfacePicker)
        TimerSurfaces.parse(intent)?.takeIf { it.source == Surface.SHORTCUT }?.let { shortcutPending = it }
        intent.action = Intent.ACTION_MAIN; setIntent(intent)
        consumeShortcut()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshCapabilities(reconcileForeground = true)
        // Overlay may only start from a visible Activity; widget/notification are redrawn on explicit events.
        vm.refreshSurfaces { runtime, view ->
            TimerSurfaces.refresh(this, runtime.tracker, view, forceWidget = true)
            FocusSignals.refresh(this, view)
            runOnUiThread { if (!isFinishing) FloatingTimerService.fromVisibleActivity(this, view.data.settings.surfaces) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        TimerSurfaces.saveShortcut(this, outState, shortcutPending)
        SurfaceChoices.save(outState,vm.surfacePickerRoute())
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

    /** A favourite from the launcher menu: its route plus the chosen reference, applied once without showing the picker. */
    private fun receiveShortcutChoice(incoming: Intent): Boolean {
        val route = SurfaceChoices.parse(incoming)?.takeIf { it.source == Surface.SHORTCUT.name } ?: return false
        val reference = incoming.getStringExtra(QuickChoiceReceiver.EXTRA_CHOICE)?.takeIf { it.length <= 4096 }
            ?.let { runCatching { dev.notai.tracker.codec.decodeFromString(dev.notai.tracker.ChoiceReference.serializer(), it) }.getOrNull() } ?: return false
        incoming.removeExtra(QuickChoiceReceiver.EXTRA_CHOICE)
        vm.chooseFromShortcut(route, reference)
        return true
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
