package dev.notai.tracker.android.ui

import dev.notai.tracker.*
import dev.notai.tracker.android.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiExportLifecycleTest {
    @Test fun exportBelongsToViewModelAndDocumentIdSurvivesRecreation() {
        val app = RuntimeEnvironment.getApplication()
        val previousOpen = UiBackends.open
        val previousSync = DirectoryDurability.sync;val previousRename = DirectoryDurability.rename
        DirectoryDurability.rename = { a,b -> java.nio.file.Files.move(a.toPath(),b.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);Unit }
        DirectoryDurability.sync = { d -> java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        val entered = CountDownLatch(1);val release = CountDownLatch(1)
        val store = object: StateStore {
            private var value = Dataset()
            override val changes = MutableStateFlow(value)
            override fun read() = value
            @Synchronized override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
                val (next,result) = block(value);validate(next);value = next;changes.value = next;return result
            }
        }
        val moment = Moment(1_800_000_000_000,1000,"export-test")
        val tracker = Tracker(store,Clock { moment })
        val backupTracker = Tracker(store,Clock { entered.countDown();check(release.await(10,TimeUnit.SECONDS));moment })
        val rollback = object: RollbackStore {
            override fun save(json: String) = error("unused")
            override fun read(reference: String) = error("unused")
        }
        val backend = object: UiBackend {
            override val tracker = tracker
            override val backups = Backups(backupTracker,rollback)
            override val focusDue = FocusDueSignals()
            override val runtime: TrackerRuntime? = null
            override fun prepareCatalog(): CatalogPreview = error("unused")
            override fun applyCatalog(preview: CatalogPreview): Tracker.Change = error("unused")
            override fun submitShortcut(action: SurfaceAction) = CompletableDeferred<Result<Tracker.Change>>()
        }
        UiBackends.open = { backend }
        val models = androidx.lifecycle.ViewModelStore()
        fun waitFor(predicate: ()->Boolean) {
            val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(!predicate()) { check(System.nanoTime()<end);Thread.sleep(10) }
        }
        try {
            val vm = TrackerViewModel(app);models.put("first",vm);waitFor { vm.boot.value is TrackerViewModel.Boot.Ready }
            val before = java.io.File(app.noBackupFilesDir,"exports").listFiles().orEmpty().size
            vm.requestExport()
            assertTrue(entered.await(10,TimeUnit.SECONDS));assertTrue(vm.exportPreparing.value)
            vm.requestExport() // repeated tap cannot create a second in-flight copy
            release.countDown()
            waitFor { !vm.exportPreparing.value && vm.documentExportId() != null }
            val id = vm.documentExportId()!!;val op = ExportOperations(app).read(id)
            assertEquals("READY",op.state);assertTrue(op.bytes>0)
            assertEquals(before+1,java.io.File(app.noBackupFilesDir,"exports").listFiles().orEmpty().size)
            assertTrue(runBlocking { vm.claimDocumentExport(id) })
            assertFalse(runBlocking { vm.claimDocumentExport(id) })
            val restored = TrackerViewModel(app);models.put("restored",restored)
            waitFor { restored.boot.value is TrackerViewModel.Boot.Ready }
            assertEquals(id,restored.documentExportId())
            assertNull(restored.documentExport.value) // registered Activity result, no second chooser
            assertEquals(op.title,restored.exportTitle(id))
            restored.writeExport(id,null)
            waitFor { restored.documentExportId() == null }
            assertEquals("CANCELLED",ExportOperations(app).read(id).state)
            assertEquals(before+1,java.io.File(app.noBackupFilesDir,"exports").listFiles().orEmpty().size)
        } finally {
            release.countDown();models.clear();UiBackends.open = previousOpen
            DirectoryDurability.sync = previousSync;DirectoryDurability.rename = previousRename
        }
    }
}
