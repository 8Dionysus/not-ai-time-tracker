package dev.notai.tracker

import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.*

class ScheduleCommandCompatibilityTest {
    @Test fun existingPersistedCommandsKeepTheirCanonicalPayloadHash() {
        val legacy = codec.parseToJsonElement("""{
          "key":"saved-start","expected":{"datasetId":"dataset","revision":7,"sessionId":null},
          "action":"START","seriesId":"series","expectedSeriesRevision":1,"rule":null,
          "effectiveFrom":null,"state":null,"occurrence":{"seriesId":"series","originalDate":"2026-10-10"},
          "override":null,"expectedOverrideRevision":null,"deviceZone":"UTC"
        }""")
        val command = codec.decodeFromString<ScheduleCommand>(legacy.toString())
        assertFalse("replacementSeriesId" in codec.encodeToJsonElement(ScheduleCommand.serializer(),command).jsonObject)
        assertEquals(sha256(canonicalJson(legacy).toString().toByteArray(Charsets.UTF_8)),command.canonicalPayloadHash())
        val replacement = command.copy(action = ScheduleAction.REPLACE,replacementSeriesId = "new-series")
        assertEquals("new-series",codec.decodeFromString<ScheduleCommand>(codec.encodeToString(replacement)).replacementSeriesId)
    }
}
