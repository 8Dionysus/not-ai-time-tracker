package dev.notai.tracker

import kotlinx.serialization.json.*

/** Optional full originals, verified against the package manifest before they are used.
 * Relations remain evidence: neither tree edges nor an extra stream of elapsed time.
 */
internal fun validateSupportingResearch(root: JsonObject,documents: Map<String,ByteArray>,sources: List<ResearchSource>,
    nodes: List<ResearchNode>,outcomes: MutableList<ResearchOutcome>) {
    fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    fun JsonObject.rows(k: String) = getValue(k).jsonArray.map { it.jsonObject }
    val manifest = root.getValue("manifest").jsonObject.rows("sourceManifest").associateBy { it.str("file") }
    val sourceByKey = sources.associateBy { it.key }
    fun source(file: String,id: String) = sourceByKey[SourceKey(file,id)] ?: fail(ErrorCode.INVALID_INPUT,"Unknown supporting source: $file#$id")
    fun checked(file: String,pointer: String,key: String,reason: String) {
        outcomes += ResearchOutcome("supporting/$file$pointer","supporting-evidence",key,ResearchDisposition.PRESERVED_ONLY,reason)
    }
    fun count(name: String,actual: Int) {
        ensure(root.getValue("manifest").jsonObject.getValue("counts").jsonObject.getValue(name).jsonPrimitive.int == actual,"Supporting manifest count mismatch: $name")
        outcomes.replaceAll { if(it.kind == "declared-count" && it.key == name) it.copy(reason = "Verified against full supporting document records") else it }
    }
    for((file,bytes) in documents) {
        ensure(manifest[file]?.str("sha256") == researchHash(bytes),"Supporting source hash mismatch: $file",ErrorCode.UNSUPPORTED_IMPORT)
        outcomes.replaceAll { if(it.key == file && it.kind in setOf("source-file","supplemental"))
            it.copy(disposition = ResearchDisposition.PRESERVED_ONLY,reason = "Full original bytes independently verified against manifest SHA-256 and retained in evidence archive") else it }
        if(!file.endsWith(".json")) continue
        val doc = codec.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        when(file) {
            "tracker_catalog_identity_resolution.json" -> {
                ensure(doc.str("schemaVersion") == "tracker-catalog-identity-resolution-1","Unsupported identity evidence")
                val decisions = doc.rows("actionResolutions")
                ensure(decisions.size == 6 && decisions.map { it.str("sourceId") }.toSet().size == 6,"Incomplete identity decisions")
                decisions.forEachIndexed { i,r ->
                    val id = r.str("sourceId")
                    val resolved = (r["researchCanonicalId"] ?: r.getValue("canonicalId")).jsonPrimitive.content
                    ensure(source("tracker_work_learning_inventory.json",id).workingId == resolved,"Identity resolution mismatch")
                    ensure(source("tracker_catalog_proposal.json",id).workingId == id,"Broad seed identity changed")
                    r["researchDefinitionRu"]?.let { definition -> ensure(nodes.single { it.id == resolved }.definition == definition.jsonPrimitive.content,"Resolved definition mismatch") }
                    checked(file,"/actionResolutions/$i",id,"Qualified seed/work IDs and refined definition verified; no history rewritten")
                }
            }
            "tracker_cross_domain_relations_v2_1.json" -> {
                ensure(doc.str("schemaVersion") == "tracker-cross-domain-relations-v2.1","Unsupported relation evidence")
                val relations = doc.rows("relations")
                count("lifeCrossDomainRelations",relations.size)
                ensure(relations.size == 357 && relations.map { it.str("id") }.toSet().size == relations.size,"Relation count/ID mismatch")
                ensure(relations.map { listOf(it.str("lifeCareRecordId"),it.str("otherDomain"),it.str("otherRecordId")) }.toSet().size == relations.size,"Duplicate relation pair")
                val domainFiles = mapOf("work_learning" to "tracker_work_learning_inventory.json","leisure_sport" to "tracker_leisure_sport_inventory.json")
                val kinds = doc.getValue("relationTypeDefinitionsRu").jsonObject.keys
                val normalizedKinds = doc.getValue("normalizedActionRelationTypeDefinitionsRu").jsonObject.keys - "normalizationNoteRu"
                val directions = doc.getValue("directionDefinitionsRu").jsonObject.keys
                fun checkDirection(o: JsonObject) { o["direction"]?.takeUnless { it is JsonNull }?.let { ensure(it.jsonPrimitive.content in directions,"Unknown relation direction") } }
                relations.forEachIndexed { i,r ->
                    val life = source("tracker_life_care_inventory.json",r.str("lifeCareRecordId"))
                    val other = source(domainFiles[r.str("otherDomain")] ?: fail(ErrorCode.INVALID_INPUT,"Unknown relation domain"),r.str("otherRecordId"))
                    ensure(r.str("relationType") in kinds,"Unknown record relation type"); checkDirection(r)
                    val normalized = r.getValue("normalizedActionRelation").jsonObject
                    ensure(normalized.str("type") in normalizedKinds,"Unknown normalized relation type"); checkDirection(normalized)
                    for(k in listOf("sourceRecordRelation","recordScopeRelation")) {
                        val scope = r.getValue(k).jsonObject
                        ensure(scope.str("type") == r.str("relationType") && scope["direction"] == r["direction"],"Relation scope/type mismatch")
                    }
                    ensure(r.str("lifeCareDefinitionRu").isNotBlank() && r.str("otherDefinitionRu").isNotBlank(),"Missing pair definitions")
                    ensure(r.getValue("sourceRecordIds").jsonArray.map { it.jsonPrimitive.content }.toSet() == setOf(life.key.id,other.key.id),"Pair source IDs disagree")
                    normalized["preservedEssentialRoles"]?.let { roles -> ensure(roles is JsonArray && roles.all { it is JsonPrimitive && it.isString },"Malformed essential roles") }
                    checked(file,"/relations/$i",r.str("id"),"Resolved ${life.key.file}#${life.key.id} -> ${life.workingId}; ${other.key.file}#${other.key.id} -> ${other.workingId}. Record ${r.str("relationType")}; normalized ${normalized.str("type")}. Both levels retained, no merge/parent/time inferred")
                }
                for((countKey,nested) in listOf("relationTypeCounts" to false,"normalizedActionRelationCounts" to true)) {
                    val actual = relations.groupingBy { if(nested) it.getValue("normalizedActionRelation").jsonObject.str("type") else it.str("relationType") }.eachCount()
                    ensure(actual == doc.getValue(countKey).jsonObject.mapValues { it.value.jsonPrimitive.int },"Relation totals mismatch")
                }
                val reviews = doc.rows("lifeCareNodeReview")
                val lifeIds = sources.filter { it.key.file == "tracker_life_care_inventory.json" }.map { it.key.id }.toSet()
                ensure(reviews.size == lifeIds.size && reviews.map { it.str("lifeCareRecordId") }.toSet() == lifeIds,"Incomplete life node review")
                reviews.forEach { r ->
                    val expected = relations.filter { it.str("lifeCareRecordId") == r.str("lifeCareRecordId") }.map { it.str("id") }.toSet()
                    val refs = r.getValue("crossDomainRelationIds").jsonArray.map { it.jsonPrimitive.content }
                    ensure(refs.size == refs.toSet().size && refs.toSet() == expected,"Node/relation backlinks mismatch")
                }
            }
            "tracker_work_learning_core_v4_compatibility.json" -> {
                ensure(doc.str("schemaVersion") == "core-v4-quantitative-compatibility-1","Unsupported quantity evidence")
                count("wordCountBases",doc.rows("quantities").sumOf { (it["wordCountVariants"] as? JsonArray)?.size ?: 0 })
                for((key,size) in listOf("quantities" to 16,"reusedExistingDefinitions" to 11)) {
                    val rows = doc.rows(key)
                    count(if(key == "quantities") "workLearningQuantityDefinitions" else "workLearningReuseDefinitions",rows.size)
                    ensure(rows.size == size && rows.map { it.str("id") }.toSet().size == size,"Quantity evidence coverage mismatch")
                    rows.forEachIndexed { i,r -> checked(file,"/$key/$i",r.str("id"),r.str("coreMapping")) }
                }
            }
            "tracker_mobile_catalog_navigation_research.json" -> {
                count("mobileSources",doc.rows("sources").size)
                count("mobileAcceptanceScenarios",doc.rows("acceptance_scenarios").size)
                ensure(doc.rows("sources").size == 39 && doc.rows("acceptance_scenarios").size == 37,"Mobile evidence coverage mismatch")
                checked(file,"/root_contract_review","mobile-research","Research only: no UI, benchmark or device acceptance inferred")
            }
        }
    }
}
