package dev.notai.tracker

import java.util.Collections

/** Kotlin read-only interfaces alone do not prevent mutation through the original builder/casts.
 * Copy and wrap at trust boundaries. Reuse only collections from the already frozen base proof. */
private val emptyListProof = Collections.unmodifiableList(ArrayList<Nothing>())
private val emptySetProof = Collections.unmodifiableSet(LinkedHashSet<Nothing>())
private val emptyMapProof = Collections.unmodifiableMap(LinkedHashMap<Nothing,Nothing>())
@Suppress("UNCHECKED_CAST")
internal fun <T> frozenList(values: Collection<T>): List<T> = if(values.isEmpty()) emptyListProof as List<T> else Collections.unmodifiableList(ArrayList(values))
@Suppress("UNCHECKED_CAST")
internal fun <T> frozenSet(values: Collection<T>): Set<T> = if(values.isEmpty()) emptySetProof as Set<T> else Collections.unmodifiableSet(LinkedHashSet(values))
@Suppress("UNCHECKED_CAST")
internal fun <K,V> frozenMap(values: Map<K,V>): Map<K,V> = if(values.isEmpty()) emptyMapProof as Map<K,V> else Collections.unmodifiableMap(LinkedHashMap(values))
internal fun Classification.frozen() = copy(details = frozenMap(details),tags = frozenSet(tags),
    ancestorPathAtWrite = frozenList(ancestorPathAtWrite),facets = frozenList(facets))
internal fun ImportOrigin.frozen() = copy(fields = frozenMap(fields))
internal fun Dataset.frozen(base: Dataset? = null): Dataset {
    val selections = HashMap<Classification,Classification>()
    fun capture(value: Classification): Classification {
        val frozen = value.frozen()
        return selections.getOrPut(frozen) { frozen }
    }
    return copy(
    focus = if(focus === base?.focus) focus else focus?.copy(segmentIds = frozenList(focus.segmentIds)),
    goals = if(goals === base?.goals) goals else frozenList(goals.map { it.copy(filter = it.filter.copy(details = frozenMap(it.filter.details),tags = frozenSet(it.filter.tags),facets = frozenList(it.filter.facets))) }),
    types = if(types === base?.types) types else frozenList(types.map { it.copy(aliases = frozenList(it.aliases),
        suggestedAxisIds = frozenSet(it.suggestedAxisIds),suggestedMetricIds = frozenSet(it.suggestedMetricIds),researchOrigins = frozenList(it.researchOrigins)) }),
    cards = if(cards === base?.cards) cards else frozenList(cards.map { it.copy(details = frozenMap(it.details),tags = frozenSet(it.tags),facets = frozenList(it.facets)) }),
    favorites = if(favorites === base?.favorites) favorites else frozenList(favorites.map { it.copy(selection = capture(it.selection)) }),
    sessions = if(sessions === base?.sessions) sessions else frozenList(sessions.map { it.copy(selection = capture(it.selection),
        importOrigin = it.importOrigin?.let { origin -> origin.copy(fields = frozenMap(origin.fields)) }) }),
    segments = if(segments === base?.segments) segments else frozenList(segments.map { it.copy(selection = capture(it.selection)) }),
    measurements = if(measurements === base?.measurements) measurements else frozenList(measurements),
    axes = if(axes === base?.axes) axes else frozenList(axes.map { it.copy(values = frozenList(it.values),researchOrigins = frozenList(it.researchOrigins)) }),
    metrics = if(metrics === base?.metrics) metrics else frozenList(metrics.map { it.copy(scopes = frozenSet(it.scopes),researchOrigins = frozenList(it.researchOrigins)) }),
    units = if(units === base?.units) units else frozenList(units)
)
}
