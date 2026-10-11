package dev.notai.tracker

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class ImportFormat { DELIMITED_TEXT, BACKUP_JSON, UNSUPPORTED }
enum class ImportField { ACTIVITY, CATEGORY, START, START_TIME, END, END_TIME, DURATION, NOTE, ID }
data class ColumnInspection(val delimiter: Char,val columns: List<String>,val examples: List<List<String>>,
    val suggestions: Map<ImportField,List<String>>)
/** null pattern explicitly chooses offset-bearing ISO timestamps; local times need both pattern and zone. */
data class CsvTimeChoice(val localPattern: String?,val zone: String)

class ImportInspection internal constructor(private val text: String,val format: ImportFormat,
    val candidates: List<ColumnInspection>,val warnings: List<String>,val problems: List<String>) {
    /** This explicit call confirms column/delimiter/time/duration choices; suggestions never apply themselves. */
    fun confirmCsv(delimiter: Char,mapping: CsvMapping,time: CsvTimeChoice,durationFormat: DurationFormat?): TransferFile {
        ensure(format == ImportFormat.DELIMITED_TEXT,"File is not supported delimited text",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(candidates.any { it.delimiter == delimiter },"Inspect the explicitly chosen delimiter first",ErrorCode.INVALID_INPUT)
        ensure(mapping.duration == null || durationFormat != null,"Choose duration units/format explicitly")
        ensure(time.zone.isNotBlank(),"Choose source timezone explicitly")
        return HistoryTransfer.parseCsv(text,mapping.copy(delimiter = delimiter,localPattern = time.localPattern,zone = time.zone,
            durationFormat = durationFormat ?: mapping.durationFormat))
    }
    /** Our versioned JSON uses the existing full-replacement preview, never CSV append semantics. */
    fun prepareRestore(backups: Backups): RestorePreview {
        ensure(format == ImportFormat.BACKUP_JSON,"Not a supported backup",ErrorCode.UNSUPPORTED_IMPORT)
        return backups.prepare(text.removePrefix("\uFEFF"))
    }
}

/** Content-based inspection: no vendor selection, no file-extension trust, no persistence. */
object HistoryFiles {
    fun inspect(bytes: ByteArray,delimiter: Char? = null): ImportInspection {
        ensure(bytes.size <= MAX_BACKUP_BYTES,"Import exceeds 10 MiB",ErrorCode.UNSUPPORTED_IMPORT)
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch(e: Exception) { return unsupported("Expected UTF-8 text; binary files/XLSX are not supported") }
        val content = text.removePrefix("\uFEFF").trimStart()
        if(delimiter == null && (content.startsWith("{") || content.startsWith("["))) {
            return try {
                Backups.decode(content)
                ImportInspection(content,ImportFormat.BACKUP_JSON,emptyList(),listOf("Full replacement of local data; use backup preview and verified rollback."),emptyList())
            } catch(e: CoreError) { unsupported("Only our versioned JSON backups are supported: ${e.message}") }
        }
        if(text.any { it == '\u0000' }) return unsupported("Binary content is not supported")
        val candidates = mutableListOf<ColumnInspection>(); val problems = mutableListOf<String>()
        for(separator in delimiter?.let { listOf(it) } ?: listOf(',', ';', '\t', '|')) {
            try {
                // At most header + five complete records. Quoted multiline fields remain intact.
                val sample = CsvTable.parse(text,separator,maxRows = 6)
                val header = sample.firstOrNull().orEmpty()
                val records = sample.drop(1).filterNot { it.size == 1 && it.single().isEmpty() }
                if(header.size < 2 || header.any { it.isBlank() } || header.distinct().size != header.size ||
                    records.any { it.size != header.size }) continue
                candidates += ColumnInspection(separator,header,records,suggestions(header))
            } catch(e: CoreError) { if(delimiter != null) problems += e.message.orEmpty() }
        }
        if(candidates.isEmpty()) return ImportInspection(text,ImportFormat.UNSUPPORTED,emptyList(),emptyList(),
            problems+"No consistent header/table in the sample; choose a delimiter explicitly or correct the file. Headerless tables are unsupported.")
        return ImportInspection(text,ImportFormat.DELIMITED_TEXT,candidates,listOf(
            "Delimiter and suggested columns require explicit confirmation; no rows have been imported.",
            "Inspection samples at most five records; confirmation validates the entire file.",
            "Choose timestamp pattern/timezone and duration format explicitly. Ambiguous local dates are not guessed.",
            if(candidates.size > 1) "Multiple delimiters fit: choose one explicitly." else "One sampled delimiter fits; confirm it before parsing."),emptyList())
    }
    private fun unsupported(message: String) = ImportInspection("",ImportFormat.UNSUPPORTED,emptyList(),emptyList(),listOf(message))
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
