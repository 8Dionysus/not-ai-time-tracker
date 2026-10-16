package dev.notai.tracker

/** Classification is one explicitly reviewed aggregate, including historical name/path.
 * EMPTY_ONLY fills wholly unclassified targets; it never erases a partial selection. */
enum class MetadataFillPolicy { EMPTY_ONLY, REPLACE }
data class MetadataNoteEdit(val value: String?,val policy: MetadataFillPolicy = MetadataFillPolicy.EMPTY_ONLY)
data class BatchMetadataRequest(val sessionIds: List<String>,val selection: Classification? = null,
    val selectionPolicy: MetadataFillPolicy = MetadataFillPolicy.EMPTY_ONLY,val note: MetadataNoteEdit? = null)
data class MetadataTargetChange(val sessionId: String,val segmentId: String?,val beforeSelection: Classification,
    val afterSelection: Classification,val beforeNote: String?,val afterNote: String?)
class BatchMetadataPreview internal constructor(val expected: Token,val request: BatchMetadataRequest,
    val changes: List<MetadataTargetChange>,internal val after: Dataset)
internal fun previewBatchMetadata(data: Dataset,input: BatchMetadataRequest): BatchMetadataPreview {
    val request=input.copy(sessionIds=frozenList(input.sessionIds),selection=input.selection?.frozen())
    ensure(request.sessionIds.isNotEmpty() && request.sessionIds.size<=10000 && request.sessionIds.distinct().size==request.sessionIds.size,"Choose distinct stable session IDs")
    ensure(request.selection!=null || request.note!=null,"Choose metadata fields")
    request.note?.value?.let { ensure(it.isNotBlank() && it.length<=16384,"Note must be null or 1..16384 characters") }
    val selected=request.selection?.let { data.classify(it).frozen().also { value -> checkStartable(data,value) } }
    val ids=request.sessionIds.toSet();val sessions=data.sessions.filter { it.id in ids }
    ensure(sessions.size==ids.size && sessions.all { it.status==SessionStatus.STOPPED },"All batch records must exist and be stopped")
    fun selection(old: Classification)=if(selected!=null && (request.selectionPolicy==MetadataFillPolicy.REPLACE || old==Classification())) selected else old
    val changes=mutableListOf<MetadataTargetChange>()
    val after=data.copy(sessions=data.sessions.map { s ->
        if(s.id !in ids) s else {
            val next=selection(s.selection)
            val note=request.note?.let { if(it.policy==MetadataFillPolicy.REPLACE || s.note.isNullOrBlank()) it.value else s.note } ?: s.note
            // An explicit null replacement must remain distinguishable from no edit.
            val actualNote=if(request.note!=null && (request.note.policy==MetadataFillPolicy.REPLACE || s.note.isNullOrBlank())) request.note.value else note
            if(next!=s.selection || actualNote!=s.note) changes += MetadataTargetChange(s.id,null,s.selection,next,s.note,actualNote)
            s.copy(selection=next,note=actualNote)
        }
    },segments=data.segments.map { s ->
        if(s.sessionId !in ids) s else {
            val next=selection(s.selection)
            if(next!=s.selection) changes += MetadataTargetChange(s.sessionId,s.id,s.selection,next,null,null)
            s.copy(selection=next)
        }
    })
    validate(after)
    return BatchMetadataPreview(token(data),request,frozenList(changes),after.frozen())
}
