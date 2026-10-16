package dev.notai.tracker

import kotlinx.serialization.Serializable

/** Focus owns its countdown mode: underlying work-session RUNNING can also mean DUE. */
fun Snapshot.isCounting(): Boolean = focus?.let { it.mode == FocusMode.RUNNING } ?: (mode == Mode.RUNNING)

/** Renderer-independent metadata. Consumers map the first known key and retain the fallback. */
data class ActivityIcon(val keys: List<String>,val fallback: String)
object ActivityIcons {
    fun resolve(data: Dataset,selection: Classification): ActivityIcon {
        val byId = data.types.associateBy { it.id }
        val lineage = mutableListOf<String>(); val seen = mutableSetOf<String>()
        var type = selection.activityTypeId?.let(byId::get)
        while(type != null && seen.add(type.id)) {
            lineage += "type:${type.id}:v${type.meaningVersion}"
            type = type.parentId?.let(byId::get)
        }
        val keys = listOfNotNull(selection.cardId?.takeIf { id -> data.cards.any { it.id == id } }?.let { "card:$it" })+lineage
        return ActivityIcon(keys,if(keys.isEmpty()) "activity.unclassified" else "activity.custom")
    }
}
/** IDs and classification variants, never a presentation title, determine quick-choice identity. */
fun selectionIdentity(c: Classification): String = sha256(classificationKey(c.copy(
    name = if(c.cardId != null || c.activityTypeId != null) null else c.name,
    ancestorPathAtWrite = emptyList())).toByteArray(Charsets.UTF_8))
@Serializable enum class ChoiceApplication { START, SWITCH }
/** History references bind the displayed occurrence and note, including bounded suggestions.
 * Old references without an occurrence remain decodable but cannot rebind to a newer record. */
@Serializable data class ChoiceReference(val datasetId: String,val barrier: String,val choiceId: String,val fingerprint: String,
    val sourceSessionId: String? = null,val sourceSegmentId: String? = null)
data class QuickChoice(val entry: ChoiceEntry,val reference: ChoiceReference,val icon: ActivityIcon)
@Serializable data class StoppedAssignment(val id: String,val datasetId: String,val barrier: String,val timerEpoch: Long,
    val sessionId: String,val recordFingerprint: String)
internal fun recordFingerprint(d: Dataset,id: String): String {
    val s = d.sessions.singleOrNull { it.id == id } ?: fail(ErrorCode.STALE,"Completed record no longer exists")
    val segments = d.segments.filter { it.sessionId == id }.sortedBy { it.id }
    val ids = segments.map { it.id }.toSet()
    val measurements = d.measurements.filter { (it.scope == Scope.SESSION && it.targetId == id) || (it.scope == Scope.SEGMENT && it.targetId in ids) }.sortedBy { it.id }
    return sha256((codec.encodeToString(s)+codec.encodeToString(segments)+codec.encodeToString(measurements)).toByteArray(Charsets.UTF_8))
}
/** Record validity is independent of the current timer's epoch. */
internal fun assignmentValid(d: Dataset,offer: StoppedAssignment): Boolean =
    offer.datasetId == d.datasetId && offer.barrier == d.controlBarrier &&
        d.sessions.any { it.id == offer.sessionId && it.status == SessionStatus.STOPPED } &&
        recordFingerprint(d,offer.sessionId) == offer.recordFingerprint
internal fun validateAssignments(d: Dataset) {
    ensure(d.pendingAssignments.size <= 32 && d.pendingAssignments.map { it.id }.distinct().size == d.pendingAssignments.size &&
        d.pendingAssignments.map { it.sessionId }.distinct().size == d.pendingAssignments.size,"Invalid assignment ledger")
    d.pendingAssignments.forEach { offer ->
        ensure(offer.id.isNotBlank() && offer.datasetId == d.datasetId && offer.barrier == d.controlBarrier &&
            offer.timerEpoch >= 0 && offer.timerEpoch <= d.timerEpoch && offer.sessionId.isNotBlank() &&
            offer.recordFingerprint.matches(Regex("[0-9a-f]{64}")),"Invalid completed-record assignment")
    }
}
