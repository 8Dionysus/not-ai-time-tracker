package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

@Serializable data class ScheduleCommandDraft(val command: ScheduleCommand,val payloadHash: String,val result: ScheduleCommandResult? = null)
data class BackendDraft(val id: String,val revision: Long)
data class WorkspaceInventory(val drafts: List<BackendDraft>,val unreadableFormIds: Set<String>)
private fun workspaceForms(forms: FormFiles): Pair<List<Pair<String,FormState>>,Set<String>> {
    val unreadable = mutableSetOf<String>()
    val rows = forms.ids().mapNotNull { id -> try { id to forms.read(id,false) } catch(_: Exception) { unreadable += id;null } }
    return rows to unreadable
}
typealias ScheduleSurfaceState = ScheduleViewState

/** Home/shade/widget share this immutable data boundary. Views do not authorize Start;
 * prepare→execute persists the exact caller key/payload, then uses Tracker's receipt. */
class ScheduleWorkspace(private val context: Context,private val tracker: Tracker,private val deviceZone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val forms = FormFiles(context)
    private val ownership = CopyLifetimes.get(context)
    private val gate get() = PrivateFileMaintenance.forContext(context)
    private val published = MutableStateFlow<ScheduleSurfaceState?>(null)
    val state = published.asStateFlow()
    fun current(): ScheduleSurfaceState {
        return tracker.view().scheduleState(deviceZone())
    }
    fun refresh(): ScheduleSurfaceState = current().also { published.value = it }
    fun prepare(value: ScheduleCommand): BackendDraft = gate.locked {
        val command = value.canonicalCapture();val hash = command.canonicalPayloadHash()
        val existing = workspaceForms(forms).first.firstOrNull { (_,f) -> f.scheduleCommand?.command?.let { it.key == command.key && it.expected.datasetId == command.expected.datasetId } == true }?.first
        if(existing != null) {
            val form = forms.read(existing,false);ensure(form.scheduleCommand!!.payloadHash == hash,"Schedule key payload changed",ErrorCode.STALE)
            return@locked BackendDraft(existing,form.backendRevision)
        }
        val id = newId()
        forms.save(id,FormState(scheduleCommand = ScheduleCommandDraft(command,hash)))
        ownership.claimForm(id,id);BackendDraft(id,0)
    }
    fun read(id: String): ScheduleCommandDraft = forms.read(id,false).scheduleCommand ?: fail(ErrorCode.INVALID_INPUT,"Not a schedule draft")
    fun inventory(): WorkspaceInventory {
        val (rows,unreadable) = workspaceForms(forms)
        return WorkspaceInventory(rows.mapNotNull { (id,f) -> if(f.scheduleCommand?.result == null && f.scheduleCommand != null) BackendDraft(id,f.backendRevision) else null },unreadable)
    }
    fun pending(): List<BackendDraft> = inventory().drafts
    fun execute(id: String,revision: Long): ScheduleCommandResult {
        ownership.claimForm(id,id)
        val result = gate.locked {
            val form = forms.read(id,false);val draft = form.scheduleCommand ?: fail(ErrorCode.INVALID_INPUT,"Not a schedule draft")
            ensure(draft.payloadHash == draft.command.canonicalPayloadHash(),"Schedule draft changed",ErrorCode.STORAGE)
            draft.result?.let { return@locked it }
            ensure(form.backendRevision == revision,"Schedule draft revision changed",ErrorCode.STALE)
            val result = tracker.scheduleCommand(draft.command)
            // A lost response/save repeats this SAME core key, never a new command.
            forms.save(id,form.copy(backendRevision = Math.addExact(revision,1),scheduleCommand = draft.copy(result = result)));ownership.claimForm(id,id);result
        }
        ownership.finish(id);refresh();return result
    }
    fun abandon(id: String,revision: Long) {
        gate.locked { val form = forms.read(id,false);ensure(form.backendRevision == revision,"Schedule draft changed",ErrorCode.STALE)
            ensure(form.scheduleCommand != null && form.scheduleCommand.result == null,"Executed or missing command cannot be abandoned",ErrorCode.STALE)
            forms.save(id,form.copy(backendRevision = revision+1,scheduleCommand = null));ownership.claimForm(id,id) }
        ownership.finish(id)
    }
}

@Serializable data class SelectiveReview(val operationId: String,val base: Token,val groupIds: Set<String>,val policies: Map<String,TransferConflictPolicy>,val operationHash: String)
@Serializable data class SelectiveDocumentDraft(val sourceId: String,val sourceSha256: String,val sourceBytes: Long,
    val state: String = "STAGED",val review: SelectiveReview? = null)
data class SelectiveDocumentPreview(val draft: BackendDraft,val operationId: String,val plan: TransferPlan)

/** File/lifetime adapter around the ONE existing selective engine. Full replacement and
 * CSV are untouched. No startup auto-apply/resume; terminal group results stay in Dataset. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class SelectiveDocuments(private val context: Context,private val tracker: Tracker,private val engine: SelectiveTransfer = SelectiveTransfer(tracker)) {
    private val forms = FormFiles(context)
    private val ownership = CopyLifetimes.get(context)
    private val gate get() = PrivateFileMaintenance.forContext(context)
    fun stage(input: InputStream): BackendDraft {
        val id = newId();ownership.activate(id)
        val file = ownership.stage(input);val sourceId = file.name.removeSuffix(".json")
        val row = gate.state().stagedInputs.single { it.id == sourceId && it.state == "READY" }
        forms.save(id,FormState(selectiveDocument = SelectiveDocumentDraft(sourceId,row.sha256!!,row.bytes)))
        ownership.claimForm(id,id);ownership.retainInput(id,sourceId)
        return BackendDraft(id,0)
    }
    fun read(id: String): SelectiveDocumentDraft = (forms.read(id,false).selectiveDocument ?: fail(ErrorCode.INVALID_INPUT,"Not a selective draft")).also {
        ensure(it.state in setOf("STAGED","REVIEWED","APPLYING","COMPLETE","ABANDONED") && it.sourceBytes >= 0 &&
            it.sourceSha256.matches(Regex("[0-9a-f]{64}")) && it.sourceId.matches(Regex("[0-9a-f-]{36}")),"Unknown selective draft state",ErrorCode.STORAGE)
        ensure(it.review == null || it.review.operationId.matches(Regex("[0-9a-f-]{36}")) && it.review.operationHash.matches(Regex("[0-9a-f]{64}")),"Invalid selective review",ErrorCode.STORAGE)
    }
    fun inventory(): WorkspaceInventory {
        val (rows,unreadable) = workspaceForms(forms)
        return WorkspaceInventory(rows.mapNotNull { (id,f) -> if(f.selectiveDocument?.state in setOf("STAGED","REVIEWED","APPLYING")) BackendDraft(id,f.backendRevision) else null },unreadable)
    }
    fun pending(): List<BackendDraft> = inventory().drafts
    private fun input(draft: SelectiveDocumentDraft): InputStream {
        ensure(draft.sourceId.matches(Regex("[0-9a-f-]{36}")),"Invalid staged source ID",ErrorCode.STORAGE)
        return Files.newInputStream(File(context.noBackupFilesDir,"restore-inputs/${draft.sourceId}.json").toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
    }
    private fun packageValue(draft: SelectiveDocumentDraft): SelectivePackage = try { decodePackage(draft) }
        catch(e: CoreError) { throw e }
        catch(_: kotlinx.serialization.SerializationException) { fail(ErrorCode.UNSUPPORTED_IMPORT,"Malformed selective archive") }
        catch(_: java.nio.charset.CharacterCodingException) { fail(ErrorCode.UNSUPPORTED_IMPORT,"Invalid selective UTF-8") }
        catch(_: java.io.IOException) { fail(ErrorCode.STORAGE,"Selective source unavailable") }
    private fun decodePackage(draft: SelectiveDocumentDraft): SelectivePackage {
        fun digestText(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        // Strict UTF-8 pass without retaining a second whole JSON String. The second
        // parser pass uses the existing serializer and must read the identical bytes.
        val first = MessageDigest.getInstance("SHA-256")
        java.io.InputStreamReader(DigestInputStream(input(draft),first),Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).use {
            val buffer = CharArray(32768);while(it.read(buffer) >= 0) { }
        }
        ensure(digestText(first.digest()) == draft.sourceSha256,"Selective source changed",ErrorCode.STALE)
        val second = MessageDigest.getInstance("SHA-256")
        val value = DigestInputStream(input(draft),second).use { codec.decodeFromStream<SelectivePackage>(it) }
        ensure(digestText(second.digest()) == draft.sourceSha256,"Selective source changed during read",ErrorCode.STALE)
        engine.inspect(value);return value
    }
    fun inspect(id: String): TransferInspection = gate.locked { engine.inspect(packageValue(read(id))) }
    fun plan(id: String,revision: Long,groups: Set<String>,policies: Map<String,TransferConflictPolicy> = emptyMap(),replaceUnappliedReview: Boolean = false): SelectiveDocumentPreview {
        ownership.claimForm(id,id)
        return gate.locked {
            val form = forms.read(id,false);val draft = form.selectiveDocument ?: fail(ErrorCode.INVALID_INPUT,"Not a selective draft")
            ensure(form.backendRevision == revision && draft.state !in setOf("COMPLETE","ABANDONED"),"Selective draft changed",ErrorCode.STALE)
            val source = packageValue(draft)
            if(replaceUnappliedReview) ensure(progress(id) == null,"An existing operation must be resumed using its original ID",ErrorCode.STALE)
            val saved = if(replaceUnappliedReview) null else draft.review
            val plan = if(saved == null) engine.plan(source,groups,policies) else {
                ensure(saved.groupIds == groups && saved.policies == policies,"Explicit new draft required for another reviewed payload",ErrorCode.STALE)
                engine.reopenPlan(source,groups,policies,saved.base)
            }
            val review = saved ?: SelectiveReview(newId(),plan.baseToken,groups.toSet(),policies.toMap(),plan.operationHash)
            ensure(review.operationHash == plan.operationHash,"Reviewed transfer hash changed",ErrorCode.STALE)
            if(saved != null) return@locked SelectiveDocumentPreview(BackendDraft(id,revision),review.operationId,plan)
            forms.save(id,form.copy(backendRevision = revision+1,selectiveDocument = draft.copy(state = "REVIEWED",review = review)))
            ownership.claimForm(id,id);SelectiveDocumentPreview(BackendDraft(id,revision+1),review.operationId,plan)
        }
    }
    fun progress(id: String): TransferOperation? = read(id).review?.let { review -> tracker.data().transfer.operations.singleOrNull { it.id == review.operationId }?.also {
        ensure(it.hash == review.operationHash,"Transfer identity changed",ErrorCode.STALE)
    } }
    fun apply(id: String,revision: Long,operationId: String): TransferOperation {
        ownership.claimForm(id,id)
        val result = gate.locked {
            val form = forms.read(id,false);val draft = form.selectiveDocument ?: fail(ErrorCode.INVALID_INPUT,"Not a selective draft")
            val review = draft.review ?: fail(ErrorCode.INVALID_INPUT,"Review a selective plan first")
            ensure(review.operationId == operationId && draft.state != "ABANDONED","Transfer operation changed",ErrorCode.STALE)
            val existing = progress(id)
            if(draft.state == "COMPLETE" && existing != null && existing.groups.none { it.state == TransferGroupState.PENDING }) return@locked existing
            if(existing == null) ensure(form.backendRevision == revision,"Selective draft revision changed",ErrorCode.STALE)
            val result = if(existing != null) engine.resume(operationId) else {
                val plan = engine.reopenPlan(packageValue(draft),review.groupIds,review.policies,review.base)
                ensure(plan.operationHash == review.operationHash,"Transfer payload changed",ErrorCode.STALE)
                forms.save(id,form.copy(selectiveDocument = draft.copy(state = "APPLYING")));ownership.claimForm(id,id)
                engine.apply(plan,operationId)
            }
            if(result.groups.none { it.state == TransferGroupState.PENDING }) { forms.save(id,form.copy(backendRevision = form.backendRevision+1,selectiveDocument = draft.copy(state = "COMPLETE")));ownership.claimForm(id,id) }
            result
        }
        if(result.groups.none { it.state == TransferGroupState.PENDING }) ownership.finish(id)
        return result
    }
    fun resume(id: String,operationId: String): TransferOperation {
        ensure(progress(id) != null,"Transfer has not been applied",ErrorCode.STALE)
        return apply(id,forms.read(id,false).backendRevision,operationId)
    }
    fun undo(id: String,expected: Token): TransferOperation {
        val review = read(id).review ?: fail(ErrorCode.INVALID_INPUT,"Transfer operation missing")
        ensure(progress(id) != null,"Transfer operation missing",ErrorCode.STALE);return engine.undo(review.operationId,expected)
    }
    fun abandon(id: String,revision: Long) {
        gate.locked {
            val form = forms.read(id,false);val draft = form.selectiveDocument ?: fail(ErrorCode.INVALID_INPUT,"Not a selective draft")
            ensure(form.backendRevision == revision && progress(id)?.groups?.none { it.state == TransferGroupState.PENDING } != false,"Pending transfer must be explicitly resumed",ErrorCode.STALE)
            forms.save(id,form.copy(backendRevision = revision+1,selectiveDocument = draft.copy(state = "ABANDONED")));ownership.claimForm(id,id)
        }
        ownership.finish(id)
    }
    fun export(selection: TransferSelection): ExportOperation {
        val value = engine.export(selection);val exports = ExportOperations(context)
        val operation = exports.begin("application/vnd.notai.tracker.selective+json","tracker-selective.json")
        return exports.prepare(operation.id) { codec.encodeToStream(value,it) }
    }
}
