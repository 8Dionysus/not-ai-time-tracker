package dev.notai.tracker.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.notai.tracker.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.WeakHashMap
import kotlinx.coroutines.*

/** Existing registry only. Saved Activity identity survives recreation; process death never
 * expires an owner. Explicit finish releases its exact token record, preserving active reads/
 * apply consumers. Unknown legacy scopes and other owners are untouched. No file deletion. */
internal interface PrivateRestoreLease: RestoreLease { val references: List<CopyReference> }
class CopyLifetimes private constructor(context: Context): RestoreLifecycle,Application.ActivityLifecycleCallbacks {
    private val context = context.applicationContext
    private val lock = Any()
    private val owners = WeakHashMap<Activity,String>()
    private val handoff = mutableSetOf<String>()
    private val activeTokens = mutableSetOf<String>()
    private val closing = mutableSetOf<String>()
    private val activeStages = mutableSetOf<String>()
    private val callingActor = ThreadLocal<String?>()
    private val unclaimed = ThreadLocal.withInitial { mutableListOf<CopyReference>() }
    private val queue = CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))
    private fun gate() = PrivateFileMaintenance.forContext(context)
    private fun ownerIds(): Set<String> = synchronized(lock) { owners.values.toSet()+handoff }
    private fun valid(id: String) = id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    private fun enqueue(block: () -> Unit) { queue.launch { try { block() } catch(_: Exception) { android.util.Log.w("PrivateCopies","Ownership completion pending; references preserved") } } }
    fun activate(ownerId: String) { require(valid(ownerId));gate().activateLifetime(ownerId) }
    private fun takeUnclaimed(id: String): CopyReference? {
        val rows = requireNotNull(unclaimed.get());val item = PrivateCopyBackend.itemId("forms/$id.json")
        val at = rows.indexOfLast { it.itemId == item };return if(at < 0) null else rows.removeAt(at)
    }
    fun claimForm(ownerId: String,id: String,claim: CopyReference? = takeUnclaimed(id)): CopyReference {
        require(valid(ownerId) && valid(id));val gate = gate();val item = PrivateCopyBackend.itemId("forms/$id.json")
        val owned = gate.retainLifetime(ownerId,"form:$id",item,CopyBlocker.LIVE_FORM_REFERENCE)
        claim?.let {
            ensure(it.owner == "form-unclaimed:$id" && it.itemId == item && it.blocker == CopyBlocker.LIVE_FORM_REFERENCE,"Wrong form handoff",ErrorCode.STORAGE)
            gate.state().references.singleOrNull { r -> r.token == it.token }?.let { saved -> ensure(saved == it,"Form handoff changed",ErrorCode.STORAGE);gate.release(saved) }
        }
        return owned
    }
    internal fun callingOwner(): String? = callingActor.get() // capture only; unclaimedForm validates active ownership outside the maintenance gate
    fun unclaimedForm(id: String,ownerId: String? = null) {
        if(ownerId != null) synchronized(lock) {
            if(ownerId in owners.values || ownerId in handoff) { claimForm(ownerId,id,null);return }
        }
        require(valid(id));val gate = gate();gate.locked {
            val owner = "form-unclaimed:$id";val item = PrivateCopyBackend.itemId("forms/$id.json")
            requireNotNull(unclaimed.get()) += gate.acquire(owner,item,CopyBlocker.LIVE_FORM_REFERENCE)
        }
    }
    fun finish(ownerId: String) = synchronized(lock) {
        val gate = gate();closing += ownerId
        val tokens = gate.state().lifetimes.singleOrNull { it.id == ownerId }?.referenceTokens.orEmpty()
        gate.finishLifetime(ownerId,tokens.intersect(activeTokens))
        if(tokens.intersect(activeTokens).isEmpty()) closing -= ownerId
    }
    /** Same private staging path used by full replacement and selective transfer. Bytes and
     * exact pending/writer tokens are recorded before I/O. Failed sources remain protected. */
    fun stage(input: InputStream): File {
        val id = newId();val item = PrivateCopyBackend.itemId("restore-inputs/$id.json");val gate = gate()
        val directory = privateCopyDirectory(context,"restore-inputs")
        val file = File(directory,"$id.json")
        synchronized(lock) { activeStages += id }
        try { return gate.writingWithReference("restore-input-stage:$id",item,CopyBlocker.RESTORE_SOURCE_IN_USE) { writer ->
            val handoffRefs = synchronized(lock) { ownerIds().map { gate.retainLifetime(it,"stage:$id",item,CopyBlocker.RESTORE_SOURCE_IN_USE) } }
            val pending = if(handoffRefs.isEmpty()) CopyReference(newId(),"restore-stage:$id",item,CopyBlocker.RESTORE_SOURCE_IN_USE) else null
            gate.update { it.copy(references = it.references+listOfNotNull(pending),stagedInputs = it.stagedInputs+StagedCopyInput(id,item,
                pendingToken = pending?.token,writerTokens = setOf(writer.token),handoffTokens = handoffRefs.map { it.token }.toSet())) }
            java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
                input.copyTo(java.nio.channels.Channels.newOutputStream(channel),64*1024);channel.force(true)
            };syncDirectory(directory)
            val hash = fileHash(file);val bytes = file.length()
            gate.update { it.copy(stagedInputs = it.stagedInputs.map { row -> if(row.id == id) row.copy(bytes = bytes,sha256 = hash,state = "READY") else row }) }
            PrivateCopyBackend.registerRestoreInput(context,file.name);file
        } } finally { synchronized(lock) { activeStages -= id } }
    }
    /** Explicit abandon of this stage, never of a live reader/chooser/foreign owner. */
    fun abandonStage(id: String) = synchronized(lock) { gate().let { gate -> gate.locked {
        ensure(id !in activeStages,"Staging writer is active",ErrorCode.STORAGE)
        val row = gate.state().stagedInputs.singleOrNull { it.id == id } ?: fail(ErrorCode.INVALID_INPUT,"Staged source missing")
        val known = row.writerTokens+setOfNotNull(row.pendingToken)+row.handoffTokens
        ensure(gate.state().references.filter { it.token in known }.all { it.itemId == row.itemId && it.blocker == CopyBlocker.RESTORE_SOURCE_IN_USE &&
            (it.owner == "restore-stage:$id" || it.owner == "restore-input-stage:$id" || it.owner.startsWith("lifetime:") && it.owner.endsWith(":stage:$id")) },"Staging ownership changed",ErrorCode.STORAGE)
        // Other owners remain blockers; abandoning this stage never releases them.
        val file = File(context.noBackupFilesDir,"restore-inputs/$id.json")
        val hash = if(file.isFile) fileHash(file) else null
        gate.update { it.copy(references = it.references.filterNot { r -> r.token in known },lifetimes = it.lifetimes.map { o -> o.copy(referenceTokens = o.referenceTokens-known) },stagedInputs = it.stagedInputs.map { s ->
            if(s.id == id) s.copy(state = "ABANDONED",pendingToken = null,sha256 = hash,bytes = file.length()) else s
        }) }
        if(file.isFile) PrivateCopyBackend.registerRestoreInput(context,file.name)
    } } }
    fun retainInput(ownerId: String,id: String): CopyReference = synchronized(lock) {
        val gate = gate();val row = gate.state().stagedInputs.singleOrNull { it.id == id && it.state == "READY" } ?: fail(ErrorCode.STORAGE,"Staged source is not ready")
        val reference = gate.retainLifetime(ownerId,"input:$id",row.itemId,CopyBlocker.RESTORE_SOURCE_IN_USE)
        row.pendingToken?.let { token -> gate.state().references.singleOrNull { it.token == token }?.let { pending ->
            ensure(pending.owner == "restore-stage:$id" && pending.itemId == row.itemId,"Staging ownership changed",ErrorCode.STORAGE);gate.release(pending)
        } }
        releaseHandoff(row,gate)
        reference
    }
    private fun releaseHandoff(row: StagedCopyInput,gate: CopyMaintenance) {
        for(token in row.handoffTokens) gate.state().references.singleOrNull { it.token == token }?.let { reference ->
            ensure(reference.itemId == row.itemId && reference.blocker == CopyBlocker.RESTORE_SOURCE_IN_USE &&
                reference.owner.startsWith("lifetime:") && reference.owner.endsWith(":stage:${row.id}"),"Stage handoff changed",ErrorCode.STORAGE)
            gate.release(reference)
        }
    }
    override fun <T> reading(block: () -> T): T {
        val refs = synchronized(lock) {
            val gate = gate();val scope = try { PrivateCopyBackend.legacyScope(context,gate) } catch(_: LinkageError) { null } catch(_: Exception) { null }
            val ids = ownerIds()
            val refs = if(ids.isEmpty()) listOf(gate.acquire("restore-read:${newId()}",null,CopyBlocker.RESTORE_SOURCE_IN_USE,scope))
                else ids.map { gate.retainLifetime(it,"read:${newId()}",null,CopyBlocker.RESTORE_SOURCE_IN_USE,scope) }
            activeTokens += refs.map { it.token };refs
        }
        try { return block() } finally { synchronized(lock) {
            val complete = gate().state().lifetimes.filter { it.id in closing && it.referenceTokens.any { token -> refs.any { it.token == token } } }.map { it.id }
            activeTokens -= refs.map { it.token }.toSet();release(refs);complete.forEach(::finish)
        } }
    }
    override fun acquire(sourceSha256: String): RestoreLease? = synchronized(lock) {
        val gate = gate();val before = gate.state()
        val stages = before.stagedInputs.filter { it.state == "READY" && it.sha256 == sourceSha256 }
        val groups = stages.map { it.itemId }.toSet()+before.copies.filter { c -> c.item.members.size == 1 && c.item.members.single().sha256 == sourceSha256 }.map { it.item.id }
        if(groups.isEmpty()) return@synchronized null // External originals are never cleanup candidates.
        val knownActors = ownerIds();val standalone = knownActors.isEmpty();val actorIds = knownActors.ifEmpty { setOf(newId()) };val preview = newId()
        val refs = actorIds.flatMap { owner -> groups.map { gate.retainLifetime(owner,"restore:$preview:$it",it,CopyBlocker.RESTORE_SOURCE_IN_USE) } }
        for(stage in stages) stage.pendingToken?.let { token -> gate.state().references.singleOrNull { it.token == token }?.let { pending ->
            ensure(pending.owner == "restore-stage:${stage.id}" && pending.itemId == stage.itemId,"Staging ownership changed",ErrorCode.STORAGE);gate.release(pending)
        } }
        stages.forEach { releaseHandoff(it,gate) }
        object: PrivateRestoreLease {
            override val references = refs.toList()
            private var closed = false;private var using = false;private var closePending = false
            override fun <T> protecting(block: () -> T): T {
                synchronized(lock) {
                    ensure(!closed && !using && gate().state().references.containsAll(refs),"Restore source lease is closed or busy",ErrorCode.STALE)
                    using = true;activeTokens += refs.map { it.token }
                }
                try { return block() } finally { synchronized(lock) {
                    using = false;activeTokens -= refs.map { it.token }.toSet()
                    if(closePending || actorIds.any { it in closing }) close()
                } }
            }
            override fun close() = synchronized(lock) {
                if(!closed) { if(using) closePending = true else { release(refs);closed = true;actorIds.filter { standalone || it in closing }.forEach(::finish) } };Unit
            }
        }
    }
    private fun release(refs: List<CopyReference>) {
        val gate = gate();for(ref in refs) gate.state().references.singleOrNull { it.token == ref.token }?.let {
            ensure(it == ref,"Ownership token changed",ErrorCode.STORAGE);gate.release(ref)
        }
    }
    override fun onActivityCreated(activity: Activity,savedInstanceState: Bundle?) {
        val id = savedInstanceState?.getString(OWNER)?.takeIf(::valid) ?: newId()
        synchronized(lock) { owners[activity] = id;handoff -= id;callingActor.set(id) }
        val form = savedInstanceState?.getString("formReference")?.takeIf(::valid)
        val claim = form?.let(::takeUnclaimed)
        enqueue { activate(id);if(form != null) claimForm(id,form,claim) }
    }
    override fun onActivitySaveInstanceState(activity: Activity,outState: Bundle) {
        val owner = synchronized(lock) { owners[activity] } ?: return
        outState.putString(OWNER,owner)
        outState.getString("formReference")?.takeIf(::valid)?.let { id -> val claim = takeUnclaimed(id);enqueue { claimForm(owner,id,claim) } }
    }
    override fun onActivityDestroyed(activity: Activity) {
        val owner = synchronized(lock) { owners.remove(activity)?.also { if(activity.isChangingConfigurations) handoff += it } } ?: return
        if(callingActor.get() == owner) callingActor.remove()
        if(activity.isFinishing && !activity.isChangingConfigurations) enqueue { finish(owner) }
    }
    override fun onActivityResumed(activity: Activity) { synchronized(lock) { callingActor.set(owners[activity]) };TrackerRuntime.refreshExistingPlatform(context) }
    override fun onActivityStarted(activity: Activity) { }
    override fun onActivityPaused(activity: Activity) { synchronized(lock) { callingActor.set(owners[activity]) } }
    override fun onActivityStopped(activity: Activity) { }
    internal fun shutdown() { queue.cancel() }
    companion object {
        private const val OWNER = "notai.privateCopyOwner.v1"
        private val instances = mutableMapOf<String,CopyLifetimes>()
        @Synchronized fun get(context: Context): CopyLifetimes = instances.getOrPut(context.applicationContext.noBackupFilesDir.absolutePath) { CopyLifetimes(context) }
        @Synchronized internal fun forOwnedSource(source: File): CopyLifetimes? = instances.values.firstOrNull { value ->
            val parent = source.parentFile?.canonicalFile
            parent == File(value.context.noBackupFilesDir,"restore-inputs").canonicalFile || parent == File(value.context.noBackupFilesDir,"rollback").canonicalFile
        }
        fun install(application: Application) { application.registerActivityLifecycleCallbacks(get(application)) }
    }
}
