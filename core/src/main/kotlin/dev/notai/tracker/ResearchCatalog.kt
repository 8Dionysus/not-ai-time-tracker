package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable data class ResearchOrigin(val packageSha256: String,val sourceFile: String,val sourceSha256: String,
    val sourceId: String,val pointer: String)
@Serializable data class SourceKey(val file: String,val id: String)
@Serializable enum class ResearchKind { ACTIVITY, GROUP, CANDIDATE_ACTIVITY, CANDIDATE_GROUP, PROPOSED_SUBTYPE, CONTEXT }
@Serializable enum class ResearchDisposition { TYPE_CANDIDATE, AXIS_CANDIDATE, PRESERVED_ONLY, CORE_DURATION, EXTERNAL_DOCUMENT }
@Serializable data class ResearchOutcome(val pointer: String,val kind: String,val key: String,
    val disposition: ResearchDisposition,val reason: String,val runtimeId: String? = null)
data class ResearchSource(val key: SourceKey,val workingId: String,val original: JsonObject,val pointer: String)
data class ResearchNode(val id: String,val name: String,val definition: String,val parentId: String?,
    val kinds: Set<ResearchKind>,val path: List<String>,val origins: List<ResearchOrigin>,val raw: JsonObject,val pointer: String)

/** Caller persists the exact evidence separately from the compact runtime database/backup.
 * put must be durable; the hash is its stable lookup key. No network is involved in this interface.
 */
interface ResearchArchive {
    fun put(sha256: String,bytes: ByteArray)
    fun read(sha256: String): ByteArray
}
data class ResearchImport(val change: Tracker.Change,val sourceSha256: String,val outcomes: List<ResearchOutcome>)

class ResearchPlan internal constructor(private val original: ByteArray,val sha256: String,val document: JsonObject,
    val sources: List<ResearchSource>,val nodes: List<ResearchNode>,val outcomes: List<ResearchOutcome>,
    private val candidates: List<ActivityType>,private val candidateAxes: List<Axis>,
    private val supporting: Map<String,ByteArray> = emptyMap()) {
    val supportingHashes get() = supporting.mapValues { researchHash(it.value) }
    fun sourceBytes(): ByteArray = original.copyOf()
    val axisCandidateIds get() = candidateAxes.map { it.id }.toSet()
    val candidateIds get() = candidates.map { it.id }.toSet()
    /** For the 17 source proposals with no declared schema, the caller explicitly chooses an
     * existing core shape. This is a new source-local version, not a migration or inferred default.
     * Stable value IDs can represent local object/role identities; text is only a descriptive label.
     */
    fun configureAxis(inventory: String,sourceId: String,localId: String,kind: ValueKind,cardinality: Cardinality,
        values: List<AxisValue> = emptyList()): VocabularyData {
        ensure(localId.matches(Regex("[A-Za-z0-9_.-]+")),"Explicit local axis ID required")
        val rows = document.getValue("axesReview").jsonObject.getValue("rows").jsonArray
        val index = rows.indexOfFirst { it.jsonObject["sourceInventory"]?.jsonPrimitive?.content == inventory &&
            it.jsonObject["sourceAxisId"]?.jsonPrimitive?.content == sourceId }
        ensure(index >= 0,"Unknown axis source")
        val row = rows[index].jsonObject; val source = row.getValue("sourceRecord").jsonObject
        ensure(source["valueKind"] == null && source["cardinality"] == null,"Source already specifies a shape")
        val descriptor = document.getValue("axisValueMapping").jsonObject.getValue("inputHashesAndCounts").jsonObject
            .getValue("sourceFiles").jsonObject.getValue(inventory).jsonObject
        val axis = Axis("research:$inventory:$sourceId:configured:$localId",
            (source["label"] ?: source["nameRu"] ?: source.getValue("name")).jsonPrimitive.content,
            valueKind = kind,cardinality = cardinality,values = values,
            researchOrigins = listOf(ResearchOrigin(sha256,descriptor.getValue("file").jsonPrimitive.content,
                descriptor.getValue("sha256").jsonPrimitive.content,sourceId,"/axesReview/rows/$index")))
        validate(Dataset(axes = listOf(axis)))
        return VocabularyData(emptyList(),listOf(axis))
    }
    /** Explicit choice to record supervision as the ONE primary action. Never creates an overlay. */
    fun primarySupervisionVocabulary(): VocabularyData {
        val node = nodes.single { it.id == "activity.caregiving.passive_supervision_concurrent" }
        val type = ActivityType("research:life_care:${node.id}:primary","Присмотр как основное занятие",
            definition = "Явно выбранное пользователем основное действие присмотра в данном периоде; не параллельный интервал ответственности.",
            researchOrigins = node.origins)
        return VocabularyData(listOf(type))
    }
    /** Explicitly bounded nonnegative totals. Page count is reading volume, never a page
     * position or unique coverage; exercise totals are not set/object identities. BY_TARGET only.
     */
    fun measureVocabulary(sourceFile: String,sourceId: String,localId: String,meaningLabel: String,scope: Scope): VocabularyData {
        val rows = document.getValue("quantitiesReview").jsonObject.getValue("rows").jsonArray
        val index = rows.indexOfFirst { it.jsonObject.getValue("sourceInventory").jsonPrimitive.content == sourceFile &&
            it.jsonObject.getValue("sourceMeasureId").jsonPrimitive.content == sourceId }
        ensure(index >= 0,"Unknown measure source")
        val spec = when(sourceId) {
            "measure.distance" -> Triple("m","length",NumberKind.DECIMAL)
            "measure.elevation_gain" -> Triple("m","length",NumberKind.DECIMAL)
            "measure.sets" -> Triple("set","exercise-set",NumberKind.INTEGER)
            "measure.repetitions" -> Triple("rep","exercise-repetition",NumberKind.INTEGER)
            "measure.pages" -> Triple("page","reading-volume",NumberKind.INTEGER)
            else -> fail(ErrorCode.INVALID_INPUT,"Compound/state quantity requires explicit decomposition")
        }
        val digest = document.getValue("manifest").jsonObject.getValue("sourceManifest").jsonArray
            .single { it.jsonObject.getValue("file").jsonPrimitive.content == sourceFile }.jsonObject.getValue("sha256").jsonPrimitive.content
        return boundedScalar("research:$sourceFile:$sourceId",localId,meaningLabel,scope,spec,6,
            ResearchOrigin(sha256,sourceFile,digest,sourceId,"/quantitiesReview/rows/$index"))
    }
    /** Bounded manual scalars from the verified work compatibility document. The caller names
     * one material/operation or response basis; no event/object identity or automatic SUM is claimed.
     * localId keeps two user definitions separate. Install with Tracker.addCatalog, then measure.
     */
    fun scalarVocabulary(sourceId: String,localId: String,meaningLabel: String,scope: Scope,maxScale: Int = 6): VocabularyData {
        val file = "tracker_work_learning_core_v4_compatibility.json"
        val bytes = supporting[file] ?: fail(ErrorCode.INVALID_INPUT,"Verified quantity source required")
        val spec = when(sourceId) {
            "qneed.work.length_quantity" -> Triple("m","length",NumberKind.DECIMAL)
            "qneed.work.area_quantity" -> Triple("m2","area",NumberKind.DECIMAL)
            "qneed.work.volume_quantity" -> Triple("m3","volume",NumberKind.DECIMAL)
            "qneed.work.mass_quantity" -> Triple("kg","mass",NumberKind.DECIMAL)
            "qneed.learning.response_attempt_count" -> Triple("response","response-attempt",NumberKind.INTEGER)
            else -> fail(ErrorCode.INVALID_INPUT,"No lossless bounded scalar projection for this source")
        }
        val quantities = codec.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("quantities").jsonArray
        val index = quantities.indexOfFirst { it.jsonObject.getValue("id").jsonPrimitive.content == sourceId }
        ensure(index >= 0 && quantities[index].jsonObject.getValue("coreCompatibility").jsonPrimitive.content == "representable","Scalar evidence mismatch")
        return boundedScalar("research:work_learning:$sourceId",localId,meaningLabel,scope,spec,maxScale,
            ResearchOrigin(sha256,file,researchHash(bytes),sourceId,"/supporting/$file/quantities/$index"))
    }
    private fun boundedScalar(prefix: String,localId: String,meaningLabel: String,scope: Scope,
        spec: Triple<String,String,NumberKind>,maxScale: Int,origin: ResearchOrigin): VocabularyData {
        ensure(localId.matches(Regex("[A-Za-z0-9_.-]+")) && meaningLabel.isNotBlank(),"Explicit local meaning required")
        ensure(maxScale in 0..1000,"Invalid scalar precision")
        val id = "$prefix:$localId"
        val unit = UnitDefinition("$id:${spec.first}",spec.first,spec.second)
        val metric = Metric(id,meaningLabel,numberKind = spec.third,canonicalUnitId = unit.id,
            maxScale = if(spec.third == NumberKind.INTEGER) 0 else maxScale,scopes = setOf(scope),
            aggregation = Aggregation.BY_TARGET,researchOrigins = listOf(origin))
        return VocabularyData(emptyList(),metrics = listOf(metric),units = listOf(unit))
    }

    /** An explicit selection is mandatory; all retained ancestors are included without guessing new parents. */
    fun vocabulary(selectedIds: Set<String>,selectedAxisIds: Set<String> = emptySet()): VocabularyData {
        ensure(selectedAxisIds.all { it in axisCandidateIds },"Selection contains a non-runtime axis")
        ensure(selectedIds.all { it in candidateIds },"Selection contains a non-runtime research item")
        val byId = candidates.associateBy { it.id }; val selected = linkedSetOf<String>()
        for(id in selectedIds.sorted()) {
            var current: String? = id
            while(current != null && selected.add(current)) {
                current = (byId[current] ?: fail(ErrorCode.INVALID_INPUT,"Runtime parent is not expressible")).parentId
            }
        }
        val types = candidates.filter { it.id in selected }
        val axes = candidateAxes.filter { it.id in selectedAxisIds }
        validate(Dataset(types = types,axes = axes,metrics = emptyList(),units = emptyList()))
        return VocabularyData(types,axes)
    }
    /** Evidence is verified before the only database mutation. A stale token cannot replace history. */
    fun apply(tracker: Tracker,expected: Token,selectedIds: Set<String>,archive: ResearchArchive,selectedAxisIds: Set<String> = emptySet()): ResearchImport = storage {
        val vocabulary = vocabulary(selectedIds,selectedAxisIds)
        for((_,bytes) in supporting) {
            val digest = researchHash(bytes); archive.put(digest,bytes.copyOf())
            ensure(researchHash(archive.read(digest)) == digest,"Supporting evidence readback failed",ErrorCode.STORAGE)
        }
        archive.put(sha256,sourceBytes())
        ensure(researchHash(archive.read(sha256)) == sha256,"Research evidence readback failed",ErrorCode.STORAGE)
        ResearchImport(tracker.addCatalog(expected,vocabulary),sha256,outcomes)
    }
}
fun researchHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Version-specific reader; unhandled research fields remain in the exact original, never silently discarded. */
object ResearchCatalog {
    fun read(bytes: ByteArray,expectedSha256: String,supportingDocuments: Map<String,ByteArray> = emptyMap()): ResearchPlan {
        ensure(researchHash(bytes) == expectedSha256,"Research package hash mismatch",ErrorCode.UNSUPPORTED_IMPORT)
        return try { parse(bytes.copyOf(),expectedSha256,supportingDocuments.mapValues { it.value.copyOf() }) }
        catch(e: CoreError) { throw e }
        catch(e: Exception) { throw CoreError(ErrorCode.UNSUPPORTED_IMPORT,"Malformed research schema").also { it.initCause(e) } }
    }
    private fun parse(bytes: ByteArray,hash: String,supporting: Map<String,ByteArray>): ResearchPlan {
        val root = codec.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val manifest = root.obj("manifest")
        ensure(manifest.text("schemaVersion") == "tracker-research-package-0.5","Unsupported research schema",ErrorCode.UNSUPPORTED_IMPORT)
        val sourceManifest = manifest.array("sourceManifest").map { it.jsonObject }
        unique(sourceManifest.map { it.text("file") },"source file")
        val files = sourceManifest.associate { it.text("file") to it.text("sha256") }
        ensure(files.values.all { it.matches(Regex("[0-9a-f]{64}")) },"Invalid source digest")
        val counts = manifest.obj("counts")
        val outcomes = mutableListOf<ResearchOutcome>()
        fun outcome(pointer: String,kind: String,key: String,disposition: ResearchDisposition,reason: String,runtimeId: String? = null) {
            outcomes += ResearchOutcome(pointer,kind,key,disposition,reason,runtimeId)
        }
        val sources = root.array("sourceRecords").mapIndexed { i,e ->
            val r = e.jsonObject; val original = r.obj("original")
            val key = SourceKey(r.text("sourceFile"),r.text("sourceId"))
            ensure(key.file in files && original.text("id") == key.id,"Invalid source-qualified record")
            ResearchSource(key,r.text("researchCanonicalId"),original,"/sourceRecords/$i")
        }
        unique(sources.map { it.key },"source record")
        val sourceByKey = sources.associateBy { it.key }
        val mappings = root.array("sourceToWorkingNode").map { it.jsonObject }
        unique(mappings.map { SourceKey(it.text("sourceFile"),it.text("sourceId")) },"source resolution")
        ensure(mappings.size == sources.size,"Incomplete source resolution")
        mappings.forEachIndexed { i,r ->
            val key = SourceKey(r.text("sourceFile"),r.text("sourceId"))
            ensure(sourceByKey[key]?.workingId == r.text("workingNodeId"),"Resolution disagrees with source record")
            outcome("/sourceToWorkingNode/$i","resolution","${key.file}#${key.id}",ResearchDisposition.PRESERVED_ONLY,
                "Explicit package resolution; never inferred from names",r.text("workingNodeId"))
        }
        val proposals = root.array("leisureProposalDecisions").map { it.jsonObject }
        unique(proposals.map { it.text("proposedId") },"proposal")
        val proposalById = proposals.associateBy { it.text("proposedId") }
        val nodes = root.array("nodes").mapIndexed { i,e ->
            val n = e.jsonObject
            val origins = n.array("sourceRecordRefs").map { ref ->
                val r = ref.jsonObject; val file = r.text("sourceFile")
                val sourceId = r.optionalText("sourceId"); val proposalId = r.optionalText("proposalId")
                ensure((sourceId == null) != (proposalId == null),"Invalid origin shape")
                val id = sourceId ?: proposalId!!
                val pointer = if(sourceId != null) {
                    val source = sourceByKey[SourceKey(file,id)] ?: fail(ErrorCode.INVALID_INPUT,"Missing node source")
                    ensure(source.workingId == n.text("id"),"Node/source mapping disagrees")
                    source.pointer
                } else {
                    ensure(proposalById.containsKey(id) && id == n.text("id"),"Missing subtype proposal")
                    "/leisureProposalDecisions/${proposals.indexOf(proposalById.getValue(id))}"
                }
                ResearchOrigin(hash,file,files[file] ?: fail(ErrorCode.INVALID_INPUT,"Unknown origin file"),id,pointer)
            }
            ensure(origins.isNotEmpty(),"Node has no provenance")
            val kinds = n.array("originalSourceKinds").map { ResearchKind.valueOf(it.jsonPrimitive.content.uppercase()) }.toSet()
            ResearchNode(n.text("id"),n.text("name"),n.text("definition"),n.optionalText("parentId"),kinds,
                n.array("researchPath").map { it.jsonPrimitive.content },origins,n,"/nodes/$i")
        }
        unique(nodes.map { it.id },"working node")
        val byId = nodes.associateBy { it.id }
        for(n in nodes) {
            val path = mutableListOf<String>(); val visited = mutableSetOf<String>(); var id: String? = n.id
            while(id != null) {
                ensure(visited.add(id),"Research parent cycle")
                val node = byId[id] ?: fail(ErrorCode.INVALID_INPUT,"Missing research parent")
                path += id; id = node.parentId
            }
            ensure(path.asReversed() == n.path,"Research path differs from parent chain")
        }
        for(s in sources) {
            val node = byId[s.workingId] ?: fail(ErrorCode.INVALID_INPUT,"Source has no working node")
            ensure(node.origins.any { it.sourceFile == s.key.file && it.sourceId == s.key.id },"Source missing from node provenance")
            outcome(s.pointer,"source","${s.key.file}#${s.key.id}",ResearchDisposition.PRESERVED_ONLY,
                "Original fields retained exactly; working classification is a separate explicit projection",s.workingId)
        }
        val kindReview = root.obj("lifeSourceKindReview")
        val kindDecisions = kindReview.array("items").map { it.jsonObject }
        for(r in kindDecisions) {
            val key = SourceKey(kindReview.text("sourceFile"),r.text("sourceId"))
            val n = byId[sourceByKey[key]?.workingId] ?: fail(ErrorCode.INVALID_INPUT,"Missing source-kind target")
            ensure(n.raw.obj("sourceKindDecision") == r,"Source-kind decision disagrees with projection")
        }
        val candidates = mutableListOf<ActivityType>()
        for(n in nodes) {
            val decision = n.raw["sourceKindDecision"]?.jsonObject?.text("decision")
            val reason = when {
                ResearchKind.CONTEXT in n.kinds -> "Context is not a duration activity"
                decision == "event_record_schema_not_standalone_duration_activity" -> "Event-log schema is not a duration activity"
                decision == "concurrent_responsibility_with_explicit_user_primary_option" -> "Concurrent responsibility needs a separate explicit primary-action choice; no second timeline"
                decision != null -> "Unknown source-kind decision requires review"
                else -> null
            }
            if(reason != null) {
                outcome(n.pointer,"node",n.id,ResearchDisposition.PRESERVED_ONLY,reason)
                continue
            }
            val originalRows = n.origins.mapNotNull { sourceByKey[SourceKey(it.sourceFile,it.sourceId)] }
            val selectable = originalRows.any { row ->
                row.original["selectable"]?.jsonPrimitive?.booleanOrNull == true ||
                    row.original["selectableProposal"]?.jsonPrimitive?.booleanOrNull == true ||
                    row.original["candidatePermissions"]?.jsonObject?.get("mayBePrimaryActivityType")?.jsonPrimitive?.booleanOrNull == true
            } || n.kinds == setOf(ResearchKind.PROPOSED_SUBTYPE)
            val aliases = originalRows.flatMap { row -> listOf("aliases","aliasesRu","searchPhrases").flatMap { key ->
                (row.original[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            } }.distinct()
            candidates += ActivityType(n.id,n.name,parentId = n.parentId,definition = n.definition,
                selectable = selectable,aliases = aliases,researchOrigins = n.origins)
            outcome(n.pointer,"node",n.id,ResearchDisposition.TYPE_CANDIDATE,
                "Prepared type only; explicit selection required. Source kinds/status and unrepresented evidence retained",n.id)
        }
        // A retained child cannot silently bypass a non-runtime parent.
        var changed: Boolean
        do {
            val ids = candidates.map { it.id }.toSet()
            val blocked = candidates.filter { it.parentId != null && it.parentId !in ids }.map { it.id }.toSet()
            changed = blocked.isNotEmpty()
            if(changed) {
                candidates.removeAll { it.id in blocked }
                outcomes.replaceAll { if(it.kind == "node" && it.key in blocked) it.copy(disposition = ResearchDisposition.PRESERVED_ONLY,
                    reason = "Parent is not expressible; no implicit reparenting",runtimeId = null) else it }
            }
        } while(changed)
        val candidateAxes = validateLayers(root,hash,files,sourceByKey,outcomes)
        fun count(name: String,actual: Int) = ensure(counts.getValue(name).jsonPrimitive.int == actual,"Manifest count mismatch: $name")
        count("sourceRows",sources.size); count("resolvedWorkingNodesIncludingGroupsContextsSubtypes",nodes.size)
        count("axisRecords",root.obj("axesReview").array("rows").size)
        count("sourceValueOccurrences",root.obj("axisValueMapping").array("valueRecords").size)
        count("originalMeasureRecords",root.obj("quantitiesReview").array("rows").size)
        count("additionalHouseholdQuantitativeCandidates",root.obj("householdQuantities").array("candidateMeasures").size)
        count("leisureConceptDecisions",proposals.size)
        count("newSelectableSubtypeProposals",nodes.count { ResearchKind.PROPOSED_SUBTYPE in it.kinds })
        val checkedCounts = setOf("sourceRows","resolvedWorkingNodesIncludingGroupsContextsSubtypes","axisRecords",
            "sourceValueOccurrences","originalMeasureRecords","additionalHouseholdQuantitativeCandidates",
            "leisureConceptDecisions","newSelectableSubtypeProposals")
        counts.keys.sorted().forEach { key -> outcome("/manifest/counts/$key","declared-count",key,ResearchDisposition.PRESERVED_ONLY,
            if(key in checkedCounts) "Verified against embedded records" else
                "Declared analytical count retained; associated standalone research content is not fully embedded/verified") }
        proposals.forEachIndexed { i,p -> outcome("/leisureProposalDecisions/$i","proposal",p.text("proposedId"),ResearchDisposition.PRESERVED_ONLY,
            "Research decision preserved; only an explicitly selected working type can be installed") }
        // All top-level content, including future/unknown layers, has an explicit retained disposition.
        root.keys.sorted().forEach { key -> outcome("/$key","section",key,ResearchDisposition.PRESERVED_ONLY,
            "Complete raw section retained; nested research evidence is not inferred into runtime rules") }
        sourceManifest.forEachIndexed { i,m -> outcome("/manifest/sourceManifest/$i","source-file",m.text("file"),ResearchDisposition.EXTERNAL_DOCUMENT,
            "Declared source-file digest retained; standalone original bytes are not embedded or independently verified") }
        validateSupportingResearch(root,supporting,sources,nodes,outcomes)
        val plan = ResearchPlan(bytes,hash,root,sources,nodes,outcomes,candidates,candidateAxes,supporting)
        plan.vocabulary(plan.candidateIds,plan.axisCandidateIds) // validate the complete proposed runtime tree, not a seed sample
        return plan
    }
    private fun validateLayers(root: JsonObject,hash: String,files: Map<String,String>,sources: Map<SourceKey,ResearchSource>,
        outcomes: MutableList<ResearchOutcome>): List<Axis> {
        val candidateAxes = mutableListOf<Axis>()
        fun keep(pointer: String,kind: String,key: String,reason: String,disposition: ResearchDisposition = ResearchDisposition.PRESERVED_ONLY) {
            outcomes += ResearchOutcome(pointer,kind,key,disposition,reason)
        }
        val valueLayer = root.obj("axisValueMapping")
        val inventoryFiles = valueLayer.obj("inputHashesAndCounts").obj("sourceFiles").mapValues { (_,v) ->
            val obj = v.jsonObject; val file = obj.text("file")
            ensure(files[file] == obj.text("sha256"),"Inventory source digest mismatch")
            file
        }
        val axes = root.obj("axesReview").array("rows").map { it.jsonObject }
        fun axisKey(o: JsonObject) = SourceKey(inventoryFiles[o.text("sourceInventory")] ?: fail(ErrorCode.INVALID_INPUT,"Unknown inventory"),o.text("sourceAxisId"))
        unique(axes.map(::axisKey),"axis source")
        val axesByKey = axes.associateBy(::axisKey)
        axes.forEachIndexed { i,a ->
            ensure(a.obj("sourceRecord").text("id") == a.text("sourceAxisId"),"Axis identity disagrees")
            val source = a.obj("sourceRecord"); val key = axisKey(a)
            // Preserve source-local shapes, never the proposed global normalization. A mixed
            // source label remains exactly that label, not an assertion that components are synonyms.
            val kind = source.optionalText("valueKind")
            val card = source.optionalText("cardinality")
            val leisureText = kind == "user-selected local value or text" &&
                card == "optional; multiple labels may coexist when true"
            val blocked = a.text("disposition") in setOf("move_to_preference","move_to_domain_record") ||
                (a.text("sourceInventory") == "work_learning" && key.id == "axis.project")
            if(!blocked && ((kind in setOf("free_label","local_choice") && card in setOf("single","multiple","single_optional")) || leisureText)) {
                val id = "research:${a.text("sourceInventory")}:${key.id}"
                // Only declared IDs become initial choices; prose/string examples remain evidence.
                val choices = if(kind == "local_choice") listOf("curatedSuggestedValues","examples","suggestedValues")
                    .flatMap { (source[it] as? JsonArray).orEmpty() }.mapNotNull { e ->
                        val v = e as? JsonObject ?: return@mapNotNull null
                        v.optionalText("valueId")?.let { AxisValue(it,v.optionalText("label") ?: it) }
                    }.distinctBy { it.id } else emptyList()
                candidateAxes += Axis(id,source.optionalText("name") ?: source.optionalText("nameRu") ?: source.text("label"),
                    meaningVersion = source["meaningVersion"]?.jsonPrimitive?.int ?: 1,
                    valueKind = if(kind == "local_choice") ValueKind.LOCAL_CHOICE else ValueKind.FREE_LABEL,
                    cardinality = if(card == "multiple" || leisureText) Cardinality.MULTIPLE else Cardinality.SINGLE,
                    values = choices,
                    researchOrigins = listOf(ResearchOrigin(hash,key.file,files.getValue(key.file),key.id,"/axesReview/rows/$i")))
                outcomes += ResearchOutcome("/axesReview/rows/$i","axis",key.toString(),ResearchDisposition.AXIS_CANDIDATE,
                    "Optional exact source-local ${kind}; declared IDs only, custom choices via saveAxis. " +
                    "No normalized components, identity registry or secondary interval inferred. Review: ${a.text("reasonRu")}",id)
            } else keep("/axesReview/rows/$i","axis",key.toString(),when {
                a.text("disposition") == "move_to_preference" -> "Visibility/search/storage preferences are not activity facets; require card preference fields"
                a.text("disposition") == "move_to_domain_record" -> "Observation fields need a structured observation target; labels cannot preserve record identity and typed values"
                blocked -> "Definition requires stable projectId; free text preserves spelling but not project identity or project/client separation"
                else -> "Source declares no valueKind/cardinality. Concrete alternatives: optional MULTIPLE free text or registered local choices; recommend text for descriptive facets, choices for status, with explicit versioned schema"
            })
        }
        val ledger = valueLayer.array("coverageLedger").map { it.jsonObject }
        ensure(ledger.map(::axisKey).toSet() == axesByKey.keys && ledger.size == axes.size,"Incomplete axis coverage ledger")
        val values = valueLayer.array("valueRecords").map { it.jsonObject }
        fun occurrence(v: JsonObject) = listOf(v.text("sourceFile"),v.text("sourceAxisId"),v.text("sourceValueField"),v.getValue("valueIndexZeroBased").jsonPrimitive.int.toString())
        unique(values.map(::occurrence),"value occurrence")
        val valueMap = values.associateBy(::occurrence)
        val seen = mutableSetOf<List<String>>()
        ledger.forEachIndexed { index,l ->
            val key = axisKey(l); ensure(key.file == l.text("sourceFile"),"Value ledger file mismatch")
            val entries = l.array("values")
            ensure(entries.size == l.getValue("perValueRecordCount").jsonPrimitive.int,"Value ledger count mismatch")
            entries.forEach { entry ->
                val e = entry.jsonObject
                val k = listOf(key.file,key.id,e.text("sourceField"),e.getValue("indexZeroBased").jsonPrimitive.int.toString())
                ensure(seen.add(k),"Duplicate ledger occurrence")
                val v = valueMap[k] ?: fail(ErrorCode.INVALID_INPUT,"Missing value occurrence")
                ensure(v.getValue("exactOriginalValue") == e.getValue("exactOriginalValue"),"Original value changed")
                val sourceField = axesByKey.getValue(key).obj("sourceRecord")[e.text("sourceField")]
                if(sourceField is JsonArray) ensure(sourceField.getOrNull(e.getValue("indexZeroBased").jsonPrimitive.int) == e.getValue("exactOriginalValue"),"Value differs from original source array")
            }
            keep("/axisValueMapping/coverageLedger/$index","value-coverage",key.toString(),"All occurrences reconciled without deduplication by label")
        }
        ensure(seen == valueMap.keys,"Uncovered value occurrences")
        values.forEachIndexed { i,v ->
            ensure(axisKey(v) in axesByKey && files[v.text("sourceFile")] == v.text("sourceFileSha256"),"Unknown value axis/source digest")
            keep("/axisValueMapping/valueRecords/$i","axis-value",occurrence(v).joinToString("#"),
                "${v.text("mappingStructure")}; preserve exact occurrence and optional/example status, do not flatten components or invent global value IDs")
        }
        val concepts = valueLayer.array("canonicalConceptProposals").map { it.jsonObject }
        unique(concepts.map { it.text("conceptId") },"concept proposal")
        val conceptIds = concepts.map { it.text("conceptId") }.toSet()
        values.forEach { v -> ensure(v.array("targetConceptProposals").all { it.jsonPrimitive.content in conceptIds },"Unknown target concept") }
        concepts.forEachIndexed { i,c -> keep("/axisValueMapping/canonicalConceptProposals/$i","concept",c.text("conceptId"),"Research concept proposal, not an automatic global axis") }
        root.obj("quantitiesReview").array("rows").forEachIndexed { i,e ->
            val m = e.jsonObject
            ensure(m.text("sourceInventory") in files && m.obj("sourceRecord").text("id") == m.text("sourceMeasureId"),"Invalid source metric")
            val duration = m.array("proposedConcepts").map { it.jsonPrimitive.content } == listOf("quantity.elapsed_duration")
            keep("/quantitiesReview/rows/$i","metric","${m.text("sourceInventory")}#${m.text("sourceMeasureId")}",
                if(duration) "Existing core segment durations only; no new manual metric or extra time" else
                    "${m.text("disposition")}; required object/event/role context is not represented by the current scalar measurement contract",
                if(duration) ResearchDisposition.CORE_DURATION else ResearchDisposition.PRESERVED_ONLY)
        }
        val household = root.obj("householdQuantities")
        val measures = household.array("candidateMeasures").map { it.jsonObject }
        unique(measures.map { it.text("id") },"household quantity")
        val lifeFile = root.obj("lifeSourceKindReview").text("sourceFile")
        measures.forEachIndexed { i,m ->
            ensure(m.array("appliesToActivityIds").all { SourceKey(lifeFile,it.jsonPrimitive.content) in sources },"Unknown household quantity action")
            keep("/householdQuantities/candidateMeasures/$i","household-quantity",m.text("id"),
                "${m.text("measureKind")}; event identity, context, visibility and aggregation need explicit schema decisions")
        }
        household.array("activityCoverage").forEachIndexed { i,e ->
            val r = e.jsonObject
            ensure(SourceKey(lifeFile,r.text("activityId")) in sources,"Unknown household coverage action")
            ensure(r.array("candidateMeasureIds").all { id -> measures.any { it.text("id") == id.jsonPrimitive.content } },"Unknown household candidate reference")
            keep("/householdQuantities/activityCoverage/$i","quantity-coverage",r.text("activityId"),"Applicability evidence retained; not an automatic measure/default")
        }
        root.obj("lifeSourceKindReview").array("items").forEachIndexed { i,e ->
            keep("/lifeSourceKindReview/items/$i","source-kind",e.jsonObject.text("sourceId"),e.jsonObject.text("decision"))
        }
        root.obj("supplementalResearch").forEach { (name,e) ->
            val r = e.jsonObject; ensure(files[name] == r.text("sha256") && name == r.text("file"),"Supplemental digest mismatch")
            keep("/supplementalResearch/$name","supplemental",name,"Only descriptor/digest embedded; document content unavailable",ResearchDisposition.EXTERNAL_DOCUMENT)
        }
        val byUnqualifiedId = sources.values.groupBy { it.key.id }
        fun reference(source: ResearchSource,pointer: String,id: String,file: String? = null) {
            val exact = sources[SourceKey(file ?: source.key.file,id)]
            val matches = if(exact != null) listOf(exact) else if(file == null) byUnqualifiedId[id].orEmpty() else emptyList()
            val targets = matches.map { it.workingId }.distinct()
            keep(pointer,"research-reference","${source.key.file}#$id",when(targets.size) {
                0 -> "UNRESOLVED: referenced source is not embedded; no runtime edge inferred"
                1 -> "RESOLVED to ${targets.single()}; research relationship remains non-additive, not a new parent"
                else -> "AMBIGUOUS across sources: ${targets.sorted().joinToString()}; source qualifier required"
            })
        }
        sources.values.forEach { source ->
            val raw = source.original
            raw.optionalText("parentId")?.let { reference(source,"${source.pointer}/original/parentId",it) }
            for(field in listOf("activityRelations","relatedConcepts")) (raw[field] as? JsonArray)?.forEachIndexed { i,e ->
                val obj = e.jsonObject; val id = obj.optionalText("targetId") ?: obj.text("id")
                reference(source,"${source.pointer}/original/$field/$i",id)
            }
            (raw["relationships"] as? JsonObject)?.let { relations ->
                relations["seedRelationships"]?.jsonArray?.forEachIndexed { i,e ->
                    reference(source,"${source.pointer}/original/relationships/seedRelationships/$i",e.jsonObject.text("seedId"),"tracker_catalog_proposal.json")
                }
                for(field in listOf("relatedCandidateIds","mayOverlapSemanticallyWith")) (relations[field] as? JsonArray)?.forEachIndexed { i,e ->
                    reference(source,"${source.pointer}/original/relationships/$field/$i",e.jsonPrimitive.content)
                }
            }
            for(field in listOf("facets","independentAxesSuggested")) (raw[field] as? JsonArray)?.forEachIndexed { i,e ->
                val id = if(e is JsonObject) e.text("axisId") else e.jsonPrimitive.content
                val key = SourceKey(source.key.file,id)
                keep("${source.pointer}/original/$field/$i","axis-reference",key.toString(),
                    if(key in axesByKey) "RESOLVED source-local axis; not an installed/default field" else "UNRESOLVED source-local axis; preserved without guessing")
            }
        }
        sources.values.filter { "seedDefinition" in it.original }.forEach { s ->
            keep("${s.pointer}/original/seedDefinition","identity-conflict","${s.key.file}#${s.key.id}",
                if(s.key.id == s.workingId) "Package explicitly reuses the canonical ID; original refinement remains separate evidence" else
                    "Package explicitly assigns new ID ${s.workingId}; original ID and meaning remain intact")
        }
        return candidateAxes
    }
    private fun <T> unique(keys: List<T>,label: String) = ensure(keys.toSet().size == keys.size,"Duplicate $label")
    private fun JsonObject.text(key: String): String {
        val value = getValue(key).jsonPrimitive
        ensure(value.isString && value.content.isNotBlank(),"Expected nonempty string: $key")
        return value.content
    }
    private fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
}
