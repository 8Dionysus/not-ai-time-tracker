package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import java.time.*
import java.time.temporal.TemporalAdjusters

@Serializable enum class ReportWindowKind { FIXED, TRAILING_DAYS, CURRENT_WEEK, PREVIOUS_WEEK, CURRENT_MONTH, PREVIOUS_MONTH }
/** Relative windows include whole labelled days; current/future time stays marked incomplete. */
@Serializable data class ReportWindow(val kind: ReportWindowKind = ReportWindowKind.TRAILING_DAYS,val days: Int = 7,
    val firstDate: String? = null,val endDateExclusive: String? = null) {
    fun resolve(now: Long,policy: ReportingDayPolicy): Pair<Long,Long> {
        policy.validate()
        ensure(days in 1..3660,"Report trailing window must be 1..3660 days")
        ensure(kind == ReportWindowKind.FIXED || (firstDate == null && endDateExclusive == null),"Dates require fixed report window")
        val today = policy.dateAt(now)
        val first: LocalDate;val end: LocalDate
        try {
            when(kind) {
                ReportWindowKind.FIXED -> { first = LocalDate.parse(firstDate);end = LocalDate.parse(endDateExclusive) }
                ReportWindowKind.TRAILING_DAYS -> { first = today.minusDays(days.toLong()-1);end = today.plusDays(1) }
                ReportWindowKind.CURRENT_WEEK -> { first = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));end = first.plusWeeks(1) }
                ReportWindowKind.PREVIOUS_WEEK -> { end = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));first = end.minusWeeks(1) }
                ReportWindowKind.CURRENT_MONTH -> { first = today.withDayOfMonth(1);end = first.plusMonths(1) }
                ReportWindowKind.PREVIOUS_MONTH -> { end = today.withDayOfMonth(1);first = end.minusMonths(1) }
            }
            val a = policy.boundary(first);val b = policy.boundary(end)
            ensure(a >= 0 && b > a && b <= MAX_TIME,"Invalid saved report window",ErrorCode.INVALID_INTERVAL)
            return a to b
        } catch(e: CoreError) { throw e }
        catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid saved report dates") }
    }
}
@Serializable enum class ReportGrouping { NONE, CARD, TYPE, FACET, DETAIL }
@Serializable data class SavedReportQuery(@Required val id: String = newId(),val name: String,val window: ReportWindow = ReportWindow(),
    val filter: Filter = Filter(),val grouping: ReportGrouping = ReportGrouping.CARD,val groupKey: String? = null,
    val policy: ReportingDayPolicy = ReportingDayPolicy())
enum class ReportReferenceStatus { AVAILABLE, ARCHIVED, MISSING }
data class ReportReference(val kind: String,val id: String,val status: ReportReferenceStatus)
data class SavedReportResult(val query: SavedReportQuery,val source: Token,val report: Report,
    val references: List<ReportReference>,val groupedMs: Map<String?,Long>)
object SavedReports {
    fun open(data: Dataset,now: Moment,id: String): SavedReportResult {
        validateMoment(now)
        val q = data.savedReports.singleOrNull { it.id == id } ?: fail(ErrorCode.INVALID_INPUT,"Missing saved report")
        val (a,b) = q.window.resolve(now.wall,q.policy)
        val report = Reports.report(data,now,a,b,q.filter,q.policy.zone,q.policy.startMinute)
        val grouped: Map<String?,Long> = when(q.grouping) {
            ReportGrouping.NONE -> mapOf(null to report.recordedMs)
            ReportGrouping.CARD -> report.byCard()
            ReportGrouping.TYPE -> report.byType()
            ReportGrouping.FACET -> report.byFacet(q.groupKey!!).mapKeys { it.key?.let { f -> codec.encodeToString(f) } }
            ReportGrouping.DETAIL -> report.byDetail(q.groupKey!!)
        }
        return SavedReportResult(q,token(data),report,frozenList(references(data,q)),frozenMap(grouped))
    }
    /** Stable IDs and historical path snapshots drive matching. Reparenting never rewrites a query.
     * Missing/archived references remain inspectable and are never widened to an unfiltered query. */
    fun references(data: Dataset,q: SavedReportQuery): List<ReportReference> = buildList {
        q.filter.cardId?.let { id -> val c = data.cards.find { it.id == id };add(ReportReference("card",id,if(c == null) ReportReferenceStatus.MISSING else if(c.archived) ReportReferenceStatus.ARCHIVED else ReportReferenceStatus.AVAILABLE)) }
        q.filter.activityTypeId?.let { id -> val t = data.types.find { it.id == id };add(ReportReference("type",id,if(t == null) ReportReferenceStatus.MISSING else if(t.archived) ReportReferenceStatus.ARCHIVED else ReportReferenceStatus.AVAILABLE)) }
        q.filter.facets.distinct().sortedBy { codec.encodeToString(it) }.forEach { f ->
            val axis = data.axes.find { it.id == f.axisId && it.meaningVersion == f.meaningVersion }
            add(ReportReference("axis-version","${f.axisId}:${f.meaningVersion}",if(axis == null) ReportReferenceStatus.MISSING else if(axis.archived) ReportReferenceStatus.ARCHIVED else ReportReferenceStatus.AVAILABLE))
            f.valueId?.let { id ->
                val value = axis?.values?.find { it.id == id && it.meaningVersion == f.valueMeaningVersion }
                add(ReportReference("facet-value","${f.axisId}:${f.meaningVersion}:$id:${f.valueMeaningVersion}",if(value == null) ReportReferenceStatus.MISSING else if(value.archived || axis?.archived == true) ReportReferenceStatus.ARCHIVED else ReportReferenceStatus.AVAILABLE))
            }
        }
        q.groupKey?.takeIf { q.grouping == ReportGrouping.FACET }?.let { id ->
            val axis = data.axes.filter { it.id == id }.maxByOrNull { it.meaningVersion }
            add(ReportReference("grouping-axis",id,if(axis == null) ReportReferenceStatus.MISSING else if(axis.archived) ReportReferenceStatus.ARCHIVED else ReportReferenceStatus.AVAILABLE))
        }
    }
}
internal fun Filter.frozenFilter() = copy(details = frozenMap(details),tags = frozenSet(tags),facets = frozenList(facets))
internal fun validateSavedReports(data: Dataset) {
    ensure(data.savedReports.map { it.id }.distinct().size == data.savedReports.size,"Duplicate saved report ID")
    data.savedReports.forEach { q ->
        ensure(q.id.isNotBlank() && q.name.isNotBlank(),"Saved report needs ID/name")
        q.policy.validate()
        // Relative windows depend on open time; validate their shape at a safe supported anchor.
        q.window.resolve(Instant.parse("2000-06-15T12:00:00Z").toEpochMilli(),q.policy)
        ensure(if(q.grouping in setOf(ReportGrouping.FACET,ReportGrouping.DETAIL)) !q.groupKey.isNullOrBlank() else q.groupKey == null,"Invalid saved report grouping key")
        val f = q.filter
        ensure(f.cardId == null || f.cardId.isNotBlank(),"Empty saved report card")
        ensure(f.activityTypeId == null || f.activityTypeId.isNotBlank(),"Empty saved report type")
        ensure(f.details.all { it.key.isNotBlank() && it.value.isNotBlank() } && f.tags.all { it.isNotBlank() },"Invalid saved report filter")
        ensure(f.facets.distinct().size == f.facets.size && f.facets.all { it.axisId.isNotBlank() && ((!it.valueId.isNullOrBlank() && (it.valueMeaningVersion ?: 0) > 0 && it.text == null) || (!it.text.isNullOrBlank() && it.valueId == null && it.valueMeaningVersion == null)) && it.meaningVersion > 0 },"Invalid saved report facets")
    }
}

/** Includes retained source/Undo snapshots: a legacy label must never hide a new contract. */
fun Dataset.hasReportingExtensions(): Boolean = settings.reportDayStartMinute != 0 || savedReports.isNotEmpty() ||
    goals.any { it.hasReportingExtensions() } || transfer.origins.any { it.localGroup.hasReportingExtensions() || it.compaction?.localEntities?.any { e -> e.kind == "report" } == true } ||
    transfer.operations.any { op -> op.groups.any { g -> g.group.entities.any { it.kind == "report" } || g.group.content.hasReportingExtensions() || g.inserted?.hasReportingExtensions() == true || g.previousOrigin?.localGroup?.hasReportingExtensions() == true || g.previousOrigin?.compaction?.localEntities?.any { it.kind == "report" } == true } }

internal fun Dataset.goalRevisionTimes(): Sequence<Long> = sequence {
    goals.forEach { g -> g.revisions.forEach { yield(it.recordedAt) } }
    fun TransferContent.times() = goals.asSequence().flatMap { it.revisions.asSequence() }.map { it.recordedAt }
    transfer.origins.forEach { yieldAll(it.localGroup.times()) }
    transfer.operations.forEach { op -> op.groups.forEach { g ->
        yieldAll(g.group.content.times());g.inserted?.let { yieldAll(it.times()) };g.previousOrigin?.let { yieldAll(it.localGroup.times()) }
    } }
}
