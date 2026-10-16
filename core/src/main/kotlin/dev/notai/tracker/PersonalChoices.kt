package dev.notai.tracker

import kotlinx.serialization.Serializable

@Serializable enum class ChoiceNotePolicy { COPY_SOURCE, CLEAR }
/** Per stable Card/Favorite identity; catalog updates never own this profile. */
@Serializable data class ChoicePersonalization(val aliases: List<String> = emptyList(),val order: Int? = null,
    val notePolicy: ChoiceNotePolicy? = null)
internal fun ChoicePersonalization.frozen() = copy(aliases = frozenList(aliases))
internal fun validatePersonalChoice(value: ChoicePersonalization) {
    ensure(value.order == null || value.order in 0..1_000_000,"Invalid personal order")
    ensure(value.aliases.size <= 64 && value.aliases.all { it.isNotBlank() && it.length <= 256 },"Invalid personal search alias")
    ensure(value.aliases.map(::normalizedChoiceText).distinct().size == value.aliases.size,"Duplicate personal search alias")
}
internal fun TransferContent.hasChoicePersonalization() = cards.any { it.personal != ChoicePersonalization() } || favorites.any { it.personal != ChoicePersonalization() }
fun Dataset.hasChoicePersonalization(): Boolean = cards.any { it.personal != ChoicePersonalization() } || favorites.any { it.personal != ChoicePersonalization() } ||
    transfer.origins.any { it.localGroup.hasChoicePersonalization() } || transfer.operations.any { op -> op.groups.any {
        it.group.content.hasChoicePersonalization() || it.inserted?.hasChoicePersonalization() == true || it.previousOrigin?.localGroup?.hasChoicePersonalization() == true
    } }
/** Exact saved identity only; no name-based note lookup. Measurement rows never enter starts. */
internal fun choiceStartNote(data: Dataset,kind: ChoiceKind,sourceId: String,selection: Classification,sourceNote: String?): String? {
    val profile = if(kind == ChoiceKind.FAVORITE) data.favorites.singleOrNull { it.id == sourceId }?.personal
        else selection.cardId?.let { id -> data.cards.singleOrNull { it.id == id }?.personal }
    val policy = profile?.notePolicy ?: if(kind == ChoiceKind.HISTORY) ChoiceNotePolicy.COPY_SOURCE else ChoiceNotePolicy.CLEAR
    if(policy == ChoiceNotePolicy.CLEAR) return null
    if(kind == ChoiceKind.HISTORY) return sourceNote
    val stopped = data.sessions.filter { it.status == SessionStatus.STOPPED }.associateBy { it.id }
    val identity = selectionIdentity(selection)
    val latest = data.segments.filter { it.sessionId in stopped && !it.uncertain && it.end != null && it.durationMs > 0 && selectionIdentity(it.selection) == identity }
        .maxWithOrNull(recordedSegmentOrder)
    return latest?.let { stopped.getValue(it.sessionId).note }
}
