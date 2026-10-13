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
            assertTrue(runBlocking { vm.claimDocumentExport(id,"owner-first") })
            assertFalse(runBlocking { vm.claimDocumentExport(id,"owner-first") })
            vm.releaseDocumentExport(id,"owner-first",android.content.ActivityNotFoundException("injected launch failure"))
            assertNull(vm.documentExport.value)
            vm.requestExport() // retry the same bytes, not a second preparation
            assertEquals(id,vm.documentExport.value)
            assertTrue(runBlocking { vm.claimDocumentExport(id,"owner-first") })
            val restored = TrackerViewModel(app);models.put("restored",restored)
            waitFor { restored.boot.value is TrackerViewModel.Boot.Ready }
            restored.recoverDocumentExport(null) // process died in claim -> launch gap
            waitFor { restored.documentExport.value == id }
            assertEquals(id,restored.documentExport.value)
            assertTrue(runBlocking { restored.claimDocumentExport(id,"owner-restored") })
            val normal = TrackerViewModel(app);models.put("normal",normal)
            waitFor { normal.boot.value is TrackerViewModel.Boot.Ready }
            normal.recoverDocumentExport(id) // normal recreation while chooser exists
            assertNull(normal.documentExport.value)
            assertEquals(id,restored.documentExportId())
            assertNull(restored.documentExport.value) // registered Activity result, no second chooser
            assertEquals(op.title,restored.exportTitle(id))
            // Lifecycle cancellation after the durable claim, before launch.
            restored.releaseDocumentExport(id,"owner-restored",IllegalStateException("setup"))
            restored.requestExport()
            val committed = CountDownLatch(1);val returnClaim = CountDownLatch(1)
            DocumentExportCoordination.afterClaimCommit = { committed.countDown();check(returnClaim.await(10,TimeUnit.SECONDS)) }
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val claimant = scope.launch {
                try { restored.claimDocumentExport(id,"old-lifecycle") }
                catch(e: CancellationException) { restored.releaseDocumentExport(id,"old-lifecycle",e);throw e }
            }
            assertTrue(committed.await(10,TimeUnit.SECONDS));claimant.cancel()
            val successor = TrackerViewModel(app);models.put("successor",successor)
            successor.recoverDocumentExport(null);waitFor { successor.documentExport.value == id }
            DocumentExportCoordination.afterClaimCommit = null
            assertTrue(runBlocking { successor.claimDocumentExport(id,"new-lifecycle") })
            returnClaim.countDown();runBlocking { claimant.join() };scope.cancel()
            successor.requestExport();assertNull(successor.documentExport.value) // old release cannot clear new owner
            assertFalse(runBlocking { successor.claimDocumentExport(id,"duplicate") })
            successor.writeExport(id,null)
            waitFor { successor.documentExportId() == null }
            assertEquals("CANCELLED",ExportOperations(app).read(id).state)
            assertEquals(before+1,java.io.File(app.noBackupFilesDir,"exports").listFiles().orEmpty().size)
            successor.requestExport();waitFor { !successor.exportPreparing.value && successor.documentExportId() != null }
            assertNotEquals(id,successor.documentExportId())
            assertEquals(before+2,java.io.File(app.noBackupFilesDir,"exports").listFiles().orEmpty().size)
        } finally {
            release.countDown();DocumentExportCoordination.afterClaimCommit = null;models.clear();UiBackends.open = previousOpen
            DirectoryDurability.sync = previousSync;DirectoryDurability.rename = previousRename
        }
    }
}
