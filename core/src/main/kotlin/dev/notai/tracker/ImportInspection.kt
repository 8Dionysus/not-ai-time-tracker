package dev.notai.tracker

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class ImportFormat { DELIMITED_TEXT, BACKUP_JSON, UNSUPPORTED }
@kotlinx.serialization.Serializable enum class ImportField { ACTIVITY, CATEGORY, START, START_TIME, END, END_TIME, DURATION, NOTE, ID }
data class ColumnInspection(val delimiter: Char,val columns: List<String>,val examples: List<List<String>>,
    val suggestions: Map<ImportField,List<String>>)
/** null pattern explicitly chooses offset-bearing ISO timestamps; local times need both pattern and zone. */
data class CsvTimeChoice(val localPattern: String?,val zone: String)

class ImportInspection internal constructor(private val text: String,val format: ImportFormat,
    candidates: List<ColumnInspection>,warnings: List<String>,problems: List<String>,issues: List<ImportIssue> = emptyList()) {
    val issues = frozenList(issues.map { it.frozenIssue() })
    val errorIssues get() = frozenList(issues.filter { it.severity == ImportIssueSeverity.ERROR })
    val warningIssues get() = frozenList(issues.filter { it.severity == ImportIssueSeverity.WARNING })
    val candidates = frozenList(candidates.map { it.copy(columns = frozenList(it.columns),examples = frozenList(it.examples.map(::frozenList)),
        suggestions = frozenMap(it.suggestions.mapValues { (_,values) -> frozenList(values) })) })
    val warnings = frozenList(warnings)
    val problems = frozenList(problems)
    /** This explicit call confirms column/delimiter/time/duration choices; suggestions never apply themselves. */
    fun confirmCsv(delimiter: Char,mapping: CsvMapping,time: CsvTimeChoice,durationFormat: DurationFormat?): TransferFile {
        importEnsure(format == ImportFormat.DELIMITED_TEXT,ImportIssueCode.DELIMITED_TEXT_REQUIRED,"File is not supported delimited text",ErrorCode.UNSUPPORTED_IMPORT,stage = ImportIssueStage.INSPECTION)
        importEnsure(candidates.any { it.delimiter == delimiter },ImportIssueCode.DELIMITER_NOT_INSPECTED,"Inspect the explicitly chosen delimiter first",arguments = mapOf("delimiter" to ErrorArgument.Text(delimiter.toString())),stage = ImportIssueStage.INSPECTION)
        importEnsure(mapping.duration == null || durationFormat != null,ImportIssueCode.DURATION_FORMAT_REQUIRED,"Choose duration units/format explicitly",field = ImportField.DURATION,stage = ImportIssueStage.INSPECTION)
        importEnsure(time.zone.isNotBlank(),ImportIssueCode.SOURCE_ZONE_REQUIRED,"Choose source timezone explicitly",stage = ImportIssueStage.INSPECTION,fieldPath = "csvMapping.zone")
        return HistoryTransfer.parseCsv(text,mapping.copy(delimiter = delimiter,localPattern = time.localPattern,zone = time.zone,
            durationFormat = durationFormat ?: mapping.durationFormat))
    }
    /** Our versioned JSON uses the existing full-replacement preview, never CSV append semantics. */
    fun prepareRestore(backups: Backups): RestorePreview {
        importEnsure(format == ImportFormat.BACKUP_JSON,ImportIssueCode.BACKUP_UNSUPPORTED,"Not a supported backup",ErrorCode.UNSUPPORTED_IMPORT,stage = ImportIssueStage.INSPECTION)
        return backups.prepare(text.removePrefix("\uFEFF"))
    }
}

/** Content-based inspection: no vendor selection, no file-extension trust, no persistence. */
object HistoryFiles {
    fun inspect(bytes: ByteArray,delimiter: Char? = null): ImportInspection {
        importEnsure(bytes.size <= MAX_CSV_BYTES,ImportIssueCode.CSV_SIZE_LIMIT,"Import exceeds 10 MiB",ErrorCode.UNSUPPORTED_IMPORT,arguments = mapOf("maxBytes" to ErrorArgument.Count(MAX_CSV_BYTES.toLong()),"actualBytes" to ErrorArgument.Count(bytes.size.toLong())),stage = ImportIssueStage.INSPECTION)
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch(e: Exception) { return unsupported(ImportIssueCode.UTF8_REQUIRED,"Expected UTF-8 text; binary files/XLSX are not supported") }
        val content = text.removePrefix("\uFEFF").trimStart()
        if(delimiter == null && (content.startsWith("{") || content.startsWith("["))) {
            return try {
                Backups.decode(content)
                inspection(content,ImportFormat.BACKUP_JSON,emptyList(),listOf(importIssue(ImportIssueCode.REPLACEMENT_REQUIRES_PREVIEW,
                    "Full replacement of local data; use backup preview and verified rollback.",ImportIssueStage.INSPECTION,ImportIssueSeverity.WARNING)))
            } catch(e: CoreError) { unsupported(ImportIssueCode.BACKUP_UNSUPPORTED,"Only our versioned JSON backups are supported: ${e.message}",e.localized) }
        }
        if(text.any { it == '\u0000' }) return unsupported(ImportIssueCode.BINARY_UNSUPPORTED,"Binary content is not supported")
        val candidates = mutableListOf<ColumnInspection>(); val issues = mutableListOf<ImportIssue>()
        for(separator in delimiter?.let { listOf(it) } ?: listOf(',', ';', '\t', '|')) {
            try {
                // At most header + five complete records. Quoted multiline fields remain intact.
                val sample = CsvTable.parse(text,separator,maxRows = 6)
                val header = sample.firstOrNull().orEmpty()
                val records = sample.drop(1).filterNot { it.size == 1 && it.single().isEmpty() }
                if(header.size < 2 || header.any { it.isBlank() } || header.distinct().size != header.size ||
                    records.any { it.size != header.size }) continue
                candidates += ColumnInspection(separator,header,records,suggestions(header))
            } catch(e: CoreError) { if(delimiter != null) issues += e.importIssue?.copy(stage = ImportIssueStage.INSPECTION)
                ?: importIssue(ImportIssueCode.INSPECTION_FAILED,e.message.orEmpty(),ImportIssueStage.INSPECTION,cause = e.localized) }
        }
        if(candidates.isEmpty()) return inspection(text,ImportFormat.UNSUPPORTED,emptyList(),issues+importIssue(ImportIssueCode.NO_CONSISTENT_TABLE,
            "No consistent header/table in the sample; choose a delimiter explicitly or correct the file. Headerless tables are unsupported.",ImportIssueStage.INSPECTION))
        val warnings = listOf(
            importIssue(ImportIssueCode.MAPPING_CONFIRMATION_REQUIRED,"Delimiter and suggested columns require explicit confirmation; no rows have been imported.",ImportIssueStage.INSPECTION,ImportIssueSeverity.WARNING),
            importIssue(ImportIssueCode.INSPECTION_SAMPLE_LIMIT,"Inspection samples at most five records; confirmation validates the entire file.",ImportIssueStage.INSPECTION,ImportIssueSeverity.WARNING,arguments = mapOf("maxSampleRecords" to ErrorArgument.Count(5))),
            importIssue(ImportIssueCode.TIME_CHOICE_REQUIRED,"Choose timestamp pattern/timezone and duration format explicitly. Ambiguous local dates are not guessed.",ImportIssueStage.INSPECTION,ImportIssueSeverity.WARNING),
            if(candidates.size > 1) importIssue(ImportIssueCode.MULTIPLE_DELIMITERS,"Multiple delimiters fit: choose one explicitly.",ImportIssueStage.INSPECTION,ImportIssueSeverity.WARNING,arguments = mapOf("delimiters" to ErrorArgument.Texts(candidates.map { it.delimiter.toString() })))
            else importIssue(ImportIssueCode.SAMPLED_DELIMITER,"One sampled delimiter fits; confirm it before parsing.",ImportIssueStage.INSPECTION,ImportIssueSeverity.WARNING,arguments = mapOf("delimiter" to ErrorArgument.Text(candidates.single().delimiter.toString()))))
        return inspection(text,ImportFormat.DELIMITED_TEXT,candidates,warnings)
    }
    private fun inspection(text: String,format: ImportFormat,candidates: List<ColumnInspection>,issues: List<ImportIssue>) =
        ImportInspection(text,format,candidates,issues.filter { it.severity == ImportIssueSeverity.WARNING }.map { it.diagnostic },
            issues.filter { it.severity == ImportIssueSeverity.ERROR }.map { it.diagnostic },issues)
    private fun unsupported(code: ImportIssueCode,message: String,cause: LocalizedError? = null) =
        inspection("",ImportFormat.UNSUPPORTED,emptyList(),listOf(importIssue(code,message,ImportIssueStage.INSPECTION,cause = cause)))
    private fun suggestions(columns: List<String>): Map<ImportField,List<String>> {
        val names = mapOf(
            ImportField.ACTIVITY to setOf("activity","name","project","занятие","дело"),
            ImportField.CATEGORY to setOf("category","client","категория"),
            ImportField.START to setOf("start","started","start date","начало","дата начала"),
            ImportField.START_TIME to setOf("start time","время начала"),
            ImportField.END to setOf("end","ended","end date","конец","дата конца"),
            ImportField.END_TIME to setOf("end time","время конца"),
            ImportField.DURATION to setOf("duration","duration_ms","seconds","длительность","секунды"),
            ImportField.NOTE to setOf("note","notes","description","заметка","комментарий"),
            ImportField.ID to setOf("id","record id","entry id","код"))
        return names.mapValues { (_,aliases) -> columns.filter { it.trim().lowercase(java.util.Locale.ROOT) in aliases } }
    }
}
