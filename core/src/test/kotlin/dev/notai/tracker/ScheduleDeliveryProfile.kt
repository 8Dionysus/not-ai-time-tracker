package dev.notai.tracker

import java.nio.file.Files
import java.time.*
import kotlinx.serialization.Serializable

/** Opt-in host experiment using a new temporary root; no real/user/Android files. */
object ScheduleDeliveryProfile {
    @Serializable data class Sample(val receipts: Int,val utf8Bytes: Int,val syncMillis: List<Double>,
        val medianMillis: Double,val maxMillis: Double,val stateReads: Int,val stateWrites: Int,
        val sampledJvmHeapBefore: Long,val sampledJvmHeapAfter: Long)
    @Serializable data class Evidence(val scope: String,val limitBytes: Int,val maxFixtureReceipts: Int,
        val samples: List<Sample>,val ceilingReserveRejected: Boolean,val receiptsUnchanged: Boolean,
        val postCalls: Int,val alarmCancelled: Boolean,val hooksDisabled: Boolean,val elapsedMillis: Double)
    @JvmStatic fun main(args: Array<String>) {
        val library = requireNotNull(System.getProperty("tracker.cleanup.library"))
        val root = Files.createTempDirectory("tracker-schedule-capacity-").toFile();val started = System.nanoTime()
        try { DescriptorCopyFiles(root.absolutePath,library).use { files ->
            val clock = FakeClock(wall = Instant.parse("2026-10-10T09:00:00Z").toEpochMilli())
            val tracker = Tracker(MemoryStore(),clock)
            val rule = ScheduleRule(Classification(),"2026-10-10",extraDates = setOf("2026-10-10","2026-10-11"),
                localTime = "10:00",reminderTime = "09:30",alertsEnabled = true)
            tracker.scheduleCommand(ScheduleCommand("profile-create",tracker.snapshot().token,ScheduleAction.CREATE,"next",rule = rule))
            val fixture = ScheduleDeliveryFixtureStore();val initial = ScheduleDelivery(tracker,fixture,ScheduleDeliveryFixturePlatform()) { ZoneId.of("UTC") }.synchronize()
            val template = initial.next!!.tickets.single();val base = fixture.persisted
            fun row(n: Int): ScheduleReminderAttempt {
                val date = LocalDate.parse("2020-01-01").plusDays((n%400).toLong())
                return ScheduleReminderAttempt(template.copy(controlBarrier = "00000000-0000-0000-0000-000000000000",
                    key = OccurrenceKey("00000000-0000-0000-0000-"+"${n/400}".padStart(12,'0'),date.toString()),
                    zone = "America/Argentina/Buenos_Aires",at = date.atTime(9,30).toInstant(ZoneOffset.UTC).toEpochMilli()),
                    clock.wall,ScheduleAttemptStatus.POSTED)
            }
            fun encoded(state: ScheduleDeliveryState) = ScheduleDeliveryJson.encode(state)
            val one = CopyMetadataBytes.encode(encoded(base.copy(attempts = listOf(row(0))))).size
            val two = CopyMetadataBytes.encode(encoded(base.copy(attempts = listOf(row(0),row(1))))).size
            val perRow = two-one;val overhead = one-perRow
            val count = (CopyMetadataBytes.MAX_BYTES-overhead)/perRow
            val rows = (0 until count).map(::row).toMutableList()
            val size = CopyMetadataBytes.encode(encoded(base.copy(attempts = rows))).size
            val padding = CopyMetadataBytes.MAX_BYTES-size
            check(padding in 0 until perRow)
            rows[0] = rows[0].copy(ticket = rows[0].ticket.copy(controlBarrier = rows[0].ticket.controlBarrier+"x".repeat(padding)))
            val samples = mutableListOf<Sample>();val metadata = files.metadata()
            for(n in listOf(0,1000,10000,count).distinct()) {
                val source = base.copy(attempts = rows.take(n));val text = encoded(source)
                val bytes = CopyMetadataBytes.encode(text).size;metadata.writeDurably(text)
                var reads = 0;var writes = 0
                val store = object: ScheduleDeliveryStore {
                    @Synchronized override fun <T> locked(block: ()->T) = block()
                    override fun read(): ScheduleDeliveryState { reads++;return ScheduleDeliveryJson.decode(metadata.read()!!) }
                    override fun writeDurably(state: ScheduleDeliveryState) { writes++;metadata.writeDurably(encoded(state)) }
                }
                val delegate = ScheduleDeliveryFixturePlatform();var hooks = true
                val platform = object: ScheduleDeliveryPlatform by delegate {
                    override fun configureWakeups(enabled: Boolean) { hooks = enabled }
                }
                val delivery = ScheduleDelivery(tracker,store,platform) { ZoneId.of("UTC") }
                fun heap() = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
                val before = heap();val times = (0 until 3).map {
                    val begin = System.nanoTime();check(delivery.synchronize().alarmAccepted);(System.nanoTime()-begin)/1_000_000.0
                }
                check(writes == 0 && reads == 3)
                samples += Sample(n,bytes,times,times.sorted()[1],times.max(),reads,writes,before,heap())
                if(n == count) {
                    check(bytes == CopyMetadataBytes.MAX_BYTES)
                    val digest = sha256(files.read("maintenance.json"));val data = tracker.data()
                    clock.advance(30*60_000)
                    var rejected = false
                    try { delivery.onAlarm(initial.next.hash) } catch(e: CoreError) { check(e.code == ErrorCode.STORAGE);rejected = true }
                    check(rejected && delegate.postCalls.isEmpty() && !hooks && delegate.pending == null)
                    check(digest == sha256(files.read("maintenance.json")) && store.read().attempts == source.attempts)
                    check(tracker.data() == data)
                    val evidence = Evidence("HOST JVM, 1GiB max heap, POSIX temp files/page cache; three sequential syncs; no Android/OEM/RAM/PSS/battery or unlimited-capacity claim",
                        CopyMetadataBytes.MAX_BYTES,count,samples,true,true,delegate.postCalls.size,delegate.pending == null,!hooks,(System.nanoTime()-started)/1_000_000.0)
                    val output = java.io.File(requireNotNull(System.getProperty("tracker.schedule.profile")))
                    output.parentFile.mkdirs();output.writeText(codec.encodeToString(evidence));println(output.readText())
                }
            }
        } } finally { root.deleteRecursively() } // Only this invocation's newly created fixture.
    }
}
