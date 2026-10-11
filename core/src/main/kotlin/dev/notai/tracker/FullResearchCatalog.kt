package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ProjectionOutcome(val file: String,val pointer: String,val key: String,
    val kind: String,val runtimeIds: List<String>,val boundary: String)

/** Exact research stays alongside the compact runtime vocabulary. No relations create time rows. */
class FullResearchPlan internal constructor(val vocabulary: VocabularyData,val outcomes: List<ProjectionOutcome>,
    private val base: ResearchPlan,private val originals: Map<String,ByteArray>) {
    val sourceHashes get() = originals.mapValues { researchHash(it.value) }
    fun sourceBytes(file: String) = originals.getValue(file).copyOf()
    fun apply(tracker: Tracker,expected: Token,archive: ResearchArchive): Tracker.Change = storage {
        base.preserveEvidence(archive)
        for(bytes in originals.values) {
            val hash = researchHash(bytes); archive.put(hash,bytes.copyOf())
            ensure(researchHash(archive.read(hash)) == hash,"Projection evidence readback failed",ErrorCode.STORAGE)
        }
        tracker.addCatalog(expected,vocabulary)
    }
}

/** Explicit v2 ISCO and corrected sports projection; the frozen baseline is never overwritten. */
object FullResearchCatalog {
    const val ISCO = "isco_full_projection_v2.json"
    const val SPORTS = "sports_additive_catalogue_projection.json"
    fun read(base: ResearchPlan,iscoBytes: ByteArray,iscoHash: String,sportsBytes: ByteArray,sportsHash: String): FullResearchPlan {
        ensure(researchHash(iscoBytes) == iscoHash && researchHash(sportsBytes) == sportsHash,"Projection hash mismatch",ErrorCode.UNSUPPORTED_IMPORT)
        return try { prepare(base,mapOf(ISCO to iscoBytes.copyOf(),SPORTS to sportsBytes.copyOf())) }
        catch(e: CoreError) { throw e }
        catch(e: Exception) { throw CoreError(ErrorCode.UNSUPPORTED_IMPORT,"Malformed full projection").also { it.initCause(e) } }
    }
    private fun prepare(base: ResearchPlan,originals: Map<String,ByteArray>): FullResearchPlan {
        val isco = codec.parseToJsonElement(originals.getValue(ISCO).toString(Charsets.UTF_8)).jsonObject
        val sport = codec.parseToJsonElement(originals.getValue(SPORTS).toString(Charsets.UTF_8)).jsonObject
        ensure(isco.obj("revision").s("version") == "2.0.0","Only corrected ISCO v2 is supported",ErrorCode.UNSUPPORTED_IMPORT)
        val baseline = base.vocabulary(base.candidateIds,base.axisCandidateIds)
        val types = baseline.types.associateBy { it.id }.toMutableMap()
        val axes = baseline.axes.associateBy { it.id }.toMutableMap()
        val outcomes = mutableListOf<ProjectionOutcome>()
        val hashes = originals.mapValues { researchHash(it.value) }
        fun emit(file: String,pointer: String,key: String,kind: String,ids: Collection<String> = emptyList(),boundary: String) {
            outcomes += ProjectionOutcome(file,pointer,key,kind,ids.distinct().sorted(),boundary)
        }
        fun origin(file: String,pointer: String,id: String): ResearchOrigin {
            val hash = hashes.getValue(file)
            return ResearchOrigin(hash,file,hash,id,pointer)
        }
        fun add(type: ActivityType) {
            val old = types[type.id]
            ensure(old == null || (old.name == type.name && old.definition == type.definition && old.parentId == type.parentId && old.selectable == type.selectable),"Conflicting projection definitions: ${type.id}")
            if(old == null) types[type.id] = type // already accepted baseline keeps its exact definition/provenance
        }
        fun localAxis(file: String,pointer: String,id: String,name: String,originId: String,values: List<AxisValue> = emptyList()): Axis {
            val axis = Axis(id,name,valueKind = if(values.isEmpty()) ValueKind.FREE_LABEL else ValueKind.LOCAL_CHOICE,
                cardinality = Cardinality.MULTIPLE,values = values,researchOrigins = listOf(origin(file,pointer,originId)))
            ensure(id !in axes,"Duplicate projection axis: $id"); axes[id] = axis
            return axis
        }
        val catalogFiles = mapOf("catalog.work_learning" to "tracker_work_learning_inventory.json",
            "catalog.life_care" to "tracker_life_care_inventory.json","catalog.leisure_sport" to "tracker_leisure_sport_inventory.json",
            "catalog.proposal" to "tracker_catalog_proposal.json")
        val sourceMap = base.sources.associate { it.key to it.workingId }
        fun resolve(ref: JsonObject): String {
            val id = ref.text("actionId")
            val file = catalogFiles[ref.text("catalogId")] ?: fail(ErrorCode.INVALID_INPUT,"Unknown source catalog")
            val resolved = sourceMap[SourceKey(file,id)] ?: id
            ensure(resolved in types,"Unresolved current action reference: $file#$id")
            return resolved
        }
        val rows = isco.rows("candidateDispositions")
        ensure(rows.size == 353 && rows.map { it.text("recordKey") }.toSet().size == 353,"ISCO source coverage mismatch")
        val rowByKey = rows.associateBy { it.text("recordKey") }
        val proposalRows = isco.rows("proposedActions")
        ensure(proposalRows.size == 137,"ISCO action proposal count mismatch")
        val proposals = proposalRows.associateBy { it.text("recordKey") }
        ensure(proposals.size == 137 && proposals.keys.all { it in rowByKey },"Invalid ISCO proposal source")
        val components = isco.rows("componentActionProposals")
        ensure(components.size == 18,"ISCO component coverage mismatch")
        val families = isco.rows("familyAndWorkflowRecords")
        ensure(families.size == 12,"ISCO family coverage mismatch")
        val relations = isco.rows("typedSourceRelations")
        val relationsById = relations.associateBy { it.text("relationId") }
        ensure(relations.size == 159 && relationsById.size == 159,"ISCO relation coverage mismatch")
        relations.forEachIndexed { i,r ->
            ensure(r.text("leftRecordKey") in rowByKey && r.text("rightRecordKey") in rowByKey,"Missing pairwise source")
            emit(ISCO,"/typedSourceRelations/$i",r.text("relationId"),"typed-relation",boundary = "${r.text("relationType")}; complete relation retained, not a parent or elapsed interval")
        }
        val sourceTargets = mutableMapOf<String,List<String>>()
        val runtimeAliases = mutableMapOf<String,String>()
        val relationAliases = isco.rows("relationAliases").associate { it.text("retiredRelationId") to it.text("canonicalRelationId") }
        ensure(relationAliases.size == 2 && relationAliases.values.all { it in relationsById },"Invalid retired relation aliases")
        val proposalIds = proposalRows.map { it.text("researchProposalRef") }.toSet()
        ensure(proposalIds.size == proposalRows.size,"Duplicate action proposal ID")
        val familyIds = families.map { it.text("familyWorkflowId") }.toSet()
        ensure(familyIds.size == families.size,"Duplicate family ID")
        fun inactive(r: JsonObject) = r.text("sourceKind") == "retired_action_candidate" ||
            r.obj("sourceCandidateSnapshot").s("sourceStatus") == "historical_prior_candidate" ||
            r.text("sourceDisposition").startsWith("not_recommended")
        rows.forEachIndexed { i,r ->
            val key = r.text("recordKey"); val p = r.obj("projection")
            ensure(r.strings("relationIds").all { it in relationsById || it in relationAliases },"Missing row relation")
            ensure(r.strings("resolvedProposalRefs").all { it in proposalIds },"Missing resolved proposal")
            ensure(p.strings("familyWorkflowRefs").all { it in familyIds },"Missing workflow reference")
            ensure(r.obj("sourceCandidateSnapshot").text("recordKey") == key && r.obj("sourceCandidateSnapshot").text("sourceId") == r.text("sourceId"),"ISCO snapshot identity differs")
            // Validate scoped live references independently of whether they provide full or partial coverage.
            for(field in listOf("reuseActionRefs","componentReuseActionRefs","partialOverlapActionRefs","adjacentButNotEquivalentActionRefs")) {
                p.rows(field).forEach { ref ->
                    val id = resolve(ref)
                    emit(ISCO,"/candidateDispositions/$i/projection/$field",key,"action-reference",listOf(id),"${ref.s("scope")}: ${ref.s("relation")}; never infer full coverage from a component/overlap")
                }
            }
            val reuse = p.rows("reuseActionRefs").map(::resolve).distinct()
            val wrapper = p.text("kind") in setOf("role_context_workflow_no_standalone_activity","nonselectable_source_compound_wrapper","split_source_compound_reuse_named_components_only")
            val proposal = proposals[key]
            if(reuse.isNotEmpty() && proposal == null) sourceTargets[key] = reuse
            else if(!inactive(r) && !wrapper) {
                val definition = proposal?.s("definition") ?: p.s("boundedDefinition")
                ensure(!definition.isNullOrBlank(),"Active action has no bounded definition: $key")
                val id = proposal?.text("researchProposalRef") ?: "isco.research.$key"
                val parent = proposal?.s("parentId") ?: p.s("parentRecommendation")
                ensure(parent == null || parent in types,"Missing corrected ISCO parent: $parent")
                add(ActivityType(id,proposal?.text("label") ?: p.text("actionLabel"),definition = definition!!,parentId = parent,
                    researchOrigins = listOf(origin(ISCO,"/candidateDispositions/$i",r.text("sourceId")))))
                sourceTargets[key] = listOf(id)
                if(parent == null) emit(ISCO,"/candidateDispositions/$i",key,"root-placement",listOf(id),"No semantic parent explicitly specified in corrected source: kept as a root action, not guessed from nearby actions")
            } else sourceTargets[key] = reuse
        }
        // Only explicitly merge-eligible pair memberships may select a shared representative.
        // Prefer a live, most-connected source. Each absorbed member MUST have a direct eligible edge
        // to that representative; there is no transitive cluster-wide merge.
        isco.rows("canonicalConceptRecommendations").forEachIndexed { ci,c ->
            val members = c.rows("recordMemberships")
            val edges = mutableSetOf<Pair<String,String>>()
            members.forEach { m -> m.rows("pairwiseRelations").filter { it.bool("mergeEligible") }.forEach { e ->
                val left = m.text("recordKey"); val right = e.text("otherRecordKey")
                val relation = relationsById[e.text("relationId")] ?: fail(ErrorCode.INVALID_INPUT,"Missing eligible relation")
                ensure(setOf(left,right) == setOf(relation.text("leftRecordKey"),relation.text("rightRecordKey")) && e.text("relationType") == relation.text("relationType"),"Eligibility disagrees with pair")
                ensure(e.text("relationType") in setOf("exact_duplicate","same_core_action_context_variant","same_action_context_variant"),"Non-equivalent relation cannot merge")
                edges += left to right
            } }
            val eligible = edges.flatMap { listOf(it.first,it.second) }.distinct()
            val representative = eligible.filter { !inactive(rowByKey.getValue(it)) && sourceTargets[it]?.size == 1 }
                .sortedWith(compareByDescending<String> { key -> edges.count { it.first == key } }.thenBy { it }).firstOrNull()
            if(representative != null) {
                val target = sourceTargets.getValue(representative).single()
                for(key in eligible.filter { it != representative && ((it to representative) in edges || (representative to it) in edges) }) {
                    val old = sourceTargets[key].orEmpty()
                    for(id in old) if(id.startsWith("isco.research.") && id != target) {
                        val removed = types.remove(id)
                        runtimeAliases[id] = target
                        if(removed != null) types[target] = types.getValue(target).copy(researchOrigins = (types.getValue(target).researchOrigins+removed.researchOrigins).distinct())
                    }
                    sourceTargets[key] = listOf(target)
                    emit(ISCO,"/canonicalConceptRecommendations/$ci",key,"explicit-pair-reuse",listOf(target),"${c.text("id")}; direct eligible source pair only; original definitions/context remain in source, no name-based or broad/specific merge")
                }
            }
        }
        components.forEachIndexed { i,r ->
            ensure(r.text("recordKey") in rowByKey,"Component has no source row")
            val id = r.text("componentProposalRef")
            add(ActivityType(id,r.text("label"),definition = r.text("definition"),parentId = r.text("parentId"),
                researchOrigins = listOf(origin(ISCO,"/componentActionProposals/$i",r.text("sourceId")))))
            emit(ISCO,"/componentActionProposals/$i",id,"optional-component",listOf(id),"Declared semantic parent retained; family/component relation is companion evidence, not additive time")
        }
        val componentIds = components.map { it.text("componentProposalRef") }.toSet()
        ensure(componentIds.size == components.size,"Duplicate component ID")
        families.forEachIndexed { i,r ->
            ensure(r.text("recordKey") in rowByKey,"Family has no source row")
            r.rows("components").forEach { v -> v.s("componentProposalRef")?.let { ensure(it in componentIds,"Missing family component") } }
            val targets = sourceTargets.getValue(r.text("recordKey"))
            if(r.bool("isSelectableAsTimerEntry")) ensure(targets.size == 1 && types.getValue(targets.single()).selectable,"Selectable family lost")
            emit(ISCO,"/familyAndWorkflowRecords/$i",r.text("familyWorkflowId"),"family-or-wrapper",targets,
                if(r.bool("isSelectableAsTimerEntry")) "Whole family/action is directly selectable; detail optional; no duplicate family node" else "Source wrapper retained as composition evidence, not a second timer action; named components remain available")
        }
        isco.rows("residualResolutions").forEachIndexed { i,r ->
            val active = r.s("activeSourceRecordKey")
            val target = if(active != null) sourceTargets[active].orEmpty() else if(r.bool("isNewActivityProposal")) {
                val id = r.text("residualId")
                add(ActivityType(id,r.text("residualAction"),definition = r.text("proposedOrReusedDefinition"),parentId = r.text("recommendedParentId"),
                    researchOrigins = listOf(origin(ISCO,"/residualResolutions/$i",id))))
                listOf(id)
            } else emptyList()
            emit(ISCO,"/residualResolutions/$i",r.text("residualId"),"residual-resolution",target,"${r.text("recommendedNextDisposition")}; retired compound not revived")
        }
        rows.forEachIndexed { i,r ->
            val targets = sourceTargets.getValue(r.text("recordKey"))
            val parts = if(targets.isEmpty()) r.obj("projection").rows("componentReuseActionRefs").map(::resolve) +
                components.filter { it.text("recordKey") == r.text("recordKey") }.map { it.text("componentProposalRef") } else emptyList()
            emit(ISCO,"/candidateDispositions/$i",r.text("recordKey"),"source-disposition",targets+parts,
                "${r.obj("projection").text("kind")}; ${if(targets.isEmpty()) "No whole-action mapping; any targets are named components only. " else ""}${r.text("recommendedHandling")}")
        }
        // Role/context descriptions are optional source-local text, never required or inferred.
        (proposalRows.map { it to "/proposedActions/${proposalRows.indexOf(it)}" } +
            components.map { it to "/componentActionProposals/${components.indexOf(it)}" }).forEach { (r,pointer) ->
            val target = r.s("componentProposalRef") ?: sourceTargets[r.text("recordKey")]?.singleOrNull()
            if(target != null && target in types) r.rows("roleAndContextFacets").forEachIndexed { i,f ->
                val key = f.text("axis"); val id = "research:isco:$target:$key"
                if(id !in axes) localAxis(ISCO,"$pointer/roleAndContextFacets/$i",id,key,r.text("sourceId"))
                types[target] = types.getValue(target).copy(suggestedAxisIds = types.getValue(target).suggestedAxisIds+id)
            }
        }
        families.forEachIndexed { i,r ->
            val target = sourceTargets[r.text("recordKey")]?.singleOrNull()
            val values = r.rows("components").filter { it.s("componentType") in setOf("source_supported_process_stage","object_domain_facet") }
                .map { AxisValue(it.text("componentId"),it.s("stage") ?: it.text("componentId")) }
            if(target != null && target in types && values.isNotEmpty()) {
                val id = "research:isco:${r.text("familyWorkflowId")}:detail"
                localAxis(ISCO,"/familyAndWorkflowRecords/$i",id,r.text("label"),r.text("sourceId"),values)
                types[target] = types.getValue(target).copy(suggestedAxisIds = types.getValue(target).suggestedAxisIds+id)
            }
        }
        val sportRows = sport.rows("rowDispositions")
        ensure(sportRows.size == 223 && sportRows.map { it.text("rowKey") }.toSet().size == 223,"Sports coverage mismatch")
        val records = sport.obj("proposedRecords")
        val sportActions = records.rows("activityCandidates")
        ensure(sportActions.size == 168 && sportActions.map { it.text("id") }.toSet().size == 168,"Sport action coverage mismatch")
        sportActions.forEachIndexed { i,r ->
            add(ActivityType(r.text("id"),r.text("name"),definition = r.text("definition"),parentId = r.text("parentId"),selectable = r.bool("selectable"),
                researchOrigins = listOf(origin(SPORTS,"/proposedRecords/activityCandidates/$i",r.text("id")))))
            emit(SPORTS,"/proposedRecords/activityCandidates/$i",r.text("id"),"activity",listOf(r.text("id")),"${r.text("proposalClass")}; exact source definition/parent; existing baseline meaning not overwritten")
        }
        val wholeEvents = records.rows("compoundEventOrPresetProposals")
        ensure(wholeEvents.size == 28,"Sport whole-event coverage mismatch")
        wholeEvents.forEachIndexed { i,r ->
            ensure(r.text("semanticRecordClass") == "whole_event_or_family_preset","Unknown compound semantics")
            // Corrected whole-event selection rule and user's explicit contract take priority over
            // the historical atomic-type flag, which remains unchanged in the archived source.
            add(ActivityType(r.text("id"),r.text("name"),definition = r.text("definition"),
                researchOrigins = listOf(origin(SPORTS,"/proposedRecords/compoundEventOrPresetProposals/$i",r.text("id")))))
            emit(SPORTS,"/proposedRecords/compoundEventOrPresetProposals/$i",r.text("id"),"whole-event",listOf(r.text("id")),
                "One directly selectable whole span; components optional. No parent is declared: root item. Historical selectableActivityType=${r.bool("selectableActivityType")} does not force atomic detail")
        }
        wholeEvents.forEachIndexed { i,r ->
            for(id in r.strings("componentActivityIds")) ensure(id in types,"Missing whole-event component: $id")
            emit(SPORTS,"/proposedRecords/compoundEventOrPresetProposals/$i/componentActivityIds",r.text("id"),"components",r.strings("componentActivityIds"),
                "Complete links retained as non-additive views; no extra segments or inferred order/durations")
        }
        val sportAliases = records.rows("reuseAndAliasRelations")
        ensure(sportAliases.size == 12,"Sports reuse coverage mismatch")
        val aliasMap = sportAliases.associate { it.text("id") to it.text("targetId") }
        fun sportTarget(id: String): String {
            var target = id; val seen = mutableSetOf<String>()
            while(target in aliasMap) { ensure(seen.add(target),"Sports alias cycle"); target = aliasMap.getValue(target) }
            ensure(target in types || target in axes,"Missing sports target: $target")
            return target
        }
        val sportFacets = records.rows("facetAndSelectorProposals")
        ensure(sportFacets.size == 37,"Sports facet coverage mismatch")
        sportFacets.forEachIndexed { i,r ->
            val values = r.rows("values").filter { it.s("value") != null }.map { AxisValue(it.text("value"),it.text("sourceLabel")) }
            val axis = localAxis(SPORTS,"/proposedRecords/facetAndSelectorProposals/$i",r.text("id"),r.text("name"),r.text("id"),values)
            for(target in r.strings("subjectActivityIds")) {
                ensure(target in types,"Missing facet host: $target")
                // Baseline hosts retain their dictionaries byte-for-byte; attachment is in the
                // companion outcome. New candidates may advertise optional fields directly.
                if(target !in base.candidateIds) types[target] = types.getValue(target).copy(suggestedAxisIds = types.getValue(target).suggestedAxisIds+axis.id)
            }
            emit(SPORTS,"/proposedRecords/facetAndSelectorProposals/$i",axis.id,"optional-facet",r.strings("subjectActivityIds"),
                "${r.text("definitionOrMeaning")}; ${axis.valueKind}, MULTIPLE; source labels without IDs stay open text, no required field")
        }
        sportAliases.forEachIndexed { i,r -> emit(SPORTS,"/proposedRecords/reuseAndAliasRelations/$i",r.text("id"),"explicit-reuse",listOf(sportTarget(r.text("targetId"))),r.s("reason") ?: "Explicit source relation; no duplicate timer type") }
        val holds = records.rows("contextOnlyRows")
        ensure(holds.size == 1 && holds.single().text("id") == "proposed.context.wheelchair_american_football","Unexpected sports holds")
        val heldIds = holds.map { it.text("id") }.toSet()
        sportRows.forEachIndexed { i,r ->
            val id = r.text("targetId")
            val targets = if(id in heldIds) emptyList() else listOf(sportTarget(id))
            emit(SPORTS,"/rowDispositions/$i",r.text("rowKey"),"source-disposition",targets,
                "${r.text("proposalDisposition")}; ${r.text("reason")}")
        }
        // Normalize intermediate action references after explicit pair reuse, preserving source keys.
        outcomes.replaceAll { outcome -> outcome.copy(runtimeIds = outcome.runtimeIds.map { initial ->
            var id = initial; val seen = mutableSetOf<String>()
            while(id in runtimeAliases) { ensure(seen.add(id),"Runtime alias cycle"); id = runtimeAliases.getValue(id) }
            id
        }.distinct().sorted()) }
        ensure(outcomes.flatMap { it.runtimeIds }.all { it in types || it in axes },"Dangling runtime projection reference")
        // Validate the entire dictionary, including every parent and optional-field reference.
        val vocabulary = VocabularyData(types.values.sortedBy { it.id },axes.values.sortedBy { it.id },baseline.metrics,baseline.units)
        validate(Dataset(types = vocabulary.types,axes = vocabulary.axes,metrics = vocabulary.metrics,units = vocabulary.units))
        ensure(base.candidateIds.all { id -> vocabulary.types.single { it.id == id } == baseline.types.single { it.id == id } },"Frozen base changed")
        for((file,doc) in listOf(ISCO to isco,SPORTS to sport)) doc.keys.sorted().forEach { section ->
            emit(file,"/$section",section,"complete-source-section",boundary = "Exact full section archived with verified digest; historical references, typed relations, source boundaries and definitions remain available without being reinterpreted as runtime rules")
        }
        return FullResearchPlan(vocabulary,outcomes,base,originals)
    }
    private fun JsonObject.s(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.text(key: String): String = s(key)?.takeIf { it.isNotBlank() } ?: fail(ErrorCode.INVALID_INPUT,"Missing projection field: $key")
    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.rows(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().map { it.jsonObject }
    private fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    private fun JsonObject.bool(key: String) = get(key)?.jsonPrimitive?.booleanOrNull == true
}
