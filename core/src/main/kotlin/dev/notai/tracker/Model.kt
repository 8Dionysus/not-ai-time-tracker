package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
val codec = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
const val MAX_TIME = 253402300799999L // last millisecond of year 9999

@Serializable data class ResearchOrigin(val packageSha256: String,val sourceFile: String,val sourceSha256: String,
    val sourceId: String,val pointer: String)

@Serializable data class ActivityType(@Required val id: String = newId(), val name: String, val archived: Boolean = false,
    val parentId: String? = null, val selectable: Boolean = true, val meaningVersion: Int = 1,
    val aliases: List<String> = emptyList(), val definition: String = "",
    val suggestedAxisIds: Set<String> = emptySet(), val suggestedMetricIds: Set<String> = emptySet(),
    val researchOrigins: List<ResearchOrigin> = emptyList())

@Serializable data class Classification(
    val cardId: String? = null,
    val name: String? = null,
    val activityTypeId: String? = null,
    val details: Map<String, String> = emptyMap(),
    val tags: Set<String> = emptySet(),
    val ancestorPathAtWrite: List<String> = emptyList(), val facets: List<Facet> = emptyList()
)
@Serializable data class Card(@Required val id: String = newId(), val name: String, val templateId: String? = null,
    val activityTypeId: String? = null, val details: Map<String, String> = emptyMap(),
    val tags: Set<String> = emptySet(), val archived: Boolean = false, val facets: List<Facet> = emptyList()) {
    fun classification() = Classification(id, name, activityTypeId, details, tags, facets = facets)
}
@Serializable data class Favorite(@Required val id: String = newId(), val label: String, val selection: Classification)
@Serializable enum class SessionStatus { RUNNING, PAUSED, STOPPED, RECOVERY }
@Serializable data class Session(@Required val id: String = newId(), val status: SessionStatus,
    val selection: Classification = Classification(), val note: String? = null, val importOrigin: ImportOrigin? = null)
@Serializable data class Anchor(val bootId: String, val elapsedMs: Long,val processId: String? = null)
/** Positive provenance for an explicitly confirmed interval. Null on older/unmarked
 * segments does not assert that their time was measured by the device. */
@Serializable data class ManualTimeConfirmation(val confirmedAt: Long)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class Segment(@Required val id: String = newId(), val sessionId: String,
    val start: Long, val end: Long? = null, @Required val durationMs: Long = 0,
    val selection: Classification = Classification(), val anchor: Anchor? = null,
    val uncertain: Boolean = false, val observedUntil: Long? = null,val limitMs: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val manualConfirmation: ManualTimeConfirmation? = null)
@Serializable enum class Scope { SESSION, SEGMENT }
@Serializable data class Measurement(@Required val id: String = newId(), val fieldId: String, @Required val meaningVersion: Int = 1,
    val unit: String, val scope: Scope, val targetId: String, val value: Decimal, val source: String? = null) {
    constructor(id: String = newId(), fieldId: String, meaningVersion: Int = 1, unit: String, scope: Scope, targetId: String, value: Long):
        this(id,fieldId,meaningVersion,unit,scope,targetId,Decimal(value.toString()))
}
@Serializable data class SurfaceSettings(val widget: Boolean = false,val notification: Boolean = false,val overlay: Boolean = false,val focusSignal: Boolean = false,
    val shortcut: Boolean = true) // Preserve the previously always-enabled launcher timer on legacy decode.
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class Settings(val reportZone: String = "UTC",val surfaces: SurfaceSettings = SurfaceSettings(),val focusPlan: FocusPlan = FocusPlan(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val schedule: ScheduleSettings = ScheduleSettings())
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class Dataset(@Required val datasetId: String = newId(), @Required val revision: Long = 0,
    @Required val types: List<ActivityType> = Catalog.types,
    @Required val cards: List<Card> = emptyList(), @Required val favorites: List<Favorite> = emptyList(),
    @Required val sessions: List<Session> = emptyList(), @Required val segments: List<Segment> = emptyList(),
    val schedule: ScheduleData = ScheduleData(),val transfer: TransferState = TransferState(),
    val goals: List<Goal> = emptyList(),val focus: FocusRun? = null,val catalogVersion: String? = null,
    val timerEpoch: Long = 0,val controlBarrier: String = "",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val pendingAssignments: List<StoppedAssignment> = emptyList(),
    @Required val measurements: List<Measurement> = emptyList(), @Required val settings: Settings = Settings(),
    @Required val axes: List<Axis> = Catalog.vocabulary.axes, @Required val metrics: List<Metric> = Catalog.vocabulary.metrics, @Required val units: List<UnitDefinition> = Catalog.vocabulary.units)

data class Moment(val wall: Long, val elapsed: Long, val bootId: String,val processId: String? = null)
fun interface Clock { fun now(): Moment }
@Serializable data class Token(val datasetId: String, val revision: Long, val sessionId: String?)
@Serializable data class TimerTarget(val datasetId: String,val sessionId: String?,val epoch: Long,val barrier: String)
enum class TimerDirective { START, PAUSE, RESUME, STOP }
enum class Mode { IDLE, RUNNING, PAUSED, RECOVERY }
data class Snapshot(val token: Token, val mode: Mode, val session: Session?, val active: Segment?,
    val trustedDurationMs: Long, val calendarUncertain: Boolean, val safeLabel: String,
    val actions: Set<String>,val focus: FocusSnapshot? = null,val timerTarget: TimerTarget? = null)
@Serializable enum class ErrorCode { STALE, INVALID_INTERVAL, OVERLAP, INVALID_INPUT, STORAGE, UNSUPPORTED_IMPORT, RECOVERY_REQUIRED }
class CoreError(val code: ErrorCode, message: String, val currentState: Snapshot? = null,
    val arguments: Map<String,ErrorArgument> = emptyMap(), val fieldPath: String? = null,val reason: ErrorReason? = null,val importIssue: ImportIssue? = null) : RuntimeException(message) {
    val localized get() = LocalizedError(code,arguments,fieldPath,reason)
}
fun fail(code: ErrorCode, message: String): Nothing = throw CoreError(code, message)
fun ensure(ok: Boolean, message: String, code: ErrorCode = ErrorCode.INVALID_INPUT) { if (!ok) fail(code, message) }

/** Implementations must serialize read/check/write atomically and roll back if block throws. */
interface StateStore {
    fun read(): Dataset
    fun <T> transaction(block: (Dataset) -> Pair<Dataset, T>): T
    val changes: Flow<Dataset>
    fun readChecked(): CheckedState = CheckedState.validate(read())
    val checkedChanges: Flow<CheckedState> get() = changes.map { CheckedState.validate(it) }
    /** Safe fallback for stores without the optimized checked-state path. */
    fun <T> checkedTransaction(block: (CheckedState) -> CheckedCommit<T>): T = transaction { old ->
        val before = CheckedState.validate(old)
        val commit = block(before); commit.requireBase(before)
        commit.state.data to commit.result
    }
}

internal fun validateMoment(n: Moment) {
    ensure(n.wall in 0..MAX_TIME && n.elapsed >= 0 && n.bootId.isNotBlank() && (n.processId == null || n.processId.isNotBlank()),"Clock out of supported range",ErrorCode.INVALID_INTERVAL)
}
internal fun validateSegmentTime(s: Segment) {
    s.manualConfirmation?.let {
        ensure(it.confirmedAt in 0..MAX_TIME && s.end != null && !s.uncertain && s.anchor == null && s.limitMs == null,
            "Invalid manually confirmed interval",ErrorCode.INVALID_INTERVAL)
    }
    ensure(s.limitMs == null || (s.end == null && s.limitMs in 1..MAX_TIME && s.durationMs <= s.limitMs && s.start in 0..MAX_TIME && s.limitMs <= MAX_TIME-s.start),"Invalid bounded interval")
    ensure(s.anchor?.processId == null || (s.limitMs != null && s.anchor.processId.isNotBlank()),"Process anchor requires bounded Focus interval")
    ensure(s.start in 0..MAX_TIME && s.durationMs in 0..MAX_TIME, "Time out of range", ErrorCode.INVALID_INTERVAL)
    ensure(s.end == null || (s.end in s.start..MAX_TIME), "Invalid interval", ErrorCode.INVALID_INTERVAL)
    ensure(s.observedUntil == null || s.observedUntil in 0..MAX_TIME, "Invalid observation")
    ensure(s.anchor == null || (s.end == null && s.anchor.elapsedMs >= 0 && s.anchor.bootId.isNotBlank()), "Invalid monotonic anchor")
    if (!s.uncertain) {
        val finish = s.end ?: s.observedUntil
        if (finish != null) ensure(finish >= s.start && (finish > s.start || s.durationMs == 0L) &&
            kotlin.math.abs(s.durationMs-(finish-s.start)) <= 1000,"Duration contradicts certain calendar interval",ErrorCode.INVALID_INTERVAL)
        else ensure(s.durationMs == 0L,"Unobserved open tail cannot have measured duration",ErrorCode.INVALID_INTERVAL)
    }
}

fun validate(data: Dataset) {
    fun ids(values: List<String>) { ensure(values.all { it.isNotBlank() } && values.distinct().size == values.size, "Empty or duplicate ID") }
    ensure(data.datasetId.isNotBlank() && data.revision >= 0 && data.timerEpoch >= 0, "Invalid dataset metadata")
    try { ZoneId.of(data.settings.reportZone) } catch (_: Exception) { fail(ErrorCode.INVALID_INPUT, "Invalid report zone") }
    validateScheduleSettings(data.settings.schedule)
    ids(data.types.map { it.id }); ids(data.cards.map { it.id }); ids(data.favorites.map { it.id }); ids(data.sessions.map { it.id })
    ids(data.segments.map { it.id }); ids(data.measurements.map { it.id })
    val typeIds = data.types.map { it.id }.toSet()
    validateVocabulary(data)
    validateGoals(data)
    validateFocus(data)
    validateAssignments(data)
    validateSchedule(data)
    validateTransfer(data)
    val cards = data.cards.associateBy { it.id }; val sessions = data.sessions.associateBy { it.id }
    fun selection(c: Classification) = validateSelection(data,c,typeIds,cards.keys)
    data.cards.forEach { ensure(it.name.isNotBlank(), "Card needs a name"); selection(it.classification()) }
    data.favorites.forEach { ensure(it.label.isNotBlank(), "Favorite needs a label"); selection(it.selection) }
    val checkedHistorical = java.util.IdentityHashMap<Classification,Boolean>()
    fun historical(c: Classification) {
        if(checkedHistorical.put(c,true) != null) return
        selection(c)
        ensure(c.activityTypeId == null || c.ancestorPathAtWrite.isNotEmpty(),"Historical classification requires a path snapshot")
    }
    data.sessions.forEach { historical(it.selection) }
    ensure(data.sessions.count { it.status != SessionStatus.STOPPED } <= 1, "Only one current session")
    ensure(data.segments.count { it.end == null } <= 1, "Only one open segment")
    data.segments.forEach { s ->
        val session = sessions[s.sessionId] ?: fail(ErrorCode.INVALID_INPUT, "Missing session")
        historical(s.selection)
        validateSegmentTime(s)
        if (s.end == null) ensure(session.status == SessionStatus.RUNNING || session.status == SessionStatus.RECOVERY, "Open segment without running/recovery session")
    }
    data.segments.sumExact { it.durationMs }
    val bySession = data.segments.groupBy { it.sessionId }
    val segmentIds = data.segments.map { it.id }.toSet()
    data.sessions.forEach { s ->
        s.importOrigin?.let { origin ->
            ensure(origin.namespace.isNotBlank() && origin.recordKey.isNotBlank(),"Invalid import origin")
            ensure(listOf(origin.rowHash,origin.fileHash).all { it.matches(Regex("[0-9a-f]{64}")) },"Invalid import digest")
            try { ZoneId.of(origin.zone) } catch(e: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid import zone") }
        }
        val own = bySession[s.id].orEmpty()
        val open = own.count { it.end == null }
        ensure(open == if (s.status == SessionStatus.RUNNING || s.status == SessionStatus.RECOVERY) 1 else 0, "Inconsistent session state")
        ensure(own.isNotEmpty(), "Session without segments")
    }
    val certain = data.segments.filter { !it.uncertain }.sortedBy { it.start }
    certain.zipWithNext().forEach { (a,b) -> ensure(a.end != null && a.end <= b.start, "Intervals overlap", ErrorCode.OVERLAP) }
    data.measurements.forEach {
        ensure(it.fieldId.isNotBlank() && it.meaningVersion > 0 && it.unit.isNotBlank() && it.value.number.signum() >= 0, "Invalid measurement")
        validateMeasurement(data,it)
        ensure(if (it.scope == Scope.SESSION) sessions.containsKey(it.targetId) else it.targetId in segmentIds, "Missing measurement target")
    }
    ensure(data.measurements.map { listOf(it.fieldId, it.meaningVersion, it.unit, it.scope, it.targetId) }.distinct().size == data.measurements.size, "Duplicate measurement for target")
}

internal fun validateSelection(data: Dataset,c: Classification,typeIds: Set<String>,cardIds: Set<String>) {
        ensure(c.cardId == null || c.cardId in cardIds, "Missing card")
        ensure(c.activityTypeId == null || c.activityTypeId in typeIds, "Missing activity type")
        ensure(c.cardId == null || c.name != null, "Card selection needs historical name")
        ensure(c.activityTypeId != null || c.ancestorPathAtWrite.isEmpty(), "Unmapped selection has a path")
        ensure(c.ancestorPathAtWrite.isEmpty() || (c.ancestorPathAtWrite.last() == c.activityTypeId && c.ancestorPathAtWrite.distinct().size == c.ancestorPathAtWrite.size && c.ancestorPathAtWrite.all { it in typeIds }), "Invalid historical path")
        validateFacets(data,c.facets)
        ensure(c.name == null || c.name.isNotBlank(), "Empty historical name")
        ensure(c.details.all { it.key.isNotBlank() && it.value.isNotBlank() } && c.tags.all { it.isNotBlank() }, "Empty detail/tag")
}

/** Temporary technical seed, loaded from data; it is not the completed product catalog. */
object Catalog {
    val vocabulary: VocabularyData by lazy {
        val text = checkNotNull(Catalog::class.java.getResourceAsStream("/catalog-bootstrap.json")).bufferedReader().use { it.readText() }
        codec.decodeFromString<VocabularyData>(text)
    }
    val types get() = vocabulary.types
    fun card(typeId: String): Card {
        val t = types.singleOrNull { it.id == typeId && it.selectable && !it.archived } ?: fail(ErrorCode.INVALID_INPUT,"Unknown/selectable type")
        return Card(name = t.name,templateId = t.id,activityTypeId = t.id)
    }
}
