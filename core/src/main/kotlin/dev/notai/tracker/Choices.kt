package dev.notai.tracker

import java.text.Normalizer
import java.util.Locale

/** Read-only discovery. No search call writes history, favorites, telemetry or a launch ledger. */
enum class ChoiceKind { TYPE, CARD, FAVORITE, HISTORY }
enum class CatalogScope { ALL, ROOTS, CHILDREN, SUBTREE }
enum class ChoiceAvailability { AVAILABLE, ARCHIVED_CARD, ARCHIVED_TYPE, GROUP }
enum class MatchField { NAME, ALIAS, SELECTION_NAME, CURRENT_CARD_NAME, CURRENT_TYPE_NAME, CURRENT_TYPE_ALIAS, UNCLASSIFIED }
enum class MatchStrength { EXACT, PREFIX, CONTAINS }
data class ChoiceQuery(val text: String = "",val kinds: Set<ChoiceKind> = setOf(ChoiceKind.TYPE,ChoiceKind.CARD,ChoiceKind.FAVORITE),
    val scope: CatalogScope = CatalogScope.ALL,val parentId: String? = null,val includeArchived: Boolean = false)
enum class SuggestionOrder { RECENT_RECORDED, FREQUENT_RECORDED }
data class HistoryWindow(val start: Long,val end: Long)
data class SuggestionQuery(val enabled: Boolean = false,val order: SuggestionOrder = SuggestionOrder.RECENT_RECORDED,val window: HistoryWindow? = null)
data class ChoicePath(val id: String,val name: String,val archived: Boolean,val selectable: Boolean)
data class MatchEvidence(val field: MatchField,val text: String,val strength: MatchStrength)
data class SuggestionEvidence(val order: SuggestionOrder,val window: HistoryWindow?,val sessionCount: Int,val lastRecordedEnd: Long)
data class ChoiceEntry(val id: String,val kind: ChoiceKind,val sourceId: String,val title: String,
    val path: List<ChoicePath>,val selection: Classification,val note: String?,val availability: ChoiceAvailability,
    val sourceSessionId: String? = null,val sourceSegmentId: String? = null) {
    val canStart get() = availability == ChoiceAvailability.AVAILABLE
}
data class ChoiceHit(val entry: ChoiceEntry,val matches: List<MatchEvidence> = emptyList(),val suggestion: SuggestionEvidence? = null)
data class ChoicePage(val token: Token,val items: List<ChoiceHit>,val total: Int,val next: ChoiceCursor?)
class ChoiceCursor internal constructor(internal val owner: ChoiceView,internal val request: Any,
    internal val hits: List<RankedChoice>,internal val offset: Int)
data class ChoiceIndexStats(val catalogBuilds: Int,val personalBuilds: Int,val historyBuilds: Int)

private fun normalized(value: String) = Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim().replace(Regex("\\s+")," ")
internal data class ChoiceText(val field: MatchField,val original: String,val normalized: String = normalized(original))
internal data class ChoiceRecord(val id: String,val kind: ChoiceKind,val sourceId: String,val title: String,
    val selection: Classification,val fields: List<ChoiceText>,val sourceSessionId: String? = null,val sourceSegmentId: String? = null) {
    val sortName = normalized(title)
}
internal data class Occurrence(val segment: Segment,val sessionId: String)
internal data class HistoryVariant(val record: ChoiceRecord,val occurrences: List<Occurrence>)
internal data class RankedChoice(val record: ChoiceRecord,val score: Int = 0,val matches: List<MatchEvidence> = emptyList(),val suggestion: SuggestionEvidence? = null)
internal class CatalogChoices(types: List<ActivityType>) {
    val byId = types.associateBy { it.id }
    val paths = types.associate { t ->
        val result = mutableListOf<ChoicePath>(); var current: ActivityType? = t
        while(current != null) { result += ChoicePath(current.id,current.name,current.archived,current.selectable); current = current.parentId?.let(byId::get) }
        t.id to frozenList(result.asReversed())
    }
    val records = types.map { t -> ChoiceRecord("type:${t.id}",ChoiceKind.TYPE,t.id,t.name,
        Classification(name = t.name,activityTypeId = t.id,ancestorPathAtWrite = frozenList(paths.getValue(t.id).map { it.id })),
        listOf(ChoiceText(MatchField.NAME,t.name))+t.aliases.map { ChoiceText(MatchField.ALIAS,it) }) }
    val fields = records.associate { it.sourceId to it.fields.map { field -> field.copy(field = if(field.field == MatchField.NAME) MatchField.CURRENT_TYPE_NAME else MatchField.CURRENT_TYPE_ALIAS) } }
}
internal class PersonalChoices(cards: List<Card>,favorites: List<Favorite>) {
    val cardsById = cards.associateBy { it.id }
    val cardNames = cards.associate { it.id to ChoiceText(MatchField.CURRENT_CARD_NAME,it.name) }
    val records = cards.map { ChoiceRecord("card:${it.id}",ChoiceKind.CARD,it.id,it.name,it.classification(),listOf(ChoiceText(MatchField.NAME,it.name))) } +
        favorites.map { ChoiceRecord("favorite:${it.id}",ChoiceKind.FAVORITE,it.id,it.label,it.selection,
            listOf(ChoiceText(MatchField.NAME,it.label))+listOfNotNull(it.selection.name?.let { name -> ChoiceText(MatchField.SELECTION_NAME,name) })) }
}
/** Reuse normalized data on ticks/typing and unrelated revisions. No global cache or search service. */
internal class ChoiceCache {
    private var typeSource: List<ActivityType>? = null; private var catalog: CatalogChoices? = null
    private var cardSource: List<Card>? = null; private var favoriteSource: List<Favorite>? = null; private var personal: PersonalChoices? = null
    private var sessionSource: List<Session>? = null; private var segmentSource: List<Segment>? = null
    private var completedIds: Set<String>? = null; private var completedSegments: List<Segment>? = null; private var history: List<HistoryVariant>? = null
    private var catalogBuilds = 0; private var personalBuilds = 0; private var historyBuilds = 0
    @Synchronized fun stats() = ChoiceIndexStats(catalogBuilds,personalBuilds,historyBuilds)
    @Synchronized fun catalog(d: Dataset): CatalogChoices {
        if(catalog == null || (typeSource !== d.types && typeSource != d.types)) { catalog = CatalogChoices(d.types); catalogBuilds++ }
        typeSource = d.types; return catalog!!
    }
    @Synchronized fun personal(d: Dataset): PersonalChoices {
        if(personal == null || (cardSource !== d.cards && cardSource != d.cards) || (favoriteSource !== d.favorites && favoriteSource != d.favorites)) {
            personal = PersonalChoices(d.cards,d.favorites); personalBuilds++
        }
        cardSource = d.cards; favoriteSource = d.favorites; return personal!!
    }
    @Synchronized fun history(d: Dataset): List<HistoryVariant> {
        if(history != null && sessionSource === d.sessions && segmentSource === d.segments) return history!!
        val ids = d.sessions.filter { it.status == SessionStatus.STOPPED }.map { it.id }.toSet()
        val segments = d.segments.filter { it.sessionId in ids }
        if(history == null || completedIds != ids || completedSegments != segments) {
            val groups = segments.groupBy { classificationKey(it.selection) }
            history = groups.map { (key,values) ->
                val latest = values.maxWith(compareBy<Segment>({ it.end ?: it.start },{ it.start },{ it.id }))
                val c = latest.selection
                HistoryVariant(ChoiceRecord("history:"+researchHash(key.toByteArray(Charsets.UTF_8)),ChoiceKind.HISTORY,latest.id,c.name ?: "Без уточнения",c,
                    if(c.name != null) listOf(ChoiceText(MatchField.NAME,c.name)) else if(c.activityTypeId == null) listOf(ChoiceText(MatchField.UNCLASSIFIED,"Без уточнения")) else emptyList(),latest.sessionId,latest.id),
                    frozenList(values.map { Occurrence(it,it.sessionId) }))
            }
            completedIds = ids; completedSegments = segments; historyBuilds++
        }
        sessionSource = d.sessions; segmentSource = d.segments
        return history!!
    }
}

/** Keep this snapshot while paging or typing. A cursor belongs to this view and exact request. */
class ChoiceView internal constructor(private val d: Dataset,private val cache: ChoiceCache,private val state: ()->Snapshot) {
    val token: Token = token(d)
    private val catalog by lazy { cache.catalog(d) }
    private val personal by lazy { cache.personal(d) }
    private val history by lazy { cache.history(d) }
    private val sessions by lazy { d.sessions.associateBy { it.id } }
    /** Diagnostic counters, not persisted analytics. Useful for integration/performance verification. */
    fun indexStats() = cache.stats()
    fun search(input: ChoiceQuery = ChoiceQuery(),cursor: ChoiceCursor? = null,pageSize: Int = 30): ChoicePage {
        val query = input.copy(kinds = frozenSet(input.kinds))
        ensure(pageSize in 1..100,"Page size must be 1..100")
        ensure(query.text.length <= 256,"Query exceeds 256 characters")
        ensure(query.kinds.isNotEmpty(),"Choose at least one result kind")
        ensure(query.scope != CatalogScope.ALL || query.parentId == null,"Global search cannot silently scope to a parent")
        ensure(query.scope != CatalogScope.ROOTS || query.parentId == null,"Root scope has no parent")
        if(query.scope in setOf(CatalogScope.CHILDREN,CatalogScope.SUBTREE)) ensure(query.parentId != null && query.parentId in catalog.byId,"Missing browse parent")
        val request = query
        if(cursor != null) return page(request,cursor.hits,cursor,pageSize)
        val text = normalized(query.text)
        val records = buildList {
            if(ChoiceKind.TYPE in query.kinds) addAll(catalog.records)
            if(ChoiceKind.CARD in query.kinds || ChoiceKind.FAVORITE in query.kinds) addAll(personal.records.filter { it.kind in query.kinds })
            if(ChoiceKind.HISTORY in query.kinds) addAll(history.map { it.record })
        }
        val ranked = records.asSequence().filter { record ->
            val availability = availability(record.selection)
            (query.includeArchived || availability !in setOf(ChoiceAvailability.ARCHIVED_CARD,ChoiceAvailability.ARCHIVED_TYPE)) && inScope(record,query)
        }.mapNotNull { record -> match(record,text) }.sortedWith(compareBy<RankedChoice>({it.score},{kindOrder(it.record.kind)},{sortName(it.record)},{it.record.id})).toList()
        return page(request,ranked,null,pageSize)
    }
    /** No work at all for disabled suggestions. Frequency always has an explicit [start,end) window. */
    fun suggestions(query: SuggestionQuery = SuggestionQuery(),cursor: ChoiceCursor? = null,pageSize: Int = 20): ChoicePage {
        if(!query.enabled) { ensure(cursor == null,"Disabled suggestions have no cursor"); return page(query,emptyList(),null,pageSize) }
        val w = query.window
        ensure(query.order != SuggestionOrder.FREQUENT_RECORDED || w != null,"Frequency requires an explicit window")
        if(w != null) ensure(w.start >= 0 && w.end > w.start && w.end <= MAX_TIME,"Invalid history window",ErrorCode.INVALID_INTERVAL)
        if(cursor != null) return page(query,cursor.hits,cursor,pageSize)
        val ranked = history.mapNotNull { variant ->
            if(availability(variant.record.selection) != ChoiceAvailability.AVAILABLE) return@mapNotNull null
            val known = variant.occurrences.filter { o -> !o.segment.uncertain && o.segment.durationMs > 0 && o.segment.end != null &&
                (w == null || o.segment.start < w.end && o.segment.end > w.start) }
            if(known.isEmpty()) return@mapNotNull null
            val latest = known.maxWith(compareBy<Occurrence>({it.segment.end},{it.segment.start},{it.segment.id}))
            val entry = variant.record.copy(sourceId = latest.segment.id,selection = latest.segment.selection,sourceSessionId = latest.sessionId,sourceSegmentId = latest.segment.id)
            RankedChoice(entry,suggestion = SuggestionEvidence(query.order,w,known.map { it.sessionId }.toSet().size,latest.segment.end!!))
        }.sortedWith(if(query.order == SuggestionOrder.RECENT_RECORDED)
            compareByDescending<RankedChoice> { it.suggestion!!.lastRecordedEnd }.thenBy { sortName(it.record) }.thenBy { it.record.id }
            else compareByDescending<RankedChoice> { it.suggestion!!.sessionCount }.thenByDescending { it.suggestion!!.lastRecordedEnd }.thenBy { sortName(it.record) }.thenBy { it.record.id })
        return page(query,ranked,null,pageSize)
    }
    private fun page(request: Any,hits: List<RankedChoice>,cursor: ChoiceCursor?,size: Int): ChoicePage {
        ensure(size in 1..100,"Page size must be 1..100")
        if(cursor != null && (cursor.owner !== this || cursor.request != request)) throw CoreError(ErrorCode.STALE,"Cursor belongs to another snapshot/query",state())
        val offset = cursor?.offset ?: 0
        val end = minOf(hits.size.toLong(),offset.toLong()+size).toInt()
        val rows = hits.subList(offset,end).map { hit ->
            val r = hit.record
            val selection = if(r.kind == ChoiceKind.CARD) r.selection.copy(ancestorPathAtWrite = frozenList(path(r).map { it.id })) else r.selection
            ChoiceHit(ChoiceEntry(r.id,r.kind,r.sourceId,title(r),path(r),selection,r.sourceSessionId?.let { sessions[it]?.note },availability(selection),r.sourceSessionId,r.sourceSegmentId),
                frozenList(hit.matches),hit.suggestion)
        }
        return ChoicePage(token,frozenList(rows),hits.size,if(end < hits.size) ChoiceCursor(this,request,hits,end) else null)
    }
    private fun title(r: ChoiceRecord) = if(r.kind == ChoiceKind.HISTORY && r.selection.name == null)
        r.selection.activityTypeId?.let { catalog.byId[it]?.name } ?: r.title else r.title
    private fun sortName(r: ChoiceRecord) = if(r.kind == ChoiceKind.HISTORY && r.selection.name == null)
        r.selection.activityTypeId?.let { catalog.fields[it]?.firstOrNull()?.normalized } ?: r.sortName else r.sortName
    private fun path(r: ChoiceRecord) = r.selection.activityTypeId?.let { catalog.paths[it] }.orEmpty()
    private fun inScope(r: ChoiceRecord,q: ChoiceQuery): Boolean {
        val type = r.selection.activityTypeId?.let(catalog.byId::get)
        return when(q.scope) {
            CatalogScope.ALL -> true
            CatalogScope.ROOTS -> if(r.kind == ChoiceKind.TYPE) type?.parentId == null else r.selection.activityTypeId == null
            CatalogScope.CHILDREN -> if(r.kind == ChoiceKind.TYPE) type?.parentId == q.parentId else type?.id == q.parentId
            CatalogScope.SUBTREE -> path(r).any { it.id == q.parentId }
        }
    }
    private fun availability(c: Classification): ChoiceAvailability {
        if(c.cardId?.let { personal.cardsById[it]?.archived } == true) return ChoiceAvailability.ARCHIVED_CARD
        val type = c.activityTypeId?.let(catalog.byId::get)
        return when { type?.archived == true -> ChoiceAvailability.ARCHIVED_TYPE; type?.selectable == false -> ChoiceAvailability.GROUP; else -> ChoiceAvailability.AVAILABLE }
    }
    private fun match(r: ChoiceRecord,text: String): RankedChoice? {
        if(text.isEmpty()) return RankedChoice(r)
        val fields = r.fields + (if(r.kind == ChoiceKind.TYPE) emptyList() else r.selection.activityTypeId?.let { catalog.fields[it] }.orEmpty()) +
            listOfNotNull(r.selection.cardId?.let { personal.cardNames[it] })
        fun evidence(field: ChoiceText,part: String): MatchEvidence? = when {
            field.normalized == part -> MatchEvidence(field.field,field.original,MatchStrength.EXACT)
            field.normalized.startsWith(part) -> MatchEvidence(field.field,field.original,MatchStrength.PREFIX)
            field.normalized.contains(part) -> MatchEvidence(field.field,field.original,MatchStrength.CONTAINS)
            else -> null
        }
        val whole = fields.mapNotNull { evidence(it,text) }.minWithOrNull(compareBy({it.strength.ordinal},{it.field.ordinal},{it.text}))
        if(whole != null) return RankedChoice(r,whole.strength.ordinal*10+whole.field.ordinal,listOf(whole))
        val reasons = text.split(' ').map { term -> fields.mapNotNull { evidence(it,term) }.minWithOrNull(compareBy({it.strength.ordinal},{it.field.ordinal},{it.text})) ?: return null }
        return RankedChoice(r,40,reasons.distinct())
    }
    private fun kindOrder(kind: ChoiceKind) = when(kind) { ChoiceKind.FAVORITE -> 0; ChoiceKind.CARD -> 1; ChoiceKind.HISTORY -> 2; ChoiceKind.TYPE -> 3 }
}

internal fun classificationKey(c: Classification): String = codec.encodeToString(c.copy(details = c.details.toSortedMap(),tags = c.tags.toSortedSet(),
        facets = c.facets.sortedWith(compareBy({it.axisId},{it.meaningVersion},{it.valueId ?: ""},{it.valueMeaningVersion ?: 0},{it.text ?: ""}))))
