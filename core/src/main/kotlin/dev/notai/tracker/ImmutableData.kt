package dev.notai.tracker

import java.util.Collections

/** Kotlin read-only interfaces alone do not prevent mutation through the original builder/casts.
 * Copy and wrap at trust boundaries. Reuse only collections from the already frozen base proof. */
internal fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <T> frozenSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
private fun <K,V> frozenMap(values: Map<K,V>): Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(values))
internal fun Classification.frozen() = copy(details = frozenMap(details),tags = frozenSet(tags),
    ancestorPathAtWrite = frozenList(ancestorPathAtWrite),facets = frozenList(facets))
internal fun Dataset.frozen(base: Dataset? = null): Dataset = copy(
    focus = if(focus === base?.focus) focus else focus?.copy(segmentIds = frozenList(focus.segmentIds)),
    goals = if(goals === base?.goals) goals else frozenList(goals.map { it.copy(filter = it.filter.copy(details = frozenMap(it.filter.details),tags = frozenSet(it.filter.tags),facets = frozenList(it.filter.facets))) }),
    types = if(types === base?.types) types else frozenList(types.map { it.copy(aliases = frozenList(it.aliases),
        suggestedAxisIds = frozenSet(it.suggestedAxisIds),suggestedMetricIds = frozenSet(it.suggestedMetricIds),researchOrigins = frozenList(it.researchOrigins)) }),
    cards = if(cards === base?.cards) cards else frozenList(cards.map { it.copy(details = frozenMap(it.details),tags = frozenSet(it.tags),facets = frozenList(it.facets)) }),
    favorites = if(favorites === base?.favorites) favorites else frozenList(favorites.map { it.copy(selection = it.selection.frozen()) }),
    sessions = if(sessions === base?.sessions) sessions else frozenList(sessions.map { it.copy(selection = it.selection.frozen(),
        importOrigin = it.importOrigin?.let { origin -> origin.copy(fields = frozenMap(origin.fields)) }) }),
    segments = if(segments === base?.segments) segments else frozenList(segments.map { it.copy(selection = it.selection.frozen()) }),
    measurements = if(measurements === base?.measurements) measurements else frozenList(measurements),
    axes = if(axes === base?.axes) axes else frozenList(axes.map { it.copy(values = frozenList(it.values),researchOrigins = frozenList(it.researchOrigins)) }),
    metrics = if(metrics === base?.metrics) metrics else frozenList(metrics.map { it.copy(scopes = frozenSet(it.scopes),researchOrigins = frozenList(it.researchOrigins)) }),
    units = if(units === base?.units) units else frozenList(units)
)
