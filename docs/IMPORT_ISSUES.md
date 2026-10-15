# Typed CSV and inspection issues — frontend handoff

`HistoryTransfer` and `HistoryFiles` expose structured issues without parsing or translating exception messages. This is a transient presentation contract: no changes to Room schema, JSON archive format, IDs, hashes, source names, CSV columns, timezone interpretation, dedupe, preview validation or atomic append semantics.

## Entry points and compatibility

| Result | Structured contract | Retained diagnostic compatibility |
|---|---|---|
| `TransferFile` | `issues`, `errorIssues`, `warningIssues` | `errors`, `warnings` |
| `HistoryPreview` | `issues`, `errorIssues`, `warningIssues` (includes file issues) | `errors`, `warnings`, `canApply` unchanged |
| `ImportInspection` | `issues`, `errorIssues`, `warningIssues` | `problems`, `warnings` |
| `HistoryExport` | `issues`, `warningIssues` | `warnings`, `csv` |
| Failed parse/confirmation/apply | `CoreError.importIssue` when produced by these CSV boundaries; `CoreError.localized` remains available | `code`, `message`, `currentState` remain available |

For production-created results, errors/problems and warnings match the corresponding structured issue diagnostics in order. Existing Kotlin callers can keep their current signatures and string reads; those reads retain English diagnostics until the frontend migrates. Compatibility defaults on internal result constructors are not a promise that manually fabricated legacy objects contain structured issues. `CoreError` retains default arguments; binary compatibility of an independently distributed core library is not claimed.

`ImportIssue` contains stable enum `code`, stable `resourceKey = tracker.import.<lowercase_enum>`, `severity`, `stage`, optional `rowNumber`, optional `ImportField`, `arguments`, optional `fieldPath`, optional typed `cause: LocalizedError`, and `diagnostic` for old callers/logging. `ErrorArgument` is `Text`, `Texts(List<String>)`, `Identifier`, `Count(Long)` or `Date(ISO string)`. Lists/maps returned by the backend are immutable snapshots. `Texts` supports header lists without converting their user-supplied contents into UI messages. All issue data can be serialized, but it is not added to archives or receipts.

Rows count logical CSV records: header=1, first data record=2. A quoted multiline cell is still one record. Blank records preserve their original position. `column` is the exact source header; `field` is the mapped semantic field. Row paths use `csv.rows[2]` or `csv.rows[2].start`; configuration paths use `csvMapping.*`. `utf16Offset` is zero-based in the BOM-stripped parser input. `Count` also carries exact millisecond timestamps/durations, not preformatted locale-sensitive text.

## Opus integration changes

`TechnicalActivity.showCsvPreview` currently concatenates `preview.errors`/`preview.warnings` directly (around line 279). Replace those reads with `preview.errorIssues`/`preview.warningIssues` and a resource renderer keyed by `resourceKey`. Apply the same renderer to inspection problems/warnings, parse confirmation failures and export warnings. For a caught `CoreError`, render `importIssue` when present, otherwise `localized`; backup restore still uses its existing typed CoreError contract. Continue to use `preview.canApply` as the domain acceptance flag. The UI must not infer acceptance from text or the number of currently displayed rows.

The renderer chooses reviewed UI resources, formats numeric arguments for presentation, and inserts source text/identifiers unchanged as values. It must not translate names, notes, source headers, namespace/record IDs, raw invalid values, or persisted provenance. Do not match substrings such as `Row`, `Missing` or `CoreError.message`; do not render `diagnostic` as a translated fallback. Unknown codes should receive a generic localized error/warning with the stable key available for support. A typed `cause` can add a generic localized domain explanation (`OVERLAP`, `STORAGE`, `STALE`, etc.) without its diagnostic prose. New language packs/resources and all screen wiring remain frontend work; these keys are not advertised as reviewed translations.

## Issue keys and arguments

Common optional arguments: `rowNumber: Count`, `column: Text`. Preview row issues also carry `activity: Text`, `category: Text`. Entries below list additional arguments; a code with no additional arguments is still renderable from its stable key. Error codes retain their enum names; resource suffix is the lowercase name.

| Codes | Additional arguments / context |
|---|---|
| `CSV_SIZE_LIMIT` | `maxBytes: Count`; `actualBytes: Count` for encoded/byte input, or `utf16Units: Count` for early string bound |
| `CSV_EMPTY`, `SOURCE_NAMESPACE_REQUIRED`, `END_OR_DURATION_REQUIRED` | No extra arguments |
| `CSV_HEADER_INVALID` | `headers: Texts`; header row 1 |
| `CSV_COLUMNS_MISSING` | `columns: Texts` of missing exact headers; row 1 |
| `CLOCK_TOLERANCE_UNSUPPORTED` | `clockToleranceMs`, `maxToleranceMs: Count` |
| `SOURCE_ZONE_INVALID` | `zone: Text` |
| `TIMESTAMP_PATTERN_INVALID` | `pattern: Text` |
| `CSV_DELIMITER_INVALID`, `DELIMITER_NOT_INSPECTED` | `delimiter: Text` |
| `CSV_COLUMN_LIMIT` | `maxColumns: Count`; logical row |
| `CSV_ROW_LIMIT` | `maxDataRows: Count`; logical row |
| `CSV_QUOTING_INVALID` | `utf16Offset: Count`; logical row |
| `CSV_QUOTE_UNCLOSED` | Logical row |
| `ROW_COLUMN_COUNT` | `expectedColumns`, `actualColumns: Count`; logical row |
| `TIMESTAMP_INVALID`, `SUBMILLISECOND_PRECISION`, `TIMESTAMP_RANGE` | `value`, `zone: Text`; START/END, row and column |
| `LOCAL_TIME_GAP_OR_FOLD` | `value`, `zone: Text`, `validOffsetCount: Count` (0 gap, 2 fold); START/END, row and column |
| `INTERVAL_INVALID` | `startEpochMs`, `endEpochMs: Count`; row |
| `INTERVAL_OVERFLOW` | `startEpochMs`, `durationMs: Count`; row |
| `DURATION_ENDPOINT_MISMATCH` | `durationMs`, `endpointDurationMs`, `clockToleranceMs: Count`; DURATION, row and source duration column |
| `SOURCE_ID_MISSING` | ID, row and source ID column |
| `DURATION_HMS_REQUIRED`, `DURATION_COMPONENT_RANGE`, `DURATION_NUMBER_INVALID`, `DURATION_PRECISION_OR_RANGE`, `DURATION_POSITIVE_REQUIRED` | `value: Text`, `durationFormat: Identifier`; DURATION, row and column |
| `ROW_PARSE_FAILED` | Defensive fallback: `exceptionType: Identifier`, optional typed cause; row |
| `SOURCE_ID_CONFLICT` | `namespace`, `recordKey: Identifier`; row; raw activity/category |
| `FUTURE_INTERVAL` | `endEpochMs`, `asOfEpochMs: Count`; END, row; typed INVALID_INTERVAL cause |
| `MAPPING_REQUIRED`, `MAPPING_INVALID` | Row, activity/category; typed domain cause |
| `BATCH_VALIDATION_FAILED` | `candidateCount: Count`; typed domain cause (e.g. OVERLAP) |
| `PREVIEW_HAS_ERRORS` | `errorCount: Count` |
| `ROLLBACK_VERIFICATION_FAILED` | No extra arguments; underlying CoreError STORAGE |
| `APPLY_STALE`, `APPLY_STORAGE_FAILED`, `APPLY_FAILED` | Typed underlying cause/arguments/fieldPath; no diagnostic parsing |
| `PROVENANCE_COLUMNS_RETAINED`, `ROW_FINGERPRINT_DEDUPE`, `EXPLICIT_OFFSET_REQUIRED` | Parse warnings; no extra arguments |
| `CLOCK_TOLERANCE_DECLARED` | Parse warning; `clockToleranceMs: Count` |
| `SOURCE_ID_NAMESPACE` | Parse warning; `namespace: Identifier` |
| `LOCAL_ZONE_DST_REJECTED` | Parse warning; `zone: Text` |
| `INTERVALS_EXCLUDED` | Export warning; `excludedCount: Count` |
| `SESSION_GROUPING_LOSS`, `CONTEXT_REQUIRES_MAPPING`, `EXACT_CSV_FORMULA_POLICY` | Export warnings; no extra arguments |
| `UTF8_REQUIRED`, `BINARY_UNSUPPORTED`, `NO_CONSISTENT_TABLE`, `DELIMITED_TEXT_REQUIRED`, `DURATION_FORMAT_REQUIRED`, `SOURCE_ZONE_REQUIRED` | Inspection/confirmation errors; no extra arguments; duration field or zone path where relevant |
| `BACKUP_UNSUPPORTED`, `INSPECTION_FAILED` | Inspection errors; optional typed backup/parser cause |
| `REPLACEMENT_REQUIRES_PREVIEW`, `MAPPING_CONFIRMATION_REQUIRED`, `TIME_CHOICE_REQUIRED` | Inspection warnings; no extra arguments |
| `INSPECTION_SAMPLE_LIMIT` | Warning; `maxSampleRecords: Count` |
| `MULTIPLE_DELIMITERS` | Warning; `delimiters: Texts` |
| `SAMPLED_DELIMITER` | Warning; `delimiter: Text` |

A CSV parser error surfaced during explicit-delimiter inspection retains its same code/arguments, with stage `INSPECTION`. Fatal parsing errors throw CoreError with `importIssue`; per-record failures return issues and reject apply through the existing preview. Warnings alone do not make a file invalid. Failed candidate parsers during automatic delimiter discovery are intentionally omitted, as before; a final `NO_CONSISTENT_TABLE` explains rejection if none fit.

## Verification

`ImportIssuesTest` exercises structural limits, missing mappings/configuration, source values, numeric/time precision/range, DST gap/fold, all duration branches, inspection/confirmation/export warning correspondence, immutability and serialization. Shared `ScenarioContract.typedImportIssuesPreserveAtomicityAndProvenance` runs on Memory and Room: missing/invalid mapping, future interval, conflicting source IDs, overlap cause, preview rejection, rollback verification, storage failure, stale apply, provenance preservation, reopen and dedupe. Room's injected metadata-write failure also asserts `APPLY_STORAGE_FAILED` and unchanged data. These are JVM/Robolectric checks, not screen/device localization acceptance.
