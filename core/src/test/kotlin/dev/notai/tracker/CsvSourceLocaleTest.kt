package dev.notai.tracker

import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals

class CsvSourceLocaleTest {
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
