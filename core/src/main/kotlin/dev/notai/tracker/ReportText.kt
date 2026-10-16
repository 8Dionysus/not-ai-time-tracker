package dev.notai.tracker

import java.time.Instant

/** Pure deterministic copy/share text. No delivery API; notes require an explicit opt-in.
 * IDs, exact milliseconds and ISO instants avoid rounding, locale and duplicate-name ambiguity. */
object ReportText {
    fun build(report: Report,includeNotes: Boolean = false): String = buildString {
        fun quoted(value: String) = codec.encodeToString(value)
        append("Report [").append(Instant.ofEpochMilli(report.start)).append(", ").append(Instant.ofEpochMilli(report.end)).append(")\n")
        append("Zone: ").append(report.zone).append("; report day start minute: ").append(report.dayPolicy.startMinute).append('\n')
        val f = report.filter
        append("Filter: ").append(codec.encodeToString(f.copy(details = f.details.toSortedMap(),tags = f.tags.toSortedSet(),
            facets = f.facets.sortedBy { codec.encodeToString(it) }))).append('\n')
        append("As of: ").append(Instant.ofEpochMilli(report.asOf)).append("; incomplete: ").append(report.incomplete).append('\n')
        append("Recorded: ").append(report.recordedMs).append(" ms; positive days: ").append(report.recordedDays.size).append('\n')
        append("Activities (historical labels; exact recorded time):\n")
        report.rows.groupBy { Triple(it.selection.cardId,it.selection.activityTypeId,it.selection.name) }.entries
            .sortedWith(compareBy({ it.key.first ?: "" },{ it.key.second ?: "" },{ it.key.third ?: "" })).forEach { (key,rows) ->
                append("- card=").append(quoted(key.first ?: "")).append("; type=").append(quoted(key.second ?: ""))
                    .append("; name=").append(quoted(key.third ?: "")).append(": ").append(rows.sumExact { it.durationMs }).append(" ms\n")
            }
        append("Unresolved segments excluded from totals: ").append(report.unresolved.size).append("; period attribution unknown\n")
        report.unresolved.sortedBy { it.id }.forEach { append("- segment=").append(quoted(it.id)).append("; session=").append(quoted(it.sessionId)).append('\n') }
        if(includeNotes) {
            append("Notes (explicitly included):\n")
            val notes = report.rows.associate { it.sessionId to it.note }+report.unresolvedSessionNotes
            notes.toSortedMap().forEach { (id,note) -> if(note != null) append("- session=").append(quoted(id)).append(": ").append(quoted(note)).append('\n') }
        }
    }
}
