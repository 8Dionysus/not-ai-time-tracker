package dev.notai.tracker

@kotlinx.serialization.Serializable enum class SubjectRecordKind(val manifestKind: String) {
    CARD("subjectCard"), EVENT("subjectEvent"), LINK("subjectLink"), OBSERVATION("subjectObservation"), CHANGE("subjectChange"),
    SET("exerciseSet"), QUANTITY("eventQuantity"), DISTANCE_TIME("distanceTime")
}
@kotlinx.serialization.Serializable data class SubjectRecordKey(val kind: SubjectRecordKind,val id: String)
fun SubjectData.recordKeys(): Set<SubjectRecordKey> = frozenSet(subjectIdentityIds(this).flatMap { (kind,ids) -> ids.map { SubjectRecordKey(SubjectRecordKind.entries.single { it.manifestKind == kind },it) } })

/** Fine-grained manifests make replay, COPY, retention and Undo account for every new record. */
internal fun subjectIdentityIds(s: SubjectData): Map<String,List<String>> = linkedMapOf(
    "subjectCard" to s.cards.map { it.cardId },"subjectEvent" to s.events.map { it.id },"subjectLink" to s.links.map { it.id },
    "subjectObservation" to s.observations.map { it.id },"subjectChange" to s.changes.map { it.id },"exerciseSet" to s.sets.map { it.id },
    "eventQuantity" to s.quantities.map { it.id },"distanceTime" to s.distanceTimes.map { it.id })
internal fun subjectManifest(s: SubjectData,revision: Long,origin: String): List<TransferEntityOrigin> = buildList {
    fun <T> rows(kind: String,rows: List<T>,id: (T)->String,hash: (T)->String) { rows.forEach { add(TransferEntityOrigin(kind,id(it),revision,hash(it),"$origin:$kind:${id(it)}")) } }
    rows("subjectCard",s.cards,{it.cardId}) { payloadHash(it) };rows("subjectEvent",s.events,{it.id}) { payloadHash(it) }
    rows("subjectLink",s.links,{it.id}) { payloadHash(it) };rows("subjectObservation",s.observations,{it.id}) { payloadHash(it) }
    rows("subjectChange",s.changes,{it.id}) { payloadHash(it) };rows("exerciseSet",s.sets,{it.id}) { payloadHash(it) }
    rows("eventQuantity",s.quantities,{it.id}) { payloadHash(it) };rows("distanceTime",s.distanceTimes,{it.id}) { payloadHash(it) }
}
internal fun SubjectData.rewrite(maps: Map<String,Map<String,String>>): SubjectData {
    fun id(k: String,v: String) = maps[k]?.get(v) ?: v
    fun ref(r: SubjectRef) = r.copy(id = id(if(r.kind == SubjectKind.CARD) "card" else "subjectEvent",r.id))
    fun def(d: QuantityDefinition) = d.copy(metricId = id("metric",d.metricId),unitId = id("unit",d.unitId))
    return copy(cards = cards.map { it.copy(cardId = id("card",it.cardId)) },events = events.map { it.copy(id = id("subjectEvent",it.id),cardId = it.cardId?.let { c -> id("card",c) }) },
        links = links.map { it.copy(id = id("subjectLink",it.id),subject = ref(it.subject),sessionId = id("session",it.sessionId)) },
        observations = observations.map { it.copy(id = id("subjectObservation",it.id),subject = ref(it.subject),definition = def(it.definition),replacesId = it.replacesId?.let { v -> id("subjectObservation",v) }) },
        changes = changes.map { it.copy(id = id("subjectChange",it.id),fromObservationId = id("subjectObservation",it.fromObservationId),toObservationId = id("subjectObservation",it.toObservationId),replacesId = it.replacesId?.let { v -> id("subjectChange",v) }) },
        sets = sets.map { it.copy(id = id("exerciseSet",it.id),sessionId = id("session",it.sessionId),loadUnitId = id("unit",it.loadUnitId),replacesId = it.replacesId?.let { v -> id("exerciseSet",v) }) },
        quantities = quantities.map { it.copy(id = id("eventQuantity",it.id),subject = ref(it.subject),definition = def(it.definition),sessionId = it.sessionId?.let { v -> id("session",v) },replacesId = it.replacesId?.let { v -> id("eventQuantity",v) }) },
        distanceTimes = distanceTimes.map { it.copy(id = id("distanceTime",it.id),measurementId = id("measurement",it.measurementId),targetId = id(if(it.scope == Scope.SESSION) "session" else "segment",it.targetId)) })
}
/** Shared projection used by ordinary and compacted receipts, including local deletion detection. */
internal fun SubjectData.pick(ids: Map<String,Set<String>>): SubjectData = copy(
    cards = cards.filter { it.cardId in ids["subjectCard"].orEmpty() },events = events.filter { it.id in ids["subjectEvent"].orEmpty() },
    links = links.filter { it.id in ids["subjectLink"].orEmpty() },observations = observations.filter { it.id in ids["subjectObservation"].orEmpty() },
    changes = changes.filter { it.id in ids["subjectChange"].orEmpty() },sets = sets.filter { it.id in ids["exerciseSet"].orEmpty() },
    quantities = quantities.filter { it.id in ids["eventQuantity"].orEmpty() },distanceTimes = distanceTimes.filter { it.id in ids["distanceTime"].orEmpty() })
internal fun SubjectData.pick(wanted: SubjectData) = pick(subjectIdentityIds(wanted).mapValues { it.value.toSet() })
internal fun SubjectData.minus(old: SubjectData): SubjectData {
    val keys = subjectIdentityIds(old)
    return pick(subjectIdentityIds(this).mapValues { (k,v) -> (v-keys.getValue(k).toSet()).toSet() })
}
internal fun SubjectData.merge(other: SubjectData): SubjectData {
    fun <T> union(a: List<T>,b: List<T>,key: (T)->String): List<T> {
        val old = a.associateBy(key);ensure(b.all { old[key(it)] == null || old[key(it)] == it },"Subject identity conflict")
        return a+b.filter { key(it) !in old }
    }
    return SubjectData(union(cards,other.cards) {it.cardId},union(events,other.events) {it.id},union(links,other.links) {it.id},
        union(observations,other.observations) {it.id},union(changes,other.changes) {it.id},union(sets,other.sets) {it.id},
        union(quantities,other.quantities) {it.id},union(distanceTimes,other.distanceTimes) {it.id})
}
/** Selecting one stage includes the event identity and its selected links, never other sessions. */
internal fun Dataset.subjectDependencies(sessionIds: Set<String>,cardIds: Set<String>,roots: Set<SubjectRecordKey>): SubjectData {
    val s = subjects
    ensure(roots.all { it in s.recordKeys() },"Missing subject transfer root")
    fun roots(kind: SubjectRecordKind) = roots.filter { it.kind == kind }.map { it.id }.toSet()
    fun <T> lineage(rows: List<T>,wanted: Set<String>,id: (T)->String,previous: (T)->String?): List<T> {
        val included = wanted.toMutableSet();var change: Boolean
        do { change = false;rows.forEach { r -> if(id(r) in included || previous(r) in included) { if(included.add(id(r))) change = true;previous(r)?.let { if(included.add(it)) change = true } } } } while(change)
        return rows.filter { id(it) in included }
    }
    val changes = lineage(s.changes,roots(SubjectRecordKind.CHANGE),{it.id},{it.replacesId})
    var observations = lineage(s.observations,roots(SubjectRecordKind.OBSERVATION)+changes.flatMap { listOf(it.fromObservationId,it.toObservationId) },{it.id},{it.replacesId})
    // Equal-time alternatives are necessary evidence of a conflict, not optional extra history.
    do {
        val previousSize = observations.size
        val peers = s.observations.filter { candidate -> candidate.effectiveAt != null && observations.any { o ->
            o.subject == candidate.subject && o.definition == candidate.definition && o.effectiveAt == candidate.effectiveAt
        } }
        observations = lineage(s.observations,(observations+peers).map { it.id }.toSet(),{it.id},{it.replacesId})
    } while(observations.size != previousSize)
    val quantities = lineage(s.quantities,roots(SubjectRecordKind.QUANTITY)+s.quantities.filter { it.sessionId in sessionIds }.map { it.id },{it.id},{it.replacesId})
    val sets = lineage(s.sets,roots(SubjectRecordKind.SET)+s.sets.filter { it.sessionId in sessionIds }.map { it.id },{it.id},{it.replacesId})
    val rates = s.distanceTimes.filter { it.id in roots(SubjectRecordKind.DISTANCE_TIME) || (if(it.scope == Scope.SESSION) it.targetId in sessionIds else segments.any { seg -> seg.id == it.targetId && seg.sessionId in sessionIds }) }
    ensure(sets.all { it.sessionId in sessionIds } && quantities.all { it.sessionId == null || it.sessionId in sessionIds },"Subject quantity/set requires selected target session")
    ensure(rates.all { if(it.scope == Scope.SESSION) it.targetId in sessionIds else segments.any { seg -> seg.id == it.targetId && seg.sessionId in sessionIds } },"Rate requires selected target session")
    val selectedLinks = s.links.filter { it.id in roots(SubjectRecordKind.LINK) }
    ensure(selectedLinks.all { it.sessionId in sessionIds },"Subject link requires selected target session")
    val links = s.links.filter { it.sessionId in sessionIds }
    val refs = observations.map { it.subject }+quantities.map { it.subject }+links.map { it.subject }
    val events = s.events.filter { it.id in roots(SubjectRecordKind.EVENT) || refs.any { r -> r.kind == SubjectKind.EVENT && r.id == it.id } }
    val cardSubjects = s.cards.filter { it.cardId in cardIds || it.cardId in roots(SubjectRecordKind.CARD) || refs.any { r -> r.kind == SubjectKind.CARD && r.id == it.cardId } }
    return SubjectData(cardSubjects,events,links,observations,changes,sets,quantities,rates)
}
internal fun SubjectData.cardDependencies() = cards.map { it.cardId }+events.mapNotNull { it.cardId }
internal fun SubjectData.metricDependencies() = (observations.map { it.definition }+quantities.map { it.definition }).map { it.metricId }
internal fun SubjectData.unitDependencies() = (observations.map { it.definition }+quantities.map { it.definition }).map { it.unitId }+sets.map { it.loadUnitId }

/** Provisional capability numbers are isolated for A's final format reconciliation. */
internal const val SUBJECT_FULL_FORMAT = 14
internal const val SUBJECT_SELECTIVE_FORMAT = 4
internal fun TransferContent.hasSubjectQuantities() = !subjects.empty || metrics.any { it.bounds != null }
private fun TransferOrigin.hasSubjectQuantities() = localGroup.hasSubjectQuantities() ||
    compaction?.localEntities?.any { it.kind in subjectIdentityIds(SubjectData()).keys } == true
fun Dataset.hasSubjectQuantities(): Boolean = !subjects.empty || metrics.any { it.bounds != null } ||
    transfer.origins.any { it.hasSubjectQuantities() } || transfer.operations.any { op -> op.groups.any {
        it.group.content.hasSubjectQuantities() || it.group.entities.any { e -> e.kind in subjectIdentityIds(SubjectData()).keys } ||
            it.inserted?.hasSubjectQuantities() == true || it.previousOrigin?.hasSubjectQuantities() == true
    } }
