package dev.notai.tracker

import kotlinx.serialization.Serializable

/** Content-free receipt identity. It cannot be submitted to the selective importer as a package. */
@Serializable data class TransferGroupHeader(val id: String,val originId: String,val sourceRevision: Long,val hash: String,val entities: List<TransferEntityOrigin>) {
    internal fun withContent(content: TransferContent) = TransferGroup(id,originId,sourceRevision,hash,content,entities)
}
@Serializable data class TransferIdentityLink(val kind: String,val sourceId: String,val localId: String)
/** Enough to compare live data and reconstruct unchanged source IDs, including COPY, without saved values. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class TransferOriginCompaction(val localEntities: List<TransferEntityOrigin>,val source: TransferGroupHeader?,val identities: List<TransferIdentityLink>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val catalogRoots: TransferCatalogRoots = TransferCatalogRoots())
internal fun TransferGroup.header() = TransferGroupHeader(id,originId,sourceRevision,hash,frozenList(entities))
internal fun List<TransferIdentityLink>.associateByKind(inverse: Boolean = false): Map<String,Map<String,String>> = groupBy { it.kind }.mapValues { (_,links) ->
    links.associate { if(inverse) it.localId to it.sourceId else it.sourceId to it.localId }
}
internal fun transferIdentityIds(c: TransferContent): Map<String,List<String>> = linkedMapOf(
    "type" to c.types.map { it.id },"card" to c.cards.map { it.id },"axis" to c.axes.map { it.id },"metric" to c.metrics.map { it.id },
    "unit" to c.units.map { it.id },"session" to c.sessions.map { it.id },"segment" to c.segments.map { it.id },"series" to c.series.map { it.id },
    "report" to c.savedReports.map { it.id },"measurement" to c.measurements.map { it.id },"goal" to c.goals.map { it.id },"favorite" to c.favorites.map { it.id },"focus" to listOfNotNull(c.focus?.id)) + subjectIdentityIds(c.subjects).filterKeys { it != "subjectCard" }
/** The original COPY rewrite, shared with its inverse; no merge/validation/mutation authority here. */
internal fun rewriteTransferIds(c: TransferContent,maps: Map<String,Map<String,String>>): TransferContent {
    fun id(kind: String,value: String) = maps[kind]?.get(value) ?: value
    fun sel(s: Classification) = s.copy(cardId = s.cardId?.let { id("card",it) },activityTypeId = s.activityTypeId?.let { id("type",it) },
        ancestorPathAtWrite = s.ancestorPathAtWrite.map { id("type",it) },facets = s.facets.map { it.copy(axisId = id("axis",it.axisId)) })
    fun filter(f: Filter) = f.copy(cardId = f.cardId?.let { id("card",it) },activityTypeId = f.activityTypeId?.let { id("type",it) },facets = f.facets.map { it.copy(axisId = id("axis",it.axisId)) })
    fun goal(g: Goal): Goal = g.copy(id = id("goal",g.id),filter = filter(g.filter),revisions = g.revisions.map { it.copy(definition = goal(it.definition)) })
    fun key(k: OccurrenceKey) = k.copy(seriesId = id("series",k.seriesId))
    return c.copy(subjects = c.subjects.rewrite(maps),types = c.types.map { t -> t.copy(id = id("type",t.id),parentId = t.parentId?.let { id("type",it) },suggestedAxisIds = t.suggestedAxisIds.map { id("axis",it) }.toSet(),suggestedMetricIds = t.suggestedMetricIds.map { id("metric",it) }.toSet()) },
        cards = c.cards.map { val s = sel(it.classification());it.copy(id = id("card",it.id),activityTypeId = s.activityTypeId,facets = s.facets,templateId = it.templateId?.let { id("type",it) }) },
        axes = c.axes.map { it.copy(id = id("axis",it.id)) },metrics = c.metrics.map { it.copy(id = id("metric",it.id),canonicalUnitId = id("unit",it.canonicalUnitId)) },units = c.units.map { it.copy(id = id("unit",it.id)) },
        sessions = c.sessions.map { it.copy(id = id("session",it.id),selection = sel(it.selection)) },segments = c.segments.map { it.copy(id = id("segment",it.id),sessionId = id("session",it.sessionId),selection = sel(it.selection)) },
        measurements = c.measurements.map { it.copy(id = id("measurement",it.id),targetId = id(if(it.scope == Scope.SESSION) "session" else "segment",it.targetId),fieldId = id("metric",it.fieldId),unit = id("unit",it.unit)) },
        series = c.series.map { it.copy(id = id("series",it.id),revisions = it.revisions.map { v -> v.copy(rule = v.rule.copy(selection = sel(v.rule.selection))) }) },
        overrides = c.overrides.map { it.copy(key = key(it.key),selection = it.selection?.let(::sel)) },links = c.links.map { it.copy(key = key(it.key),sessionId = id("session",it.sessionId)) },
        goals = c.goals.map(::goal),
        savedReports = c.savedReports.map { it.copy(id = id("report",it.id),filter = filter(it.filter),groupKey = if(it.grouping == ReportGrouping.FACET) it.groupKey?.let { k -> id("axis",k) } else it.groupKey) },
        favorites = c.favorites.map { it.copy(id = id("favorite",it.id),selection = sel(it.selection)) },
        focus = c.focus?.let { it.copy(id = id("focus",it.id),sessionId = id("session",it.sessionId),segmentIds = it.segmentIds.map { seg -> id("segment",seg) }) },
        catalogRoots = TransferCatalogRoots(c.catalogRoots.cardIds.map { id("card",it) }.toSet(),c.catalogRoots.typeIds.map { id("type",it) }.toSet()))
}
internal fun originCatalogRoots(origin: TransferOrigin) = origin.compaction?.catalogRoots ?: origin.localGroup.catalogRoots
internal fun originMemberIds(origin: TransferOrigin,kind: String): Set<String> = origin.compaction?.localEntities?.filter { it.kind == kind }?.map { it.sourceEntityId }?.toSet()
    ?: if(kind == "subjectCard") origin.localGroup.subjects.cards.map { it.cardId }.toSet() else transferIdentityIds(origin.localGroup).getValue(kind).toSet()
internal fun currentOriginContent(d: Dataset,origin: TransferOrigin): TransferContent {
    val capsule = origin.compaction ?: return SelectiveTransfer.currentContent(d,origin.localGroup)
    fun <T> pick(kind: String,values: List<T>,key: (T)->String): List<T> {
        val byId = values.associateBy(key)
        return capsule.localEntities.filter { it.kind == kind }.mapNotNull { byId[it.sourceEntityId] }
    }
    val segments = pick("segment",d.segments) { it.id };val sessions = originMemberIds(origin,"session")
    return TransferContent(types = pick("type",d.types) { it.id },cards = pick("card",d.cards) { it.id },axes = pick("axis",d.axes) { "${it.id}:${it.meaningVersion}" },
        metrics = pick("metric",d.metrics) { "${it.id}:${it.meaningVersion}" },units = pick("unit",d.units) { it.id },sessions = pick("session",d.sessions) { it.id },
        segments = segments+d.segments.filter { it.sessionId in sessions && segments.none { old -> old.id == it.id } },measurements = pick("measurement",d.measurements) { it.id },
        series = pick("series",d.schedule.series) { it.id },overrides = pick("override",d.schedule.overrides) { "${it.key.seriesId}:${it.key.originalDate}" },
        links = pick("link",d.schedule.links) { "${it.key.seriesId}:${it.key.originalDate}" },focus = d.focus?.takeIf { f -> capsule.localEntities.any { it.kind == "focus" && it.sourceEntityId == f.id } },
        savedReports = pick("report",d.savedReports) { it.id },goals = pick("goal",d.goals) { it.id },favorites = pick("favorite",d.favorites) { it.id },catalogRoots = capsule.catalogRoots,subjects = d.subjects.pick(capsule.localEntities.groupBy { it.kind }.mapValues { (_,v) -> v.map { it.sourceEntityId }.toSet() }))
}
internal fun originUnchanged(d: Dataset,origin: TransferOrigin): Boolean {
    val current = currentOriginContent(d,origin)
    if(payloadHash(current) != origin.localHash) return false
    val capsule = origin.compaction ?: return true
    val source = capsule.source ?: return origin.hash == origin.localHash
    val restored = rewriteTransferIds(current,capsule.identities.associateByKind(inverse = true))
    return payloadHash(restored) == source.hash && SelectiveTransfer.entityManifest(restored,source.sourceRevision,source.originId) == source.entities
}
internal fun compactTransferOrigin(d: Dataset,origin: TransferOrigin): TransferOrigin {
    if(origin.compaction != null) return origin
    val matching = d.transfer.operations.flatMap { op -> op.groups.filter { it.group.originId == origin.originId && it.group.hash == origin.hash }.map { op.payloadsPruned to it.group } }
    val header = matching.firstOrNull()?.second?.header()
    val original = matching.firstOrNull { !it.first }?.second?.content ?: origin.localGroup.takeIf { origin.localHash == origin.hash }
        ?: fail(ErrorCode.INVALID_INPUT,"Original COPY source payload is missing")
    val local = transferIdentityIds(origin.localGroup)
    val identities = transferIdentityIds(original).flatMap { (kind,ids) ->
        val localIds = local.getValue(kind);ensure(ids.size == localIds.size,"Source/local identity arity differs")
        ids.zip(localIds).map { (source,id) -> TransferIdentityLink(kind,source,id) }
    }.distinct().filter { it.sourceId != it.localId }
    ensure(identities.map { it.kind to it.sourceId }.distinct().size == identities.size && identities.map { it.kind to it.localId }.distinct().size == identities.size,"Ambiguous source/local identity mapping")
    ensure(rewriteTransferIds(origin.localGroup,identities.associateByKind(inverse = true)) == original,"Original COPY payload is not reversibly reconstructable")
    header?.let { ensure(payloadHash(original) == it.hash && SelectiveTransfer.entityManifest(original,it.sourceRevision,it.originId) == it.entities,"Original source receipt manifest differs") }
    val capsule = TransferOriginCompaction(SelectiveTransfer.entityManifest(origin.localGroup,header?.sourceRevision ?: 0,origin.originId),header,identities,origin.localGroup.catalogRoots)
    return origin.copy(localGroup = TransferContent(),compaction = capsule)
}
private val transferKinds = subjectIdentityIds(SubjectData()).keys + setOf("report","type","card","axis","metric","unit","session","segment","measurement","series","override","link","focus","goal","favorite")
internal fun validateTransferManifest(manifest: List<TransferEntityOrigin>) {
    ensure(manifest.map { it.kind to it.sourceEntityId }.distinct().size == manifest.size,"Duplicate transfer identity")
    manifest.forEach { ensure(it.kind in transferKinds && it.sourceEntityId.isNotBlank() && it.sourceRevision >= 0 && it.originId.isNotBlank() && it.hash.matches(Regex("[0-9a-f]{64}")),"Invalid compact transfer identity") }
}
internal fun validateTransferHeader(h: TransferGroupHeader) {
    ensure(h.id.isNotBlank() && h.originId.isNotBlank() && h.sourceRevision >= 0 && h.hash.matches(Regex("[0-9a-f]{64}")),"Invalid transfer receipt header")
    validateTransferManifest(h.entities)
    ensure(h.entities.all { it.sourceRevision == h.sourceRevision && it.originId == "${h.originId}:${it.kind}:${it.sourceEntityId}" },"Invalid source receipt manifest")
}
internal fun validateTransferOrigin(origin: TransferOrigin) {
    ensure(origin.originId.isNotBlank() && listOf(origin.hash,origin.localHash).all { it.matches(Regex("[0-9a-f]{64}")) },"Invalid transfer provenance digest")
    val c = origin.compaction
    if(c == null) {
        ensure(payloadHash(origin.localGroup) == origin.localHash,"Invalid transfer provenance")
        SelectiveTransfer.validateContent(origin.localGroup)
    } else {
        ensure(origin.localGroup == TransferContent(),"Compacted origin retains values");validateTransferManifest(c.localEntities)
        ensure(c.catalogRoots.cardIds.all { id -> c.localEntities.any { it.kind == "card" && it.sourceEntityId == id } } &&
            c.catalogRoots.typeIds.all { id -> c.localEntities.any { it.kind == "type" && it.sourceEntityId == id } },"Compacted catalog root missing")
        c.source?.let { validateTransferHeader(it);ensure(it.originId == origin.originId && it.hash == origin.hash,"Compacted source identity mismatch") }
        ensure(c.source != null || (c.identities.isEmpty() && origin.hash == origin.localHash),"Missing original COPY identity")
        c.source?.let { source ->
            val ids = c.identities.associateByKind()
            fun mapped(kind: String,key: String): String = when(kind) {
                "subjectCard" -> ids["card"]?.get(key) ?: key
                "axis","metric" -> (ids[kind]?.get(key.substringBeforeLast(':')) ?: key.substringBeforeLast(':'))+":"+key.substringAfterLast(':')
                "override","link" -> (ids["series"]?.get(key.substringBeforeLast(':')) ?: key.substringBeforeLast(':'))+":"+key.substringAfterLast(':')
                else -> ids[kind]?.get(key) ?: key
            }
            ensure(source.entities.map { it.kind to mapped(it.kind,it.sourceEntityId) } == c.localEntities.map { it.kind to it.sourceEntityId },"Source/local compact manifests differ")
        }
        ensure(c.identities.all { it.kind in transferIdentityIds(TransferContent()).keys && it.sourceId.isNotBlank() && it.localId.isNotBlank() } &&
            c.identities.map { it.kind to it.sourceId }.distinct().size == c.identities.size && c.identities.map { it.kind to it.localId }.distinct().size == c.identities.size,"Invalid compact identity mapping")
    }
}
