package dev.notai.tracker

import java.io.IOException
import java.time.*
import org.junit.Test
import kotlin.test.*

class ScheduleReceiptIndexTest {
    private fun row(n: Int,status: ScheduleAttemptStatus = ScheduleAttemptStatus.entries[n%4]) = ScheduleReminderAttempt(
        ScheduleReminderTicket("dataset","barrier",OccurrenceKey("series-$n","2026-10-10"),1,null,"UTC",1791624600000),1791622800000,status)
    @Test fun legacyMigrationPreservesEveryFieldStatusOrderAndPagesAcrossColdReopen() = ScheduleIndexFixture().use { f ->
        val rows = (0 until 2050).map(::row)
        val before = ScheduleDeliveryState(revision = 42,pending = ScheduleAlarmBatch(rows.first().ticket.at,listOf(rows.first().ticket)),attempts = rows)
        val legacy = codec.encodeToString(before);f.manifest.writeDurably(legacy)
        f.store().let { s -> s.locked { assertEquals(before,s.read()) } }
        assertEquals(legacy,f.source.read());assertFails { ScheduleDeliveryJson.decode(f.manifest.read()!!) }
        val s = f.store()
        s.locked {
            assertEquals(before,s.read())
            for(a in rows) assertEquals(a,s.find(a.ticket.datasetId,a.ticket.key))
            assertNull(s.find("other-dataset",rows.first().ticket.key))
            assertFalse(s.reserve(42,rows.first().copy(status = ScheduleAttemptStatus.RESERVED)))
            assertEquals(before,s.read())
            assertFailsWith<CoreError> { s.writeDurably(ScheduleDeliveryState()) }
        }
        assertEquals(legacy,f.source.read())
    }
    @Test fun interruptedImportRollsBackAndCommittedImportCompletesOnlyTheSameMarker() {
        for(point in listOf("insert","commit","marker-before","marker-after")) ScheduleIndexFixture().use { f ->
            val legacy = ScheduleDeliveryState(revision = 7,attempts = (0..9).map(::row))
            val text = ScheduleDeliveryJson.encode(legacy);f.manifest.writeDurably(text)
            var inserts = 0
            f.beforeExecute = { if(point == "insert" && it.startsWith("INSERT INTO attempts") && ++inserts == 4) throw IOException("fixture import interruption") }
            f.afterCommit = { if(point == "commit") throw IOException("fixture durable commit receipt lost") }
            f.beforeManifest = { if(point == "marker-before") throw IOException("fixture marker interruption") }
            f.afterManifest = { if(point == "marker-after") throw IOException("fixture marker receipt lost") }
            assertFails { f.store().locked { error("initialization should refuse") } }
            f.beforeExecute = null;f.afterCommit = null;f.beforeManifest = null;f.afterManifest = null
            val reopened = f.store();reopened.locked { assertEquals(legacy,reopened.read()) }
            assertEquals(text,f.source.read())
        }
    }
    @Test fun missingOrMismatchedAuthorityCorruptRowsAndFutureVersionsNeverResetReceipts() {
        ScheduleIndexFixture().use { f ->
            val s = f.store();s.locked { assertTrue(s.reserve(0,row(1,ScheduleAttemptStatus.RESERVED))) }
            val marker = f.manifest.read()!!;val held = FileHolder(f.file)
            held.hold();assertFails { f.store().locked {} };assertEquals(marker,f.manifest.read());held.restore()
            f.sql(false).use { db -> db.execute("UPDATE attempts SET series_id='wrong' WHERE sequence=1") }
            assertFails { f.store().let { it.locked { it.find("dataset",row(1).ticket.key) } } }
            f.sql(false).use { db -> db.execute("UPDATE attempts SET series_id='series-1' WHERE sequence=1");db.execute("PRAGMA user_version=2") }
            assertFails { f.store().locked {} }
            assertEquals(marker,f.manifest.read())
        }
        ScheduleIndexFixture().use { f ->
            val legacy = ScheduleDeliveryJson.encode(ScheduleDeliveryState(attempts = listOf(row(1))))
            f.manifest.writeDurably(legacy)
            f.beforeManifest = { throw IOException("fixture") };assertFails { f.store().locked {} };f.beforeManifest = null
            val changed = ScheduleDeliveryJson.encode(ScheduleDeliveryState(attempts = listOf(row(2))))
            f.manifest.writeDurably(changed);assertFails { f.store().locked {} }
            assertEquals(changed,f.manifest.read());assertEquals(legacy,f.source.read())
        }
    }
    private class FileHolder(val file: java.io.File) {
        val held = java.io.File(file.parentFile,"held-index")
        fun hold() { check(file.renameTo(held)) }
        fun restore() { check(held.renameTo(file)) }
    }
    @Test fun invalidLegacyNeverGrantsAnEmptyAuthorityOrLosesOriginal() = ScheduleIndexFixture().use { f ->
        for(state in listOf(ScheduleDeliveryState(format = 3),ScheduleDeliveryState(attempts = listOf(row(1),row(1))),
            ScheduleDeliveryState(attempts = listOf(row(1).copy(attemptedAt = -1))),ScheduleDeliveryState(attempts = listOf(row(1).copy(ticket = row(1).ticket.copy(zone = "bad/zone")))))) {
            val text = ScheduleDeliveryJson.encode(state);f.manifest.writeDurably(text)
            assertFails { f.store().locked {} };assertEquals(text,f.manifest.read());assertNull(f.source.read())
        }
    }
    @Test fun reserveAndCompletionCommitUncertaintyCannotReplayAcrossColdReopenMoveEditOrRestore() {
        for(point in listOf("before-reserve","after-reserve","after-post","after-finish")) ScheduleIndexFixture().use { f ->
            val clock = FakeClock(wall = Instant.parse("2026-10-10T09:00:00Z").toEpochMilli());val t = Tracker(MemoryStore(),clock)
            val rule = ScheduleRule(Classification(),"2026-10-10",extraDates = setOf("2026-10-10","2026-10-11"),localTime = "10:00",reminderTime = "09:30",alertsEnabled = true)
            t.scheduleCommand(ScheduleCommand("create",t.snapshot().token,ScheduleAction.CREATE,"a",rule = rule))
            val platform = ScheduleDeliveryFixturePlatform();var s = f.store();var delivery = ScheduleDelivery(t,s,platform) { ZoneId.of("UTC") }
            val batch = delivery.synchronize().next!!;clock.advance(30*60_000)
            f.beforeExecute = { sql -> if(point == "before-reserve" && sql.startsWith("INSERT INTO attempts") || point == "after-post" && sql.startsWith("UPDATE attempts")) throw IOException("fixture SQL failure") }
            f.afterCommit = { if(point == "after-reserve" && platform.postCalls.isEmpty() || point == "after-finish" && platform.postCalls.isNotEmpty()) throw IOException("fixture committed result lost") }
            assertFailsWith<CoreError> { delivery.onAlarm(batch.hash) }
            f.beforeExecute = null;f.afterCommit = null
            val posts = platform.postCalls.size;s = f.store();delivery = ScheduleDelivery(t,s,platform) { ZoneId.of("UTC") }
            delivery.onAlarm(batch.hash)
            assertEquals(if(point == "before-reserve") 1 else posts,platform.postCalls.size)
            val stored = s.locked { s.find(batch.tickets.single().datasetId,batch.tickets.single().key)!! }
            assertEquals(if(point == "after-reserve" || point == "after-post") ScheduleAttemptStatus.RESERVED else ScheduleAttemptStatus.POSTED,stored.status)
            assertEquals(if(stored.status == ScheduleAttemptStatus.RESERVED) ScheduleAttemptStatus.UNKNOWN else stored.status,delivery.attemptsPage().attempts.single().status)
            val key = batch.tickets.single().key
            t.scheduleCommand(ScheduleCommand("move",t.snapshot().token,ScheduleAction.OVERRIDE,"a",1,occurrence = key,override = OccurrenceOverride(key,OverrideOperation.MOVE,date = "2026-10-12")))
            assertEquals("2026-10-11",delivery.synchronize().next!!.tickets.single().key.originalDate)
            val backup = StreamingBackup.portable(t.data(),clock.now())
            val restored = Tracker(MemoryStore(Backups.restoredData(backup,backup.data.revision+1)),clock)
            val afterRestore = ScheduleDelivery(restored,f.store(),platform) { ZoneId.of("UTC") }
            assertEquals("2026-10-11",afterRestore.synchronize().next!!.tickets.single().key.originalDate)
            assertEquals(stored,s.locked { s.find(stored.ticket.datasetId,key) })
            assertEquals(if(point == "before-reserve") 1 else posts,platform.postCalls.size)
        }
    }
}
