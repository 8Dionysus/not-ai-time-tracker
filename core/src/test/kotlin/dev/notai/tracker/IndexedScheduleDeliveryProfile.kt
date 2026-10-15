package dev.notai.tracker

import java.time.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Synthetic host SQLite, existing cached JDBC driver; never a device benchmark. */
object IndexedScheduleDeliveryProfile {
    @Serializable data class Sample(val receipts: Int,val fileBytes: Long,val syncMillis: List<Double>,val medianMillis: Double,
        val queries: Int,val returnedRows: Int,val sqlWrites: Int)
    @Serializable data class Evidence(val scope: String,val samples: List<Sample>,val identityQueryPlan: List<String>,
        val receiptPayloadBytes: Long,val successfulReserveBeyondLegacyLimit: Boolean,val oldReceiptsDigestBefore: String,
        val oldReceiptsDigestAfter: String,val coldReopenReceiptCount: Long,val posted: Int,val migrationPolicy: String)
    @JvmStatic fun main(args: Array<String>) = ScheduleIndexFixture().use { f ->
        val clock = FakeClock(wall = Instant.parse("2026-10-10T09:00:00Z").toEpochMilli());val t = Tracker(MemoryStore(),clock)
        val rule = ScheduleRule(Classification(),"2026-10-10",extraDates = setOf("2026-10-10","2026-10-11"),localTime = "10:00",reminderTime = "09:30",alertsEnabled = true)
        t.scheduleCommand(ScheduleCommand("profile-index",t.snapshot().token,ScheduleAction.CREATE,"active",rule = rule))
        val platform = ScheduleDeliveryFixturePlatform();val first = ScheduleDelivery(t,f.store(),platform) { ZoneId.of("UTC") }.synchronize().next!!
        val json = Json(codec) { prettyPrint = false };val template = first.tickets.single();var seeded = 0
        val samples = mutableListOf<Sample>()
        for(n in listOf(0,1000,10000,47392,100000)) {
            f.sql(false).use { db -> db.transaction {
                for(i in seeded until n) {
                    val a = ScheduleReminderAttempt(template.copy(key = OccurrenceKey("retired-$i","2026-10-09")),clock.wall,ScheduleAttemptStatus.entries[i%4])
                    db.execute("INSERT INTO attempts (identity,dataset_id,series_id,original_date,payload) VALUES (?,?,?,?,?)",
                        listOf(a.ticket.identity,a.ticket.datasetId,a.ticket.key.seriesId,a.ticket.key.originalDate,json.encodeToString(a)))
                }
            } };seeded = n
            // Recreate both store and SQL connection for every measured sync. OS page cache stays warm.
            f.resetCounts()
            val times = (0 until 3).map {
                val start = System.nanoTime();check(ScheduleDelivery(t,f.store(),platform) { ZoneId.of("UTC") }.synchronize().alarmAccepted)
                (System.nanoTime()-start)/1_000_000.0
            }
            check(f.executions == 0 && f.returnedRows <= 30)
            samples += Sample(n,f.file.length(),times,times.sorted()[1],f.queryCount,f.returnedRows,f.executions)
        }
        fun fingerprint(): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256");val s = f.store();var seen = 0
            s.locked {
                var cursor = 0L
                do { val page = s.page(cursor,1024)
                    page.attempts.filter { it.ticket.key.seriesId.startsWith("retired-") }.forEach { digest.update(json.encodeToString(it).toByteArray());seen++ }
                    cursor = page.nextAfterSequence ?: break
                } while(true)
            }
            check(seen == 100000)
            return sha256(digest.digest())
        }
        val before = fingerprint()
        val (plan,bytes) = f.sql(false).use { db ->
            db.query("EXPLAIN QUERY PLAN SELECT payload FROM attempts WHERE identity=?",listOf(template.identity)).map { it.last()!! } to
                db.query("SELECT sum(length(CAST(payload AS BLOB))) FROM attempts").single().single()!!.toLong()
        }
        check(plan.any { it.contains("SEARCH") && it.contains("INDEX") } && bytes > CopyMetadataBytes.MAX_BYTES)
        clock.advance(30*60_000)
        val result = ScheduleDelivery(t,f.store(),platform) { ZoneId.of("UTC") }.onAlarm(first.hash)
        check(result.attempted.single().status == ScheduleAttemptStatus.POSTED && platform.postCalls.size == 1)
        val reopened = ScheduleDelivery(t,f.store(),platform) { ZoneId.of("UTC") };reopened.onAlarm(first.hash)
        val after = fingerprint();check(before == after && platform.postCalls.size == 1)
        val count = f.sql(false).use { it.query("SELECT count(*) FROM attempts").single().single()!!.toLong() };check(count == 100001L)
        val evidence = Evidence("HOST JVM/SQLite JDBC, 1GiB max heap, synthetic POSIX temp root; cold SQL connections, warm OS page cache; no phone/PSS/battery claim",
            samples,plan,bytes,true,before,after,count,platform.postCalls.size,"No eviction/retention. Exact identities/statuses. v1 source retained; durable SQLite commit precedes v2 authority marker.")
        val output = java.io.File(requireNotNull(System.getProperty("tracker.schedule.index.profile")))
        output.parentFile.mkdirs();output.writeText(codec.encodeToString(evidence));println(output.readText())
    }
}
