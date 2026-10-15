package dev.notai.tracker

import org.junit.Test
import kotlin.test.*

/** These tests assert typed contracts; English diagnostics are checked only for caller compatibility. */
class ImportIssuesTest {
    private val mapping = CsvMapping("fixture","activity","start",end = "end",duration = "duration",durationFormat = DurationFormat.SECONDS,id = "id")
    private val header = "activity,start,end,duration,id\n"
    private val valid = "Исходное =Read,2026-10-08T08:00:00Z,2026-10-08T08:01:00Z,60,opaque\n"
    private fun parse(row: String = valid,m: CsvMapping = mapping) = HistoryTransfer.parseCsv(header+row,m)
    private fun fatal(code: ImportIssueCode,block: ()->Unit): ImportIssue {
        val issue = assertFailsWith<CoreError>(block = block).importIssue!!
        assertEquals(code,issue.code);assertEquals(ImportIssueSeverity.ERROR,issue.severity)
        assertEquals("tracker.import.${code.name.lowercase(java.util.Locale.ROOT)}",issue.resourceKey)
        return issue
    }
    private fun row(code: ImportIssueCode,text: String,m: CsvMapping = mapping): ImportIssue {
        val file = parse(text,m);assertTrue(file.rows.isEmpty())
        assertEquals(file.errors,file.errorIssues.map { it.diagnostic })
        val issue = file.errorIssues.single();assertEquals(code,issue.code)
        assertEquals(2,issue.rowNumber);assertEquals(ErrorArgument.Count(2),issue.arguments["rowNumber"])
        return issue
    }
    @Test fun preflightAndStrictCsvFailuresAreTyped() {
        fatal(ImportIssueCode.CSV_SIZE_LIMIT) { HistoryTransfer.parseCsv("x".repeat(MAX_CSV_BYTES+1),mapping) }
        fatal(ImportIssueCode.CSV_SIZE_LIMIT) { HistoryTransfer.parseCsv("я".repeat(MAX_CSV_BYTES/2+1),mapping) }
        fatal(ImportIssueCode.SOURCE_NAMESPACE_REQUIRED) { parse(m = mapping.copy(namespace = "")) }
        fatal(ImportIssueCode.CLOCK_TOLERANCE_UNSUPPORTED) { parse(m = mapping.copy(clockToleranceMs = 1001)) }
        fatal(ImportIssueCode.CSV_EMPTY) { HistoryTransfer.parseCsv("",mapping) }
        fatal(ImportIssueCode.CSV_HEADER_INVALID) { HistoryTransfer.parseCsv("start,start\n",mapping) }
        val missing = fatal(ImportIssueCode.CSV_COLUMNS_MISSING) { parse(m = mapping.copy(activity = "Исходное поле")) }
        assertEquals(ErrorArgument.Texts(listOf("Исходное поле")),missing.arguments["columns"])
        assertFailsWith<UnsupportedOperationException> { ((missing.arguments.getValue("columns") as ErrorArgument.Texts).values as MutableList).clear() }
        fatal(ImportIssueCode.END_OR_DURATION_REQUIRED) { parse(m = mapping.copy(end = null,duration = null)) }
        fatal(ImportIssueCode.SOURCE_ZONE_INVALID) { parse(m = mapping.copy(zone = "Not/AZone")) }
        fatal(ImportIssueCode.SOURCE_LOCALE_INVALID) { parse(m = mapping.copy(sourceLocale = "invalid_locale!")) }
        fatal(ImportIssueCode.TIMESTAMP_PATTERN_INVALID) { parse(m = mapping.copy(localPattern = "[invalid#")) }
        fatal(ImportIssueCode.CSV_DELIMITER_INVALID) { CsvTable.parse("",'"') }
        fatal(ImportIssueCode.CSV_COLUMN_LIMIT) { CsvTable.parse((0..128).joinToString(","),',') }
        fatal(ImportIssueCode.CSV_ROW_LIMIT) { CsvTable.parse("x\n".repeat(50_002),',') }
        assertEquals(2,fatal(ImportIssueCode.CSV_QUOTING_INVALID) { CsvTable.parse("a,b\n\"x\"oops,y",',') }.rowNumber)
        assertEquals(2,fatal(ImportIssueCode.CSV_QUOTE_UNCLOSED) { CsvTable.parse("a,b\n\"x\ny",',') }.rowNumber)
    }
    @Test fun rowIssuesCarryExactValuesFieldsAndLogicalRecordNumbers() {
        row(ImportIssueCode.ROW_COLUMN_COUNT,"only one\n")
        val timestamp = row(ImportIssueCode.TIMESTAMP_INVALID,valid.replace("2026-10-08T08:00:00Z","дата как введена"))
        assertEquals(ImportField.START,timestamp.field);assertEquals(ErrorArgument.Text("start"),timestamp.arguments["column"])
        assertEquals(ErrorArgument.Text("дата как введена"),timestamp.arguments["value"])
        row(ImportIssueCode.SUBMILLISECOND_PRECISION,valid.replace("08:00:00Z","08:00:00.000000001Z"))
        row(ImportIssueCode.TIMESTAMP_RANGE,valid.replace("2026-10-08T08:00:00Z","+999999999-01-01T00:00:00Z"))
        row(ImportIssueCode.INTERVAL_INVALID,valid.replace("08:01:00Z","08:00:00Z"))
        row(ImportIssueCode.INTERVAL_OVERFLOW,valid.replace(",60,",",9223372036854775807,"),mapping.copy(end = null,durationFormat = DurationFormat.MILLISECONDS))
        row(ImportIssueCode.DURATION_ENDPOINT_MISMATCH,valid.replace(",60,",",61,"))
        row(ImportIssueCode.SOURCE_ID_MISSING,valid.replace(",opaque",","))
        val multiline = parse("\"Исходное\nRead\",bad,2026-10-08T08:01:00Z,60,opaque\n")
        assertEquals(2,multiline.errorIssues.single().rowNumber) // CSV records, not physical lines
        val local = "Read,2026-10-25 02:00:00,2026-10-25 02:01:00,60,opaque\n"
        val fold = row(ImportIssueCode.LOCAL_TIME_GAP_OR_FOLD,local,mapping.copy(localPattern = "uuuu-MM-dd HH:mm:ss",zone = "Europe/Berlin"))
        assertEquals(ErrorArgument.Count(2),fold.arguments["validOffsetCount"])
        val gap = row(ImportIssueCode.LOCAL_TIME_GAP_OR_FOLD,local.replace("10-25","03-29"),mapping.copy(localPattern = "uuuu-MM-dd HH:mm:ss",zone = "Europe/Berlin"))
        assertEquals(ErrorArgument.Count(0),gap.arguments["validOffsetCount"])
    }
    @Test fun durationVariantsNeverNeedExceptionMessageParsing() {
        row(ImportIssueCode.DURATION_HMS_REQUIRED,valid,mapping.copy(durationFormat = DurationFormat.HMS))
        row(ImportIssueCode.DURATION_COMPONENT_RANGE,valid.replace(",60,",",0:60:00,"),mapping.copy(durationFormat = DurationFormat.HMS))
        row(ImportIssueCode.DURATION_NUMBER_INVALID,valid.replace(",60,",",not a number,"))
        row(ImportIssueCode.DURATION_PRECISION_OR_RANGE,valid.replace(",60,",",0.0001,"))
        row(ImportIssueCode.DURATION_POSITIVE_REQUIRED,valid.replace(",60,",",0,"))
    }
    @Test fun warningsAndDiagnosticsAreAlignedAndImmutable() {
        val file = parse();assertEquals(file.warnings,file.warningIssues.map { it.diagnostic });assertTrue(file.errors.isEmpty())
        assertEquals(listOf(ImportIssueCode.PROVENANCE_COLUMNS_RETAINED,ImportIssueCode.CLOCK_TOLERANCE_DECLARED,ImportIssueCode.SOURCE_ID_NAMESPACE,ImportIssueCode.EXPLICIT_OFFSET_REQUIRED),file.warningIssues.map { it.code })
        assertEquals("Исходное =Read",file.rows.single().activity.activity)
        assertEquals("Исходное =Read",file.rows.single().origin.fields["activity"])
        assertFailsWith<UnsupportedOperationException> { (file.issues as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (file.warningIssues.first().arguments as MutableMap).clear() }
        assertEquals(ImportIssueCode.ROW_FINGERPRINT_DEDUPE,parse(m = mapping.copy(id = null)).warningIssues[2].code)
        val local = parse(valid.replace("T08:"," 08:").replace("Z",""),mapping.copy(localPattern = "uuuu-MM-dd HH:mm:ss",zone = "UTC"))
        assertTrue(local.errors.isEmpty());assertEquals(ImportIssueCode.LOCAL_ZONE_DST_REJECTED,local.warningIssues.last().code)
        val exported = HistoryTransfer.export(Dataset())
        assertEquals(exported.warnings,exported.warningIssues.map { it.diagnostic })
        assertEquals(ErrorArgument.Count(0),exported.warningIssues.first().arguments["excludedCount"])
        assertEquals(4,exported.warningIssues.size);assertTrue(exported.warningIssues.all { it.stage == ImportIssueStage.EXPORT })
    }
    @Test fun inspectionAndConfirmationHaveTypedResults() {
        fun inspect(text: String) = HistoryFiles.inspect(text.toByteArray())
        fun check(inspection: ImportInspection) {
            assertEquals(inspection.problems,inspection.errorIssues.map { it.diagnostic })
            assertEquals(inspection.warnings,inspection.warningIssues.map { it.diagnostic })
            assertTrue(inspection.issues.all { it.stage == ImportIssueStage.INSPECTION })
        }
        val ordinary = inspect(header+valid);check(ordinary);assertEquals(4,ordinary.warningIssues.size)
        assertEquals(ErrorArgument.Count(5),ordinary.warningIssues[1].arguments["maxSampleRecords"])
        val ambiguous = inspect("a,b;c\nx,y;z\n");check(ambiguous)
        assertEquals(ImportIssueCode.MULTIPLE_DELIMITERS,ambiguous.warningIssues.last().code)
        for((inspection,code) in listOf(
            HistoryFiles.inspect(byteArrayOf(0xff.toByte())) to ImportIssueCode.UTF8_REQUIRED,
            inspect("a\u0000b") to ImportIssueCode.BINARY_UNSUPPORTED,
            inspect("{}") to ImportIssueCode.BACKUP_UNSUPPORTED,
            inspect("one column") to ImportIssueCode.NO_CONSISTENT_TABLE)) {
            check(inspection);assertEquals(code,inspection.errorIssues.single().code)
        }
        val malformed = HistoryFiles.inspect("a,b\n\"bad".toByteArray(),',');check(malformed)
        assertEquals(listOf(ImportIssueCode.CSV_QUOTE_UNCLOSED,ImportIssueCode.NO_CONSISTENT_TABLE),malformed.errorIssues.map { it.code })
        fatal(ImportIssueCode.CSV_SIZE_LIMIT) { HistoryFiles.inspect(ByteArray(MAX_CSV_BYTES+1)) }
        fatal(ImportIssueCode.DELIMITED_TEXT_REQUIRED) { inspect("one").confirmCsv(',',mapping,CsvTimeChoice(null,"UTC"),DurationFormat.SECONDS) }
        fatal(ImportIssueCode.DELIMITER_NOT_INSPECTED) { ordinary.confirmCsv(';',mapping,CsvTimeChoice(null,"UTC"),DurationFormat.SECONDS) }
        fatal(ImportIssueCode.DURATION_FORMAT_REQUIRED) { ordinary.confirmCsv(',',mapping,CsvTimeChoice(null,"UTC"),null) }
        fatal(ImportIssueCode.SOURCE_ZONE_REQUIRED) { ordinary.confirmCsv(',',mapping,CsvTimeChoice(null,""),DurationFormat.SECONDS) }
        val t = Tracker(MemoryStore(),FakeClock());val backups = Backups(t,MemoryRollback())
        fatal(ImportIssueCode.BACKUP_UNSUPPORTED) { ordinary.prepareRestore(backups) }
        val backup = inspect(backups.export());check(backup)
        assertEquals(ImportIssueCode.REPLACEMENT_REQUIRES_PREVIEW,backup.warningIssues.single().code)
    }
    @Test fun issueSerializationUsesStableEnumsAndPreservesRawArguments() {
        val issue = parse(valid.replace(",60,",",текст," )).errorIssues.single()
        assertEquals(issue,codec.decodeFromString<ImportIssue>(codec.encodeToString(issue)))
        assertEquals(ErrorArgument.Text("текст"),issue.arguments["value"])
    }
}
