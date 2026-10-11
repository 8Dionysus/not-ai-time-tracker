package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
val codec = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
const val MAX_TIME = 253402300799999L // last millisecond of year 9999

@Serializable data class ActivityType(@Required val id: String = newId(), val name: String, val archived: Boolean = false)

@Serializable data class Classification(
    val cardId: String? = null,
    val name: String? = null,
    val partOf: String? = null,
    val details: Map<String, String> = emptyMap(),
    val tags: Set<String> = emptySet()
)
@Serializable data class Card(@Required val id: String = newId(), val name: String, val templateId: String? = null,
    val partOf: String? = null, val details: Map<String, String> = emptyMap(),
    val tags: Set<String> = emptySet(), val archived: Boolean = false) {
    fun classification() = Classification(id, name, partOf, details, tags)
}
@Serializable data class Favorite(@Required val id: String = newId(), val label: String, val selection: Classification)
@Serializable enum class SessionStatus { RUNNING, PAUSED, STOPPED, RECOVERY }
@Serializable data class Session(@Required val id: String = newId(), val status: SessionStatus,
    val selection: Classification = Classification(), val note: String? = null)
@Serializable data class Anchor(val bootId: String, val elapsedMs: Long)
@Serializable data class Segment(@Required val id: String = newId(), val sessionId: String,
    val start: Long, val end: Long? = null, @Required val durationMs: Long = 0,
    val selection: Classification = Classification(), val anchor: Anchor? = null,
    val uncertain: Boolean = false, val observedUntil: Long? = null)
@Serializable enum class Scope { SESSION, SEGMENT }
@Serializable data class Measurement(@Required val id: String = newId(), val fieldId: String, @Required val meaningVersion: Int = 1,
    val unit: String, val scope: Scope, val targetId: String, val value: Long)
@Serializable data class Settings(val reportZone: String = "UTC")
@Serializable data class Dataset(@Required val datasetId: String = newId(), @Required val revision: Long = 0,
    @Required val types: List<ActivityType> = Catalog.types,
    @Required val cards: List<Card> = emptyList(), @Required val favorites: List<Favorite> = emptyList(),
    @Required val sessions: List<Session> = emptyList(), @Required val segments: List<Segment> = emptyList(),
    @Required val measurements: List<Measurement> = emptyList(), @Required val settings: Settings = Settings())

data class Moment(val wall: Long, val elapsed: Long, val bootId: String)
fun interface Clock { fun now(): Moment }
data class Token(val datasetId: String, val revision: Long, val sessionId: String?)
enum class Mode { IDLE, RUNNING, PAUSED, RECOVERY }
data class Snapshot(val token: Token, val mode: Mode, val session: Session?, val active: Segment?,
    val trustedDurationMs: Long, val calendarUncertain: Boolean, val safeLabel: String,
    val actions: Set<String>)
enum class ErrorCode { STALE, INVALID_INTERVAL, OVERLAP, INVALID_INPUT, STORAGE, UNSUPPORTED_IMPORT, RECOVERY_REQUIRED }
class CoreError(val code: ErrorCode, message: String, val currentState: Snapshot? = null) : RuntimeException(message)
fun fail(code: ErrorCode, message: String): Nothing = throw CoreError(code, message)
fun ensure(ok: Boolean, message: String, code: ErrorCode = ErrorCode.INVALID_INPUT) { if (!ok) fail(code, message) }

/** Implementations must serialize read/check/write atomically and roll back if block throws. */
interface StateStore {
    fun read(): Dataset
    fun <T> transaction(block: (Dataset) -> Pair<Dataset, T>): T
    val changes: Flow<Dataset>
}

fun validate(data: Dataset) {
    fun ids(values: List<String>) { ensure(values.all { it.isNotBlank() } && values.distinct().size == values.size, "Empty or duplicate ID") }
    ensure(data.datasetId.isNotBlank() && data.revision >= 0, "Invalid dataset metadata")
    try { ZoneId.of(data.settings.reportZone) } catch (_: Exception) { fail(ErrorCode.INVALID_INPUT, "Invalid report zone") }
    ids(data.types.map { it.id }); ids(data.cards.map { it.id }); ids(data.favorites.map { it.id }); ids(data.sessions.map { it.id })
    ids(data.segments.map { it.id }); ids(data.measurements.map { it.id })
    val typeIds = data.types.map { it.id }.toSet()
    data.types.forEach { ensure(it.name.isNotBlank(), "Type needs a name") }
    val cards = data.cards.associateBy { it.id }; val sessions = data.sessions.associateBy { it.id }
    fun selection(c: Classification) {
        ensure(c.cardId == null || cards.containsKey(c.cardId), "Missing card")
        ensure(c.partOf == null || c.partOf in typeIds, "Missing activity type")
        ensure((c.cardId == null) == (c.name == null), "Unspecified selection must have no card name")
        ensure(c.name == null || c.name.isNotBlank(), "Empty historical name")
        ensure(c.details.all { it.key.isNotBlank() && it.value.isNotBlank() } && c.tags.all { it.isNotBlank() }, "Empty detail/tag")
    }
    data.cards.forEach { ensure(it.name.isNotBlank(), "Card needs a name"); selection(it.classification()) }
    data.favorites.forEach { ensure(it.label.isNotBlank(), "Favorite needs a label"); selection(it.selection) }
    data.sessions.forEach { selection(it.selection) }
    ensure(data.sessions.count { it.status != SessionStatus.STOPPED } <= 1, "Only one current session")
    ensure(data.segments.count { it.end == null } <= 1, "Only one open segment")
    data.segments.forEach { s ->
        val session = sessions[s.sessionId] ?: fail(ErrorCode.INVALID_INPUT, "Missing session")
        selection(s.selection)
        ensure(s.start in 0..MAX_TIME && s.durationMs in 0..MAX_TIME, "Time out of range", ErrorCode.INVALID_INTERVAL)
        ensure(s.end == null || (s.end in s.start..MAX_TIME), "Invalid interval", ErrorCode.INVALID_INTERVAL)
        ensure(s.observedUntil == null || s.observedUntil in 0..MAX_TIME, "Invalid observation")
        ensure(s.anchor == null || (s.end == null && s.anchor.elapsedMs >= 0 && s.anchor.bootId.isNotBlank()), "Invalid monotonic anchor")
        if (s.end == null) ensure(session.status == SessionStatus.RUNNING || session.status == SessionStatus.RECOVERY, "Open segment without running/recovery session")
        if (s.end != null && !s.uncertain) ensure((s.end > s.start || s.durationMs == 0L) && kotlin.math.abs(s.durationMs - (s.end - s.start)) <= 1000, "Duration contradicts certain calendar interval")
    }
    val bySession = data.segments.groupBy { it.sessionId }
    val segmentIds = data.segments.map { it.id }.toSet()
    data.sessions.forEach { s ->
        val own = bySession[s.id].orEmpty()
        val open = own.count { it.end == null }
        ensure(open == if (s.status == SessionStatus.RUNNING || s.status == SessionStatus.RECOVERY) 1 else 0, "Inconsistent session state")
        ensure(own.isNotEmpty(), "Session without segments")
    }
    val certain = data.segments.filter { !it.uncertain }.sortedBy { it.start }
    certain.zipWithNext().forEach { (a,b) -> ensure(a.end != null && a.end <= b.start, "Intervals overlap", ErrorCode.OVERLAP) }
    data.measurements.forEach {
        ensure(it.fieldId.isNotBlank() && it.meaningVersion > 0 && it.unit.isNotBlank() && it.value >= 0, "Invalid measurement")
        ensure(if (it.scope == Scope.SESSION) sessions.containsKey(it.targetId) else it.targetId in segmentIds, "Missing measurement target")
    }
    ensure(data.measurements.map { listOf(it.fieldId, it.meaningVersion, it.unit, it.scope, it.targetId) }.distinct().size == data.measurements.size, "Duplicate measurement for target")
}

object Catalog {
    val types = listOf(ActivityType("language","Языковая практика"),ActivityType("fitness","Физическая активность"),
        ActivityType("reading","Чтение"),ActivityType("work","Работа"),ActivityType("rest","Отдых"))
    data class Template(val id: String, val name: String, val partOf: String, val options: Map<String,List<String>>, val searchNames: List<String> = listOf(name))
    val templates = listOf(
        Template("language-v1", "Языковая практика", "language", mapOf("activity" to listOf("reading", "writing", "listening", "speaking"))),
        Template("training-v1", "Тренировка", "fitness", mapOf("activity" to listOf("cardio", "strength"))),
        Template("reading-v1", "Чтение", "reading", emptyMap()),
        Template("work-v1", "Работа", "work", emptyMap()),
        Template("rest-v1", "Отдых", "rest", emptyMap())
    )
    fun card(templateId: String): Card {
        val t = templates.singleOrNull { it.id == templateId } ?: fail(ErrorCode.INVALID_INPUT, "Unknown template")
        return Card(name = t.name, templateId = t.id, partOf = t.partOf)
    }
}
