package dev.notai.tracker.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import dev.notai.tracker.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

/** Actual TechnicalActivity methods, framework recreation/results and real private bytes/registry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TechnicalExportLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val oldSync = DirectoryDurability.sync;private val oldRename = DirectoryDurability.rename
    private lateinit var controller: ActivityController<TechnicalActivity>
    @Before fun setup() {
        DirectoryDurability.sync = { FileChannel.open(it.toPath(),StandardOpenOption.READ).use { fd -> fd.force(true) } }
        DirectoryDurability.rename = { a,b -> Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
        android.provider.Settings.Global.putInt(app.contentResolver,"boot_count",1)
        TrackerRuntime.beginRecovery() // This fixture exercises export, not database bootstrap.
        controller = Robolectric.buildActivity(TechnicalActivity::class.java).create().start().resume()
    }
    @After fun close() {
        controller.pause().stop().destroy();TrackerRuntime.endRecovery()
        DirectoryDurability.sync = oldSync;DirectoryDurability.rename = oldRename
    }
    private fun waitFor(predicate: () -> Boolean) {
        val end = System.nanoTime()+10_000_000_000L
        while(!predicate()) { shadowOf(Looper.getMainLooper()).idle();if(System.nanoTime()>end) throw AssertionError("Technical export fixture timed out");Thread.sleep(5) }
    }
    private fun invoke(name: String,vararg arguments: Any?) = TechnicalActivity::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(controller.get(),*arguments)
    private fun pending() = TechnicalActivity::class.java.getDeclaredField("pendingExportId").apply { isAccessible = true }.get(controller.get()) as String?
    private fun nextPicker() {
        var seen = false
        waitFor {
            shadowOf(controller.get()).nextStartedActivityForResult?.let {
                assertEquals(1,it.requestCode);assertEquals(Intent.ACTION_CREATE_DOCUMENT,it.intent.action);seen = true
            }
            seen
        }
    }
    private fun begin(): String {
        val writer: (java.io.OutputStream) -> Unit = { it.write("Technical export fixture bytes".toByteArray()) }
        invoke("beginExport","application/json","fixture.json",writer)
        val id = requireNotNull(pending());nextPicker();assertEquals("READY",ExportOperations(app).read(id).state);return id
    }
    private fun result(uri: Uri?) = invoke("onActivityResult",1,if(uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,uri?.let { Intent().setData(it) })
    private fun assertRegistered(id: String,state: String) {
        DescriptorCopyFiles(app.noBackupFilesDir.path,requireNotNull(System.getProperty("tracker.cleanup.library"))).use { files ->
            val catalog = PrivateCopyCatalog(files,PrivateFileMaintenance.forContext(app));val opaque = catalog.registerExport(id)
            val item = catalog.inventory().items.single { it.id == opaque }
            assertEquals(state,item.state);assertFalse(item.hardBlockers.contains(CopyBlocker.CHOOSER_RESULT_PENDING))
        }
    }
    @Test fun chooserLeaseIsDurableBeforeExternalPickerAndSurvivesRecreation() {
        val id = begin();val gate = PrivateFileMaintenance.forContext(app)
        val held = gate.state().references.singleOrNull { it.owner == "export-chooser:$id" }
        assertNotNull("Technical picker must own its durable chooser lease",held)
        controller.recreate();assertEquals(id,pending())
        assertEquals(held,gate.state().references.single { it.owner == "export-chooser:$id" })
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        result(null);assertTrue(ExportOperations(app).read(id).resultReceived)
    }
    @Test fun successConsumesChooserBeforeWriteAndRegistersExactCompleteGroup() {
        val id = begin();val exports = ExportOperations(app);val staged = File(app.noBackupFilesDir,"exports/$id/data");val bytes = staged.readBytes()
        val gate = PrivateFileMaintenance.forContext(app);val other = gate.acquire("another-copy-owner",PrivateCopyBackend.itemId("exports/$id"),CopyBlocker.RESTORE_SOURCE_IN_USE)
        val destination = File(app.noBackupFilesDir,"technical-destination.json");result(Uri.fromFile(destination))
        waitFor { exports.read(id).state == "COMPLETE" };assertTrue(exports.read(id).resultReceived)
        assertArrayEquals(bytes,staged.readBytes());assertArrayEquals(bytes,destination.readBytes())
        assertTrue(other in gate.state().references);assertFalse(gate.state().references.any { it.owner == "export-chooser:$id" })
        assertRegistered(id,"COMPLETE")
    }
    @Test fun cancelConsumesChooserPreservesBytesAndRegistersRetryWarning() {
        val id = begin();val exports = ExportOperations(app);val staged = File(app.noBackupFilesDir,"exports/$id/data");val bytes = staged.readBytes()
        result(null);waitFor { exports.read(id).state == "CANCELLED" }
        assertTrue(exports.read(id).resultReceived);assertArrayEquals(bytes,staged.readBytes());assertEquals(id,pending())
        assertRegistered(id,"CANCELLED")
    }
    @Test fun cancelledCopyRetriesSameBytesAcrossActivityRecreation() {
        val id = begin();val exports = ExportOperations(app);val before = exports.read(id)
        result(null);waitFor { exports.read(id).state == "CANCELLED" };assertTrue(exports.read(id).resultReceived)
        controller.recreate();assertEquals(id,pending());invoke("retryExport");nextPicker()
        assertFalse(exports.read(id).resultReceived);assertEquals(before.bytes,exports.read(id).bytes);assertEquals(before.sha256,exports.read(id).sha256)
        controller.recreate();assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        val destination = File(app.noBackupFilesDir,"technical-retry.json");result(Uri.fromFile(destination))
        waitFor { exports.read(id).state == "COMPLETE" };assertTrue(exports.read(id).resultReceived)
        assertEquals(before.sha256,fileHash(destination));assertRegistered(id,"COMPLETE")
    }
    @Test fun explicitCancelDuringPreparationBecomesTerminalOnlyAfterWriterReleases() {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val writer:(java.io.OutputStream)->Unit={ out -> out.write("Before cancellation".toByteArray());entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS));out.write(" retained tail".toByteArray()) }
        invoke("beginExport","application/json","cancel-preparing.json",writer)
        val id=requireNotNull(pending());waitFor { entered.count==0L }
        val exports=ExportOperations(app);val gate=PrivateFileMaintenance.forContext(app)
        try {
            invoke("cancelPreparedExport");waitFor { exports.read(id).state=="CANCELLED" }
            assertTrue("An explicitly cancelled preparation has no chooser result left to receive",exports.read(id).resultReceived)
            assertTrue(gate.state().references.any { it.blocker==CopyBlocker.EXPORT_WRITING })
        } finally { release.countDown();waitFor { gate.state().references.none { it.owner=="export-prepare:$id" } } }
        waitFor { exports.read(id).bytes>0 && gate.state().references.none { it.owner=="export-prepare:$id" } }
        assertTrue(exports.read(id).resultReceived);assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        assertEquals("Before cancellation retained tail",File(app.noBackupFilesDir,"exports/$id/data").readText())
        assertRegistered(id,"CANCELLED")
    }
    @Test fun explicitCancelCannotLoseActiveChooserOrLaunchAnotherBeforeResult() {
        val id=begin();val exports=ExportOperations(app);val gate=PrivateFileMaintenance.forContext(app)
        val held=gate.state().references.single { it.owner=="export-chooser:$id" }
        val foreign=gate.acquire("foreign-owner-kept",PrivateCopyBackend.itemId("exports/$id"),CopyBlocker.RESTORE_SOURCE_IN_USE)
        invoke("cancelPreparedExport");waitFor { exports.read(id).state=="CANCELLED" }
        assertEquals(id,pending());assertFalse(exports.read(id).resultReceived);assertTrue(held in gate.state().references)
        invoke("retryExport");shadowOf(Looper.getMainLooper()).idle();assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        val writer:(java.io.OutputStream)->Unit={ it.write("No second preparation".toByteArray()) }
        invoke("beginExport","application/json","not-launched.json",writer);assertEquals(id,pending())
        result(null);assertTrue(exports.read(id).resultReceived);assertFalse(held in gate.state().references);assertTrue(foreign in gate.state().references)
        assertRegistered(id,"CANCELLED")
    }

}
