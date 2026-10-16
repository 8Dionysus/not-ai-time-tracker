package dev.notai.tracker

/** Display/search overlay for one captured catalog/type state. No persistence,
 * translated user originals, alternate search engine or timer command authority. */
class ChoiceCatalogLabels internal constructor(internal val token: Token,internal val barrier: String,
    internal val typeSignature: String,val requestedLocaleTag: String,entries: List<CatalogDisplayEntry>) {
    internal val presentations = frozenMap(entries.associate { row ->
        ensure(row.key.kind == CatalogContentKind.TYPE,"Choice labels require TYPE identities")
        row.key.stableId to row.display.copy(aliases = frozenList(row.display.aliases))
    })
    init { ensure(entries.map { it.key.stableId }.distinct().size == entries.size,"Duplicate choice label identity") }
    val provenance = frozenList(entries.mapNotNull { it.provenance }.distinct())
    val translatedTypeCount = entries.count { !it.userOverride && it.resolvedLocaleTag != it.sourceLocaleTag }
    val aliasCount = entries.sumOf { it.display.aliases.size }
    internal val typeFields = frozenMap(entries.associate { row -> row.key.stableId to frozenList(
        listOf(ChoiceText(MatchField.NAME,row.display.label,localeTag = row.resolvedLocaleTag))+
            row.display.aliases.map { ChoiceText(MatchField.ALIAS,it,localeTag = row.resolvedLocaleTag) }) })
    internal val currentTypeFields = frozenMap(typeFields.mapValues { (_,fields) -> frozenList(fields.map {
        it.copy(field = if(it.field == MatchField.NAME) MatchField.CURRENT_TYPE_NAME else MatchField.CURRENT_TYPE_ALIAS)
    }) })
}
