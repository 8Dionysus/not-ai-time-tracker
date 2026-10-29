package dev.notai.tracker.android

import dev.notai.tracker.Classification
import dev.notai.tracker.Dataset

/**
 * Preset activity names in the chosen language, for display only. A record keeps the name it was written with;
 * it is shown translated only while that name is still the catalog's own (current or source) wording. A name the
 * person typed or renamed is always shown as written.
 */
object CatalogNames {
    @Volatile var adapter: CatalogContentAdapter? = null
    private class Entry(val tag: String, val types: Any, val names: Map<String, Pair<String, String>>)
    @Volatile private var cache: Entry? = null

    private fun names(d: Dataset): Map<String, Pair<String, String>> {
        val tag = L.currentTag
        if (tag == "ru") return emptyMap()
        val a = adapter ?: return emptyMap()
        cache?.let { if (it.tag == tag && it.types === d.types) return it.names }
        val names = runCatching { a.localizedTypeNames(tag, d) }.getOrDefault(emptyMap())
        cache = Entry(tag, d.types, names)
        return names
    }

    /** The translated name of a preset type, or null to keep [current]. */
    fun type(d: Dataset, typeId: String?, current: String?): String? {
        val id = typeId ?: return null
        val (label, source) = names(d)[id] ?: return null
        val installed = d.types.firstOrNull { it.id == id }?.name
        return if (current == null || current == installed || current == source) label else null
    }

    fun title(d: Dataset, c: Classification): String? = type(d, c.activityTypeId, c.name)
}
