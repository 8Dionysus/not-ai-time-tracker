package dev.notai.tracker

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable enum class ImportIssueSeverity { ERROR, WARNING }
@Serializable enum class ImportIssueStage { INSPECTION, PARSE, PREVIEW, APPLY, EXPORT }
@Serializable enum class ImportIssueCode {
    CSV_SIZE_LIMIT, CSV_EMPTY, CSV_HEADER_INVALID, CSV_COLUMNS_MISSING, SOURCE_NAMESPACE_REQUIRED,
    CLOCK_TOLERANCE_UNSUPPORTED, END_OR_DURATION_REQUIRED, SOURCE_ZONE_INVALID, TIMESTAMP_PATTERN_INVALID,
    CSV_DELIMITER_INVALID, CSV_COLUMN_LIMIT, CSV_ROW_LIMIT, CSV_QUOTING_INVALID, CSV_QUOTE_UNCLOSED,
    ROW_COLUMN_COUNT, TIMESTAMP_INVALID, LOCAL_TIME_GAP_OR_FOLD, SUBMILLISECOND_PRECISION,
    TIMESTAMP_RANGE, INTERVAL_INVALID, INTERVAL_OVERFLOW, DURATION_ENDPOINT_MISMATCH, SOURCE_ID_MISSING,
    DURATION_HMS_REQUIRED, DURATION_COMPONENT_RANGE, DURATION_NUMBER_INVALID, DURATION_PRECISION_OR_RANGE,
    DURATION_POSITIVE_REQUIRED, ROW_PARSE_FAILED, SOURCE_ID_CONFLICT, FUTURE_INTERVAL, MAPPING_REQUIRED,
    MAPPING_INVALID, BATCH_VALIDATION_FAILED, PREVIEW_HAS_ERRORS, ROLLBACK_VERIFICATION_FAILED,
    APPLY_STALE, APPLY_STORAGE_FAILED, APPLY_FAILED,
    PROVENANCE_COLUMNS_RETAINED, CLOCK_TOLERANCE_DECLARED, ROW_FINGERPRINT_DEDUPE, SOURCE_ID_NAMESPACE,
    EXPLICIT_OFFSET_REQUIRED, LOCAL_ZONE_DST_REJECTED, INTERVALS_EXCLUDED, SESSION_GROUPING_LOSS,
    CONTEXT_REQUIRES_MAPPING, EXACT_CSV_FORMULA_POLICY,
    UTF8_REQUIRED, BACKUP_UNSUPPORTED, BINARY_UNSUPPORTED, NO_CONSISTENT_TABLE,
    REPLACEMENT_REQUIRES_PREVIEW, MAPPING_CONFIRMATION_REQUIRED, INSPECTION_SAMPLE_LIMIT,
    TIME_CHOICE_REQUIRED, MULTIPLE_DELIMITERS, SAMPLED_DELIMITER, DELIMITED_TEXT_REQUIRED,
    DELIMITER_NOT_INSPECTED, DURATION_FORMAT_REQUIRED, SOURCE_ZONE_REQUIRED, INSPECTION_FAILED
}
/** Transient UI contract, never a domain ID or archive field. Diagnostic is compatibility/logging
 * text only; render the stable resourceKey with typed arguments, row and field context.
 */
@Serializable data class ImportIssue(val code: ImportIssueCode,val severity: ImportIssueSeverity,val stage: ImportIssueStage,
    val rowNumber: Int? = null,val field: ImportField? = null,val arguments: Map<String,ErrorArgument> = emptyMap(),
    val fieldPath: String? = null,val cause: LocalizedError? = null,val diagnostic: String = "") {
    val resourceKey get() = "tracker.import.${code.name.lowercase(Locale.ROOT)}"
}
private fun issueArguments(values: Map<String,ErrorArgument>) = frozenMap(values.mapValues { (_,v) ->
    if(v is ErrorArgument.Texts) v.copy(values = frozenList(v.values)) else v
})
internal fun ImportIssue.frozenIssue(): ImportIssue = copy(arguments = issueArguments(arguments),cause = cause?.copy(arguments = issueArguments(cause.arguments)))
internal fun importIssue(code: ImportIssueCode,diagnostic: String,stage: ImportIssueStage = ImportIssueStage.PARSE,
    severity: ImportIssueSeverity = ImportIssueSeverity.ERROR,row: Int? = null,field: ImportField? = null,column: String? = null,
    arguments: Map<String,ErrorArgument> = emptyMap(),cause: LocalizedError? = null,fieldPath: String? = null): ImportIssue {
    val args = arguments.toMutableMap()
    if(row != null) args["rowNumber"] = ErrorArgument.Count(row.toLong())
    if(column != null) args["column"] = ErrorArgument.Text(column)
    return ImportIssue(code,severity,stage,row,field,args,fieldPath ?: row?.let { "csv.rows[$it]"+(field?.let { f -> ".${f.name.lowercase(Locale.ROOT)}" } ?: "") },cause,diagnostic).frozenIssue()
}
internal fun importFail(code: ImportIssueCode,diagnostic: String,error: ErrorCode = ErrorCode.INVALID_INPUT,
    row: Int? = null,field: ImportField? = null,column: String? = null,arguments: Map<String,ErrorArgument> = emptyMap(),
    stage: ImportIssueStage = ImportIssueStage.PARSE,fieldPath: String? = null): Nothing {
    val issue = importIssue(code,diagnostic,stage,row = row,field = field,column = column,arguments = arguments,fieldPath = fieldPath)
    throw CoreError(error,diagnostic,arguments = issue.arguments,fieldPath = issue.fieldPath,importIssue = issue)
}
internal fun importEnsure(ok: Boolean,code: ImportIssueCode,diagnostic: String,error: ErrorCode = ErrorCode.INVALID_INPUT,
    row: Int? = null,field: ImportField? = null,column: String? = null,arguments: Map<String,ErrorArgument> = emptyMap(),
    stage: ImportIssueStage = ImportIssueStage.PARSE,fieldPath: String? = null) {
    if(!ok) importFail(code,diagnostic,error,row,field,column,arguments,stage,fieldPath)
}
/** Preserve the existing CoreError code/state/diagnostic while adding import-specific context. */
internal fun importApplyBoundary(block: ()->HistoryImportResult): HistoryImportResult = try { storage(block) }
catch(e: CoreError) {
    if(e.importIssue != null) throw e
    val code = when(e.code) { ErrorCode.STALE -> ImportIssueCode.APPLY_STALE; ErrorCode.STORAGE -> ImportIssueCode.APPLY_STORAGE_FAILED; else -> ImportIssueCode.APPLY_FAILED }
    val issue = importIssue(code,e.message.orEmpty(),ImportIssueStage.APPLY,cause = e.localized,arguments = e.arguments,fieldPath = e.fieldPath)
    throw CoreError(e.code,e.message.orEmpty(),e.currentState,e.arguments,e.fieldPath,e.reason,issue)
}
