package dev.notai.tracker.android

import dev.notai.tracker.codec
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** All documents are synthetic. Restart reconstructs the controller from serialized journal bytes. */
class AutomaticBackupControllerTest {
    private class Journal: AutomaticBackupStore {
        var bytes = codec.encodeToString(AutomaticBackupState())
        @Synchronized override fun read() = codec.decodeFromString<AutomaticBackupState>(bytes)
        @Synchronized override fun update(change: (AutomaticBackupState)->AutomaticBackupState): AutomaticBackupState =
            change(read()).also { bytes = codec.encodeToString(it) }
    }
    private class Fixture {
        val journal = Journal();var time = 1_000_000L;var revision = 1L
        val files = mutableMapOf("foreign" to "untouched".toByteArray())
        val payloads = mutableMapOf<String,ByteArray>();var creates = 0;var writes = 0;var deletes = 0
        var grantError: AutomaticBackupError? = null;var readback = true;var corrupt = false;var interrupted = false
        var deletionFails = false;var afterWrite: (()->Unit)? = null;var beforeStage: (()->Unit)? = null
        var afterBegin: (()->Unit)? = null;var beforeComplete: (()->Unit)? = null
        var beforeWrite: (()->Unit)? = null
        val begun = mutableSetOf<String>();val completed = mutableSetOf<String>();val destinations = mutableListOf<String>()
        val destination = object: BackupDestination {
            override fun check(destination: String) { grantError?.let { throw BackupDestinationException(it) } }
            override fun create(destination: String,name: String): String = "own-${++creates}".also { files[it] = byteArrayOf();destinations += destination }
            override fun verify(destination: String,document: String,bytes: Long,sha256: String): Boolean {
                if(!readback) throw BackupDestinationException(AutomaticBackupError.READBACK_UNAVAILABLE)
                val b = files[document] ?: return false
                return b.size.toLong() == bytes && hash(b) == sha256
            }
            override fun delete(destination: String,document: String) {
                check(document != "foreign");if(deletionFails) throw java.io.IOException("synthetic")
                deletes++;files.remove(document)
            }
            override fun exists(destination: String,document: String) = files.containsKey(document)
        }
        val staging = object: BackupStaging {
            override fun begin(exportId: String) { begun += exportId;afterBegin?.invoke() }
            override fun prepare(exportId: String): StagedBackup {
                val captured = revision;val b = "snapshot-$captured".toByteArray();payloads[exportId] = b
                beforeStage?.invoke()
                return StagedBackup(exportId,BackupRevision("data",captured),time,b.size.toLong(),hash(b))
            }
            override fun write(snapshot: StagedBackup,document: String): Boolean {
                beforeWrite?.invoke()
                writes++;val b = payloads.getValue(snapshot.exportId)
                if(interrupted) { files[document] = b.copyOf(2);throw java.io.IOException("interrupted") }
                files[document] = if(corrupt) "corrupt".toByteArray() else b
                afterWrite?.invoke();return readback
            }
            override fun complete(exportId: String) { beforeComplete?.invoke();completed += exportId }
            override fun abandon(exportId: String) {}
        }
        fun controller() = AutomaticBackupController(journal,destination,staging,BackupWorkScheduler {},{ time })
        var c = controller()
        fun start() { c.enable("tree");c.changed(BackupRevision("data",revision));time += AutomaticBackupController.DEBOUNCE_MS;c.run() }
        fun next() { revision++;c.changed(BackupRevision("data",revision));time += AutomaticBackupController.DEBOUNCE_MS;c.run() }
        fun retry() { time += AutomaticBackupController.RETRY_MS;c = controller();c.retry();c.run() }
    }
    @Test fun interruptedWriteRestartsSameDocumentAndPreservesLastGoodUntilVerified() {
        val f = Fixture();f.start();val good = f.c.status().lastSuccess!!
        f.interrupted = true;f.next()
        assertEquals(good,f.c.status().lastSuccess);assertEquals(2,f.creates)
        val pending = f.c.status().pending!!;f.interrupted = false;f.retry()
        assertEquals(2,f.creates);assertEquals(pending.id,f.c.status().lastSuccess!!.operationId)
        assertEquals(2L,f.c.status().lastSuccess!!.snapshot.source.revision)
    }
    @Test fun corruptOrUnavailableReadbackCannotCommitSuccessOrRetireGoodCopies() {
        val f = Fixture();f.start();val good = f.c.status().lastSuccess
        f.corrupt = true;f.next()
        assertEquals(AutomaticBackupError.CORRUPT_READBACK,f.c.status().error);assertEquals(good,f.c.status().lastSuccess)
        f.corrupt = false;f.readback = false;f.retry()
        assertEquals(AutomaticBackupError.READBACK_UNAVAILABLE,f.c.status().error);assertEquals(good,f.c.status().lastSuccess)
        assertEquals(0,f.deletes)
        f.readback = true;f.retry();assertEquals(2L,f.c.status().lastSuccess!!.snapshot.source.revision)
    }
    @Test fun repeatedJobAndRestartAfterProviderWriteDoNotCreateOrWriteAgain() {
        val f = Fixture();f.start()
        f.afterWrite = { throw java.io.IOException("death before completion") };f.next()
        val id = f.c.status().pending!!.id;val writes = f.writes
        f.afterWrite = null;f.retry()
        assertEquals(id,f.c.status().lastSuccess!!.operationId);assertEquals(writes,f.writes)
        f.c.run();assertEquals(2,f.creates);assertEquals(writes,f.writes)
    }
    @Test fun newerRevisionDuringStageIsNotClaimedByOlderSnapshotAndQueueCoalesces() {
        val f = Fixture();f.c.enable("tree");f.c.changed(BackupRevision("data",1))
        f.revision = 5;f.c.changed(BackupRevision("data",5));f.time += AutomaticBackupController.DEBOUNCE_MS
        f.beforeStage = { f.revision = 6;f.c.changed(BackupRevision("data",6)) }
        f.c.run();assertEquals(5L,f.c.status().lastSuccess!!.snapshot.source.revision)
        assertEquals(BackupRevision("data",6),f.c.status().wanted);assertNotNull(f.c.status().dirtyAt)
        f.beforeStage = null;f.c.run();assertEquals(6L,f.c.status().lastSuccess!!.snapshot.source.revision)
        assertNull(f.c.status().dirtyAt);assertEquals(2,f.creates)
    }
    @Test fun revokedGrantOfflineAndUnsupportedDoNotLoseQueueOrLastSuccess() {
        val f = Fixture();f.start();val good = f.c.status().lastSuccess
        for(error in listOf(AutomaticBackupError.GRANT_REVOKED,AutomaticBackupError.PROVIDER_UNAVAILABLE,AutomaticBackupError.UNSUPPORTED)) {
            f.grantError = error;f.revision++;f.c.changed(BackupRevision("data",f.revision));f.time += 100*AutomaticBackupController.RETRY_MS;f.c.run()
            assertEquals(error,f.c.status().error);assertEquals(good,f.c.status().lastSuccess);assertEquals(1,f.creates)
        }
        f.grantError = null;f.retry();assertEquals(f.revision,f.c.status().lastSuccess!!.snapshot.source.revision)
    }
    @Test fun disableDuringUploadRevokesCompletionAndExplicitRetryReconcilesIt() {
        val f = Fixture();f.start();val good = f.c.status().lastSuccess
        f.afterWrite = { f.c.disable() };f.next()
        assertFalse(f.c.status().enabled);assertEquals(good,f.c.status().lastSuccess);assertNotNull(f.c.status().pending)
        val writes = f.writes;f.afterWrite = null;f.c.enable("tree");f.c.run()
        assertEquals(writes,f.writes);assertEquals(2L,f.c.status().lastSuccess!!.snapshot.source.revision)
    }
    @Test fun ambiguousCreationRequiresExplicitRecoveryAndDoesNotMultiplyUnknownFiles() {
        val f = Fixture();f.c.enable("tree");f.c.changed(BackupRevision("data",1))
        val op = AutomaticBackupOperation(creating = true)
        f.journal.update { it.copy(pending = op) };f.c.run();f.retry()
        assertEquals(AutomaticBackupError.CREATION_UNCERTAIN,f.c.status().error);assertEquals(0,f.creates)
        f.c.abandonPending(op.id);f.time += AutomaticBackupController.DEBOUNCE_MS;f.c.run()
        assertNotNull(f.c.status().lastSuccess);assertEquals(1,f.creates)
    }
    @Test fun retentionFailureRetainsNewestSuccessAndOnlyRetriesOwnedVerifiedVersions() {
        val f = Fixture();f.start()
        f.c.enable("tree",BackupPolicy(retain = 1));f.deletionFails = true;f.next()
        val latest = f.c.status().lastSuccess!!
        assertEquals(AutomaticBackupError.RETENTION_FAILED,f.c.status().error)
        assertEquals(2,f.c.status().versions.size);assertTrue(f.files.containsKey(latest.document));assertTrue(f.files.containsKey("foreign"))
        f.deletionFails = false;f.retry()
        assertEquals(1,f.c.status().versions.size);assertNull(f.c.status().error);assertEquals(latest,f.c.status().lastSuccess)
        assertTrue(f.files.containsKey("foreign"));assertEquals(1,f.deletes)
    }
    @Test fun tamperedOldDocumentIsNotDeletedAsAnOwnedBackup() {
        val f = Fixture();f.start();val first = f.c.status().lastSuccess!!
        f.files[first.document] = "user replaced contents".toByteArray();f.c.enable("tree",BackupPolicy(retain = 1));f.next()
        assertEquals(AutomaticBackupError.RETENTION_FAILED,f.c.status().error);assertEquals(0,f.deletes)
        assertArrayEquals("user replaced contents".toByteArray(),f.files[first.document])
    }
    @Test fun automaticRetriesAreBoundedAndExplicitRetryKeepsTheSamePendingOperation() {
        val f = Fixture();f.start();f.interrupted = true;f.next();val op = f.c.status().pending!!.id
        repeat(6) { f.time += 100*AutomaticBackupController.RETRY_MS;f.c.run() }
        assertEquals(5,f.c.status().consecutiveFailures);assertEquals(Long.MAX_VALUE,f.c.status().nextAttemptAt)
        assertEquals(op,f.c.status().pending!!.id);assertEquals(2,f.creates)
        f.interrupted = false;f.c.retry();f.c.run()
        assertEquals(op,f.c.status().lastSuccess!!.operationId);assertEquals(0,f.c.status().consecutiveFailures)
    }
    @Test fun explicitBackupRequestAllowsAnotherVerifiedVersionWithoutInventingARevision() {
        val f = Fixture();f.start();val first = f.c.status().lastSuccess!!
        f.c.requestBackup();f.c.run()
        val second = f.c.status().lastSuccess!!
        assertNotEquals(first.operationId,second.operationId);assertEquals(first.snapshot.source,second.snapshot.source)
        assertEquals(2,f.creates);assertFalse(f.c.status().requested)
    }
    @Test fun switchingDestinationImmediatelyBacksUpTheSameRevisionWithoutWritingTheOldFolder() {
        val f = Fixture();f.start();val original = f.c.status().lastSuccess!!
        f.c.enable("new-tree");f.c.changed(original.snapshot.source);f.c.run()
        assertEquals(listOf("tree","new-tree"),f.destinations)
        assertEquals(original.snapshot.source,f.c.status().lastSuccess!!.snapshot.source)
        assertEquals("new-tree",f.c.status().lastSuccess!!.destination)
        f.c.run();assertEquals(2,f.creates)
    }
    @Test fun deathBetweenReservedStagingBeginAndSnapshotKeepsTheExactExportIdentity() {
        val f = Fixture();f.c.enable("tree");f.c.changed(BackupRevision("data",1))
        f.afterBegin = { throw SimulatedDeath() }
        try { f.c.run();fail("death") } catch(_: SimulatedDeath) {}
        val pending = f.c.status().pending!!;assertEquals(pending.id,pending.exportId)
        f.afterBegin = null;f.c = f.controller();f.c.run()
        assertEquals(setOf(pending.id),f.begun);assertEquals(1,f.creates)
        assertEquals(pending.id,f.c.status().lastSuccess!!.snapshot.exportId)
    }
    @Test fun deathAfterDurableSuccessRetainsAndReplaysExactPrivateCompletionWithoutAnotherUpload() {
        val f = Fixture();f.beforeComplete = { throw SimulatedDeath() }
        try { f.start();fail("death") } catch(_: SimulatedDeath) {}
        val success = f.c.status().lastSuccess!!;assertNull(f.c.status().pending)
        assertEquals(listOf(BackupFinalization(success.snapshot.exportId)),f.c.status().finalizations)
        assertTrue(f.completed.isEmpty())
        f.beforeComplete = null;f.c = f.controller();f.c.run()
        assertEquals(setOf(success.snapshot.exportId),f.completed);assertTrue(f.c.status().finalizations.isEmpty())
        assertEquals(success,f.c.status().lastSuccess);assertEquals(1,f.creates);assertEquals(1,f.writes)
    }
    @Test fun periodicIntervalIntentionallyRefreshesTheSameRevisionButDisplayTicksDoNot() {
        val f = Fixture();f.start();repeat(10) { f.c.changed(BackupRevision("data",1));f.c.run() }
        assertEquals(1,f.creates)
        f.time += 24*3_600_000L;f.c.run()
        assertEquals(2,f.creates);assertEquals(BackupRevision("data",1),f.c.status().lastSuccess!!.snapshot.source)
    }
    @Test fun stopRequestedDuringProviderIoCannotPublishCompletionAndRetryReconcilesBytes() {
        val f = Fixture();f.start();val good = f.c.status().lastSuccess
        var stopped = false;f.afterWrite = { stopped = true };f.revision++
        f.c.changed(BackupRevision("data",f.revision));f.time += AutomaticBackupController.DEBOUNCE_MS
        f.c.run { stopped }
        assertEquals(good,f.c.status().lastSuccess);assertEquals(AutomaticBackupError.INTERRUPTED,f.c.status().error)
        val writes = f.writes;f.afterWrite = null;f.retry();assertEquals(writes,f.writes)
    }
    @Test fun blockedProviderIoAllowsOptOutAndRefusesAnotherWorker() {
        val f = Fixture();f.start();val good = f.c.status().lastSuccess
        val entered = java.util.concurrent.CountDownLatch(1);val release = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        f.beforeWrite = { entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS)) }
        val thread = Thread { try { f.next() } catch(e: Throwable) { error.set(e) } }
        thread.start()
        try {
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(f.c.run());f.c.disable();assertFalse(f.c.status().enabled)
        } finally { release.countDown();thread.join(10000) }
        assertFalse(thread.isAlive);assertNull(error.get());assertEquals(good,f.c.status().lastSuccess)
        assertNotNull(f.c.status().pending)
    }
    @Test fun exactPrivateCompletionCanBeRetriedAfterOptOutWithoutAnyExternalWrite() {
        val f = Fixture();f.beforeComplete = { throw SimulatedDeath() }
        try { f.start();fail("death") } catch(_: SimulatedDeath) {}
        val writes = f.writes;val creates = f.creates;f.c.disable();f.beforeComplete = null
        f.c = f.controller();f.c.retry()
        assertTrue(f.c.status().finalizations.isEmpty());assertFalse(f.c.status().enabled)
        assertEquals(writes,f.writes);assertEquals(creates,f.creates)
    }
    private class SimulatedDeath: Error()
    companion object { private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
}
