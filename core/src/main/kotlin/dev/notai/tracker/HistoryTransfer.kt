package dev.notai.tracker

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

const val MAX_CSV_BYTES = 10*1024*1024

@Serializable data class ImportOrigin(val namespace: String,val recordKey: String,val rowHash: String,
    val fileHash: String,val zone: String,val fields: Map<String,String>)
data class SourceActivity(val activity: String,val category: String)
@Serializable enum class DurationFormat { HMS, DECIMAL_HOURS, SECONDS, MILLISECONDS }
@Serializable data class CsvMapping(val namespace: String,val activity: String,val start: String,
    val end: String? = null,val duration: String? = null,val durationFormat: DurationFormat = DurationFormat.HMS,
    val category: String? = null,val note: String? = null,val id: String? = null,
    val startTime: String? = null,val endTime: String? = null,
    val localPattern: String? = null,val zone: String = "UTC",val delimiter: Char = ',',val clockToleranceMs: Long = 0,
    val sourceLocale: String? = null) {
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
class TransferFile internal constructor(rows: List<TransferRow>,errors: List<String>,warnings: List<String>,issues: List<ImportIssue> = emptyList()) {
    val issues = frozenList(issues.map { it.frozenIssue() })
    val errorIssues get() = frozenList(issues.filter { it.severity == ImportIssueSeverity.ERROR })
    val warningIssues get() = frozenList(issues.filter { it.severity == ImportIssueSeverity.WARNING })
    val rows = frozenList(rows.map { it.copy(origin = it.origin.frozen()) })
    val errors = frozenList(errors)
    val warnings = frozenList(warnings)
    val activities get() = frozenList(rows.map { it.activity }.distinct())
}
class HistoryPreview internal constructor(val expected: Token,val file: TransferFile,
    sessions: List<Session>,segments: List<Segment>,val duplicates: Int,errors: List<String>,issues: List<ImportIssue> = emptyList()) {
    val issues = frozenList(issues.map { it.frozenIssue() })
    val errorIssues get() = frozenList(issues.filter { it.severity == ImportIssueSeverity.ERROR })
    val warningIssues get() = frozenList(issues.filter { it.severity == ImportIssueSeverity.WARNING })
    internal val sessions = frozenList(sessions.map { it.copy(selection = it.selection.frozen(),importOrigin = it.importOrigin?.frozen()) })
    internal val segments = frozenList(segments.map { it.copy(selection = it.selection.frozen()) })
    val errors = frozenList(errors)
    val added get() = sessions.size
    val canApply get() = errors.isEmpty()
    val warnings get() = file.warnings
    val intervals get() = segments
}
data class HistoryImportResult(val state: Snapshot,val added: Int,val duplicates: Int,val rollbackReference: String?)
data class HistoryExport(val csv: String,val warnings: List<String>,val issues: List<ImportIssue> = emptyList()) {
    val warningIssues get() = frozenList(issues.filter { it.severity == ImportIssueSeverity.WARNING })
}

/** Local, bounded transfer. No network, no guessing date formats, zones, names or overlap resolution. */
class HistoryTransfer(private val tracker: Tracker,private val rollback: RollbackStore) {
    fun preview(file: TransferFile,mapping: Map<SourceActivity,Classification>): HistoryPreview {
        val d = tracker.data(); val now = tracker.clock.now()
        val errors = file.errors.toMutableList();val issues = file.issues.toMutableList()
        val sessions = mutableListOf<Session>(); val segments = mutableListOf<Segment>()
        fun error(issue: ImportIssue) { issues += issue;errors += issue.diagnostic }
        val known = d.sessions.associateBy { it.id }.toMutableMap(); var duplicates = 0
        // Selective COPY can remap the local session ID while retaining the external record identity.
        val knownOrigins = mutableMapOf<Pair<String,String>,MutableSet<String>>()
        for(session in d.sessions) session.importOrigin?.let { origin ->
            knownOrigins.getOrPut(origin.namespace to origin.recordKey) { mutableSetOf() }.add(origin.rowHash)
        }
        for(row in file.rows) {
            val id = "import:"+sha256(codec.encodeToString(listOf(row.origin.namespace,row.origin.recordKey)).toByteArray())
            val args = mapOf("activity" to ErrorArgument.Text(row.activity.activity),"category" to ErrorArgument.Text(row.activity.category))
            val sourceKey = row.origin.namespace to row.origin.recordKey
            val matchingHashes = knownOrigins[sourceKey]
            val previous = known[id]
            if(previous != null || matchingHashes != null) {
                val sameIdentity = previous == null || previous.importOrigin?.let { it.namespace == row.origin.namespace && it.recordKey == row.origin.recordKey } == true
                if(sameIdentity && matchingHashes?.all { it == row.origin.rowHash } == true) duplicates++
                else error(importIssue(ImportIssueCode.SOURCE_ID_CONFLICT,"Row ${row.number}: source ID already exists with different content; resolve explicitly",
                    ImportIssueStage.PREVIEW,row = row.number,arguments = args+mapOf("namespace" to ErrorArgument.Identifier(row.origin.namespace),"recordKey" to ErrorArgument.Identifier(row.origin.recordKey))))
                continue
            }
            if(row.end > now.wall) {
                error(importIssue(ImportIssueCode.FUTURE_INTERVAL,"Row ${row.number}: Future imported interval",ImportIssueStage.PREVIEW,row = row.number,
                    field = ImportField.END,arguments = args+mapOf("endEpochMs" to ErrorArgument.Count(row.end),"asOfEpochMs" to ErrorArgument.Count(now.wall)),cause = LocalizedError(ErrorCode.INVALID_INTERVAL)))
                continue
            }
            val selection = mapping[row.activity]
            if(selection == null) {
                error(importIssue(ImportIssueCode.MAPPING_REQUIRED,"Row ${row.number}: Choose mapping for ${row.activity}",ImportIssueStage.PREVIEW,row = row.number,arguments = args,cause = LocalizedError(ErrorCode.INVALID_INPUT)))
                continue
            }
            try {
                val selected = d.classify(selection)
                val session = Session(id,SessionStatus.STOPPED,selected,row.note,row.origin)
                val segment = Segment("$id:segment",id,row.start,row.end,row.durationMs,selected)
                sessions += session; segments += segment; known[id] = session
                knownOrigins.getOrPut(sourceKey) { mutableSetOf() }.add(row.origin.rowHash)
            } catch(e: CoreError) { error(importIssue(ImportIssueCode.MAPPING_INVALID,"Row ${row.number}: ${e.message}",ImportIssueStage.PREVIEW,row = row.number,arguments = args,cause = e.localized)) }
        }
        if(errors.isEmpty()) try { StreamingBackup.portable(d.copy(sessions = d.sessions+sessions,segments = d.segments+segments),now) }
        catch(e: CoreError) { error(importIssue(ImportIssueCode.BATCH_VALIDATION_FAILED,"${e.code}: ${e.message}; no rows will be applied",ImportIssueStage.PREVIEW,
            arguments = mapOf("candidateCount" to ErrorArgument.Count(sessions.size.toLong())),cause = e.localized)) }
        return HistoryPreview(token(d),file,sessions.toList(),segments.toList(),duplicates,errors.toList(),issues)
    }
    fun apply(preview: HistoryPreview): HistoryImportResult = importApplyBoundary { rollback.protecting {
        importEnsure(preview.canApply,ImportIssueCode.PREVIEW_HAS_ERRORS,"Resolve preview errors before applying",ErrorCode.UNSUPPORTED_IMPORT,
            arguments = mapOf("errorCount" to ErrorArgument.Count(preview.errors.size.toLong())),stage = ImportIssueStage.APPLY)
        val before = tracker.data(); checkToken(before,preview.expected,tracker.clock.now())
        if(preview.added == 0) HistoryImportResult(tracker.snapshot(),0,preview.duplicates,null) else {
            val backup = StreamingBackup.portable(before,tracker.clock.now())
            val reference = rollback.saveSnapshot(backup)
            importEnsure(rollback.readSnapshot(reference) == backup,ImportIssueCode.ROLLBACK_VERIFICATION_FAILED,"Transfer rollback verification failed",ErrorCode.STORAGE,stage = ImportIssueStage.APPLY)
            val change = tracker.importHistory(preview.expected,preview.sessions,preview.segments)
            HistoryImportResult(change.state,preview.added,preview.duplicates,reference)
        }
    } }
    companion object {
        fun parseToggl(text: String,namespace: String,zone: String,datePattern: String = "uuuu-MM-dd"): TransferFile {
            val bytes = checkedCsvBytes(text)
            val table = CsvTable.parse(text,',')
            val headers = table.firstOrNull().orEmpty()
            val hasEnd = "End date" in headers || "End time" in headers
            val mapping = CsvMapping.toggl(namespace,zone,datePattern).let {
                if(hasEnd) it.copy(end = "End date",endTime = "End time") else it
            }
            return parseTable(table,mapping,sha256(bytes))
        }
        fun parseCsv(text: String,mapping: CsvMapping): TransferFile {
            val bytes = checkedCsvBytes(text)
            return parseTable(CsvTable.parse(text,mapping.delimiter),mapping,sha256(bytes))
        }
        private fun checkedCsvBytes(text: String): ByteArray {
            importEnsure(text.length <= MAX_CSV_BYTES,ImportIssueCode.CSV_SIZE_LIMIT,"CSV exceeds 10 MiB",ErrorCode.UNSUPPORTED_IMPORT,
                arguments = mapOf("maxBytes" to ErrorArgument.Count(MAX_CSV_BYTES.toLong()),"utf16Units" to ErrorArgument.Count(text.length.toLong())))
            return text.toByteArray(Charsets.UTF_8).also {
                importEnsure(it.size <= MAX_CSV_BYTES,ImportIssueCode.CSV_SIZE_LIMIT,"CSV exceeds 10 MiB",ErrorCode.UNSUPPORTED_IMPORT,
                    arguments = mapOf("maxBytes" to ErrorArgument.Count(MAX_CSV_BYTES.toLong()),"actualBytes" to ErrorArgument.Count(it.size.toLong())))
            }
        }
        private fun parseTable(table: List<List<String>>,mapping: CsvMapping,fileHash: String): TransferFile {
            importEnsure(mapping.namespace.isNotBlank(),ImportIssueCode.SOURCE_NAMESPACE_REQUIRED,"Stable source account/workspace namespace is required",fieldPath = "csvMapping.namespace")
            importEnsure(mapping.clockToleranceMs in 0..1000,ImportIssueCode.CLOCK_TOLERANCE_UNSUPPORTED,"Unsupported clock tolerance",
                arguments = mapOf("clockToleranceMs" to ErrorArgument.Count(mapping.clockToleranceMs),"maxToleranceMs" to ErrorArgument.Count(1000)),fieldPath = "csvMapping.clockToleranceMs")
            importEnsure(table.isNotEmpty(),ImportIssueCode.CSV_EMPTY,"Empty CSV",ErrorCode.UNSUPPORTED_IMPORT)
            val headers = table.first()
            importEnsure(headers.all { it.isNotBlank() } && headers.distinct().size == headers.size,ImportIssueCode.CSV_HEADER_INVALID,"Duplicate/empty CSV header",ErrorCode.UNSUPPORTED_IMPORT,
                row = 1,arguments = mapOf("headers" to ErrorArgument.Texts(headers)))
            val required = listOfNotNull(mapping.activity,mapping.start,mapping.end,mapping.duration,mapping.category,mapping.note,mapping.id,mapping.startTime,mapping.endTime)
            val missing = required.filterNot { it in headers }
            importEnsure(headers.containsAll(required),ImportIssueCode.CSV_COLUMNS_MISSING,"Missing columns: $missing",ErrorCode.UNSUPPORTED_IMPORT,
                row = 1,arguments = mapOf("columns" to ErrorArgument.Texts(missing)))
            importEnsure(mapping.end != null || mapping.duration != null,ImportIssueCode.END_OR_DURATION_REQUIRED,"End or duration is required",fieldPath = "csvMapping")
            val zone = try { ZoneId.of(mapping.zone) } catch(e: Exception) { importFail(ImportIssueCode.SOURCE_ZONE_INVALID,"Unknown source zone",
                arguments = mapOf("zone" to ErrorArgument.Text(mapping.zone)),fieldPath = "csvMapping.zone") }
            val sourceLocale = mapping.sourceLocale?.let { tag -> try { java.util.Locale.Builder().setLanguageTag(tag).build().also {
                importEnsure(it.language.isNotBlank(),ImportIssueCode.SOURCE_LOCALE_INVALID,"Source locale must identify a language",arguments = mapOf("localeTag" to ErrorArgument.Text(tag)),fieldPath = "csvMapping.sourceLocale")
            } } catch(e: CoreError) { throw e } catch(_: java.util.IllformedLocaleException) {
                importFail(ImportIssueCode.SOURCE_LOCALE_INVALID,"Invalid source BCP-47 locale",arguments = mapOf("localeTag" to ErrorArgument.Text(tag)),fieldPath = "csvMapping.sourceLocale")
            } } ?: java.util.Locale.ROOT
            val format = mapping.localPattern?.let { pattern -> try { DateTimeFormatter.ofPattern(pattern,sourceLocale).withResolverStyle(ResolverStyle.STRICT) }
                catch(e: IllegalArgumentException) { importFail(ImportIssueCode.TIMESTAMP_PATTERN_INVALID,e.message.orEmpty(),arguments = mapOf("pattern" to ErrorArgument.Text(pattern)),fieldPath = "csvMapping.localPattern") } }
            val rows = mutableListOf<TransferRow>();val errors = mutableListOf<String>();val issues = mutableListOf<ImportIssue>()
            fun timestamp(value: String,row: Int,field: ImportField,column: String): Long {
                val args = mapOf("value" to ErrorArgument.Text(value),"zone" to ErrorArgument.Text(mapping.zone))
                try {
                    val instant = if(format == null) OffsetDateTime.parse(value).toInstant() else {
                        val local = LocalDateTime.parse(value,format)
                        val offsets = zone.rules.getValidOffsets(local)
                        importEnsure(offsets.size == 1,ImportIssueCode.LOCAL_TIME_GAP_OR_FOLD,"Ambiguous/nonexistent local time; provide explicit-offset timestamps",ErrorCode.INVALID_INTERVAL,
                            row,field,column,args+mapOf("validOffsetCount" to ErrorArgument.Count(offsets.size.toLong())))
                        local.toInstant(offsets.single())
                    }
                    importEnsure(instant.nano % 1_000_000 == 0,ImportIssueCode.SUBMILLISECOND_PRECISION,"Sub-millisecond precision is unsupported",row = row,field = field,column = column,arguments = args)
                    return instant.toEpochMilli()
                } catch(e: CoreError) { throw e }
                  catch(e: java.time.format.DateTimeParseException) { importFail(ImportIssueCode.TIMESTAMP_INVALID,e.message.orEmpty(),row = row,field = field,column = column,arguments = args) }
                  catch(e: ArithmeticException) { importFail(ImportIssueCode.TIMESTAMP_RANGE,e.message.orEmpty(),ErrorCode.INVALID_INTERVAL,row,field,column,args) }
            }
            for((index,cells) in table.drop(1).withIndex()) {
                if(cells.size == 1 && cells.single().isEmpty()) continue
                val number = index+2
                try {
                    importEnsure(cells.size == headers.size,ImportIssueCode.ROW_COLUMN_COUNT,"Column count mismatch",row = number,
                        arguments = mapOf("expectedColumns" to ErrorArgument.Count(headers.size.toLong()),"actualColumns" to ErrorArgument.Count(cells.size.toLong())))
                    val fields = headers.zip(cells).toMap()
                    fun get(key: String?) = key?.let { fields.getValue(it) }.orEmpty()
                    val start = timestamp(get(mapping.start)+(mapping.startTime?.let { " "+get(it) } ?: ""),number,ImportField.START,mapping.start)
                    val duration = mapping.duration?.let { duration(get(it),mapping.durationFormat,number,it) }
                    val end = mapping.end?.let { timestamp(get(it)+(mapping.endTime?.let { key -> " "+get(key) } ?: ""),number,ImportField.END,it) }
                        ?: try { Math.addExact(start,requireNotNull(duration)) } catch(e: ArithmeticException) {
                            importFail(ImportIssueCode.INTERVAL_OVERFLOW,e.message.orEmpty(),ErrorCode.INVALID_INTERVAL,number,
                                arguments = mapOf("startEpochMs" to ErrorArgument.Count(start),"durationMs" to ErrorArgument.Count(duration!!)))
                        }
                    importEnsure(start >= 0 && end > start && end <= MAX_TIME,ImportIssueCode.INTERVAL_INVALID,"Invalid interval",ErrorCode.INVALID_INTERVAL,number,
                        arguments = mapOf("startEpochMs" to ErrorArgument.Count(start),"endEpochMs" to ErrorArgument.Count(end)))
                    importEnsure(duration == null || kotlin.math.abs((end-start)-duration) <= mapping.clockToleranceMs,ImportIssueCode.DURATION_ENDPOINT_MISMATCH,"Duration disagrees with endpoints; no rounding inferred",
                        row = number,field = ImportField.DURATION,column = mapping.duration,
                        arguments = mapOf("durationMs" to ErrorArgument.Count(duration ?: (end-start)),"endpointDurationMs" to ErrorArgument.Count(end-start),"clockToleranceMs" to ErrorArgument.Count(mapping.clockToleranceMs)))
                    val rawHash = sha256(codec.encodeToString(fields.toSortedMap().toMap()).toByteArray())
                    val hash = sha256(codec.encodeToString(listOf(rawHash,start.toString(),end.toString(),mapping.zone)).toByteArray())
                    val key = mapping.id?.let { get(it).also { value -> importEnsure(value.isNotBlank(),ImportIssueCode.SOURCE_ID_MISSING,"Missing source ID",row = number,field = ImportField.ID,column = it) } } ?: rawHash
                    rows += TransferRow(number,SourceActivity(get(mapping.activity),get(mapping.category)),start,end,
                        get(mapping.note).takeIf { it.isNotEmpty() },ImportOrigin(mapping.namespace,key,hash,fileHash,mapping.zone,fields),duration ?: (end-start))
                } catch(e: Exception) {
                    val diagnostic = "Row $number: ${e.message ?: e.javaClass.simpleName}"
                    val issue = (e as? CoreError)?.importIssue?.copy(diagnostic = diagnostic)
                        ?: importIssue(ImportIssueCode.ROW_PARSE_FAILED,diagnostic,row = number,arguments = mapOf("exceptionType" to ErrorArgument.Identifier(e.javaClass.simpleName)),cause = (e as? CoreError)?.localized)
                    issues += issue;errors += diagnostic
                }
            }
            fun warning(code: ImportIssueCode,text: String,args: Map<String,ErrorArgument> = emptyMap()) {
                issues += importIssue(code,text,severity = ImportIssueSeverity.WARNING,arguments = args)
            }
            warning(ImportIssueCode.PROVENANCE_COLUMNS_RETAINED,"All original columns are retained as provenance; billing, tags, tasks and quantities are not inferred into local metrics.")
            warning(ImportIssueCode.CLOCK_TOLERANCE_DECLARED,"Declared clock tolerance: ${mapping.clockToleranceMs} ms; source duration is preserved separately from calendar endpoints.",mapOf("clockToleranceMs" to ErrorArgument.Count(mapping.clockToleranceMs)))
            if(mapping.id == null) warning(ImportIssueCode.ROW_FINGERPRINT_DEDUPE,"No source IDs: exact row fingerprints deduplicate replay/reordered files; changed source rows require review and may overlap.")
            else warning(ImportIssueCode.SOURCE_ID_NAMESPACE,"Source IDs are scoped by the supplied account/workspace namespace.",mapOf("namespace" to ErrorArgument.Identifier(mapping.namespace)))
            if(format == null) warning(ImportIssueCode.EXPLICIT_OFFSET_REQUIRED,"Timestamps require explicit UTC offsets.")
            else warning(ImportIssueCode.LOCAL_ZONE_DST_REJECTED,"Local timestamps use explicit zone ${mapping.zone}; DST gaps/folds are rejected.",mapOf("zone" to ErrorArgument.Text(mapping.zone)))
            return TransferFile(rows.toList(),errors.toList(),issues.filter { it.severity == ImportIssueSeverity.WARNING }.map { it.diagnostic },issues)
        }
        private fun duration(value: String,format: DurationFormat,row: Int,column: String): Long {
            val args = mapOf("value" to ErrorArgument.Text(value),"durationFormat" to ErrorArgument.Identifier(format.name))
            try {
                val milliseconds = if(format == DurationFormat.HMS) {
                    val p = value.split(':');importEnsure(p.size == 3 && p[0].matches(Regex("[0-9]+")),ImportIssueCode.DURATION_HMS_REQUIRED,"Duration must be H:mm:ss",row = row,field = ImportField.DURATION,column = column,arguments = args)
                    val minutes = p[1].toInt();val seconds = BigDecimal(p[2])
                    importEnsure(minutes in 0..59 && seconds >= BigDecimal.ZERO && seconds < BigDecimal(60),ImportIssueCode.DURATION_COMPONENT_RANGE,"Invalid duration",row = row,field = ImportField.DURATION,column = column,arguments = args)
                    (BigDecimal(p[0])*BigDecimal(3600)+BigDecimal(minutes*60)+seconds)*BigDecimal(1000)
                } else BigDecimal(value)*when(format) { DurationFormat.DECIMAL_HOURS -> BigDecimal(3_600_000); DurationFormat.SECONDS -> BigDecimal(1000); else -> BigDecimal.ONE }
                return milliseconds.longValueExact().also { importEnsure(it > 0,ImportIssueCode.DURATION_POSITIVE_REQUIRED,"Duration must be positive",row = row,field = ImportField.DURATION,column = column,arguments = args) }
            } catch(e: CoreError) { throw e }
              catch(e: NumberFormatException) { importFail(ImportIssueCode.DURATION_NUMBER_INVALID,e.message.orEmpty(),row = row,field = ImportField.DURATION,column = column,arguments = args) }
              catch(e: ArithmeticException) { importFail(ImportIssueCode.DURATION_PRECISION_OR_RANGE,e.message.orEmpty(),row = row,field = ImportField.DURATION,column = column,arguments = args) }
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
            val issues = listOf(
                importIssue(ImportIssueCode.INTERVALS_EXCLUDED,"${d.segments.size-eligible.size} open/uncertain/zero intervals excluded.",ImportIssueStage.EXPORT,ImportIssueSeverity.WARNING,
                    arguments = mapOf("excludedCount" to ErrorArgument.Count((d.segments.size-eligible.size).toLong()))),
                importIssue(ImportIssueCode.SESSION_GROUPING_LOSS,"Each interval imports as one stopped session; pauses/session grouping, manual-time confirmation provenance and measurements require full JSON or selective archive.",ImportIssueStage.EXPORT,ImportIssueSeverity.WARNING,
                    arguments = mapOf("manuallyConfirmedIntervalCount" to ErrorArgument.Count(eligible.count { it.manualConfirmation != null }.toLong()))),
                importIssue(ImportIssueCode.CONTEXT_REQUIRES_MAPPING,"selection_json and origin_json preserve source context; a CSV importer must map local classification explicitly.",ImportIssueStage.EXPORT,ImportIssueSeverity.WARNING),
                importIssue(ImportIssueCode.EXACT_CSV_FORMULA_POLICY,"Exact machine CSV: spreadsheet formula escaping is not applied; use the analysis CSV for spreadsheets.",ImportIssueStage.EXPORT,ImportIssueSeverity.WARNING))
            return HistoryExport(CsvTable.write(rows),frozenList(issues.map { it.diagnostic }),frozenList(issues))
        }
    }
}

/** Small strict RFC-4180 reader/writer, including quoted commas, CRLF, embedded newlines and BOM. */
internal object CsvTable {
    fun parse(input: String,delimiter: Char,maxRows: Int? = null): List<List<String>> {
        importEnsure(delimiter !in charArrayOf('"','\r','\n'),ImportIssueCode.CSV_DELIMITER_INVALID,"Invalid CSV delimiter",arguments = mapOf("delimiter" to ErrorArgument.Text(delimiter.toString())))
        val text = input.removePrefix("\uFEFF"); val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var closed = false; var i = 0
        fun field() {
            importEnsure(row.size < 128,ImportIssueCode.CSV_COLUMN_LIMIT,"CSV exceeds 128 columns",ErrorCode.UNSUPPORTED_IMPORT,row = rows.size+1,arguments = mapOf("maxColumns" to ErrorArgument.Count(128)))
            row += cell.toString(); cell.setLength(0); closed = false
        }
        while(i < text.length) {
            importEnsure(rows.size < 50_001,ImportIssueCode.CSV_ROW_LIMIT,"CSV exceeds 50000 data rows",ErrorCode.UNSUPPORTED_IMPORT,row = rows.size+1,arguments = mapOf("maxDataRows" to ErrorArgument.Count(50_000)))
            val c = text[i++]
            if(quoted) {
                if(c == '"') { if(i < text.length && text[i] == '"') { cell.append('"'); i++ } else { quoted = false; closed = true } }
                else cell.append(c)
            } else when {
                c == delimiter -> field()
                c == '\r' || c == '\n' -> { field(); rows += row; row = mutableListOf(); if(c == '\r' && i < text.length && text[i] == '\n') i++; if(maxRows != null && rows.size >= maxRows) return rows }
                c == '"' && cell.isEmpty() && !closed -> quoted = true
                c == '"' || closed -> importFail(ImportIssueCode.CSV_QUOTING_INVALID,"Malformed CSV quoting",ErrorCode.UNSUPPORTED_IMPORT,row = rows.size+1,arguments = mapOf("utf16Offset" to ErrorArgument.Count((i-1).toLong())))
                else -> cell.append(c)
            }
        }
        importEnsure(!quoted,ImportIssueCode.CSV_QUOTE_UNCLOSED,"Unclosed CSV quote",ErrorCode.UNSUPPORTED_IMPORT,row = rows.size+1)
        if(cell.isNotEmpty() || row.isNotEmpty() || closed) { field(); rows += row }
        return rows
    }
    fun write(rows: List<List<String>>) = rows.joinToString("\r\n",postfix = "\r\n") { row -> row.joinToString(",") { "\""+it.replace("\"","\"\"")+"\"" } }
}
