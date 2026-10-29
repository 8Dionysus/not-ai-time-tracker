package dev.notai.tracker.android

import android.app.job.JobScheduler
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import dev.notai.tracker.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Synthetic document provider / JobScheduler host evidence, never a live cloud or device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticBackupAndroidTest {
    private class Provider: ContentProvider() {
        var writable = true;var available = true;var mime = "application/json"
        override fun onCreate() = true
        override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,sort: String?): android.database.Cursor {
            if(!available) throw java.io.FileNotFoundException("synthetic offline")
            val columns = projection ?: emptyArray()
            val values = columns.map {
                when(it) {
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> DocumentsContract.Document.MIME_TYPE_DIR
                    DocumentsContract.Document.COLUMN_FLAGS -> if(writable) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
                    else -> "root"
                }
            }.toTypedArray()
            return MatrixCursor(columns).apply { addRow(values) }
        }
        override fun getType(uri: Uri) = mime
        override fun insert(uri: Uri,values: ContentValues?): Uri? = null
        override fun delete(uri: Uri,selection: String?,args: Array<out String>?) = 0
        override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?) = 0
    }
    @Test fun actualGrantCapabilityAndAvailabilityAreRequiredNotJustUri() {
        val context = RuntimeEnvironment.getApplication();val provider = Provider()
        provider.attachInfo(context,ProviderInfo().apply { authority = "synthetic.backup";exported = true;grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("synthetic.backup",provider)
        val uri = Uri.parse("content://synthetic.backup/tree/root");val saf = SafBackupDestination(context)
        fun reason(block: ()->Unit): AutomaticBackupError {
            try { block();fail("Expected typed refusal") } catch(e: BackupDestinationException) { return e.reason }
            error("unreachable")
        }
        assertEquals(AutomaticBackupError.GRANT_REVOKED,reason { saf.check(uri.toString()) })
        assertEquals(AutomaticBackupError.REQUIRES_SELECTION,reason { saf.acceptSelection(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        assertEquals(uri.toString(),saf.acceptSelection(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        provider.writable = false;assertEquals(AutomaticBackupError.UNSUPPORTED,reason { saf.check(uri.toString()) })
        provider.writable = true;provider.available = false
        assertEquals(AutomaticBackupError.PROVIDER_UNAVAILABLE,reason { saf.check(uri.toString()) })
        provider.available = true;saf.check(uri.toString())
        context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(AutomaticBackupError.GRANT_REVOKED,reason { saf.check(uri.toString()) })
    }
    @Test fun existingStreamingStagingProviderWriteAndRestorePreserveDatasetAndVersion15() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication();val sync = DirectoryDurability.sync;val rename = DirectoryDurability.rename
        DirectoryDurability.sync = { d -> java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        DirectoryDurability.rename = { a,b -> Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
        val databases = mutableListOf<TrackerDatabase>()
        fun store() = RoomStore(TrackerDatabase.open(context,"backup-${newId()}.db").also { databases += it })
        try {
            val tracker = Tracker(store(),FakeClock());tracker.saveType(tracker.snapshot().token,ActivityType("own","Synthetic"))
            val original = tracker.data();val backups = Backups(tracker,MemoryRollback())
            val exports = ExportOperations(context) { _,_ -> };val operation = exports.beginOwned(newId(),"application/json","test.json")
            var captured: BackupSource? = null
            val prepared = exports.prepare(operation.id) { captured = backups.writeSnapshot(it) }
            assertEquals(original.revision,captured!!.revision)
            val uri = Uri.parse("content://synthetic.backup/tree/root/document/test")
            val output = ByteArrayOutputStream();val resolver = shadowOf(context.contentResolver)
            resolver.registerOutputStream(uri,output)
            resolver.registerInputStreamSupplier(uri) { output.toByteArray().inputStream() }
            val result = exports.retryWrite(operation.id,uri,prepared.bytes,requireNotNull(prepared.sha256))
            assertTrue(result.verified);assertEquals(prepared.bytes,result.bytes)
            val decoded = StreamingBackup.read { output.toByteArray().inputStream() };assertEquals(CURRENT_BACKUP_FORMAT,decoded.formatVersion)
            assertEquals(original.types,decoded.data.types);assertEquals(original.datasetId,decoded.data.datasetId)
            val target = Tracker(store(),FakeClock());val rollback = MemoryRollback();val restore = Backups(target,rollback)
            val preview = restore.prepare { output.toByteArray().inputStream() };restore.apply(preview)
            assertEquals(original.types,target.data().types);assertEquals(original.sessions,target.data().sessions);assertEquals(original.settings,target.data().settings)
            assertFalse(rollback.saved.isEmpty())
        } finally { databases.forEach { it.close() };DirectoryDurability.sync = sync;DirectoryDurability.rename = rename }
    }
    @Test fun restoreStagesOriginalV1ContentUrisWithoutDependingOnFilename() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication();val sync = DirectoryDurability.sync;val rename = DirectoryDurability.rename
        DirectoryDurability.sync = { d -> java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        DirectoryDurability.rename = { a,b -> Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
        val databases = mutableListOf<TrackerDatabase>()
        fun store() = RoomStore(TrackerDatabase.open(context,"old-restore-${newId()}.db").also { databases += it })
        try {
            val root = java.io.File(System.getProperty("tracker.samples"))
            val bytes = root.resolve("history-v1-initial.json").readBytes()
            val laterV1 = Backups.decode(root.resolve("history-v1.json").readText())
            val builtins = listOf(ActivityType("language","Языковая практика"),ActivityType("fitness","Тренировка"),
                ActivityType("reading","Чтение"),ActivityType("work","Работа"),ActivityType("rest","Отдых"))
            val expected = laterV1.copy(data = laterV1.data.copy(types = builtins))
            val resolver = shadowOf(context.contentResolver)
            val provider = Provider().apply { mime = "application/octet-stream" }
            provider.attachInfo(context,ProviderInfo().apply { authority = "synthetic.restore";exported = true })
            ShadowContentResolver.registerProviderInternal("synthetic.restore",provider)
            val variants = listOf(
                Uri.parse("content://synthetic.restore/without-extension") to bytes,
                Uri.parse("content://synthetic.restore/archive.bin") to (byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())+bytes))
            for((uri,content) in variants) {
                resolver.registerInputStreamSupplier(uri) { content.inputStream() }
                assertEquals("application/octet-stream",context.contentResolver.getType(uri))
                val target = Tracker(store(),FakeClock());val rollback = MemoryRollback();val backup = Backups(target,rollback)
                val before = target.data()
                val preview = requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupDocuments.prepare(context,it,backup) }
                assertEquals(before,target.data());assertEquals(expected.backupId,preview.backupId)
                val result = backup.apply(preview)
                assertEquals(expected.data.copy(revision = target.data().revision,controlBarrier = target.data().controlBarrier),target.data())
                assertEquals(before,rollback.readSnapshot(result.rollbackReference).data)
            }
            val truncated = Uri.parse("content://synthetic.restore/truncated.json")
            resolver.registerInputStreamSupplier(truncated) { bytes.copyOf(bytes.size-7).inputStream() }
            val target = Tracker(store(),FakeClock());val before = target.data()
            try {
                requireNotNull(context.contentResolver.openInputStream(truncated)).use { BackupDocuments.prepare(context,it,Backups(target,MemoryRollback())) }
                fail("Truncated backup must not be restored")
            } catch(e: CoreError) { assertEquals(ErrorCode.UNSUPPORTED_IMPORT,e.code) }
            assertEquals(before,target.data())
        } finally { databases.forEach { it.close() };DirectoryDurability.sync = sync;DirectoryDurability.rename = rename }
    }
    @Test fun deviceLocalJournalSurvivesNewControllerAndJobsAreOptInUniqueAndCancellable() {
        val context = RuntimeEnvironment.getApplication();val sync = DirectoryDurability.sync;val rename = DirectoryDurability.rename
        DirectoryDurability.sync = { d -> java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        DirectoryDurability.rename = { a,b -> Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
        try {
            val journal = AndroidBackupJournal(context)
            journal.update { AutomaticBackupState(enabled = true,destination = "synthetic",wanted = BackupRevision("data",9),dirtyAt = System.currentTimeMillis()) }
            val state = AndroidBackupJournal(context).read();assertEquals(9L,state.wanted!!.revision)
            AutomaticBackupJobs.reconcile(context,state);AutomaticBackupJobs.reconcile(context,state)
            val jobs = context.getSystemService(JobScheduler::class.java)
            assertNotNull(jobs.getPendingJob(AutomaticBackupJobs.ON_CHANGE));assertNotNull(jobs.getPendingJob(AutomaticBackupJobs.RECOVERY))
            assertEquals(2,jobs.allPendingJobs.count { it.id in setOf(AutomaticBackupJobs.ON_CHANGE,AutomaticBackupJobs.RECOVERY) })
            AutomaticBackupJobs.reconcile(context,state.copy(enabled = false))
            assertNull(jobs.getPendingJob(AutomaticBackupJobs.ON_CHANGE));assertNull(jobs.getPendingJob(AutomaticBackupJobs.RECOVERY))
        } finally { DirectoryDurability.sync = sync;DirectoryDurability.rename = rename }
    }
    @Test fun realExportFailureRetriesExactOwnedBytesOnSameDocumentAndManualFailureAdmissionIsUnchanged() {
        val context = RuntimeEnvironment.getApplication();val sync = DirectoryDurability.sync;val rename = DirectoryDurability.rename
        DirectoryDurability.sync = { d -> java.nio.channels.FileChannel.open(d.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        DirectoryDurability.rename = { a,b -> Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
        try {
            val exports = ExportOperations(context) { _,_ -> };val id = newId()
            exports.beginOwned(id,"application/json","test.json")
            assertEquals(id,ExportOperations(context) { _,_ -> }.beginOwned(id,"application/json","test.json").id)
            val payload = "synthetic immutable payload".toByteArray()
            val staged = exports.prepare(id) { it.write(payload) };val sha = requireNotNull(staged.sha256)
            val uri = Uri.parse("content://synthetic.backup/tree/root/document/retry")
            val resolver = shadowOf(context.contentResolver);val partial = ByteArrayOutputStream()
            resolver.registerOutputStream(uri,object: java.io.OutputStream() {
                override fun write(value: Int) { if(partial.size() == 3) throw java.io.IOException("synthetic write failure");partial.write(value) }
            })
            try { exports.retryWrite(id,uri,staged.bytes,sha);fail("write must fail") } catch(_: java.io.IOException) {}
            assertEquals(3,partial.size());assertEquals("READY",exports.read(id).state)
            // A failed re-prepare may leave an older immutable staged payload and its receipt intact.
            try { exports.prepare(id) { throw java.io.IOException("synthetic prepare failure") };fail("prepare") } catch(_: java.io.IOException) {}
            assertEquals("FAILED",exports.read(id).state)
            val output = ByteArrayOutputStream();resolver.registerOutputStream(uri,output)
            resolver.registerInputStreamSupplier(uri) { output.toByteArray().inputStream() }
            try { exports.retryWrite(id,uri,staged.bytes+1,sha);fail("wrong receipt") } catch(e: CoreError) { assertEquals(ErrorCode.STALE,e.code) }
            assertEquals(0,output.size())
            assertTrue(exports.retryWrite(id,uri,staged.bytes,sha).verified);assertArrayEquals(payload,output.toByteArray())
            assertFalse(java.io.File(context.noBackupFilesDir,"exports/$id/data.part").exists())
            exports.receiveResult(id,false);exports.receiveResult(id,false)
            assertTrue(exports.read(id).resultReceived);assertEquals("COMPLETE",exports.read(id).state)
            val manual = exports.begin("application/json","manual.json")
            val manualReady = exports.prepare(manual.id) { it.write(payload) }
            try { exports.prepare(manual.id) { throw java.io.IOException("manual prepare failure") } } catch(_: java.io.IOException) {}
            try { exports.write(manual.id,uri);fail("manual FAILED still refused") } catch(_: CoreError) {}
            try { exports.retryWrite(manual.id,uri,manualReady.bytes,requireNotNull(manualReady.sha256));fail("not an automatic owner") } catch(e: CoreError) { assertEquals(ErrorCode.STALE,e.code) }
            assertFalse(exports.read(manual.id).resultReceived)
        } finally { DirectoryDurability.sync = sync;DirectoryDurability.rename = rename }
    }
}
