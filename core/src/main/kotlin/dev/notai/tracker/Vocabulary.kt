package dev.notai.tracker

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Exact decimal, encoded as a string. No binary floating point enters persistence or totals. */
@Serializable(with = DecimalSerializer::class)
class Decimal(text: String) {
    val number: BigDecimal
    init {
        ensure(text.length <= 1024 && text.matches(Regex("[0-9]+(\\.[0-9]+)?")),"Use a nonnegative plain decimal string")
        number = BigDecimal(text).stripTrailingZeros()
    }
    override fun toString(): String = number.toPlainString()
    override fun equals(other: Any?) = other is Decimal && number.compareTo(other.number) == 0
    override fun hashCode() = number.hashCode()
    operator fun plus(other: Decimal) = Decimal(number.add(other.number).toPlainString())
    companion object { val ZERO = Decimal("0") }
}
object DecimalSerializer: KSerializer<Decimal> {
    override val descriptor = PrimitiveSerialDescriptor("ExactDecimal",PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder,value: Decimal) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = Decimal(decoder.decodeString())
}
@Serializable enum class ValueKind { FREE_LABEL, LOCAL_CHOICE }
@Serializable enum class Cardinality { SINGLE, MULTIPLE }
@Serializable data class AxisValue(val id: String,val label: String,val meaningVersion: Int = 1,val archived: Boolean = false)
@Serializable data class Axis(val id: String,val name: String,val meaningVersion: Int = 1,
    val valueKind: ValueKind,val cardinality: Cardinality = Cardinality.SINGLE,val values: List<AxisValue> = emptyList(),val archived: Boolean = false,
    val researchOrigins: List<ResearchOrigin> = emptyList())
@Serializable data class Facet(val axisId: String,val meaningVersion: Int = 1,val valueId: String? = null,
    val valueMeaningVersion: Int? = null,val text: String? = null)
@Serializable enum class NumberKind { INTEGER, DECIMAL }
@Serializable enum class Aggregation { SUM, BY_TARGET }
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class Metric(val id: String,val name: String,val meaningVersion: Int = 1,
    val numberKind: NumberKind,val canonicalUnitId: String,val maxScale: Int = 0,
    val scopes: Set<Scope> = setOf(Scope.SESSION,Scope.SEGMENT),val aggregation: Aggregation = Aggregation.BY_TARGET,
    val researchOrigins: List<ResearchOrigin> = emptyList(),@kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val bounds: ScaleBounds? = null)
@Serializable data class UnitDefinition(val id: String,val name: String,val dimension: String,
    val factorToBase: Decimal = Decimal("1"))

fun Dataset.ancestorPath(typeId: String): List<String> = ancestorPath(types.associateBy { it.id },typeId)
private fun ancestorPath(nodes: Map<String,ActivityType>,typeId: String): List<String> {
    val path = mutableListOf<String>(); val seen = mutableSetOf<String>()
    var id: String? = typeId
    while (id != null) {
        ensure(seen.add(id),"Activity tree contains a cycle")
        val node = nodes[id] ?: fail(ErrorCode.INVALID_INPUT,"Missing activity parent/type")
        path += id; id = node.parentId
    }
    return path.asReversed()
}
/** Resolve only on a new explicit classification; existing history is never re-walked by reports. */
fun Dataset.classify(selection: Classification): Classification = selection.copy(
    ancestorPathAtWrite = selection.activityTypeId?.let { ancestorPath(it) }.orEmpty())
fun Dataset.children(parentId: String? = null) = types.filter { it.parentId == parentId && !it.archived }
@Deprecated("Use Tracker.choices().search for indexed, paged discovery; this flat compatibility helper keeps contains semantics")
fun Dataset.searchTypes(query: String) = types.filter { !it.archived && (listOf(it.name)+it.aliases).any { name -> name.contains(query,ignoreCase = true) } }

fun Dataset.cardFromType(typeId: String): Card {
    val type = types.find { it.id == typeId && !it.archived && it.selectable } ?: fail(ErrorCode.INVALID_INPUT,"Type missing or archived")
    return Card(name = type.name,templateId = type.id,activityTypeId = type.id)
}

internal fun validateVocabulary(d: Dataset) {
    val nodes = d.types.associateBy { it.id }
    val axes = d.axes.map { it.id }.toSet(); val metrics = d.metrics.map { it.id }.toSet()
    d.types.forEach { t ->
        ensure(t.name.isNotBlank() && t.meaningVersion > 0,"Invalid type")
        validateResearchOrigins(t.researchOrigins)
        ancestorPath(nodes,t.id)
        ensure(t.suggestedAxisIds.all { id -> id in axes },"Unknown suggested axis")
        ensure(t.suggestedMetricIds.all { id -> id in metrics },"Unknown suggested metric")
    }
    ensure(d.axes.map { it.id to it.meaningVersion }.distinct().size == d.axes.size,"Duplicate axis version")
    d.axes.forEach { a ->
        validateResearchOrigins(a.researchOrigins)
        ensure(a.id.isNotBlank() && a.name.isNotBlank() && a.meaningVersion > 0,"Invalid axis")
        ensure(a.values.map { it.id to it.meaningVersion }.distinct().size == a.values.size,"Duplicate axis value version")
        a.values.forEach { ensure(it.id.isNotBlank() && it.label.isNotBlank() && it.meaningVersion > 0,"Invalid value") }
        ensure(a.valueKind != ValueKind.FREE_LABEL || a.values.isEmpty(),"Free-label axis has no choice registry")
    }
    ensure(d.units.map { it.id }.distinct().size == d.units.size,"Duplicate unit")
    d.units.forEach { ensure(it.id.isNotBlank() && it.name.isNotBlank() && it.dimension.isNotBlank() && it.factorToBase.number.signum() > 0,"Invalid unit") }
    ensure(d.metrics.map { it.id to it.meaningVersion }.distinct().size == d.metrics.size,"Duplicate metric version")
    d.metrics.forEach { m ->
        validateResearchOrigins(m.researchOrigins)
        ensure(m.id.isNotBlank() && m.name.isNotBlank() && m.meaningVersion > 0 && m.maxScale in 0..1000 && m.scopes.isNotEmpty(),"Invalid metric")
        ensure(d.units.any { it.id == m.canonicalUnitId },"Missing canonical unit")
        m.bounds?.let { ensure(it.min.number <= it.max.number && it.minMeaning.isNotBlank() && it.maxMeaning.isNotBlank() && maxOf(0,it.min.number.scale(),it.max.number.scale()) <= m.maxScale,"Invalid scale bounds") }
        ensure(m.numberKind != NumberKind.INTEGER || m.maxScale == 0,"Integer metric cannot have fractional precision")
    }
}
internal fun validateFacets(d: Dataset,facets: List<Facet>) {
    ensure(facets.distinct().size == facets.size,"Duplicate facet")
    facets.groupBy { it.axisId to it.meaningVersion }.forEach { (key,values) ->
        val axis = d.axes.find { it.id == key.first && it.meaningVersion == key.second } ?: fail(ErrorCode.INVALID_INPUT,"Unknown axis version")
        ensure(axis.cardinality == Cardinality.MULTIPLE || values.size == 1,"Single-valued axis")
        values.forEach { f -> when(axis.valueKind) {
            ValueKind.FREE_LABEL -> ensure(!f.text.isNullOrBlank() && f.valueId == null && f.valueMeaningVersion == null,"Free label requires text only")
            ValueKind.LOCAL_CHOICE -> ensure(f.text == null && axis.values.any { it.id == f.valueId && it.meaningVersion == f.valueMeaningVersion },"Unknown choice/version")
        } }
    }
}
internal fun validateMeasurement(d: Dataset,m: Measurement) {
    // Unregistered v1/user fields retain their own explicit ID/version/unit; no guessed mapping.
    val metric = d.metrics.find { it.id == m.fieldId && it.meaningVersion == m.meaningVersion } ?: return
    ensure(m.unit == metric.canonicalUnitId && m.scope in metric.scopes,"Metric unit/scope mismatch")
    metric.bounds?.let { ensure(m.value.number >= it.min.number && m.value.number <= it.max.number,"Measurement outside scale bounds") }
    ensure(maxOf(0,m.value.number.scale()) <= metric.maxScale,"Metric precision exceeded")
}
/** Explicit conversion; unrelated dimensions or nonterminating conversions are rejected, never rounded. */
fun Dataset.convert(value: Decimal,fromUnitId: String,toUnitId: String): Decimal {
    val from = units.find { it.id == fromUnitId } ?: fail(ErrorCode.INVALID_INPUT,"Unknown source unit")
    val to = units.find { it.id == toUnitId } ?: fail(ErrorCode.INVALID_INPUT,"Unknown target unit")
    ensure(from.dimension == to.dimension,"Incompatible units")
    return try { Decimal(value.number.multiply(from.factorToBase.number).divide(to.factorToBase.number).toPlainString()) }
    catch (_: ArithmeticException) { fail(ErrorCode.INVALID_INPUT,"Exact conversion is nonterminating. Nothing was saved; keep the input draft and enter an explicitly chosen value in the target unit") }
}

/** Versioned dictionaries are data, independent of tree size, depth and UI layout. */
@Serializable data class VocabularyData(val types: List<ActivityType>,val axes: List<Axis> = emptyList(),
    val metrics: List<Metric> = emptyList(),val units: List<UnitDefinition> = emptyList())


enum class CatalogEntryStatus { ADDED, UNCHANGED, UPDATED, CONFLICT }
data class CatalogEntryOutcome(val kind: String,val id: String,val meaningVersion: Int?,val status: CatalogEntryStatus)
data class CatalogImportReport(val entries: List<CatalogEntryOutcome>) {
    val added get() = frozenList(entries.filter { it.status == CatalogEntryStatus.ADDED })
    val unchanged get() = frozenList(entries.filter { it.status == CatalogEntryStatus.UNCHANGED })
    val updated get() = frozenList(entries.filter { it.status == CatalogEntryStatus.UPDATED })
    val conflicts get() = frozenList(entries.filter { it.status == CatalogEntryStatus.CONFLICT })
}
enum class CatalogInstallPolicy { STRICT, PRESERVE_LOCAL_PRESENTATION }
/** Technical origin revisions and local presentation do not change an activity's meaning.
 * Keep the installed entry intact; definitions, versions and references still compare exactly.
 */
fun catalogMeaningEquivalent(previous: Any,entry: Any): Boolean = when {
    previous is ActivityType && entry is ActivityType -> previous.copy(name = entry.name,archived = entry.archived,
        aliases = entry.aliases,researchOrigins = entry.researchOrigins) == entry
    previous is Axis && entry is Axis -> {
        val oldValues = previous.values.associateBy { it.id to it.meaningVersion }
        previous.values.size == entry.values.size && oldValues.keys == entry.values.map { it.id to it.meaningVersion }.toSet() &&
            previous.copy(name = entry.name,archived = entry.archived,researchOrigins = entry.researchOrigins,
                values = entry.values.map { fresh -> oldValues.getValue(fresh.id to fresh.meaningVersion).copy(label = fresh.label,archived = fresh.archived) }) == entry
    }
    previous is Metric && entry is Metric -> previous.copy(name = entry.name,researchOrigins = entry.researchOrigins) == entry
    previous is UnitDefinition && entry is UnitDefinition -> previous.copy(name = entry.name) == entry
    else -> false
}
private fun editorialReplacement(previous: Any,entry: Any,revisions: List<CatalogEditorialRevision>,inUse: (String)->Boolean): Any? {
    if(previous is Axis && entry is Axis) {
        val revision = revisions.singleOrNull { it.kind == "axis" && it.id == entry.id } ?: return null
        val current = catalogMeaningFingerprint(entry)
        if(current != revision.toMeaningSha256 || catalogMeaningFingerprint(previous) != revision.fromMeaningSha256 ||
            !catalogMeaningEquivalent(previous,entry)) return null
        return previous.copy(name = if(revision.previousDefaultNameSha256 != null &&
            sha256(previous.name.toByteArray(Charsets.UTF_8)) == revision.previousDefaultNameSha256) entry.name else previous.name).takeUnless { it == previous }
    }
    if(previous !is ActivityType || entry !is ActivityType) return null
    val revision = revisions.singleOrNull { it.kind == "type" && it.id == entry.id } ?: return null
    val current = catalogMeaningFingerprint(entry);val before = catalogMeaningFingerprint(previous)
    val accepted = setOf(revision.fromMeaningSha256)+revision.alsoFromMeaningSha256
    val base = previous.copy(definition = entry.definition,parentId = if(revision.parentMove) entry.parentId else previous.parentId)
    if(current != revision.toMeaningSha256 || (before !in accepted && before != current) || !catalogMeaningEquivalent(base,entry)) return null
    val defaults = listOfNotNull(revision.previousDefaultNameSha256)+revision.previousDefaultNameSha256s
    // Names and aliases can be customized independently. Keep installed aliases even when the default name changes.
    val renamed = sha256(previous.name.toByteArray(Charsets.UTF_8)) in defaults
    val updated = base.copy(name = if(renamed) entry.name else previous.name,
        aliases = if(renamed) (entry.aliases + previous.aliases).distinct() else previous.aliases,
        archived = previous.archived || (revision.archive && entry.archived && !inUse(entry.id)))
    return updated.takeUnless { it == previous }
}
internal fun Dataset.mergeCatalog(incoming: VocabularyData,policy: CatalogInstallPolicy = CatalogInstallPolicy.STRICT,
    revisions: List<CatalogEditorialRevision> = emptyList()): Pair<Dataset,CatalogImportReport> {
    val outcomes = mutableListOf<CatalogEntryOutcome>()
    // A retired type stays live wherever the user's own data names it. The check reads every record outside the vocabulary,
    // so a new kind of reference can never be missed; it runs only when the catalog actually retires something.
    val references by lazy { codec.encodeToString(Dataset.serializer(),copy(types = emptyList(),axes = emptyList(),metrics = emptyList(),units = emptyList())) }
    val inUse: (String)->Boolean = { id -> references.contains(kotlinx.serialization.json.JsonPrimitive(id).toString()) }
    fun <T> merge(kind: String,old: List<T>,fresh: List<T>,key: (T)->Pair<String,Int?>): List<T> {
        val keys = fresh.map(key)
        ensure(keys.distinct().size == keys.size,"Duplicate $kind key in incoming catalog")
        val existing = old.associateBy(key)
        val replacements = mutableMapOf<Pair<String,Int?>,T>()
        fresh.forEach { entry ->
            val (id,version) = key(entry); val previous = existing[id to version]
            val replacement = if(previous != null && policy == CatalogInstallPolicy.PRESERVE_LOCAL_PRESENTATION)
                editorialReplacement(previous as Any,entry as Any,revisions,inUse) else null
            @Suppress("UNCHECKED_CAST")
            if(replacement != null) replacements[id to version] = replacement as T
            outcomes += CatalogEntryOutcome(kind,id,version,when {
                previous == null -> CatalogEntryStatus.ADDED
                previous == entry -> CatalogEntryStatus.UNCHANGED
                replacement != null -> CatalogEntryStatus.UPDATED
                policy == CatalogInstallPolicy.PRESERVE_LOCAL_PRESENTATION && catalogMeaningEquivalent(previous as Any,entry as Any) -> CatalogEntryStatus.UNCHANGED
                else -> CatalogEntryStatus.CONFLICT
            })
        }
        return (if(replacements.isEmpty()) old else old.map { replacements[key(it)] ?: it }) + fresh.filter { key(it) !in existing }
    }
    val result = copy(types = merge("type",types,incoming.types) { it.id to null },
        axes = merge("axis",axes,incoming.axes) { it.id to it.meaningVersion },
        metrics = merge("metric",metrics,incoming.metrics) { it.id to it.meaningVersion },
        units = merge("unit",units,incoming.units) { it.id to null })
    // Validate incoming definitions even when an existing local key will win a conflict.
    val candidate = copy(types = types.filterNot { old -> incoming.types.any { it.id == old.id } } + incoming.types,
        axes = axes.filterNot { old -> incoming.axes.any { it.id == old.id && it.meaningVersion == old.meaningVersion } } + incoming.axes,
        metrics = metrics.filterNot { old -> incoming.metrics.any { it.id == old.id && it.meaningVersion == old.meaningVersion } } + incoming.metrics,
        units = units.filterNot { old -> incoming.units.any { it.id == old.id } } + incoming.units)
    validateVocabulary(candidate)
    validate(result)
    return result to CatalogImportReport(frozenList(outcomes))
}

/** A form value is returned intact on conversion/precision failure. No write, fallback unit or rounding. */
data class MeasurementDraft(val metricId: String,val meaningVersion: Int,val value: Decimal,val unitId: String,
    val scope: Scope,val targetId: String)
data class PreparedMeasurement(val draft: MeasurementDraft,val measurement: Measurement?,val error: String?)
fun Dataset.prepareMeasurement(draft: MeasurementDraft): PreparedMeasurement {
    val metric = metrics.find { it.id == draft.metricId && it.meaningVersion == draft.meaningVersion }
        ?: return PreparedMeasurement(draft,null,"Unknown metric/version; keep the draft and choose a defined metric")
    return try {
        val converted = convert(draft.value,draft.unitId,metric.canonicalUnitId)
        val measurement = Measurement(fieldId = metric.id,meaningVersion = metric.meaningVersion,
            unit = metric.canonicalUnitId,scope = draft.scope,targetId = draft.targetId,value = converted)
        validateMeasurement(this,measurement)
        PreparedMeasurement(draft,measurement,null)
    } catch (e: CoreError) {
        PreparedMeasurement(draft,null,"${e.message}. Nothing was saved. Keep this draft; explicitly enter a value in ${metric.canonicalUnitId} within scale ${metric.maxScale}, or cancel")
    }
}

internal fun validateResearchOrigins(origins: List<ResearchOrigin>) {
    ensure(origins.distinct().size == origins.size,"Duplicate research origin")
    origins.forEach { origin ->
        ensure(listOf(origin.packageSha256,origin.sourceSha256).all { it.matches(Regex("[0-9a-f]{64}")) } &&
            origin.sourceFile.isNotBlank() && origin.sourceId.isNotBlank() && origin.pointer.startsWith("/"),"Invalid research provenance")
    }
}
