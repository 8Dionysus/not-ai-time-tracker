package dev.notai.tracker.android

import dev.notai.tracker.newId
import kotlinx.serialization.Serializable
import java.util.concurrent.locks.ReentrantLock

@Serializable data class BackupRevision(val datasetId: String,val revision: Long)
@Serializable enum class AutomaticBackupError {
    REQUIRES_SELECTION, UNSUPPORTED, GRANT_REVOKED, PROVIDER_UNAVAILABLE, READBACK_UNAVAILABLE,
    CORRUPT_READBACK, INTERRUPTED, CREATION_UNCERTAIN, RETENTION_FAILED, SCHEDULING_FAILED, STORAGE
}
class BackupDestinationException(val reason: AutomaticBackupError): java.io.IOException(reason.name)
@Serializable data class BackupPolicy(val retain: Int = 7,val intervalHours: Int = 24) {
    fun validate() { require(retain in 1..100 && intervalHours in 1..168) }
}
@Serializable data class StagedBackup(val exportId: String,val source: BackupRevision,val asOf: Long,val bytes: Long,val sha256: String)
@Serializable data class ExternalBackupVersion(val operationId: String,val destination: String,val document: String,
    val snapshot: StagedBackup,val verifiedAt: Long)
@Serializable data class AutomaticBackupOperation(val id: String = newId(),val exportId: String? = null,
    val snapshot: StagedBackup? = null,val document: String? = null,val creating: Boolean = false,val attempts: Int = 0)
@Serializable data class BackupFinalization(val exportId: String,val cancelled: Boolean = false)
@Serializable data class AutomaticBackupState(val format: Int = 1,val generation: Long = 0,val enabled: Boolean = false,
    val destination: String? = null,val policy: BackupPolicy = BackupPolicy(),val wanted: BackupRevision? = null,
    val dirtyAt: Long? = null,val requested: Boolean = false,val nextAttemptAt: Long = 0,val pending: AutomaticBackupOperation? = null,
    val versions: List<ExternalBackupVersion> = emptyList(),val lastSuccess: ExternalBackupVersion? = null,
    val error: AutomaticBackupError? = null,val consecutiveFailures: Int = 0,
    val finalizations: List<BackupFinalization> = emptyList())

/** Device-local journal. The implementation serializes read/change/write; no URI authority in JSON backups. */
interface AutomaticBackupStore {
    fun read(): AutomaticBackupState
    fun update(change: (AutomaticBackupState)->AutomaticBackupState): AutomaticBackupState
}
interface BackupDestination {
    /** Must establish a persisted read+write grant, directory/create/list support and current availability. */
    fun check(destination: String)
    fun create(destination: String,name: String): String
    fun verify(destination: String,document: String,bytes: Long,sha256: String): Boolean
    fun exists(destination: String,document: String): Boolean
    fun delete(destination: String,document: String)
}
interface BackupStaging {
    fun begin(exportId: String)
    fun prepare(exportId: String): StagedBackup
    fun write(snapshot: StagedBackup,document: String): Boolean
    fun complete(exportId: String)
    fun abandon(exportId: String)
}
fun interface BackupWorkScheduler { fun reconcile(state: AutomaticBackupState) }

/** Small durable command controller. Long provider IO never holds the state/configuration monitor. */
class AutomaticBackupController(private val store: AutomaticBackupStore,private val destination: BackupDestination,
    private val staging: BackupStaging,private val scheduler: BackupWorkScheduler,private val now: ()->Long,
    private val runner: ReentrantLock = ReentrantLock()) {
    companion object { const val DEBOUNCE_MS = 120_000L;const val RETRY_MS = 900_000L }
    fun status(): AutomaticBackupState = store.read()
    private fun schedule() { try { scheduler.reconcile(status()) } catch(_: Exception) { store.update { it.copy(error = AutomaticBackupError.SCHEDULING_FAILED) } } }
    /** Selection grants are obtained by the Android picker adapter, never by this command. */
    fun enable(tree: String,policy: BackupPolicy = BackupPolicy()): AutomaticBackupState {
        policy.validate()
        destination.check(tree)
        // Refuse silently losing an interrupted operation when switching folders.
        store.update {
            require(it.pending == null || it.destination == tree) { "Resolve the pending operation before changing destination" }
            it.copy(generation = it.generation+1,enabled = true,destination = tree,policy = policy,error = null,nextAttemptAt = 0,consecutiveFailures = 0,
                requested = it.requested || it.destination != tree,dirtyAt = if(it.destination != tree) now()-DEBOUNCE_MS else it.dirtyAt)
        }
        schedule();return status()
    }
    fun disable(): AutomaticBackupState {
        store.update { it.copy(generation = it.generation+1,enabled = false) };schedule();return status()
    }
    /** Called only for committed Dataset revisions; never on display ticks. Bounded coalescing, not endless debounce. */
    fun changed(source: BackupRevision) {
        if(!status().enabled) { if(status().finalizations.isNotEmpty()) run();return }
        store.update {
            if(!it.enabled || it.wanted == source) it else it.copy(wanted = source,dirtyAt = it.dirtyAt ?: now())
        };schedule()
    }
    fun retry() {
        store.update { it.copy(error = null,nextAttemptAt = 0,consecutiveFailures = 0) }
        if(!status().enabled && status().finalizations.isNotEmpty()) run() else schedule()
    }
    fun requestBackup() {
        store.update { require(it.enabled) { "Backup is disabled" };it.copy(requested = true,dirtyAt = now()-DEBOUNCE_MS,error = null,nextAttemptAt = 0,consecutiveFailures = 0) }
        schedule()
    }
    /** Explicit recovery for creation with no receipt. Unknown external files are retained, never guessed/deleted. */
    fun abandonPending(operationId: String) {
        check(!runner.isHeldByCurrentThread && runner.tryLock()) { "Backup is running" }
        try {
            val old = status();val pending = requireNotNull(old.pending);require(pending.id == operationId)
            store.update { it.copy(generation = it.generation+1,pending = null,error = null,nextAttemptAt = 0,consecutiveFailures = 0,
                finalizations = it.finalizations + listOfNotNull(pending.exportId?.let { id -> BackupFinalization(id,true) })) }
            finishStaging()
        } catch(e: Exception) { store.update { failed(it,AutomaticBackupError.STORAGE) };throw e }
        finally { runner.unlock();schedule() }
    }
    private fun current(generation: Long) = status().let { it.enabled && it.generation == generation }
    private fun requireCurrent(generation: Long) { if(!current(generation)) throw BackupDestinationException(AutomaticBackupError.INTERRUPTED) }
    private fun saveOperation(generation: Long,op: AutomaticBackupOperation) {
        store.update { if(it.generation != generation || !it.enabled) throw BackupDestinationException(AutomaticBackupError.INTERRUPTED) else it.copy(pending = op) }
    }
    /** Idempotent OS wake/restart. Returns false when another worker is already running. */
    fun run(stopped: ()->Boolean = { false }): Boolean {
        if(runner.isHeldByCurrentThread || !runner.tryLock()) return false
        var generation = -1L
        try {
            if(now() < status().nextAttemptAt) return true
            finishStaging()
            val s = status();if(!s.enabled) return true
            generation = s.generation
            val tree = s.destination ?: throw BackupDestinationException(AutomaticBackupError.REQUIRES_SELECTION)
            val time = now()
            val last = s.lastSuccess
            if(time < s.nextAttemptAt) return true
            destination.check(tree)
            if(s.pending == null && !s.requested && (s.wanted == null || (last != null && last.destination == tree && s.wanted == last.snapshot.source &&
                    time - (s.lastSuccess?.verifiedAt ?: 0) < s.policy.intervalHours*3_600_000L))) {
                prune(generation);return true
            }
            if(s.pending == null && s.dirtyAt != null && time-s.dirtyAt < DEBOUNCE_MS) return true
            var op = s.pending ?: AutomaticBackupOperation().also { saveOperation(generation,it) }
            if(op.creating && op.document == null) throw BackupDestinationException(AutomaticBackupError.CREATION_UNCERTAIN)
            if(stopped()) throw BackupDestinationException(AutomaticBackupError.INTERRUPTED)
            if(op.snapshot == null) {
                requireCurrent(generation)
                if(op.exportId == null) op = op.copy(exportId = op.id).also { saveOperation(generation,it) }
                staging.begin(requireNotNull(op.exportId))
                op = op.copy(snapshot = staging.prepare(requireNotNull(op.exportId))).also { saveOperation(generation,it) }
            }
            val snapshot = requireNotNull(op.snapshot)
            requireCurrent(generation)
            if(stopped()) throw BackupDestinationException(AutomaticBackupError.INTERRUPTED)
            if(op.document == null) {
                saveOperation(generation,op.copy(creating = true))
                val document = destination.create(tree,"not-ai-${snapshot.asOf}-${op.id}.json")
                // Store returned identity even if disable raced create; only delivery/commit is revoked.
                op = op.copy(document = document,creating = false)
                store.update { require(it.pending?.id == op.id);it.copy(pending = op) }
                requireCurrent(generation)
            }
            val document = requireNotNull(op.document)
            if(op.attempts > 0 && destination.verify(tree,document,snapshot.bytes,snapshot.sha256)) {
                // A previous write completed but died before durable completion. Do not upload twice.
            } else {
                requireCurrent(generation)
                if(stopped()) throw BackupDestinationException(AutomaticBackupError.INTERRUPTED)
                op = op.copy(attempts = op.attempts+1).also { saveOperation(generation,it) }
                if(!staging.write(snapshot,document)) throw BackupDestinationException(AutomaticBackupError.READBACK_UNAVAILABLE)
                if(!destination.verify(tree,document,snapshot.bytes,snapshot.sha256))
                    throw BackupDestinationException(AutomaticBackupError.CORRUPT_READBACK)
            }
            requireCurrent(generation)
            if(stopped()) throw BackupDestinationException(AutomaticBackupError.INTERRUPTED)
            val version = ExternalBackupVersion(op.id,tree,document,snapshot,now())
            store.update {
                if(it.generation != generation || !it.enabled) throw BackupDestinationException(AutomaticBackupError.INTERRUPTED)
                it.copy(pending = null,versions = it.versions+version,lastSuccess = version,error = null,requested = false,nextAttemptAt = 0,consecutiveFailures = 0,
                    dirtyAt = if(it.wanted == snapshot.source) null else it.dirtyAt ?: now(),
                    finalizations = it.finalizations+BackupFinalization(snapshot.exportId))
            }
            finishStaging()
            prune(generation)
            return true
        } catch(e: Exception) {
            val error = (e as? BackupDestinationException)?.reason ?: AutomaticBackupError.STORAGE
            store.update { if(generation >= 0 && it.generation != generation) it else failed(it,error) }
            return true
        } finally { runner.unlock();schedule() }
    }
    /** Completion obligation and success are committed together; replay releases only these exact receipts. */
    private fun finishStaging() {
        for(item in status().finalizations) {
            if(item.cancelled) staging.abandon(item.exportId) else staging.complete(item.exportId)
            store.update { it.copy(finalizations = it.finalizations.filterNot { f -> f == item }) }
        }
    }
    private fun failed(state: AutomaticBackupState,error: AutomaticBackupError): AutomaticBackupState {
        val failures = minOf(5,state.consecutiveFailures+1)
        return state.copy(error = error,consecutiveFailures = failures,
            nextAttemptAt = if(failures >= 5) Long.MAX_VALUE else now()+RETRY_MS*(1L shl (failures-1)))
    }
    private fun prune(generation: Long) {
        val s = status();val same = s.versions.filter { it.destination == s.destination }
        for(version in same.dropLast(s.policy.retain)) {
            requireCurrent(generation)
            try {
                if(destination.exists(version.destination,version.document)) {
                    if(!destination.verify(version.destination,version.document,version.snapshot.bytes,version.snapshot.sha256))
                        throw BackupDestinationException(AutomaticBackupError.CORRUPT_READBACK)
                    destination.delete(version.destination,version.document)
                }
                store.update { it.copy(versions = it.versions.filterNot { v -> v.operationId == version.operationId }) }
            } catch(_: Exception) { store.update { failed(it,AutomaticBackupError.RETENTION_FAILED) };return }
        }
        store.update { if(it.error == AutomaticBackupError.RETENTION_FAILED) it.copy(error = null,nextAttemptAt = 0,consecutiveFailures = 0) else it }
    }
}
