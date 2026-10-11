package dev.notai.tracker

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Serializable data class ImportOrigin(val namespace: String,val recordKey: String,val rowHash: String,
    val fileHash: String,val zone: String,val fields: Map<String,String>)
data class SourceActivity(val activity: String,val category: String)
enum class DurationFormat { HMS, DECIMAL_HOURS, SECONDS, MILLISECONDS }
data class CsvMapping(val namespace: String,val activity: String,val start: String,
    val end: String? = null,val duration: String? = null,val durationFormat: DurationFormat = DurationFormat.HMS,
    val category: String? = null,val note: String? = null,val id: String? = null,
    val startTime: String? = null,val endTime: String? = null,
    val localPattern: String? = null,val zone: String = "UTC",val delimiter: Char = ',',val clockToleranceMs: Long = 0) {
    companion object {
        /** Confirmed Toggl time-entry CSV columns. Date pattern/zone must match the exported profile.
         * Summary reports, decimal durations and localized headers need an explicit CsvMapping.
         */
        fun toggl(namespace: String,zone: String,datePattern: String = "uuuu-MM-dd") = CsvMapping(namespace,
            activity = "Project",category = "Client",note = "Description",start = "Start date",startTime = "Start time",
            duration = "Duration",localPattern = "$datePattern HH:mm:ss",zone = zone)
        fun portable(namespace: String) = CsvMapping(namespace,activity = "activity",category = "category",note = "note",
            id = "id",start = "start",end = "end",duration = "duration_ms",durationFormat = DurationFormat.MILLISECONDS,clockToleranceMs = 1000)
    }
}
data class TransferRow(val number: Int,val activity: SourceActivity,val start: Long,val end: Long,
    val note: String?,val origin: ImportOrigin,val durationMs: Long)
class TransferFile internal constructor(val rows: List<TransferRow>,val errors: List<String>,val warnings: List<String>) {
    val activities get() = rows.map { it.activity }.distinct()
}
class HistoryPreview internal constructor(val expected: Token,val file: TransferFile,
    internal val sessions: List<Session>,internal val segments: List<Segment>,val duplicates: Int,val errors: List<String>) {
    val added get() = sessions.size
    val canApply get() = errors.isEmpty()
    val warnings get() = file.warnings
    val intervals get() = segments.toList()
}
data class HistoryImportResult(val state: Snapshot,val added: Int,val duplicates: Int,val rollbackReference: String?)
data class HistoryExport(val csv: String,val warnings: List<String>)

/** Local, bounded transfer. No network, no guessing date formats, zones, names or overlap resolution. */
class HistoryTransfer(private val tracker: Tracker,private val rollback: RollbackStore) {
    fun preview(file: TransferFile,mapping: Map<SourceActivity,Classification>): HistoryPreview {
        val d = tracker.data(); val now = tracker.clock.now()
        val errors = file.errors.toMutableList(); val sessions = mutableListOf<Session>(); val segments = mutableListOf<Segment>()
        val known = d.sessions.associateBy { it.id }.toMutableMap(); var duplicates = 0
        for(row in file.rows) {
            val id = "import:"+researchHash(codec.encodeToString(listOf(row.origin.namespace,row.origin.recordKey)).toByteArray())
            val previous = known[id]
            if(previous != null) {
                if(previous.importOrigin?.rowHash == row.origin.rowHash) duplicates++
                else errors += "Row ${row.number}: source ID already exists with different content; resolve explicitly"
                continue
            }
            try {
                ensure(row.end <= now.wall,"Future imported interval",ErrorCode.INVALID_INTERVAL)
                val selected = d.classify(mapping[row.activity] ?: fail(ErrorCode.INVALID_INPUT,"Choose mapping for ${row.activity}"))
                val session = Session(id,SessionStatus.STOPPED,selected,row.note,row.origin)
                val segment = Segment("$id:segment",id,row.start,row.end,row.durationMs,selected)
                sessions += session; segments += segment; known[id] = session
            } catch(e: CoreError) { errors += "Row ${row.number}: ${e.message}" }
        }
        if(errors.isEmpty()) try { Backups.encode(d.copy(sessions = d.sessions+sessions,segments = d.segments+segments),now) }
        catch(e: CoreError) { errors += "${e.code}: ${e.message}; no rows will be applied" }
        return HistoryPreview(token(d),file,sessions.toList(),segments.toList(),duplicates,errors.toList())
    }
    fun apply(preview: HistoryPreview): HistoryImportResult = storage {
        ensure(preview.canApply,"Resolve preview errors before applying",ErrorCode.UNSUPPORTED_IMPORT)
        val before = tracker.data(); checkToken(before,preview.expected,tracker.clock.now())
        if(preview.added == 0) return@storage HistoryImportResult(tracker.snapshot(),0,preview.duplicates,null)
        val backup = Backups.encode(before,tracker.clock.now())
        val reference = rollback.save(backup)
        ensure(rollback.read(reference) == backup,"Transfer rollback verification failed",ErrorCode.STORAGE)
        Backups.decode(backup)
        val change = tracker.importHistory(preview.expected,preview.sessions,preview.segments)
        HistoryImportResult(change.state,preview.added,preview.duplicates,reference)
    }
    companion object {
        fun parseToggl(text: String,namespace: String,zone: String,datePattern: String = "uuuu-MM-dd"): TransferFile {
            val bytes = checkedCsvBytes(text)
            val table = CsvTable.parse(text,',')
            val headers = table.firstOrNull().orEmpty()
            val hasEnd = "End date" in headers || "End time" in headers
            val mapping = CsvMapping.toggl(namespace,zone,datePattern).let {
                if(hasEnd) it.copy(end = "End date",endTime = "End time") else it
            }
            return parseTable(table,mapping,researchHash(bytes))
        }
        fun parseCsv(text: String,mapping: CsvMapping): TransferFile {
            val bytes = checkedCsvBytes(text)
            return parseTable(CsvTable.parse(text,mapping.delimiter),mapping,researchHash(bytes))
        }
        private fun checkedCsvBytes(text: String): ByteArray {
            // UTF-8 needs at least as many bytes as UTF-16 code units. Bound encoding too.
            ensure(text.length <= MAX_BACKUP_BYTES,"CSV exceeds 10 MiB",ErrorCode.UNSUPPORTED_IMPORT)
            return text.toByteArray(Charsets.UTF_8).also {
                ensure(it.size <= MAX_BACKUP_BYTES,"CSV exceeds 10 MiB",ErrorCode.UNSUPPORTED_IMPORT)
            }
        }
        private fun parseTable(table: List<List<String>>,mapping: CsvMapping,fileHash: String): TransferFile {
            ensure(mapping.namespace.isNotBlank(),"Stable source account/workspace namespace is required")
            ensure(mapping.clockToleranceMs in 0..1000,"Unsupported clock tolerance")
            ensure(table.isNotEmpty(),"Empty CSV",ErrorCode.UNSUPPORTED_IMPORT)
            val headers = table.first()
            ensure(headers.all { it.isNotBlank() } && headers.distinct().size == headers.size,"Duplicate/empty CSV header",ErrorCode.UNSUPPORTED_IMPORT)
            val required = listOfNotNull(mapping.activity,mapping.start,mapping.end,mapping.duration,mapping.category,mapping.note,mapping.id,mapping.startTime,mapping.endTime)
            ensure(headers.containsAll(required),"Missing columns: ${required.filterNot { it in headers }}",ErrorCode.UNSUPPORTED_IMPORT)
            ensure(mapping.end != null || mapping.duration != null,"End or duration is required")
            val zone = try { ZoneId.of(mapping.zone) } catch(e: Exception) { fail(ErrorCode.INVALID_INPUT,"Unknown source zone") }
            val format = mapping.localPattern?.let { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }
            val rows = mutableListOf<TransferRow>(); val errors = mutableListOf<String>()
            fun timestamp(value: String): Long {
                val instant = if(format == null) OffsetDateTime.parse(value).toInstant() else {
                    val local = LocalDateTime.parse(value,format)
                    val offsets = zone.rules.getValidOffsets(local)
                    ensure(offsets.size == 1,"Ambiguous/nonexistent local time; provide explicit-offset timestamps",ErrorCode.INVALID_INTERVAL)
                    local.toInstant(offsets.single())
                }
                ensure(instant.nano % 1_000_000 == 0,"Sub-millisecond precision is unsupported")
                return instant.toEpochMilli()
            }
            for((index,cells) in table.drop(1).withIndex()) {
                if(cells.size == 1 && cells.single().isEmpty()) continue
                try {
                    ensure(cells.size == headers.size,"Column count mismatch")
                    val fields = headers.zip(cells).toMap()
                    fun get(key: String?) = key?.let { fields.getValue(it) }.orEmpty()
                    val start = timestamp(get(mapping.start)+(mapping.startTime?.let { " "+get(it) } ?: ""))
                    val duration = mapping.duration?.let { duration(get(it),mapping.durationFormat) }
                    val end = mapping.end?.let { timestamp(get(it)+(mapping.endTime?.let { key -> " "+get(key) } ?: "")) }
                        ?: Math.addExact(start,requireNotNull(duration))
                    ensure(start >= 0 && end > start && end <= MAX_TIME,"Invalid interval",ErrorCode.INVALID_INTERVAL)
                    ensure(duration == null || kotlin.math.abs((end-start)-duration) <= mapping.clockToleranceMs,"Duration disagrees with endpoints; no rounding inferred")
                    val rawHash = researchHash(codec.encodeToString(fields.toSortedMap().toMap()).toByteArray())
                    val hash = researchHash(codec.encodeToString(listOf(rawHash,start.toString(),end.toString(),mapping.zone)).toByteArray())
                    val key = mapping.id?.let { get(it).also { value -> ensure(value.isNotBlank(),"Missing source ID") } } ?: rawHash
                    rows += TransferRow(index+2,SourceActivity(get(mapping.activity),get(mapping.category)),start,end,
                        get(mapping.note).takeIf { it.isNotEmpty() },ImportOrigin(mapping.namespace,key,hash,fileHash,mapping.zone,fields),duration ?: (end-start))
                } catch(e: Exception) { errors += "Row ${index+2}: ${e.message ?: e.javaClass.simpleName}" }
            }
            return TransferFile(rows.toList(),errors.toList(),listOf(
                "All original columns are retained as provenance; billing, tags, tasks and quantities are not inferred into local metrics.",
                "Declared clock tolerance: ${mapping.clockToleranceMs} ms; source duration is preserved separately from calendar endpoints.",
                if(mapping.id == null) "No source IDs: exact row fingerprints deduplicate replay/reordered files; changed source rows require review and may overlap." else "Source IDs are scoped by the supplied account/workspace namespace.",
                if(format == null) "Timestamps require explicit UTC offsets." else "Local timestamps use explicit zone ${mapping.zone}; DST gaps/folds are rejected."))
        }
        private fun duration(value: String,format: DurationFormat): Long {
            val milliseconds = if(format == DurationFormat.HMS) {
                val p = value.split(':'); ensure(p.size == 3 && p[0].matches(Regex("[0-9]+")),"Duration must be H:mm:ss")
                val minutes = p[1].toInt(); val seconds = BigDecimal(p[2])
                ensure(minutes in 0..59 && seconds >= BigDecimal.ZERO && seconds < BigDecimal(60),"Invalid duration")
                (BigDecimal(p[0])*BigDecimal(3600)+BigDecimal(minutes*60)+seconds)*BigDecimal(1000)
            } else BigDecimal(value)*when(format) { DurationFormat.DECIMAL_HOURS -> BigDecimal(3_600_000); DurationFormat.SECONDS -> BigDecimal(1000); else -> BigDecimal.ONE }
            return milliseconds.longValueExact().also { ensure(it > 0,"Duration must be positive") }
        }
        /** One exact closed interval per row, portable UTC ISO dates; full fidelity remains JSON backup.
         * Raw machine CSV deliberately retains text exactly. Use Csv.export for spreadsheet-safe analysis.
         */
        fun export(d: Dataset): HistoryExport {
            val rows = mutableListOf(listOf("id","activity","category","start","end","duration_ms","note","selection_json","origin_json"))
            val sessions = d.sessions.associateBy { it.id }
            val eligible = d.segments.filter { it.end != null && !it.uncertain && it.durationMs > 0 }
            for(s in eligible) {
                val session = sessions.getValue(s.sessionId)
                rows += listOf("${d.datasetId}:${s.id}",s.selection.name.orEmpty(),s.selection.details["category"].orEmpty(),
                    Instant.ofEpochMilli(s.start).toString(),Instant.ofEpochMilli(s.end!!).toString(),s.durationMs.toString(),session.note.orEmpty(),
                    codec.encodeToString(s.selection),codec.encodeToString(session.importOrigin))
            }
            return HistoryExport(CsvTable.write(rows),listOf("${d.segments.size-eligible.size} open/uncertain/zero intervals excluded.",
                "Each interval imports as one stopped session; pauses/session grouping and measurements require full JSON backup.",
                "selection_json and origin_json preserve source context; a CSV importer must map local classification explicitly.",
                "Exact machine CSV: spreadsheet formula escaping is not applied; use the analysis CSV for spreadsheets."))
        }
    }
}

/** Small strict RFC-4180 reader/writer, including quoted commas, CRLF, embedded newlines and BOM. */
internal object CsvTable {
    fun parse(input: String,delimiter: Char): List<List<String>> {
        ensure(delimiter !in charArrayOf('"','\r','\n'),"Invalid CSV delimiter")
        val text = input.removePrefix("\uFEFF"); val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var closed = false; var i = 0
        fun field() {
            ensure(row.size < 128,"CSV exceeds 128 columns",ErrorCode.UNSUPPORTED_IMPORT)
            row += cell.toString(); cell.setLength(0); closed = false
        }
        while(i < text.length) {
            ensure(rows.size < 50_001,"CSV exceeds 50000 data rows",ErrorCode.UNSUPPORTED_IMPORT)
            val c = text[i++]
            if(quoted) {
                if(c == '"') { if(i < text.length && text[i] == '"') { cell.append('"'); i++ } else { quoted = false; closed = true } }
                else cell.append(c)
            } else when {
                c == delimiter -> field()
                c == '\r' || c == '\n' -> { field(); rows += row; row = mutableListOf(); if(c == '\r' && i < text.length && text[i] == '\n') i++ }
                c == '"' && cell.isEmpty() && !closed -> quoted = true
                c == '"' || closed -> fail(ErrorCode.UNSUPPORTED_IMPORT,"Malformed CSV quoting")
                else -> cell.append(c)
            }
        }
        ensure(!quoted,"Unclosed CSV quote",ErrorCode.UNSUPPORTED_IMPORT)
        if(cell.isNotEmpty() || row.isNotEmpty() || closed) { field(); rows += row }
        return rows
    }
    fun write(rows: List<List<String>>) = rows.joinToString("\r\n",postfix = "\r\n") { row -> row.joinToString(",") { "\""+it.replace("\"","\"\"")+"\"" } }
}
