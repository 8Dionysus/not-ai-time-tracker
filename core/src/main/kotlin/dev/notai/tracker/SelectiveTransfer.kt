package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable data class TransferSelection(val sessionIds: Set<String> = emptySet(),val seriesIds: Set<String> = emptySet(),
    val measurementIds: Set<String> = emptySet(),val excludedOverrides: Set<OccurrenceKey> = emptySet(),val discardLinks: Set<OccurrenceKey> = emptySet(),val includeFocus: Boolean = false,val goalIds: Set<String> = emptySet(),val favoriteIds: Set<String> = emptySet(),
    val cardIds: Set<String> = emptySet(),val typeIds: Set<String> = emptySet(),val savedReportIds: Set<String> = emptySet())
/** Canonical selection roots, unlike ordered hierarchy paths. Never lose duplicate input IDs. */
internal object CatalogRootIdsSerializer: KSerializer<Set<String>> {
    private val list = ListSerializer(String.serializer())
    override val descriptor = list.descriptor
    override fun serialize(encoder: Encoder,value: Set<String>) = list.serialize(encoder,value.sorted())
    override fun deserialize(decoder: Decoder): Set<String> {
        val values = list.deserialize(decoder);val duplicate = values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        ensureReason(duplicate == null,ErrorReason.CATALOG_ROOT_INVALID,arguments = mapOf("rootId" to ErrorArgument.Identifier(duplicate ?: ""),"rule" to ErrorArgument.Text("DUPLICATE_ROOT")),fieldPath = "transfer.catalogRoots")
        return frozenSet(values.sorted())
    }
}
@Serializable data class TransferCatalogRoots(
    @Serializable(with = CatalogRootIdsSerializer::class) val cardIds: Set<String> = emptySet(),
    @Serializable(with = CatalogRootIdsSerializer::class) val typeIds: Set<String> = emptySet()) {
    val empty get() = cardIds.isEmpty() && typeIds.isEmpty()
}
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class TransferContent(val types: List<ActivityType> = emptyList(),val cards: List<Card> = emptyList(),val axes: List<Axis> = emptyList(),
    val metrics: List<Metric> = emptyList(),val units: List<UnitDefinition> = emptyList(),val sessions: List<Session> = emptyList(),val segments: List<Segment> = emptyList(),
    val measurements: List<Measurement> = emptyList(),val series: List<ScheduleSeries> = emptyList(),val overrides: List<OccurrenceOverride> = emptyList(),val links: List<ScheduleStartLink> = emptyList(),val focus: FocusRun? = null,val goals: List<Goal> = emptyList(),val favorites: List<Favorite> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val catalogRoots: TransferCatalogRoots = TransferCatalogRoots(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val savedReports: List<SavedReportQuery> = emptyList())
@Serializable data class TransferEntityOrigin(val kind: String,val sourceEntityId: String,val sourceRevision: Long,val hash: String,val originId: String)
@Serializable data class TransferGroup(val id: String,val originId: String,val sourceRevision: Long,val hash: String,val content: TransferContent,val entities: List<TransferEntityOrigin>)
@Serializable data class SelectivePackage(val formatVersion: Int = 1,val minimumReader: Int = 1,val transferId: String = newId(),val sourceDatasetId: String,
    val groups: List<TransferGroup>,val losses: List<String> = emptyList())
@Serializable data class TransferOrigin(val originId: String,val hash: String,val localGroup: TransferContent,val localHash: String,val compaction: TransferOriginCompaction? = null)
@Serializable enum class TransferConflictPolicy { BLOCK, COPY }
@Serializable enum class TransferGroupState { PENDING, APPLIED, UNCHANGED, REJECTED }
@Serializable data class TransferGroupProgress(val group: TransferGroup,val policy: TransferConflictPolicy,val state: TransferGroupState = TransferGroupState.PENDING,val reason: String? = null,val inserted: TransferContent? = null,val previousOrigin: TransferOrigin? = null)
@Serializable data class TransferOperation(val id: String,val hash: String,val baseDatasetId: String,val baseControlBarrier: String,val nextRevision: Long,val groups: List<TransferGroupProgress>,val undone: Boolean = false,val payloadsPruned: Boolean = false) {
    val complete get() = groups.none { it.state == TransferGroupState.PENDING }
    val partial get() = groups.any { it.state == TransferGroupState.REJECTED } || !complete
}
@Serializable data class TransferState(val origins: List<TransferOrigin> = emptyList(),val operations: List<TransferOperation> = emptyList(),val compactions: List<TransferCompactionReceipt> = emptyList())
data class TransferInspection(val packageId: String,val groups: List<TransferGroup>,val losses: List<String>,val uncertainSessionIds: Set<String>)
data class TransferConflict(val groupId: String,val reason: String)
class TransferPlan internal constructor(internal val owner: Tracker,internal val packageValue: SelectivePackage,internal val expected: Token,
    internal val policies: Map<String,TransferConflictPolicy>,val conflicts: List<TransferConflict>) {
    val groups get() = packageValue.groups
    val losses get() = packageValue.losses
    val baseRevision get() = expected.revision
    val baseToken get() = expected
    val operationHash get() = payloadHash(packageValue)+payloadHash<Map<String,TransferConflictPolicy>>(policies)
}
/** Tracker-specific transfer, alongside explicit replacement JSON and the existing CSV append. */
class SelectiveTransfer(private val tracker: Tracker) {
    fun export(selection: TransferSelection): SelectivePackage {
        val d = tracker.data()
        val typeRows = d.types.withIndex().associate { it.value.id to it }
        val cardRows = d.cards.withIndex().associate { it.value.id to it }
        val captured = selection.copy(sessionIds = frozenSet(selection.sessionIds),seriesIds = frozenSet(selection.seriesIds),
            measurementIds = frozenSet(selection.measurementIds),excludedOverrides = frozenSet(selection.excludedOverrides),discardLinks = frozenSet(selection.discardLinks),
            savedReportIds = frozenSet(selection.savedReportIds),goalIds = frozenSet(selection.goalIds),favoriteIds = frozenSet(selection.favoriteIds),cardIds = frozenSet(selection.cardIds),typeIds = frozenSet(selection.typeIds))
        val wanted = if(captured.includeFocus) {
            val focus = d.focus ?: fail(ErrorCode.INVALID_INPUT,"No focus selected")
            captured.copy(sessionIds = frozenSet(captured.sessionIds+focus.sessionId))
        } else captured
        ensure(d.focus == null || d.focus.sessionId !in wanted.sessionIds || wanted.includeFocus,"Active Focus is a required explicit dependency")
        ensure(wanted.savedReportIds.isNotEmpty() || wanted.sessionIds.isNotEmpty() || wanted.seriesIds.isNotEmpty() || wanted.goalIds.isNotEmpty() || wanted.favoriteIds.isNotEmpty() || wanted.cardIds.isNotEmpty() || wanted.typeIds.isNotEmpty(),"Empty selection")
        ensure(wanted.sessionIds.all { id -> d.sessions.any { it.id == id } } && wanted.seriesIds.all { id -> d.schedule.series.any { it.id == id } },"Missing selection")
        ensure(wanted.savedReportIds.all { id -> d.savedReports.any { it.id == id } },"Missing saved report selection")
        ensure(wanted.goalIds.all { id -> d.goals.any { it.id == id } } && wanted.favoriteIds.all { id -> d.favorites.any { it.id == id } },"Missing goal/favorite selection")
        for((field,ids,present) in listOf(Triple("cardIds",wanted.cardIds,d.cards.map { it.id }.toSet()),Triple("typeIds",wanted.typeIds,d.types.map { it.id }.toSet()))) {
            val missing = (ids-present).sorted().firstOrNull()
            ensureReason(missing == null,ErrorReason.CATALOG_ROOT_INVALID,arguments = mapOf("rootId" to ErrorArgument.Identifier(missing ?: ""),"rule" to ErrorArgument.Text("MISSING_SELECTION")),fieldPath = "transfer.selection.$field")
        }
        ensure(wanted.measurementIds.all { id -> d.measurements.any { it.id == id } },"Missing selected measurement")
        ensure(wanted.excludedOverrides.all { key -> key.seriesId in wanted.seriesIds && d.schedule.overrides.any { it.key == key } },
            "Excluded override must exist in a selected series")
        val segmentIds = d.segments.filter { it.sessionId in wanted.sessionIds }.map { it.id }.toSet()
        ensure(d.measurements.filter { it.id in wanted.measurementIds }.all { if(it.scope == Scope.SESSION) it.targetId in wanted.sessionIds else it.targetId in segmentIds },"Measurement requires complete target session")
        val relevant = d.schedule.links.filter { it.key.seriesId in wanted.seriesIds || it.sessionId in wanted.sessionIds }
        ensure(relevant.all { it.key in wanted.discardLinks || (it.key.seriesId in wanted.seriesIds && it.sessionId in wanted.sessionIds) },"Link dependency unresolved; include both sides or explicitly discard link")
        ensure(wanted.discardLinks.all { key -> relevant.any { it.key == key } },"Unknown discarded link")
        val links = relevant.filterNot { it.key in wanted.discardLinks }
        val nodes = (wanted.sessionIds.map { "session:$it" }+wanted.seriesIds.map { "series:$it" }+wanted.goalIds.map { "goal:$it" }+wanted.favoriteIds.map { "favorite:$it" }+
            wanted.savedReportIds.map { "report:$it" }+wanted.cardIds.map { "card:$it" }+wanted.typeIds.map { "type:$it" }).sorted()
        val parents = nodes.associateWith { it }.toMutableMap()
        fun leader(node: String): String {
            var root = node;while(parents.getValue(root) != root) root = parents.getValue(root)
            var current = node;while(current != root) { val next = parents.getValue(current);parents[current] = root;current = next }
            return root
        }
        fun join(first: String,second: String) {
            val a = leader(first);val b = leader(second);if(a != b) parents[b] = a
        }
        for(l in links) join("session:${l.sessionId}","series:${l.key.seriesId}")
        for(c in d.cards.filter { it.id in wanted.cardIds }) c.activityTypeId?.takeIf { it in wanted.typeIds }?.let { join("card:${c.id}","type:$it") }
        for(t in d.types.filter { it.id in wanted.typeIds }) t.parentId?.takeIf { it in wanted.typeIds }?.let { join("type:${t.id}","type:$it") }
        val byLeader = nodes.groupBy(::leader)
        // Retain the original keep-first-component ordering, including legacy linked facts.
        val components = nodes.filter { leader(it) == it }.map { byLeader.getValue(it).toSet() }
        val now = tracker.clock.now()
        val portable = StreamingBackup.portable(d,now).data
        val groups = components.map { component ->
            val ss = wanted.sessionIds.filter { "session:$it" in component }.toSet();val ps = wanted.seriesIds.filter { "series:$it" in component }.toSet()
            val sessions = portable.sessions.filter { it.id in ss }.map { if(it.status == SessionStatus.RUNNING) it.copy(status = SessionStatus.RECOVERY) else it }
            val segments = portable.segments.filter { it.sessionId in ss }.map { it.copy(uncertain = it.uncertain || it.end == null) }
            val series = d.schedule.series.filter { it.id in ps }
            val overrides = d.schedule.overrides.filter { it.key.seriesId in ps && it.key !in wanted.excludedOverrides }
            val measurements = d.measurements.filter { it.id in wanted.measurementIds && (it.targetId in ss || it.targetId in segments.map { seg -> seg.id }) }
            val savedReports = d.savedReports.filter { "report:${it.id}" in component }
            val goals = d.goals.filter { "goal:${it.id}" in component };val favorites = d.favorites.filter { "favorite:${it.id}" in component }
            val roots = TransferCatalogRoots(frozenSet(wanted.cardIds.filter { "card:$it" in component }.sorted()),frozenSet(wanted.typeIds.filter { "type:$it" in component }.sorted()))
            val goalSelections = goals.flatMap { it.definitions() }.map { it.filter }.plus(savedReports.map { it.filter }).map { f ->
                Classification(cardId = f.cardId?.takeIf { id -> id in cardRows },name = f.cardId?.let { id -> cardRows[id]?.value?.name },
                    activityTypeId = f.activityTypeId?.takeIf { id -> id in typeRows },details = f.details,tags = f.tags,
                    facets = f.facets.filter { facet -> d.axes.any { it.id == facet.axisId && it.meaningVersion == facet.meaningVersion } })
            }
            val ownLinks = links.filter { it.key.seriesId in ps }
            val selections = goalSelections+favorites.map { it.selection }+sessions.map { it.selection }+segments.map { it.selection }+series.flatMap { it.revisions.map { v -> v.rule.selection } }+overrides.mapNotNull { it.selection }
            val cards = (roots.cardIds+selections.mapNotNull { it.cardId }).map { cardRows.getValue(it) }.sortedBy { it.index }.map { it.value }
            val allSelections = selections+cards.map { it.classification() }
            val typeIds = (allSelections.flatMap { it.ancestorPathAtWrite+listOfNotNull(it.activityTypeId) }+roots.typeIds).toMutableSet()
            val queue = ArrayDeque(typeIds)
            while(queue.isNotEmpty()) typeRows.getValue(queue.removeFirst()).value.parentId?.let { if(typeIds.add(it)) queue.addLast(it) }
            val types = typeIds.map { typeRows.getValue(it) }.sortedBy { it.index }.map { it.value }
            val groupingAxes = savedReports.filter { it.grouping == ReportGrouping.FACET }.mapNotNull { it.groupKey }.toSet()
            val axisIds = groupingAxes+allSelections.flatMap { it.facets.map { f -> f.axisId } }.toSet()+types.flatMap { it.suggestedAxisIds }
            val metricIds = measurements.map { it.fieldId }.toSet()+types.flatMap { it.suggestedMetricIds }
            val metrics = d.metrics.filter { it.id in metricIds };val units = d.units.filter { it.id in (metrics.map { m -> m.canonicalUnitId }+measurements.map { m -> m.unit }) }
            val content = TransferContent(types,cards,d.axes.filter { it.id in axisIds },metrics,units,sessions,segments,measurements,series,overrides,ownLinks,portable.focus?.takeIf { wanted.includeFocus && it.sessionId in ss },goals,favorites,roots,savedReports)
            validateContent(content)
            val id = if(roots.empty) component.sorted().joinToString("|") else "catalog:"+payloadHash(component.sorted())
            val previous = d.transfer.origins.find { o -> originMemberIds(o,"report") == savedReports.map { it.id }.toSet() && originMemberIds(o,"session") == ss && originMemberIds(o,"series") == ps && originMemberIds(o,"goal") == goals.map { it.id }.toSet() && originMemberIds(o,"favorite") == favorites.map { it.id }.toSet() && originCatalogRoots(o) == roots }
            val original = previous?.takeIf { originUnchanged(d,it) && currentOriginContent(d,it) == content }?.let { o ->
                val earliest = d.transfer.operations.flatMap { op -> op.groups.map { op.payloadsPruned to it.group } }
                    .firstOrNull { it.second.originId == o.originId && it.second.hash == o.hash }
                val capsule = o.compaction ?: if(earliest?.first == true) compactTransferOrigin(d,o).compaction else null
                if(capsule?.source != null) {
                    val restored = rewriteTransferIds(content,capsule.identities.associateByKind(inverse = true))
                    capsule.source.withContent(restored).also { g ->
                        ensure(g.hash == payloadHash(g.content) && g.entities == entityManifest(g.content,g.sourceRevision,g.originId),"Compacted source reconstruction failed",ErrorCode.STORAGE)
                    }
                } else d.transfer.operations.filterNot { it.payloadsPruned }.flatMap { it.groups }
                    .firstOrNull { it.group.originId == o.originId && it.group.hash == o.hash }?.group
            }
            original ?: group(id,previous?.originId ?: "${d.datasetId}:$id",d.revision,content)
        }
        val omittedMeasurements = d.measurements.count { (it.targetId in wanted.sessionIds || it.targetId in segmentIds) && it.id !in wanted.measurementIds }
        val losses = buildList {
            add("GLOBAL_SETTINGS_AND_CONTROL_ASSIGNMENTS_NOT_PORTABLE")
            if(d.savedReports.any { it.id !in wanted.savedReportIds }) add("SAVED_REPORTS_NOT_SELECTED:${d.savedReports.count { it.id !in wanted.savedReportIds }}")
            if(d.goals.any { it.id !in wanted.goalIds }) add("GOALS_NOT_SELECTED:${d.goals.count { it.id !in wanted.goalIds }}")
            if(d.favorites.any { it.id !in wanted.favoriteIds }) add("FAVORITES_NOT_SELECTED:${d.favorites.count { it.id !in wanted.favoriteIds }}")
            if(d.focus != null && !wanted.includeFocus) add("FOCUS_NOT_SELECTED")
            if(omittedMeasurements > 0) add("MEASUREMENTS_EXCLUDED:$omittedMeasurements")
            if(wanted.discardLinks.isNotEmpty()) add("LINKS_EXPLICITLY_DISCARDED:${wanted.discardLinks.size}")
            if(wanted.excludedOverrides.isNotEmpty()) add("OVERRIDES_EXCLUDED:${wanted.excludedOverrides.size}")
        }
        val format = if(groups.any { it.content.hasChoicePersonalization() || it.content.hasReportingExtensions() }) 4 else if(groups.any { g -> g.content.segments.any { it.manualConfirmation != null } }) 3 else if(wanted.cardIds.isEmpty() && wanted.typeIds.isEmpty()) 1 else 2
        return SelectivePackage(formatVersion = format,minimumReader = format,sourceDatasetId = d.datasetId,groups = groups,losses = losses)
    }
    fun inspect(text: String): TransferInspection = try { inspect(codec.decodeFromString<SelectivePackage>(text)) }
        catch(e: CoreError) { throw e } catch(_: Exception) { fail(ErrorCode.UNSUPPORTED_IMPORT,"Malformed selective package") }
    fun inspect(value: SelectivePackage): TransferInspection {
        val snapshot = value.frozen()
        ensure(snapshot.formatVersion in 1..4 && snapshot.minimumReader == snapshot.formatVersion && snapshot.sourceDatasetId.isNotBlank() && snapshot.transferId.isNotBlank(),"Unsupported selective package",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(snapshot.formatVersion >= 2 || snapshot.groups.all { it.content.catalogRoots.empty },"Catalog roots require selective format2",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(snapshot.formatVersion >= 4 || snapshot.groups.none { it.content.hasReportingExtensions() },"Reporting extensions require selective format4",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(snapshot.formatVersion >= 3 || snapshot.groups.none { g -> g.content.segments.any { it.manualConfirmation != null } },"Manual confirmation requires selective format3",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(snapshot.formatVersion >= 4 || snapshot.groups.none { it.content.hasChoicePersonalization() },"Personal choices require selective format4",ErrorCode.UNSUPPORTED_IMPORT)
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
    /** Recreate the exact reviewed base after process death, never silently rebase it. */
    fun reopenPlan(value: SelectivePackage,groupIds: Set<String>,policies: Map<String,TransferConflictPolicy>,expected: Token): TransferPlan =
        plan(value,groupIds,policies).also { ensure(it.baseToken == expected,"Reviewed transfer base changed",ErrorCode.STALE) }
    fun apply(plan: TransferPlan,operationId: String): TransferOperation = storage { applyInternal(plan,operationId) }
    private fun applyInternal(plan: TransferPlan,operationId: String): TransferOperation {
        ensure(plan.owner === tracker && operationId.isNotBlank(),"Invalid transfer plan")
        val hash = plan.operationHash
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
            if(op.groups.none { it.state == TransferGroupState.PENDING }) return op
            tracker.store.transaction { d ->
                val live = d.transfer.operations.single { it.id == operationId }
                val pending = live.groups.indexOfFirst { it.state == TransferGroupState.PENDING }
                if(pending < 0) return@transaction d to live
                ensureReason(live.baseDatasetId == d.datasetId && live.baseControlBarrier == d.controlBarrier && live.nextRevision == d.revision,ErrorReason.TRANSFER_BASE_CHANGED,ErrorCode.STALE,mapOf("expectedRevision" to ErrorArgument.Count(live.nextRevision),"actualRevision" to ErrorArgument.Count(d.revision)))
                ensure(!live.undone,"Operation was undone",ErrorCode.STALE)
                val progress = live.groups[pending];var next = d
                val origin = d.transfer.origins.find { it.originId == progress.group.originId }
                val unchanged = origin != null && origin.hash == progress.group.hash && originUnchanged(d,origin)
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
                            units = imported.units.filter { x -> d.units.none { it.id == x.id } },catalogRoots = TransferCatalogRoots()))
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
        ensureReason(!op.payloadsPruned,ErrorReason.TRANSFER_PAYLOAD_PRUNED,ErrorCode.STALE,mapOf("operationId" to ErrorArgument.Identifier(operationId)))
        ensure(op.complete && !op.undone && op.baseDatasetId == d.datasetId && op.baseControlBarrier == d.controlBarrier && op.nextRevision == d.revision,"Transfer undo base changed",ErrorCode.STALE)
        var next = d;var origins = d.transfer.origins
        for(progress in op.groups.asReversed()) {
            val c = progress.inserted ?: continue
            next = next.copy(types = next.types.filterNot { x -> c.types.any { it.id == x.id } },cards = next.cards.filterNot { x -> c.cards.any { it.id == x.id } },
                axes = next.axes.filterNot { x -> c.axes.any { it.id == x.id && it.meaningVersion == x.meaningVersion } },metrics = next.metrics.filterNot { x -> c.metrics.any { it.id == x.id && it.meaningVersion == x.meaningVersion } },
                units = next.units.filterNot { x -> c.units.any { it.id == x.id } },sessions = next.sessions.filterNot { x -> c.sessions.any { it.id == x.id } },segments = next.segments.filterNot { x -> c.segments.any { it.id == x.id } },
                savedReports = next.savedReports.filterNot { x -> c.savedReports.any { it.id == x.id } },
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
            c.savedReports.forEach { add("report",it.id,payloadHash(it)) }
            c.goals.forEach { add("goal",it.id,payloadHash(it)) };c.favorites.forEach { add("favorite",it.id,payloadHash(it)) }
            c.focus?.let { add("focus",it.id,payloadHash(it)) }
        }
        fun contentHash(content: TransferContent): String = payloadHash(content)
        internal fun validateContent(c: TransferContent) {
            for((field,ids,present) in listOf(Triple("cardIds",c.catalogRoots.cardIds,c.cards.map { it.id }.toSet()),Triple("typeIds",c.catalogRoots.typeIds,c.types.map { it.id }.toSet()))) {
                val missing = (ids-present).sorted().firstOrNull()
                ensureReason(missing == null,ErrorReason.CATALOG_ROOT_INVALID,arguments = mapOf("rootId" to ErrorArgument.Identifier(missing ?: ""),"rule" to ErrorArgument.Text("MISSING_REFERENCE")),fieldPath = "transfer.catalogRoots.$field")
            }
            ensure(c.sessions.none { it.status == SessionStatus.RUNNING } && c.segments.all { it.anchor == null && (it.end != null || it.uncertain) } && c.focus?.breakAnchor == null,"Transfer cannot contain live device anchors")
            validate(Dataset(types = c.types,cards = c.cards,sessions = c.sessions,segments = c.segments,measurements = c.measurements,
            axes = c.axes,metrics = c.metrics,units = c.units,schedule = ScheduleData(c.series,c.overrides,c.links),focus = c.focus,goals = c.goals,favorites = c.favorites,savedReports = c.savedReports)) }
        private fun conflict(d: Dataset,g: TransferGroup): String? {
            val origin = d.transfer.origins.find { it.originId == g.originId }
            if(origin != null) return if(origin.hash == g.hash && originUnchanged(d,origin)) null else "ORIGIN_CHANGED_OR_LOCALLY_EDITED"
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
            ensure(c.savedReports.none { q -> d.savedReports.any { it.id == q.id } } && c.sessions.none { s -> d.sessions.any { it.id == s.id } } && c.series.none { s -> d.schedule.series.any { it.id == s.id } } && c.goals.none { g -> d.goals.any { it.id == g.id } } && c.favorites.none { f -> d.favorites.any { it.id == f.id } },"Local entity ID collision")
            return d.copy(savedReports = d.savedReports+c.savedReports,goals = d.goals+c.goals,favorites = d.favorites+c.favorites,focus = c.focus ?: d.focus,types = union(d.types,c.types) { it.id },cards = union(d.cards,c.cards) { it.id },axes = union(d.axes,c.axes) { "${it.id}:${it.meaningVersion}" },
                metrics = union(d.metrics,c.metrics) { "${it.id}:${it.meaningVersion}" },units = union(d.units,c.units) { it.id },sessions = d.sessions+c.sessions,segments = d.segments+c.segments,measurements = d.measurements+c.measurements,
                schedule = d.schedule.copy(series = d.schedule.series+c.series,overrides = d.schedule.overrides+c.overrides,links = d.schedule.links+c.links))
        }
        internal fun currentContent(d: Dataset,c: TransferContent): TransferContent {
            fun <T,K> pick(wanted: List<T>,live: List<T>,key: (T)->K): List<T> = wanted.mapNotNull { w -> live.find { key(it) == key(w) } }
            val oldSegments = pick(c.segments,d.segments) { it.id }
            val extraSegments = d.segments.filter { seg -> c.sessions.any { it.id == seg.sessionId } && c.segments.none { it.id == seg.id } }
            return c.copy(types = pick(c.types,d.types) { it.id },cards = pick(c.cards,d.cards) { it.id },axes = pick(c.axes,d.axes) { it.id to it.meaningVersion },
                metrics = pick(c.metrics,d.metrics) { it.id to it.meaningVersion },units = pick(c.units,d.units) { it.id },sessions = pick(c.sessions,d.sessions) { it.id },
                segments = oldSegments+extraSegments,measurements = pick(c.measurements,d.measurements) { it.id },series = pick(c.series,d.schedule.series) { it.id },
                overrides = pick(c.overrides,d.schedule.overrides) { it.key },links = pick(c.links,d.schedule.links) { it.key },focus = d.focus?.takeIf { it.id == c.focus?.id },
                savedReports = pick(c.savedReports,d.savedReports) { it.id },goals = pick(c.goals,d.goals) { it.id },favorites = pick(c.favorites,d.favorites) { it.id })
        }
        private fun remap(c: TransferContent): TransferContent = rewriteTransferIds(c,transferIdentityIds(c).mapValues { (_,ids) -> ids.distinct().associateWith { newId() } })
    }
}
internal fun validateTransfer(d: Dataset) {
    ensure(d.transfer.origins.map { it.originId }.distinct().size == d.transfer.origins.size,"Duplicate transfer origins")
    ensure(d.transfer.operations.map { it.id }.distinct().size == d.transfer.operations.size,"Duplicate transfer operations")
    d.transfer.origins.forEach(::validateTransferOrigin)
    d.transfer.operations.forEach { op ->
        op.groups.forEach {
            if(op.payloadsPruned) {
                ensure(op.complete && it.group.content == TransferContent() && it.inserted == null && it.previousOrigin == null,"Pruned transfer retains payload/pending state")
                validateTransferHeader(it.group.header())
            } else {
                ensure(payloadHash(it.group.content) == it.group.hash && it.group.entities == SelectiveTransfer.entityManifest(it.group.content,it.group.sourceRevision,it.group.originId),"Transfer journal hash mismatch")
                SelectiveTransfer.validateContent(it.group.content)
                it.previousOrigin?.let(::validateTransferOrigin)
            }
        }
        ensure(op.id.isNotBlank() && op.hash.matches(Regex("[0-9a-f]{128}")) && op.nextRevision >= 0 && op.groups.map { it.group.id }.distinct().size == op.groups.size,"Invalid transfer journal")
    }
    validateTransferCompactions(d.transfer.compactions)
}

private fun SelectivePackage.frozen(): SelectivePackage = copy(
    groups = frozenList(groups.map { it.copy(content = it.content.frozen(),entities = frozenList(it.entities)) }),losses = frozenList(losses))

/** Persisted format10 additions, including minimal receipts after explicit retention. */
fun TransferState.hasCatalogRoots(): Boolean {
    fun rooted(origin: TransferOrigin) = !originCatalogRoots(origin).empty
    return origins.any(::rooted) || operations.any { op -> op.groups.any {
        !it.group.content.catalogRoots.empty || it.inserted?.catalogRoots?.empty == false || it.previousOrigin?.let(::rooted) == true
    } }
}

internal fun Goal.hasReportingExtensions() = metric == GoalMetric.DISTINCT_RECORDED_DAYS || dayStartMinute != 0 || revisions.isNotEmpty()
internal fun TransferContent.hasReportingExtensions() = savedReports.isNotEmpty() || goals.any { it.hasReportingExtensions() }
