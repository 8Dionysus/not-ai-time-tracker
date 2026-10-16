package dev.notai.tracker

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import java.math.BigDecimal

/** A role for an existing Card, never a second item catalogue. */
@Serializable data class CardSubject(val cardId: String,val kind: String)
@Serializable enum class SubjectKind { CARD, EVENT }
@Serializable data class SubjectRef(val kind: SubjectKind,val id: String)
@Serializable data class SubjectEvent(val id: String = newId(),val kind: String,val cardId: String? = null,val archived: Boolean = false)
@Serializable data class SubjectSessionLink(val id: String = newId(),val subject: SubjectRef,val sessionId: String)
@Serializable data class QuantityDefinition(val metricId: String,val version: Int,val unitId: String)
/** recordedAt is audit time; null effectiveAt deliberately means unknown occurrence time. */
@Serializable data class StateObservation(val id: String = newId(),val subject: SubjectRef,val definition: QuantityDefinition,
    val value: Decimal,val effectiveAt: Long?,val recordedAt: Long,val source: String,val replacesId: String? = null)
@Serializable(with = SignedDecimalSerializer::class)
class SignedDecimal(text: String) {
    val number: BigDecimal
    init { ensure(text.length <= 1024 && text.matches(Regex("-?[0-9]+(\\.[0-9]+)?")),"Use a signed plain decimal");number = BigDecimal(text).stripTrailingZeros() }
    override fun toString() = number.toPlainString()
    override fun equals(other: Any?) = other is SignedDecimal && number.compareTo(other.number) == 0
    override fun hashCode() = number.hashCode()
}
object SignedDecimalSerializer: KSerializer<SignedDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("SignedChange",PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder,value: SignedDecimal) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = SignedDecimal(decoder.decodeString())
}
@Serializable data class SubjectChange(val id: String = newId(),val fromObservationId: String,val toObservationId: String,
    val value: SignedDecimal,val recordedAt: Long,val source: String,val replacesId: String? = null)
/** Bounds have semantic labels and travel with the immutable metric meaning version. */
@Serializable data class ScaleBounds(val min: Decimal,val max: Decimal,val minMeaning: String,val maxMeaning: String)
@Serializable enum class LoadMeaning { TOTAL_EXTERNAL, PER_IMPLEMENT }
@Serializable data class ExerciseSet(val id: String = newId(),val sessionId: String,val ordinal: Int,val repetitions: Int,
    val load: Decimal,val loadUnitId: String,val loadMeaning: LoadMeaning,val implementCount: Int? = null,
    val effectiveAt: Long? = null,val recordedAt: Long,val source: String,val replacesId: String? = null)
@Serializable data class EventQuantity(val id: String = newId(),val subject: SubjectRef,val definition: QuantityDefinition,
    val value: Decimal,val effectiveAt: Long?,val recordedAt: Long,val source: String,val sessionId: String? = null,
    val replacesId: String? = null)
@Serializable enum class TimeBasis { RECORDED, MOVING }
/** Explicit pairing; a distance never borrows time from an unrelated session. */
@Serializable data class DistanceTimeInput(val id: String = newId(),val measurementId: String,val scope: Scope,val targetId: String,
    val start: Long,val end: Long,val durationMs: Long,val timeBasis: TimeBasis,val source: String)
@Serializable data class SubjectData(val cards: List<CardSubject> = emptyList(),val events: List<SubjectEvent> = emptyList(),
    val links: List<SubjectSessionLink> = emptyList(),val observations: List<StateObservation> = emptyList(),
    val changes: List<SubjectChange> = emptyList(),val sets: List<ExerciseSet> = emptyList(),
    val quantities: List<EventQuantity> = emptyList(),val distanceTimes: List<DistanceTimeInput> = emptyList(),val schemaVersion: Int = 1) {
    val empty get() = this == SubjectData()
}
internal fun SubjectData.frozen() = copy(cards = frozenList(cards),events = frozenList(events),links = frozenList(links),
    observations = frozenList(observations),changes = frozenList(changes),sets = frozenList(sets),quantities = frozenList(quantities),distanceTimes = frozenList(distanceTimes))
internal fun validateSubjects(d: Dataset) {
    val s = d.subjects
    ensure(s.schemaVersion == 1,"Unsupported subject data schema",ErrorCode.UNSUPPORTED_IMPORT)
    fun ids(ids: List<String>) = ensure(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size,"Duplicate/empty subject identity")
    ids(s.cards.map { it.cardId });ids(s.events.map { it.id });ids(s.links.map { it.id });ids(s.observations.map { it.id })
    ids(s.changes.map { it.id });ids(s.sets.map { it.id });ids(s.quantities.map { it.id });ids(s.distanceTimes.map { it.id })
    fun subject(ref: SubjectRef) = ensure(when(ref.kind) { SubjectKind.CARD -> s.cards.any { it.cardId == ref.id };SubjectKind.EVENT -> s.events.any { it.id == ref.id } },"Missing subject")
    fun session(id: String) = ensure(d.sessions.any { it.id == id },"Missing subject session")
    fun audit(effectiveAt: Long?,recordedAt: Long,source: String) {
        ensure((effectiveAt == null || effectiveAt in 0..recordedAt) && recordedAt in 0..MAX_TIME && source.isNotBlank(),"Invalid observation time/source")
    }
    fun definition(def: QuantityDefinition,value: Decimal) {
        val m = d.metrics.singleOrNull { it.id == def.metricId && it.meaningVersion == def.version } ?: fail(ErrorCode.INVALID_INPUT,"Missing quantity definition/version")
        ensure(m.canonicalUnitId == def.unitId,"Quantity unit differs from definition")
        ensure(maxOf(0,value.number.scale()) <= m.maxScale,"Quantity precision exceeded")
        m.bounds?.let { ensure(value.number >= it.min.number && value.number <= it.max.number,"Quantity outside scale bounds") }
    }
    s.cards.forEach { ensure(it.kind.isNotBlank() && d.cards.any { c -> c.id == it.cardId },"Subject requires existing card") }
    s.events.forEach { ensure(it.kind.isNotBlank() && (it.cardId == null || d.cards.any { c -> c.id == it.cardId }),"Invalid event/card") }
    s.links.forEach { subject(it.subject);session(it.sessionId) }
    ensure(s.links.map { it.subject to it.sessionId }.distinct().size == s.links.size,"Duplicate subject/session link")
    fun <T> corrections(rows: List<T>,id: (T)->String,previous: (T)->String?,recorded: (T)->Long,compatible: (T,T)->Boolean) {
        val byId = rows.associateBy(id)
        ensure(rows.mapNotNull(previous).distinct().size == rows.mapNotNull(previous).size,"Correction branches require explicit conflict resolution")
        rows.forEach { row ->
            val seen = mutableSetOf(id(row));var current = row
            while(previous(current) != null) {
                val p = byId[previous(current)] ?: fail(ErrorCode.INVALID_INPUT,"Missing correction predecessor")
                ensure(seen.add(id(p)) && recorded(current) >= recorded(p) && compatible(current,p),"Invalid correction lineage")
                current = p
            }
        }
    }
    s.observations.forEach { subject(it.subject);definition(it.definition,it.value);audit(it.effectiveAt,it.recordedAt,it.source) }
    corrections(s.observations,{it.id},{it.replacesId},{it.recordedAt}) { a,b -> a.subject == b.subject && a.definition == b.definition }
    s.changes.forEach { c ->
        val a = s.observations.singleOrNull { it.id == c.fromObservationId } ?: fail(ErrorCode.INVALID_INPUT,"Missing change basis")
        val b = s.observations.singleOrNull { it.id == c.toObservationId } ?: fail(ErrorCode.INVALID_INPUT,"Missing change end")
        audit(null,c.recordedAt,c.source)
        ensure(a.id != b.id && a.subject == b.subject && a.definition == b.definition && a.effectiveAt != null && b.effectiveAt != null && a.effectiveAt < b.effectiveAt,
            "Change needs ordered observations on one subject definition")
        ensure(b.value.number.subtract(a.value.number).compareTo(c.value.number) == 0,"Signed change differs from explicit basis")
    }
    corrections(s.changes,{it.id},{it.replacesId},{it.recordedAt}) { a,b ->
        s.observations.single { it.id == a.fromObservationId }.let { x -> s.observations.single { it.id == b.fromObservationId }.let { y -> x.subject == y.subject && x.definition == y.definition } }
    }
    s.sets.forEach {
        session(it.sessionId);audit(it.effectiveAt,it.recordedAt,it.source)
        ensure(it.ordinal >= 0 && it.repetitions > 0 && (it.implementCount == null || it.implementCount > 0),"Invalid set repetitions/order/implements")
        ensure(d.units.any { u -> u.id == it.loadUnitId && u.dimension.isNotBlank() },"Missing load unit")
        ensure(it.loadMeaning != LoadMeaning.TOTAL_EXTERNAL || it.implementCount == null,"Total load must not multiply implements")
    }
    corrections(s.sets,{it.id},{it.replacesId},{it.recordedAt}) { a,b -> a.sessionId == b.sessionId }
    val replacedSets = s.sets.mapNotNull { it.replacesId }.toSet()
    val activeSets = s.sets.filter { it.id !in replacedSets }
    ensure(activeSets.map { it.sessionId to it.ordinal }.distinct().size == activeSets.size,"Duplicate active set order")
    s.quantities.forEach { subject(it.subject);definition(it.definition,it.value);audit(it.effectiveAt,it.recordedAt,it.source);it.sessionId?.let { id ->
        session(id);ensure(s.links.any { l -> l.subject == it.subject && l.sessionId == id },"Quantity requires explicit subject/session link")
    } }
    corrections(s.quantities,{it.id},{it.replacesId},{it.recordedAt}) { a,b -> a.subject == b.subject && a.definition == b.definition && a.sessionId == b.sessionId }
    s.distanceTimes.forEach { input ->
        val m = d.measurements.singleOrNull { it.id == input.measurementId } ?: fail(ErrorCode.INVALID_INPUT,"Missing distance measurement")
        ensure(m.scope == input.scope && m.targetId == input.targetId && input.start in 0..MAX_TIME && input.end in input.start..MAX_TIME && input.durationMs in 0..(input.end-input.start) && input.source.isNotBlank(),"Invalid distance/time pairing")
        val unit = d.units.singleOrNull { it.id == m.unit } ?: fail(ErrorCode.INVALID_INPUT,"Missing distance unit")
        ensure(unit.dimension == "distance","Rate input must be distance")

    }
    ensure(s.distanceTimes.map { it.measurementId to it.timeBasis }.distinct().size == s.distanceTimes.size,"Duplicate rate pairing")
}

data class SubjectCount(val cardIds: Set<String>,val eventIds: Set<String>,val sessionIds: Set<String>)
enum class QuantityUnavailable { NO_OBSERVATION, CONFLICT, UNKNOWN_EFFECTIVE_TIME, DISCONTINUOUS_BASIS, SUPERSEDED_BASIS, INCOMPATIBLE_INPUTS, ZERO_DISTANCE, ZERO_TIME, CLIPPED_WINDOW, OVERLAPPING_TARGETS, TARGET_CHANGED, NO_INPUTS }
data class StateAt(val observations: List<StateObservation>,val value: Decimal?,val reason: QuantityUnavailable?)
data class SignedTotal(val value: SignedDecimal?,val changeIds: List<String>,val reason: QuantityUnavailable?)
data class SetTotal(val setIds: List<String>,val repetitions: Long,val volume: Decimal?,val unitId: String?,val reason: QuantityUnavailable?)
data class ExactRatio(val numerator: BigDecimal,val denominator: BigDecimal)
data class RateResult(val speedBaseUnitsPerMs: ExactRatio?,val paceMsPerBaseUnit: ExactRatio?,val measurementIds: List<String>,
    val inputIds: List<String>,val timeBasis: TimeBasis,val start: Long,val end: Long,val reason: QuantityUnavailable?)
data class QuantityWindow(val token: Token,val events: List<EventQuantity>,val unknownTime: List<EventQuantity>,val wholeTargets: List<Measurement>) {
    /** Only timed event quantities are additive across disjoint windows. Legacy values stay separate. */
    /** Combining daily rows unions provenance IDs; whole-session values remain whole-session. */
    fun combine(other: QuantityWindow): QuantityWindow {
        ensure(token == other.token,"Quantity windows belong to different revisions")
        fun <T> union(a: List<T>,b: List<T>,id: (T)->String): List<T> {
            val byId = a.associateBy(id)
            ensure(b.all { byId[id(it)] == null || byId[id(it)] == it },"Quantity windows belong to different revisions")
            return frozenList(a+b.filter { id(it) !in byId })
        }
        return QuantityWindow(token,union(events,other.events) { it.id },union(unknownTime,other.unknownTime) { it.id },union(wholeTargets,other.wholeTargets) { it.id })
    }
    fun totals() = events.groupBy { it.subject to it.definition }.mapValues { (_,q) -> q.fold(Decimal.ZERO) { sum,v -> sum+v.value } }
}
class SubjectRead internal constructor(private val d: Dataset) {
    private val s get() = d.subjects
    fun count(sessionIds: Set<String>): SubjectCount {
        val selections = d.sessions.filter { it.id in sessionIds }.map { it.selection }+d.segments.filter { it.sessionId in sessionIds }.map { it.selection }
        val explicit = s.links.filter { it.sessionId in sessionIds }.map { it.subject }
        return SubjectCount(frozenSet(selections.mapNotNull { it.cardId }.filter { id -> s.cards.any { it.cardId == id } }+explicit.filter { it.kind == SubjectKind.CARD }.map { it.id }),
            frozenSet(explicit.filter { it.kind == SubjectKind.EVENT }.map { it.id }),frozenSet(d.sessions.filter { it.id in sessionIds }.map { it.id }))
    }
    fun history(subject: SubjectRef,definition: QuantityDefinition) = frozenList(s.observations.filter { it.subject == subject && it.definition == definition }.sortedWith(compareBy({it.effectiveAt ?: Long.MAX_VALUE},{it.recordedAt},{it.id})))
    fun stateAt(subject: SubjectRef,definition: QuantityDefinition,at: Long): StateAt {
        ensure(at in 0..MAX_TIME,"Invalid state query time")
        val rows = history(subject,definition);val superseded = rows.mapNotNull { it.replacesId }.toSet()
        val active = rows.filter { it.id !in superseded && it.effectiveAt != null && it.effectiveAt <= at }
        val last = active.maxOfOrNull { it.effectiveAt!! }
        val candidates = frozenList(active.filter { it.effectiveAt == last })
        return StateAt(candidates,candidates.firstOrNull()?.value?.takeIf { v -> candidates.all { it.value == v } },when {
            candidates.isEmpty() -> if(rows.any { it.effectiveAt == null }) QuantityUnavailable.UNKNOWN_EFFECTIVE_TIME else QuantityUnavailable.NO_OBSERVATION
            candidates.map { it.value }.distinct().size > 1 -> QuantityUnavailable.CONFLICT
            else -> null
        })
    }
    fun changeTotal(ids: Set<String>): SignedTotal {
        val rows = s.changes.filter { it.id in ids };ensure(rows.size == ids.size,"Missing change")
        fun no(reason: QuantityUnavailable) = SignedTotal(null,frozenList(rows.map { it.id }),reason)
        if(rows.isEmpty()) return no(QuantityUnavailable.NO_INPUTS)
        val replaced = s.changes.mapNotNull { it.replacesId }.toSet();val oldObservations = s.observations.mapNotNull { it.replacesId }.toSet()
        if(rows.any { it.id in replaced || it.fromObservationId in oldObservations || it.toObservationId in oldObservations }) return no(QuantityUnavailable.SUPERSEDED_BASIS)
        val byId = s.observations.associateBy { it.id }
        val sorted = rows.sortedBy { byId.getValue(it.fromObservationId).effectiveAt }
        if(sorted.map { byId.getValue(it.fromObservationId).let { o -> o.subject to o.definition } }.distinct().size != 1) return no(QuantityUnavailable.INCOMPATIBLE_INPUTS)
        if(sorted.zipWithNext().any { (a,b) -> a.toObservationId != b.fromObservationId }) return no(QuantityUnavailable.DISCONTINUOUS_BASIS)
        if(sorted.any { c -> listOf(c.fromObservationId,c.toObservationId).any { id -> val o = byId.getValue(id);stateAt(o.subject,o.definition,o.effectiveAt!!).reason != null } }) return no(QuantityUnavailable.CONFLICT)
        return SignedTotal(SignedDecimal(rows.fold(BigDecimal.ZERO) { n,c -> n+c.value.number }.toPlainString()),frozenList(sorted.map { it.id }),null)
    }
    fun sets(sessionIds: Set<String>): List<ExerciseSet> { val old = s.sets.mapNotNull { it.replacesId }.toSet();return frozenList(s.sets.filter { it.sessionId in sessionIds && it.id !in old }.sortedWith(compareBy({it.sessionId},{it.ordinal},{it.id}))) }
    fun setTotal(ids: Set<String>): SetTotal {
        val active = sets(d.sessions.map { it.id }.toSet()).filter { it.id in ids };ensure(active.size == ids.size,"Missing or superseded set")
        val reps = active.fold(0L) { n,v -> Math.addExact(n,v.repetitions.toLong()) }
        val targetUnit = active.firstOrNull()?.loadUnitId
        fun no(reason: QuantityUnavailable) = SetTotal(frozenList(active.map { it.id }),reps,null,targetUnit,reason)
        if(active.isEmpty()) return no(QuantityUnavailable.NO_INPUTS)
        if(active.any { it.loadMeaning == LoadMeaning.PER_IMPLEMENT && it.implementCount == null }) return no(QuantityUnavailable.INCOMPATIBLE_INPUTS)
        val units = d.units.associateBy { it.id };if(active.map { units.getValue(it.loadUnitId).dimension }.distinct().size != 1) return no(QuantityUnavailable.INCOMPATIBLE_INPUTS)
        val volume = try { active.fold(BigDecimal.ZERO) { n,v -> n+d.convert(v.load,v.loadUnitId,targetUnit!!).number.multiply(BigDecimal(v.repetitions)).multiply(BigDecimal(if(v.loadMeaning == LoadMeaning.PER_IMPLEMENT) v.implementCount!! else 1)) } } catch(_: CoreError) { return no(QuantityUnavailable.INCOMPATIBLE_INPUTS) }
        return SetTotal(frozenList(active.map { it.id }),reps,Decimal(volume.toPlainString()),targetUnit,null)
    }
    fun window(start: Long,end: Long,sessionIds: Set<String>? = null): QuantityWindow {
        ensure(start >= 0 && end > start && end <= MAX_TIME,"Invalid quantity window")
        val old = s.quantities.mapNotNull { it.replacesId }.toSet()
        val quantities = s.quantities.filter { it.id !in old && (sessionIds == null || it.sessionId in sessionIds) }
        val sessions = d.segments.filter { (sessionIds == null || it.sessionId in sessionIds) && it.start < end && (it.end ?: it.observedUntil ?: it.start) > start }.map { it.sessionId }.toSet()
        val segments = d.segments.filter { it.sessionId in sessions && it.start < end && (it.end ?: it.observedUntil ?: it.start) > start }.map { it.id }.toSet()
        return QuantityWindow(token(d),frozenList(quantities.filter { it.effectiveAt != null && it.effectiveAt >= start && it.effectiveAt < end }),frozenList(quantities.filter { it.effectiveAt == null }),
            frozenList(d.measurements.filter { if(it.scope == Scope.SESSION) it.targetId in sessions else it.targetId in segments }))
    }
    fun rates(inputIds: Set<String>,start: Long,end: Long,basis: TimeBasis = TimeBasis.RECORDED): RateResult {
        ensure(start >= 0 && end > start && end <= MAX_TIME,"Invalid rate window")
        val inputs = s.distanceTimes.filter { it.id in inputIds };ensure(inputs.size == inputIds.size,"Missing rate input")
        val measurements = d.measurements.associateBy { it.id }
        fun no(r: QuantityUnavailable) = RateResult(null,null,frozenList(inputs.map { it.measurementId }),frozenList(inputs.map { it.id }),basis,start,end,r)
        if(inputs.isEmpty()) return no(QuantityUnavailable.NO_INPUTS)
        if(inputs.any { !distanceTimeMatches(d,it) }) return no(QuantityUnavailable.TARGET_CHANGED)
        if(inputs.any { it.timeBasis != basis }) return no(QuantityUnavailable.INCOMPATIBLE_INPUTS)
        if(inputs.any { it.start < start || it.end > end }) return no(QuantityUnavailable.CLIPPED_WINDOW)
        val targetSegments = inputs.map { input -> d.segments.filter { if(input.scope == Scope.SESSION) it.sessionId == input.targetId else it.id == input.targetId }.map { it.id }.toSet() }
        if(targetSegments.withIndex().any { (i,ids) -> targetSegments.take(i).any { (ids intersect it).isNotEmpty() } }) return no(QuantityUnavailable.OVERLAPPING_TARGETS)
        val distance = inputs.fold(BigDecimal.ZERO) { n,input -> val m = measurements.getValue(input.measurementId);val u = d.units.single { it.id == m.unit };n+m.value.number.multiply(u.factorToBase.number) }
        val duration = inputs.fold(BigDecimal.ZERO) { n,v -> n+BigDecimal(v.durationMs) }
        return RateResult(if(duration.signum() == 0) null else ExactRatio(distance,duration),if(distance.signum() == 0) null else ExactRatio(duration,distance),
            frozenList(inputs.map { it.measurementId }),frozenList(inputs.map { it.id }),basis,start,end,when { duration.signum() == 0 -> QuantityUnavailable.ZERO_TIME;distance.signum() == 0 -> QuantityUnavailable.ZERO_DISTANCE;else -> null })
    }
}

internal fun distanceTimeMatches(d: Dataset,input: DistanceTimeInput): Boolean {
    val segments = d.segments.filter { if(input.scope == Scope.SESSION) it.sessionId == input.targetId else it.id == input.targetId }
    if(segments.isEmpty() || segments.any { it.end == null || it.uncertain }) return false
    return input.start == segments.minOf { it.start } && input.end == segments.maxOf { it.end!! } &&
        (input.timeBasis != TimeBasis.RECORDED || input.durationMs == segments.sumExact { it.durationMs })
}

internal fun Dataset.quantityRecordedTimes(): List<Long> = subjects.observations.map { it.recordedAt }+subjects.changes.map { it.recordedAt }+subjects.sets.map { it.recordedAt }+subjects.quantities.map { it.recordedAt }
