package dev.notai.tracker

import kotlinx.serialization.Serializable

@Serializable data class TransferSelection(val sessionIds: Set<String> = emptySet(),val seriesIds: Set<String> = emptySet(),
    val measurementIds: Set<String> = emptySet(),val excludedOverrides: Set<OccurrenceKey> = emptySet(),val discardLinks: Set<OccurrenceKey> = emptySet(),val includeFocus: Boolean = false,val goalIds: Set<String> = emptySet(),val favoriteIds: Set<String> = emptySet())
@Serializable data class TransferContent(val types: List<ActivityType> = emptyList(),val cards: List<Card> = emptyList(),val axes: List<Axis> = emptyList(),
    val metrics: List<Metric> = emptyList(),val units: List<UnitDefinition> = emptyList(),val sessions: List<Session> = emptyList(),val segments: List<Segment> = emptyList(),
    val measurements: List<Measurement> = emptyList(),val series: List<ScheduleSeries> = emptyList(),val overrides: List<OccurrenceOverride> = emptyList(),val links: List<ScheduleStartLink> = emptyList(),val focus: FocusRun? = null,val goals: List<Goal> = emptyList(),val favorites: List<Favorite> = emptyList())
@Serializable data class TransferEntityOrigin(val kind: String,val sourceEntityId: String,val sourceRevision: Long,val hash: String,val originId: String)
@Serializable data class TransferGroup(val id: String,val originId: String,val sourceRevision: Long,val hash: String,val content: TransferContent,val entities: List<TransferEntityOrigin>)
@Serializable data class SelectivePackage(val formatVersion: Int = 1,val minimumReader: Int = 1,val transferId: String = newId(),val sourceDatasetId: String,
    val groups: List<TransferGroup>,val losses: List<String> = emptyList())
@Serializable data class TransferOrigin(val originId: String,val hash: String,val localGroup: TransferContent,val localHash: String)
@Serializable enum class TransferConflictPolicy { BLOCK, COPY }
@Serializable enum class TransferGroupState { PENDING, APPLIED, UNCHANGED, REJECTED }
@Serializable data class TransferGroupProgress(val group: TransferGroup,val policy: TransferConflictPolicy,val state: TransferGroupState = TransferGroupState.PENDING,val reason: String? = null,val inserted: TransferContent? = null,val previousOrigin: TransferOrigin? = null)
@Serializable data class TransferOperation(val id: String,val hash: String,val baseDatasetId: String,val baseControlBarrier: String,val nextRevision: Long,val groups: List<TransferGroupProgress>,val undone: Boolean = false) {
    val complete get() = groups.none { it.state == TransferGroupState.PENDING }
    val partial get() = groups.any { it.state == TransferGroupState.REJECTED } || !complete
}
@Serializable data class TransferState(val origins: List<TransferOrigin> = emptyList(),val operations: List<TransferOperation> = emptyList())
data class TransferInspection(val packageId: String,val groups: List<TransferGroup>,val losses: List<String>,val uncertainSessionIds: Set<String>)
data class TransferConflict(val groupId: String,val reason: String)
class TransferPlan internal constructor(internal val owner: Tracker,internal val packageValue: SelectivePackage,internal val expected: Token,
    internal val policies: Map<String,TransferConflictPolicy>,val conflicts: List<TransferConflict>) {
    val groups get() = packageValue.groups
    val losses get() = packageValue.losses
    val baseRevision get() = expected.revision
}
/** Tracker-specific transfer, alongside explicit replacement JSON and the existing CSV append. */
class SelectiveTransfer(private val tracker: Tracker) {
    fun export(selection: TransferSelection): SelectivePackage {
        val d = tracker.data()
        val wanted = if(selection.includeFocus) {
            val focus = d.focus ?: fail(ErrorCode.INVALID_INPUT,"No focus selected")
            selection.copy(sessionIds = selection.sessionIds+focus.sessionId)
        } else selection
        ensure(d.focus == null || d.focus.sessionId !in wanted.sessionIds || wanted.includeFocus,"Active Focus is a required explicit dependency")
        ensure(wanted.sessionIds.isNotEmpty() || wanted.seriesIds.isNotEmpty() || wanted.goalIds.isNotEmpty() || wanted.favoriteIds.isNotEmpty(),"Empty selection")
        ensure(wanted.sessionIds.all { id -> d.sessions.any { it.id == id } } && wanted.seriesIds.all { id -> d.schedule.series.any { it.id == id } },"Missing selection")
        ensure(wanted.goalIds.all { id -> d.goals.any { it.id == id } } && wanted.favoriteIds.all { id -> d.favorites.any { it.id == id } },"Missing goal/favorite selection")
        ensure(wanted.measurementIds.all { id -> d.measurements.any { it.id == id } },"Missing selected measurement")
        val segmentIds = d.segments.filter { it.sessionId in wanted.sessionIds }.map { it.id }.toSet()
        ensure(d.measurements.filter { it.id in wanted.measurementIds }.all { if(it.scope == Scope.SESSION) it.targetId in wanted.sessionIds else it.targetId in segmentIds },"Measurement requires complete target session")
        val relevant = d.schedule.links.filter { it.key.seriesId in wanted.seriesIds || it.sessionId in wanted.sessionIds }
        ensure(relevant.all { it.key in wanted.discardLinks || (it.key.seriesId in wanted.seriesIds && it.sessionId in wanted.sessionIds) },"Link dependency unresolved; include both sides or explicitly discard link")
        ensure(wanted.discardLinks.all { key -> relevant.any { it.key == key } },"Unknown discarded link")
        val links = relevant.filterNot { it.key in wanted.discardLinks }
        val nodes = (wanted.sessionIds.map { "session:$it" }+wanted.seriesIds.map { "series:$it" }+wanted.goalIds.map { "goal:$it" }+wanted.favoriteIds.map { "favorite:$it" }).sorted()
        val components = mutableListOf<MutableSet<String>>()
        nodes.forEach { components += mutableSetOf(it) }
        for(l in links) {
            val a = components.single { "session:${l.sessionId}" in it };val b = components.single { "series:${l.key.seriesId}" in it }
            if(a !== b) { a += b;components.remove(b) }
        }
        val now = tracker.clock.now()
        val portable = StreamingBackup.portable(d,now).data
        val groups = components.map { component ->
            val ss = wanted.sessionIds.filter { "session:$it" in component }.toSet();val ps = wanted.seriesIds.filter { "series:$it" in component }.toSet()
            val sessions = portable.sessions.filter { it.id in ss }.map { if(it.status == SessionStatus.RUNNING) it.copy(status = SessionStatus.RECOVERY) else it }
            val segments = portable.segments.filter { it.sessionId in ss }.map { it.copy(uncertain = it.uncertain || it.end == null) }
            val series = d.schedule.series.filter { it.id in ps }
            val overrides = d.schedule.overrides.filter { it.key.seriesId in ps && it.key !in wanted.excludedOverrides }
            val measurements = d.measurements.filter { it.id in wanted.measurementIds && (it.targetId in ss || it.targetId in segments.map { seg -> seg.id }) }
            val goals = d.goals.filter { "goal:${it.id}" in component };val favorites = d.favorites.filter { "favorite:${it.id}" in component }
            val goalSelections = goals.map { goal -> val f = goal.filter;Classification(cardId = f.cardId,name = f.cardId?.let { id -> d.cards.single { it.id == id }.name },activityTypeId = f.activityTypeId,details = f.details,tags = f.tags,facets = f.facets) }
            val ownLinks = links.filter { it.key.seriesId in ps }
            val selections = goalSelections+favorites.map { it.selection }+sessions.map { it.selection }+segments.map { it.selection }+series.flatMap { it.revisions.map { v -> v.rule.selection } }+overrides.mapNotNull { it.selection }
            val cards = d.cards.filter { c -> selections.any { it.cardId == c.id } }
            val allSelections = selections+cards.map { it.classification() }
            val typeIds = allSelections.flatMap { it.ancestorPathAtWrite+listOfNotNull(it.activityTypeId) }.toMutableSet()
            typeIds.toList().forEach { typeIds += d.ancestorPath(it) }
            val types = d.types.filter { it.id in typeIds }
            val axisIds = allSelections.flatMap { it.facets.map { f -> f.axisId } }.toSet()+types.flatMap { it.suggestedAxisIds }
            val metricIds = measurements.map { it.fieldId }.toSet()+types.flatMap { it.suggestedMetricIds }
            val metrics = d.metrics.filter { it.id in metricIds };val units = d.units.filter { it.id in (metrics.map { m -> m.canonicalUnitId }+measurements.map { m -> m.unit }) }
            val content = TransferContent(types,cards,d.axes.filter { it.id in axisIds },metrics,units,sessions,segments,measurements,series,overrides,ownLinks,portable.focus?.takeIf { wanted.includeFocus && it.sessionId in ss },goals,favorites)
            validateContent(content)
            val id = component.sorted().joinToString("|")
            val previous = d.transfer.origins.find { o -> o.localGroup.sessions.map { it.id }.toSet() == ss && o.localGroup.series.map { it.id }.toSet() == ps && o.localGroup.goals.map { it.id }.toSet() == goals.map { it.id }.toSet() && o.localGroup.favorites.map { it.id }.toSet() == favorites.map { it.id }.toSet() }
            val original = previous?.takeIf { it.localGroup == content }?.let { o ->
                d.transfer.operations.flatMap { it.groups }.firstOrNull { it.group.originId == o.originId && it.group.hash == o.hash }?.group
            }
            original ?: group(id,previous?.originId ?: "${d.datasetId}:$id",d.revision,content)
        }
        val omittedMeasurements = d.measurements.count { (it.targetId in wanted.sessionIds || it.targetId in segmentIds) && it.id !in wanted.measurementIds }
        val losses = buildList {
            add("GLOBAL_SETTINGS_AND_CONTROL_ASSIGNMENTS_NOT_PORTABLE")
            if(d.goals.any { it.id !in wanted.goalIds }) add("GOALS_NOT_SELECTED:${d.goals.count { it.id !in wanted.goalIds }}")
            if(d.favorites.any { it.id !in wanted.favoriteIds }) add("FAVORITES_NOT_SELECTED:${d.favorites.count { it.id !in wanted.favoriteIds }}")
            if(d.focus != null && !wanted.includeFocus) add("FOCUS_NOT_SELECTED")
            if(omittedMeasurements > 0) add("MEASUREMENTS_EXCLUDED:$omittedMeasurements")
            if(wanted.discardLinks.isNotEmpty()) add("LINKS_EXPLICITLY_DISCARDED:${wanted.discardLinks.size}")
            if(wanted.excludedOverrides.isNotEmpty()) add("OVERRIDES_EXCLUDED:${wanted.excludedOverrides.size}")
        }
        return SelectivePackage(sourceDatasetId = d.datasetId,groups = groups,losses = losses)
    }
    fun inspect(text: String): TransferInspection = try { inspect(codec.decodeFromString<SelectivePackage>(text)) }
        catch(e: CoreError) { throw e } catch(_: Exception) { fail(ErrorCode.UNSUPPORTED_IMPORT,"Malformed selective package") }
    fun inspect(value: SelectivePackage): TransferInspection {
        val snapshot = value.frozen()
        ensure(snapshot.formatVersion == 1 && snapshot.minimumReader == 1 && snapshot.sourceDatasetId.isNotBlank() && snapshot.transferId.isNotBlank(),"Unsupported selective package",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(snapshot.groups.isNotEmpty() && snapshot.groups.map { it.id }.distinct().size == snapshot.groups.size && snapshot.groups.map { it.originId }.distinct().size == snapshot.groups.size,"Duplicate/empty transfer groups")
        snapshot.groups.forEach { ensure(it.id.isNotBlank() && it.originId.isNotBlank() && it.sourceRevision >= 0 && payloadHash(it.content) == it.hash && it.entities == entityManifest(it.content,it.sourceRevision,it.originId),"Transfer hash/manifest mismatch");validateContent(it.content) }
        return TransferInspection(snapshot.transferId,snapshot.groups,snapshot.losses,frozenSet(snapshot.groups.flatMap { it.content.sessions }.filter { it.status != SessionStatus.STOPPED }.map { it.id }))
    }
    fun plan(value: SelectivePackage,groupIds: Set<String>,policies: Map<String,TransferConflictPolicy> = emptyMap()): TransferPlan {
        // Deep-copy the inspected source at the trust boundary; subsequent caller edits cannot change preview.
        val frozen = codec.decodeFromString<SelectivePackage>(codec.encodeToString(value)).frozen();inspect(frozen)
        ensure(groupIds.isNotEmpty() && groupIds.all { id -> frozen.groups.any { it.id == id } } && policies.keys.all { it in groupIds },"Invalid group selection")
        val selected = frozen.copy(groups = frozenList(frozen.groups.filter { it.id in groupIds }));val d = tracker.data()
        val conflicts = selected.groups.mapNotNull { group -> conflict(d,group)?.let { TransferConflict(group.id,it) } }
        return TransferPlan(tracker,selected,token(d),frozenMap(policies),frozenList(conflicts))
    }
    fun preview(plan: TransferPlan): TransferPlan { ensure(plan.owner === tracker,"Wrong transfer owner");checkToken(tracker.data(),plan.expected,tracker.clock.now());return plan }
    fun apply(plan: TransferPlan,operationId: String): TransferOperation = storage { applyInternal(plan,operationId) }
    private fun applyInternal(plan: TransferPlan,operationId: String): TransferOperation {
        ensure(plan.owner === tracker && operationId.isNotBlank(),"Invalid transfer plan")
        val hash = payloadHash(plan.packageValue)+payloadHash<Map<String,TransferConflictPolicy>>(plan.policies)
        tracker.store.transaction { d ->
            val existing = d.transfer.operations.find { it.id == operationId }
            if(existing != null) { ensureReason(existing.hash == hash,ErrorReason.IDEMPOTENCY_CONFLICT,ErrorCode.STALE,mapOf("operationId" to ErrorArgument.Identifier(operationId)));return@transaction d to existing }
            checkToken(d,plan.expected,tracker.clock.now())
            val revision = Math.addExact(d.revision,1)
            val operation = TransferOperation(operationId,hash,d.datasetId,d.controlBarrier,revision,plan.groups.map { TransferGroupProgress(it,plan.policies[it.id] ?: TransferConflictPolicy.BLOCK) })
            d.copy(revision = revision,transfer = d.transfer.copy(operations = d.transfer.operations+operation)) to operation
        }
        return resume(operationId)
    }
    /** Explicit resume only. Own commits advance expected revision; unrelated edits require a new plan. */
    fun resume(operationId: String): TransferOperation = storage { resumeInternal(operationId) }
    private fun resumeInternal(operationId: String): TransferOperation {
        while(true) {
            val op = tracker.data().transfer.operations.find { it.id == operationId } ?: fail(ErrorCode.INVALID_INPUT,"Operation missing")
            val pending = op.groups.indexOfFirst { it.state == TransferGroupState.PENDING };if(pending < 0) return op
            tracker.store.transaction { d ->
                val live = d.transfer.operations.single { it.id == operationId }
                ensureReason(live.baseDatasetId == d.datasetId && live.baseControlBarrier == d.controlBarrier && live.nextRevision == d.revision,ErrorReason.TRANSFER_BASE_CHANGED,ErrorCode.STALE,mapOf("expectedRevision" to ErrorArgument.Count(live.nextRevision),"actualRevision" to ErrorArgument.Count(d.revision)))
                ensure(!live.undone,"Operation was undone",ErrorCode.STALE)
                val progress = live.groups[pending];var next = d
                val origin = d.transfer.origins.find { it.originId == progress.group.originId }
                val unchanged = origin != null && origin.hash == progress.group.hash && currentContent(d,origin.localGroup) == origin.localGroup
                val reason = conflict(d,progress.group)
                val completed = if(unchanged) progress.copy(state = TransferGroupState.UNCHANGED) else if(reason != null && progress.policy == TransferConflictPolicy.BLOCK) {
                    progress.copy(state = TransferGroupState.REJECTED,reason = reason)
                } else {
                    try {
                        val imported = if(reason != null) remap(progress.group.content) else progress.group.content
                        next = merge(d,imported)
                        validate(next)
                        val savedOrigin = TransferOrigin(progress.group.originId,progress.group.hash,imported,payloadHash(imported))
                        next = next.copy(transfer = d.transfer.copy(origins = d.transfer.origins.filterNot { it.originId == savedOrigin.originId }+savedOrigin))
                        progress.copy(state = TransferGroupState.APPLIED,previousOrigin = origin,inserted = imported.copy(
                            types = imported.types.filter { x -> d.types.none { it.id == x.id } },cards = imported.cards.filter { x -> d.cards.none { it.id == x.id } },
                            axes = imported.axes.filter { x -> d.axes.none { it.id == x.id && it.meaningVersion == x.meaningVersion } },
                            metrics = imported.metrics.filter { x -> d.metrics.none { it.id == x.id && it.meaningVersion == x.meaningVersion } },
                            units = imported.units.filter { x -> d.units.none { it.id == x.id } }))
                    } catch(e: CoreError) { next = d;progress.copy(state = TransferGroupState.REJECTED,reason = e.code.name) }
                }
                val revision = Math.addExact(d.revision,1)
                val result = live.copy(nextRevision = revision,groups = live.groups.mapIndexed { i,g -> if(i == pending) completed else g })
                next.copy(revision = revision,transfer = next.transfer.copy(operations = d.transfer.operations.map { if(it.id == operationId) result else it })) to result
            }
        }
    }
    /** Explicit compensating removal, only while no later mutation has occurred. */
    fun undo(operationId: String,expected: Token): TransferOperation = storage { undoInternal(operationId,expected) }
    private fun undoInternal(operationId: String,expected: Token): TransferOperation = tracker.store.transaction { d ->
        val op = d.transfer.operations.singleOrNull { it.id == operationId } ?: fail(ErrorCode.INVALID_INPUT,"Operation missing")
        checkToken(d,expected,tracker.clock.now())
        ensure(op.complete && !op.undone && op.baseDatasetId == d.datasetId && op.baseControlBarrier == d.controlBarrier && op.nextRevision == d.revision,"Transfer undo base changed",ErrorCode.STALE)
        var next = d;var origins = d.transfer.origins
        for(progress in op.groups.asReversed()) {
            val c = progress.inserted ?: continue
            next = next.copy(types = next.types.filterNot { x -> c.types.any { it.id == x.id } },cards = next.cards.filterNot { x -> c.cards.any { it.id == x.id } },
                axes = next.axes.filterNot { x -> c.axes.any { it.id == x.id && it.meaningVersion == x.meaningVersion } },metrics = next.metrics.filterNot { x -> c.metrics.any { it.id == x.id && it.meaningVersion == x.meaningVersion } },
                units = next.units.filterNot { x -> c.units.any { it.id == x.id } },sessions = next.sessions.filterNot { x -> c.sessions.any { it.id == x.id } },segments = next.segments.filterNot { x -> c.segments.any { it.id == x.id } },
                measurements = next.measurements.filterNot { x -> c.measurements.any { it.id == x.id } },goals = next.goals.filterNot { x -> c.goals.any { it.id == x.id } },favorites = next.favorites.filterNot { x -> c.favorites.any { it.id == x.id } },
                focus = next.focus?.takeUnless { it.id == c.focus?.id },schedule = next.schedule.copy(series = next.schedule.series.filterNot { x -> c.series.any { it.id == x.id } },
                    overrides = next.schedule.overrides.filterNot { x -> c.overrides.any { it.key == x.key } },links = next.schedule.links.filterNot { x -> c.links.any { it.key == x.key } }))
            origins = origins.filterNot { it.originId == progress.group.originId }+listOfNotNull(progress.previousOrigin)
        }
        val revision = Math.addExact(d.revision,1);val result = op.copy(nextRevision = revision,undone = true)
        next.copy(revision = revision,timerEpoch = Math.addExact(d.timerEpoch,1),transfer = d.transfer.copy(origins = origins,operations = d.transfer.operations.map { if(it.id == operationId) result else it })) to result
    }
    companion object {
        fun group(id: String,originId: String,sourceRevision: Long,content: TransferContent): TransferGroup =
            TransferGroup(id,originId,sourceRevision,payloadHash(content),content,entityManifest(content,sourceRevision,originId))
        internal fun entityManifest(c: TransferContent,revision: Long,origin: String): List<TransferEntityOrigin> = buildList {
            fun add(kind: String,id: String,hash: String) { add(TransferEntityOrigin(kind,id,revision,hash,"$origin:$kind:$id")) }
            c.types.forEach { add("type",it.id,payloadHash(it)) };c.cards.forEach { add("card",it.id,payloadHash(it)) }
            c.axes.forEach { add("axis","${it.id}:${it.meaningVersion}",payloadHash(it)) };c.metrics.forEach { add("metric","${it.id}:${it.meaningVersion}",payloadHash(it)) }
            c.units.forEach { add("unit",it.id,payloadHash(it)) };c.sessions.forEach { add("session",it.id,payloadHash(it)) };c.segments.forEach { add("segment",it.id,payloadHash(it)) }
            c.measurements.forEach { add("measurement",it.id,payloadHash(it)) };c.series.forEach { add("series",it.id,payloadHash(it)) }
            c.overrides.forEach { add("override","${it.key.seriesId}:${it.key.originalDate}",payloadHash(it)) };c.links.forEach { add("link","${it.key.seriesId}:${it.key.originalDate}",payloadHash(it)) }
            c.goals.forEach { add("goal",it.id,payloadHash(it)) };c.favorites.forEach { add("favorite",it.id,payloadHash(it)) }
            c.focus?.let { add("focus",it.id,payloadHash(it)) }
        }
        fun contentHash(content: TransferContent): String = payloadHash(content)
        internal fun validateContent(c: TransferContent) {
            ensure(c.sessions.none { it.status == SessionStatus.RUNNING } && c.segments.all { it.anchor == null && (it.end != null || it.uncertain) } && c.focus?.breakAnchor == null,"Transfer cannot contain live device anchors")
            validate(Dataset(types = c.types,cards = c.cards,sessions = c.sessions,segments = c.segments,measurements = c.measurements,
            axes = c.axes,metrics = c.metrics,units = c.units,schedule = ScheduleData(c.series,c.overrides,c.links),focus = c.focus,goals = c.goals,favorites = c.favorites)) }
        private fun conflict(d: Dataset,g: TransferGroup): String? {
            val origin = d.transfer.origins.find { it.originId == g.originId }
            if(origin != null) return if(origin.hash == g.hash && currentContent(d,origin.localGroup) == origin.localGroup) null else "ORIGIN_CHANGED_OR_LOCALLY_EDITED"
            return try { validate(merge(d,g.content));null } catch(_: CoreError) { "IDENTITY_OR_REFERENCE_CONFLICT" }
        }
        private fun merge(d: Dataset,c: TransferContent): Dataset {
            fun <T> union(a: List<T>,b: List<T>,key: (T)->String): List<T> {
                val old = a.associateBy(key)
                ensure(b.all { old[key(it)] == null || old[key(it)] == it },"Identity conflict")
                return a+b.filter { key(it) !in old }
            }
            ensure(d.focus == null || c.focus == null,"Focus identity conflict")
            ensure(c.metrics.none { m -> d.metrics.none { it.id == m.id } && d.measurements.any { it.fieldId == m.id } },"Metric ID already used by an unregistered field")
            // Entity identity collision is explicit even for identical sessions with no shared origin.
            ensure(c.sessions.none { s -> d.sessions.any { it.id == s.id } } && c.series.none { s -> d.schedule.series.any { it.id == s.id } } && c.goals.none { g -> d.goals.any { it.id == g.id } } && c.favorites.none { f -> d.favorites.any { it.id == f.id } },"Local entity ID collision")
            return d.copy(goals = d.goals+c.goals,favorites = d.favorites+c.favorites,focus = c.focus ?: d.focus,types = union(d.types,c.types) { it.id },cards = union(d.cards,c.cards) { it.id },axes = union(d.axes,c.axes) { "${it.id}:${it.meaningVersion}" },
                metrics = union(d.metrics,c.metrics) { "${it.id}:${it.meaningVersion}" },units = union(d.units,c.units) { it.id },sessions = d.sessions+c.sessions,segments = d.segments+c.segments,measurements = d.measurements+c.measurements,
                schedule = d.schedule.copy(series = d.schedule.series+c.series,overrides = d.schedule.overrides+c.overrides,links = d.schedule.links+c.links))
        }
        private fun currentContent(d: Dataset,c: TransferContent): TransferContent {
            fun <T,K> pick(wanted: List<T>,live: List<T>,key: (T)->K): List<T> = wanted.mapNotNull { w -> live.find { key(it) == key(w) } }
            val oldSegments = pick(c.segments,d.segments) { it.id }
            val extraSegments = d.segments.filter { seg -> c.sessions.any { it.id == seg.sessionId } && c.segments.none { it.id == seg.id } }
            return c.copy(types = pick(c.types,d.types) { it.id },cards = pick(c.cards,d.cards) { it.id },axes = pick(c.axes,d.axes) { it.id to it.meaningVersion },
                metrics = pick(c.metrics,d.metrics) { it.id to it.meaningVersion },units = pick(c.units,d.units) { it.id },sessions = pick(c.sessions,d.sessions) { it.id },
                segments = oldSegments+extraSegments,measurements = pick(c.measurements,d.measurements) { it.id },series = pick(c.series,d.schedule.series) { it.id },
                overrides = pick(c.overrides,d.schedule.overrides) { it.key },links = pick(c.links,d.schedule.links) { it.key },focus = d.focus?.takeIf { it.id == c.focus?.id },
                goals = pick(c.goals,d.goals) { it.id },favorites = pick(c.favorites,d.favorites) { it.id })
        }
        private fun remap(c: TransferContent): TransferContent {
            fun ids(values: List<String>) = values.distinct().associateWith { newId() }
            val types = ids(c.types.map { it.id });val cards = ids(c.cards.map { it.id });val axes = ids(c.axes.map { it.id });val metrics = ids(c.metrics.map { it.id })
            val units = ids(c.units.map { it.id });val sessions = ids(c.sessions.map { it.id });val segments = ids(c.segments.map { it.id });val series = ids(c.series.map { it.id })
            fun id(map: Map<String,String>,raw: String) = map[raw] ?: raw
            fun sel(s: Classification) = s.copy(cardId = s.cardId?.let { id(cards,it) },activityTypeId = s.activityTypeId?.let { id(types,it) },
                ancestorPathAtWrite = s.ancestorPathAtWrite.map { id(types,it) },facets = s.facets.map { it.copy(axisId = id(axes,it.axisId)) })
            fun key(k: OccurrenceKey) = k.copy(seriesId = id(series,k.seriesId))
            return c.copy(types = c.types.map { t -> t.copy(id = id(types,t.id),parentId = t.parentId?.let { id(types,it) },suggestedAxisIds = t.suggestedAxisIds.map { id(axes,it) }.toSet(),suggestedMetricIds = t.suggestedMetricIds.map { id(metrics,it) }.toSet()) },
                cards = c.cards.map { val s = sel(it.classification());it.copy(id = id(cards,it.id),activityTypeId = s.activityTypeId,facets = s.facets,templateId = it.templateId?.let { id(types,it) }) },
                axes = c.axes.map { it.copy(id = id(axes,it.id)) },metrics = c.metrics.map { it.copy(id = id(metrics,it.id),canonicalUnitId = id(units,it.canonicalUnitId)) },units = c.units.map { it.copy(id = id(units,it.id)) },
                sessions = c.sessions.map { it.copy(id = id(sessions,it.id),selection = sel(it.selection)) },segments = c.segments.map { it.copy(id = id(segments,it.id),sessionId = id(sessions,it.sessionId),selection = sel(it.selection)) },
                measurements = c.measurements.map { it.copy(id = newId(),targetId = id(if(it.scope == Scope.SESSION) sessions else segments,it.targetId),fieldId = id(metrics,it.fieldId),unit = id(units,it.unit)) },
                series = c.series.map { it.copy(id = id(series,it.id),revisions = it.revisions.map { v -> v.copy(rule = v.rule.copy(selection = sel(v.rule.selection))) }) },
                overrides = c.overrides.map { it.copy(key = key(it.key),selection = it.selection?.let(::sel)) },links = c.links.map { it.copy(key = key(it.key),sessionId = id(sessions,it.sessionId)) },
                goals = c.goals.map { g -> val f = g.filter;g.copy(id = newId(),filter = f.copy(cardId = f.cardId?.let { id(cards,it) },activityTypeId = f.activityTypeId?.let { id(types,it) },facets = f.facets.map { it.copy(axisId = id(axes,it.axisId)) })) },
                favorites = c.favorites.map { it.copy(id = newId(),selection = sel(it.selection)) },
                focus = c.focus?.let { it.copy(id = newId(),sessionId = id(sessions,it.sessionId),segmentIds = it.segmentIds.map { seg -> id(segments,seg) }) })
        }
    }
}
internal fun validateTransfer(d: Dataset) {
    ensure(d.transfer.origins.map { it.originId }.distinct().size == d.transfer.origins.size,"Duplicate transfer origins")
    ensure(d.transfer.operations.map { it.id }.distinct().size == d.transfer.operations.size,"Duplicate transfer operations")
    d.transfer.origins.forEach { ensure(it.originId.isNotBlank() && it.hash.matches(Regex("[0-9a-f]{64}")) && payloadHash(it.localGroup) == it.localHash,"Invalid transfer provenance") }
    d.transfer.operations.forEach { op -> op.groups.forEach { ensure(payloadHash(it.group.content) == it.group.hash && it.group.entities == SelectiveTransfer.entityManifest(it.group.content,it.group.sourceRevision,it.group.originId),"Transfer journal hash mismatch");SelectiveTransfer.validateContent(it.group.content) };ensure(op.id.isNotBlank() && op.hash.length == 128 && op.nextRevision >= 0 && op.groups.map { it.group.id }.distinct().size == op.groups.size,"Invalid transfer journal") }
}

private fun SelectivePackage.frozen(): SelectivePackage = copy(
    groups = frozenList(groups.map { it.copy(content = it.content.frozen(),entities = frozenList(it.entities)) }),losses = frozenList(losses))
