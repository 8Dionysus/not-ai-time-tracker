package dev.notai.tracker

import java.io.ByteArrayOutputStream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingDelimiterTest {
    @Test fun exactWhitespaceAndNumericDelimiterSet() {
        val backup = Backup(backupId = "delimiter-regression",asOf = 1_234_567_890_123,
            data = Dataset(datasetId = "delimiter-dataset",revision = 123_456_789,
                types = emptyList(),axes = emptyList(),metrics = emptyList(),units = emptyList()))
        val bytes = ByteArrayOutputStream().also { StreamingBackup.write(backup,it) }.toByteArray()
        val raw = bytes.toString(Charsets.UTF_8)
        for(space in listOf("\t","\n","\r"," ","\t\r\n ")) {
            var quoted = false;var escaped = false
            val framed = buildString {
                append('\uFEFF');append(space)
                for(c in raw) {
                    if(!quoted && c in "{}[]:,") append(space)
                    append(c)
                    if(quoted) { if(escaped) escaped = false else if(c == '\\') escaped = true else if(c == '"') quoted = false }
                    else if(c == '"') quoted = true
                    if(!quoted && c in "{}[]:,") append(space)
                }
                append(space)
            }.toByteArray()
            assertEquals(backup,StreamingBackup.read { framed.inputStream() })
        }
        for(invalid in listOf('\u000B','\u000C','\u00A0')) {
            val framed = ("{"+invalid+raw.substring(1)).toByteArray()
            assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { StreamingBackup.read { framed.inputStream() } }.code)
        }
        for(suffix in listOf("0","false",",","\u000B")) {
            val framed = (raw+suffix).toByteArray()
            assertFailsWith<CoreError> { StreamingBackup.read { framed.inputStream() } }
        }
    }
}
