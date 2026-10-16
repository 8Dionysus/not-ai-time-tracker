package dev.notai.tracker

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class AttentionPreferenceArchiveTest {
    @Test fun fullJsonKeepsDisabledUserValuesAndRejectsThemInALegacyVersion() {
        val t = Tracker(MemoryStore(),FakeClock());val local = AttentionReminderStore()
        val controller = LongRecordReminder(t,local,AttentionReminderPlatform())
        val preference = LongRecordPreference(false,321,17)
        controller.configure(t.snapshot().token,preference,local.value.revision)
        val backups = Backups(t,MemoryRollback());val text = backups.export()
        val root = codec.parseToJsonElement(text).jsonObject
        assertEquals(12,root.getValue("formatVersion").jsonPrimitive.int)
        assertEquals(preference,Backups.decode(text).data.settings.longRecordReminder)
        val settings = root.getValue("data").jsonObject.getValue("settings").jsonObject
        assertEquals(setOf("enabled","afterMs","snoozeMs"),settings.getValue("longRecordReminder").jsonObject.keys)
        assertFalse("activation" in root.getValue("data").jsonObject)
        assertFalse("longRecordControl" in root.getValue("data").jsonObject)
        val downgraded = JsonObject(root+("formatVersion" to JsonPrimitive(11))).toString()
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { Backups.decode(downgraded) }.code)
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { StreamingBackup.read { downgraded.byteInputStream() } }.code)
        val data = root.getValue("data").jsonObject
        val legacy = JsonObject(root+("formatVersion" to JsonPrimitive(11))+
            ("data" to JsonObject(data+("settings" to JsonObject(settings.filterKeys { it != "longRecordReminder" }))))).toString()
        assertEquals(LongRecordPreference(),Backups.decode(legacy).data.settings.longRecordReminder)
        assertEquals(LongRecordPreference(),StreamingBackup.read { legacy.byteInputStream() }.data.settings.longRecordReminder)
    }
}
