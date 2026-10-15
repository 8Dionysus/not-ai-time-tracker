package dev.notai.tracker

import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.*

class CsvSourceLocaleTest {
    @Test fun invalidLocaleMatchesTypedEditorialDeltaWithoutApplyingData() {
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile
        val delta = codec.parseToJsonElement(java.io.File(root,"docs/localization/backend-typed-delta.json").readText()).jsonObject
        assertEquals(ImportIssueCode.entries.size,delta.getValue("currentImportIssueCount").jsonPrimitive.int)
        val contract = delta.getValue("entries").jsonArray.single().jsonObject
        val csv = "activity,start,end\nFixture,2026-03-08T10:00:00Z,2026-03-08T10:01:00Z\n"
        for(tag in listOf("en_US","und","---")) {
            val error = assertFailsWith<CoreError> { HistoryTransfer.parseCsv(csv,CsvMapping("fixture","activity","start",end = "end",sourceLocale = tag)) }
            val issue = error.importIssue!!
            assertEquals(contract.getValue("resourceKey").jsonPrimitive.content,issue.resourceKey)
            assertEquals(contract.getValue("stage").jsonPrimitive.content,issue.stage.name)
            assertEquals(contract.getValue("fieldPath").jsonPrimitive.content,issue.fieldPath)
            assertEquals(mapOf("localeTag" to ErrorArgument.Text(tag)),issue.arguments)
        }
    }
    @Test fun uiLanguageCannotChangeTimestampParsingOrProvenance() {
        val csv = "activity,start,end\nFixture,08 Mar 2026 10:00:00,08 Mar 2026 10:01:00\n"
        val mapping = CsvMapping("locale-fixture","activity","start",end = "end",localPattern = "dd MMM uuuu HH:mm:ss")
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH);val expected = HistoryTransfer.parseCsv(csv,mapping)
            assertEquals(emptyList(),expected.errors)
            for(locale in listOf(Locale.forLanguageTag("ru"),Locale.FRENCH,Locale.forLanguageTag("tr"))) {
                Locale.setDefault(locale);assertEquals(expected.rows,HistoryTransfer.parseCsv(csv,mapping).rows)
            }
            val french = HistoryTransfer.parseCsv(csv.replace("Mar","mars"),mapping.copy(sourceLocale = "fr"))
            assertEquals(emptyList(),french.errors);assertEquals(expected.rows.single().start,french.rows.single().start)
        } finally { Locale.setDefault(old) }
    }
}
