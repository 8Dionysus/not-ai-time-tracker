package dev.notai.tracker

/** Reasons are separate from unknown occurrence time; neither contributes an invented amount. */
enum class QuantityReportExclusion { NO_SESSION_CLASSIFICATION, OUTSIDE_MATCHING_INTERVAL, AUDIT_AFTER_AS_OF, EFFECTIVE_AFTER_AS_OF, MIXED_WHOLE_TARGET }
data class ExcludedQuantity(val id: String,val reason: QuantityReportExclusion)
data class QuantityReport(val token: Token,val report: Report,val quantities: QuantityWindow,val excluded: List<ExcludedQuantity>)

/** Joins one immutable revision to its already captured report. Event totals use half-open rows,
 * never the session's latest classification. Whole-target measurements are never apportioned. */
object QuantityReports {
    fun build(data: Dataset,report: Report): QuantityReport {
        val filtered = report.filter.let { it.cardId != null || it.activityTypeId != null || it.details.isNotEmpty() || it.tags.isNotEmpty() || it.facets.isNotEmpty() }
        val excluded = mutableListOf<ExcludedQuantity>()
        val superseded = data.subjects.quantities.filter { it.recordedAt <= report.asOf }.mapNotNull { it.replacesId }.toSet()
        val rows = report.rows.groupBy { it.sessionId }
        val events = mutableListOf<EventQuantity>();val unknown = mutableListOf<EventQuantity>()
        data.subjects.quantities.filter { it.id !in superseded }.forEach { q ->
            val at = q.effectiveAt
            // Unknown-time entries remain independently visible, without period attribution.
            if(at != null && (at < report.start || at >= report.end)) return@forEach
            val reason = when {
                q.recordedAt > report.asOf -> QuantityReportExclusion.AUDIT_AFTER_AS_OF
                at != null && at > report.asOf -> QuantityReportExclusion.EFFECTIVE_AFTER_AS_OF
                filtered && q.sessionId == null -> QuantityReportExclusion.NO_SESSION_CLASSIFICATION
                filtered && at != null && rows[q.sessionId].orEmpty().none { at >= it.start && at < it.end } -> QuantityReportExclusion.OUTSIDE_MATCHING_INTERVAL
                filtered && at == null && rows[q.sessionId].isNullOrEmpty() -> QuantityReportExclusion.NO_SESSION_CLASSIFICATION
                else -> null
            }
            if(reason != null) excluded += ExcludedQuantity(q.id,reason)
            else if(at == null) unknown += q else events += q
        }
        val whole = report.measurements.filter { m ->
            val accepted = !filtered || m.scope == Scope.SEGMENT || data.segments.filter { it.sessionId == m.targetId }.let { segments ->
                segments.isNotEmpty() && segments.all { !it.uncertain && report.filter.matches(it.selection) }
            }
            if(!accepted) excluded += ExcludedQuantity(m.id,QuantityReportExclusion.MIXED_WHOLE_TARGET)
            accepted
        }
        val window = QuantityWindow(token(data),frozenList(events),frozenList(unknown),frozenList(whole))
        return QuantityReport(token(data),report,window,frozenList(excluded))
    }
}
