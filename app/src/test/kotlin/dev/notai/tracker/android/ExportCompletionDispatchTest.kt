package dev.notai.tracker.android

import dev.notai.tracker.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Deterministic blocked registration port, not an OS I/O timing or native filesystem failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportCompletionDispatchTest {
    @Test fun durableResultAndChooserReleaseReturnWhileOptionalRegistrationIsBlocked() {
        val app = RuntimeEnvironment.getApplication()
        val sync = DirectoryDurability.sync;val rename = DirectoryDurability.rename
        val entered = CountDownLatch(1);val release = CountDownLatch(1);val returned = CountDownLatch(1);val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>();val thread = AtomicReference<Thread?>()
        DirectoryDurability.sync = { d -> java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        DirectoryDurability.rename = { a,b -> Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
        val exports = ExportOperations(app) { c,_ ->
            try {
                assertSame(app,c);thread.set(Thread.currentThread())
                PrivateFileMaintenance.forContext(c).locked {
                    entered.countDown();check(release.await(10,TimeUnit.SECONDS))
                }
            } finally { finished.countDown() }
        }
        var caller: Thread? = null
        try {
            val operation = exports.begin("text/plain","fixture bytes")
            val bytes = "retained staged bytes".toByteArray();exports.prepare(operation.id) { it.write(bytes) };exports.awaitResult(operation.id)
            val gate = PrivateFileMaintenance.forContext(app);val foreign = gate.acquire("foreign-owner",PrivateCopyBackend.itemId("exports/${operation.id}"),CopyBlocker.LIVE_FORM_REFERENCE)
            caller = Thread {
                try { exports.receiveResult(operation.id,true);returned.countDown() } catch(t: Throwable) { error.set(t) }
            }.apply { start() }
            assertTrue("Registration must actually enter the blocked port",entered.await(10,TimeUnit.SECONDS))
            assertTrue("Optional catalog registration must not hold the durable result callback",returned.await(500,TimeUnit.MILLISECONDS))
            assertNotSame(caller,thread.get());assertNull(error.get())
            val persisted = ExportOperations(app).read(operation.id)
            assertTrue(persisted.resultReceived);assertEquals("CANCELLED",persisted.state)
            assertArrayEquals(bytes,File(app.noBackupFilesDir,"exports/${operation.id}/data").readBytes())
            // Read the atomic durable file through a fresh decoder while the optional worker
            // owns the maintenance gate: the callback must already have released both refs.
            val durable = codec.decodeFromString<CopyMaintenanceState>(requireNotNull(
                PrivateFileMaintenance.metadata(File(app.noBackupFilesDir,"private-copy-maintenance.json")).read()))
            val refs = durable.references
            assertTrue(foreign in refs);assertFalse(refs.any { it.owner == "export-chooser:${operation.id}" })
            assertFalse(refs.any { it.owner == "export-result:${operation.id}" })
            assertTrue(durable.copies.none { it.item.id == foreign.itemId }) // Unregistered is not deletion authority.
        } finally {
            release.countDown();caller?.join(10000);finished.await(10,TimeUnit.SECONDS)
            DirectoryDurability.sync = sync;DirectoryDurability.rename = rename
        }
    }
}
