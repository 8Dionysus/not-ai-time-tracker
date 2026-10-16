package dev.notai.tracker

/** EMPTY_ONLY fills missing classification fields while retaining existing values.
 * REPLACE deliberately replaces the reviewed aggregate, including historical path. */
enum class MetadataFillPolicy { EMPTY_ONLY, REPLACE }
data class MetadataNoteEdit(val value: String?,val policy: MetadataFillPolicy = MetadataFillPolicy.EMPTY_ONLY)
data class BatchMetadataRequest(val sessionIds: List<String>,val selection: Classification? = null,
    val selectionPolicy: MetadataFillPolicy = MetadataFillPolicy.EMPTY_ONLY,val note: MetadataNoteEdit? = null)
enum class MetadataChangedField { NAME, CARD, ACTIVITY_TYPE, HISTORICAL_PATH, DETAILS, TAGS, FACETS, NOTE }
data class MetadataTargetChange(val sessionId: String,val segmentId: String?,val beforeSelection: Classification,
    val afterSelection: Classification,val beforeNote: String?,val afterNote: String?) {
    val fields: Set<MetadataChangedField> get() = frozenSet(buildSet {
        if(beforeSelection.name!=afterSelection.name)add(MetadataChangedField.NAME)
        if(beforeSelection.cardId!=afterSelection.cardId)add(MetadataChangedField.CARD)
        if(beforeSelection.activityTypeId!=afterSelection.activityTypeId)add(MetadataChangedField.ACTIVITY_TYPE)
        if(beforeSelection.ancestorPathAtWrite!=afterSelection.ancestorPathAtWrite)add(MetadataChangedField.HISTORICAL_PATH)
        if(beforeSelection.details!=afterSelection.details)add(MetadataChangedField.DETAILS)
        if(beforeSelection.tags!=afterSelection.tags)add(MetadataChangedField.TAGS)
        if(beforeSelection.facets!=afterSelection.facets)add(MetadataChangedField.FACETS)
        if(beforeNote!=afterNote)add(MetadataChangedField.NOTE)
    })
}
class BatchMetadataPreview internal constructor(val expected: Token,val request: BatchMetadataRequest,
    val changes: List<MetadataTargetChange>,internal val after: Dataset)
internal fun previewBatchMetadata(data: Dataset,input: BatchMetadataRequest): BatchMetadataPreview {
    val request=input.copy(sessionIds=frozenList(input.sessionIds),selection=input.selection?.frozen())
    fun check(ok: Boolean,rule: String) = ensureReason(ok,ErrorReason.METADATA_BATCH_INVALID,arguments=mapOf("rule" to ErrorArgument.Identifier(rule),"recordCount" to ErrorArgument.Count(request.sessionIds.size.toLong())),fieldPath="metadataBatch")
    check(request.sessionIds.isNotEmpty() && request.sessionIds.size<=10000 && request.sessionIds.distinct().size==request.sessionIds.size,"SESSION_IDS")
    check(request.selection!=null || request.note!=null,"FIELDS_REQUIRED")
    request.note?.value?.let { check(it.isNotBlank() && it.length<=16384,"NOTE_BOUNDS") }
    val selected=request.selection?.let { data.classify(it).frozen().also { value -> checkStartable(data,value) } }
    val ids=request.sessionIds.toSet();val sessions=data.sessions.filter { it.id in ids }
    check(sessions.size==ids.size && sessions.all { it.status==SessionStatus.STOPPED },"STOPPED_RECORDS_REQUIRED")
    fun selection(old: Classification): Classification {
        if(selected==null)return old
        if(request.selectionPolicy==MetadataFillPolicy.REPLACE)return selected
        return old.copy(cardId=old.cardId ?: selected.cardId,name=old.name ?: selected.name,
            activityTypeId=old.activityTypeId ?: selected.activityTypeId,
            ancestorPathAtWrite=if(old.activityTypeId==null) selected.ancestorPathAtWrite else old.ancestorPathAtWrite,
            details=selected.details+old.details,tags=if(old.tags.isEmpty())selected.tags else old.tags,
            facets=if(old.facets.isEmpty())selected.facets else old.facets).frozen()
    }
    val changes=mutableListOf<MetadataTargetChange>()
    val after=data.copy(sessions=data.sessions.map { s ->
        if(s.id !in ids) s else {
            val next=selection(s.selection)
            val actualNote=if(request.note!=null && (request.note.policy==MetadataFillPolicy.REPLACE || s.note.isNullOrBlank())) request.note.value else s.note
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
