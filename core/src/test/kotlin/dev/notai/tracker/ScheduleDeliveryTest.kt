package dev.notai.tracker

import java.io.IOException
import java.time.*
import org.junit.Test
import kotlin.test.*

class ScheduleDeliveryTest {
    private fun clock() = FakeClock(wall = Instant.parse("2026-10-10T09:00:00Z").toEpochMilli())
    private fun add(t: Tracker,id: String = "a",rule: ScheduleRule = ScheduleRule(Classification(),"2026-10-10",extraDates = setOf("2026-10-10","2026-10-11"),localTime = "10:00",alertsEnabled = true,reminderTime = "09:30")) {
        t.scheduleCommand(ScheduleCommand("create-$id",t.snapshot().token,ScheduleAction.CREATE,id,rule = rule))
    }
    @Test fun optInAndExplicitTimesAreRequiredWhileQuietProjectionRemains() {
        val clock = clock();val t = Tracker(MemoryStore(),clock)
        val rule = ScheduleRule(Classification(),"2026-10-10",extraDates = setOf("2026-10-10"),localTime = "10:00",reminderTime = "09:30")
        add(t,"disabled",rule);add(t,"date-only",rule.copy(localTime = null,alertsEnabled = true));add(t,"no-reminder",rule.copy(reminderTime = null,alertsEnabled = true))
        val before = t.data();val platform = ScheduleDeliveryFixturePlatform();val state = ScheduleDeliveryFixtureStore()
        val report = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") }.synchronize()
        assertNull(report.next);assertNull(platform.pending);assertEquals(0,state.persisted.revision)
        assertEquals(3,t.listOccurrences(LocalDate.parse("2026-10-10"),LocalDate.parse("2026-10-10"),ZoneId.of("UTC")).size)
        assertEquals(before,t.data())
    }
    @Test fun permissionLossPreservesPreferenceAndOnlyDurablePendingCanCatchUp() {
        val clock = clock();val t = Tracker(MemoryStore(),clock);add(t)
        val platform = ScheduleDeliveryFixturePlatform();val state = ScheduleDeliveryFixtureStore();val delivery = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") }
        val first = delivery.synchronize().next!!;val before = t.data()
        platform.capability = ScheduleDeliveryAvailability(setOf(ScheduleDeliveryBlocker.PERMISSION))
        delivery.synchronize();assertNull(platform.pending);clock.advance(31*60_000)
        assertTrue(delivery.onAlarm(first.hash).attempted.isEmpty());assertTrue(state.persisted.attempts.isEmpty())
        platform.capability = ScheduleDeliveryAvailability();assertEquals(first,delivery.synchronize().next)
        assertEquals(ScheduleAttemptStatus.POSTED,delivery.onAlarm(first.hash).attempted.single().status);assertEquals(before,t.data())
        val fresh = ScheduleDelivery(t,ScheduleDeliveryFixtureStore(),ScheduleDeliveryFixturePlatform()) { ZoneId.of("UTC") }
        assertEquals("2026-10-11",fresh.synchronize().next!!.tickets.single().key.originalDate) // No historical backfill without a prior pending intent.
    }
    @Test fun staleCallbacksAndEarlyClockNeverPostAndMoveNeverRepeatsAReceipt() {
        val clock = clock();val t = Tracker(MemoryStore(),clock);add(t)
        val state = ScheduleDeliveryFixtureStore();val platform = ScheduleDeliveryFixturePlatform();val delivery = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") }
        val old = delivery.synchronize().next!!
        assertEquals(ScheduleDeliveryOutcome.EARLY_CALLBACK,delivery.onAlarm(old.hash).outcome)
        val rule = t.data().schedule.series.single().revisions.last().rule.copy(reminderTime = "10:30",localTime = "11:00")
        t.scheduleCommand(ScheduleCommand("later",t.snapshot().token,ScheduleAction.UPDATE_FUTURE,"a",1,rule = rule,effectiveFrom = "2026-10-10"))
        clock.advance(30*60_000);assertEquals(ScheduleDeliveryOutcome.STALE_CALLBACK,delivery.onAlarm(old.hash).outcome);assertTrue(platform.postCalls.isEmpty())
        val next = platform.pending!!;clock.advance(60*60_000);delivery.onAlarm(next.hash);assertEquals(1,platform.postCalls.size)
        val key = OccurrenceKey("a","2026-10-10")
        t.scheduleCommand(ScheduleCommand("move",t.snapshot().token,ScheduleAction.OVERRIDE,"a",2,occurrence = key,override = OccurrenceOverride(key,OverrideOperation.MOVE,date = "2026-10-12")))
        assertEquals("2026-10-11",delivery.synchronize().next!!.tickets.single().key.originalDate)
        assertTrue(platform.notices.isEmpty()) // Obsolete visible projection revoked, receipt retained.
        assertEquals(1,delivery.attempts().size)
    }
    @Test fun storageUncertaintyCannotProduceDuplicatePostsOrLoseReceipts() {
        for(point in listOf("before-reserve","after-reserve","after-post")) {
            val clock = clock();val t = Tracker(MemoryStore(),clock);add(t)
            val state = ScheduleDeliveryFixtureStore();val platform = ScheduleDeliveryFixturePlatform();val delivery = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") }
            val batch = delivery.synchronize().next!!;clock.advance(30*60_000)
            state.beforeWrite = { s -> if(point == "before-reserve" && s.attempts.any { it.status == ScheduleAttemptStatus.RESERVED } || point == "after-post" && s.attempts.any { it.status == ScheduleAttemptStatus.POSTED }) throw IOException("fixture journal failure") }
            state.afterWrite = { s -> if(point == "after-reserve" && s.attempts.any { it.status == ScheduleAttemptStatus.RESERVED }) throw IOException("fixture uncertain fsync") }
            assertEquals(ErrorCode.STORAGE,assertFailsWith<CoreError> { delivery.onAlarm(batch.hash) }.code)
            assertEquals(if(point == "after-post") 1 else 0,platform.postCalls.size)
            state.beforeWrite = null;state.afterWrite = null
            val reopened = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") }
            if(point != "before-reserve") assertEquals(ScheduleAttemptStatus.UNKNOWN,reopened.attempts().single().status)
            reopened.onAlarm(batch.hash);assertEquals(if(point == "after-reserve") 0 else 1,platform.postCalls.size)
            assertTrue(t.data().sessions.isEmpty())
        }
    }
    @Test fun postExceptionIsUnknownAndSecondTieRechecksFreshOptIn() {
        val clock = clock();val t = Tracker(MemoryStore(),clock);add(t,"a");add(t,"b")
        val state = ScheduleDeliveryFixtureStore();val platform = ScheduleDeliveryFixturePlatform();val delivery = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") }
        platform.prepareAction = { ticket,_ ->
            assertEquals(ScheduleAttemptStatus.RESERVED,state.persisted.attempts.single { it.ticket.identity == ticket.identity }.status)
            val other = if(ticket.key.seriesId == "a") "b" else "a"
            t.scheduleCommand(ScheduleCommand("archive-other",t.snapshot().token,ScheduleAction.SET_STATE,other,1,state = ScheduleState.ARCHIVED))
        }
        platform.postAction = { ticket,_ ->
            platform.notices[ticket.identity] = ticket;throw IOException("fixture: post accepted, result lost")
        }
        val batch = delivery.synchronize().next!!;clock.advance(30*60_000)
        assertEquals(ScheduleAttemptStatus.UNKNOWN,delivery.onAlarm(batch.hash).attempted.single().status)
        assertEquals(1,platform.postCalls.size);delivery.onAlarm(batch.hash);assertEquals(1,platform.postCalls.size)
    }
    @Test fun nextReminderMatchesProjectionAndHasNoTenYearHorizon() {
        val rule = ScheduleRule(Classification(),"2026-01-01",weekdays = setOf(7),localTime = "02:30",reminderTime = "02:20",alertsEnabled = true,zonePolicy = ScheduleZonePolicy.FIXED,zoneId = "Europe/Berlin")
        val d = Dataset(schedule = ScheduleData(series = listOf(ScheduleSeries("dst",listOf(ScheduleRevision(rule.startDate,rule))))))
        for(now in listOf(Instant.parse("2026-03-28T12:00:00Z"),Instant.parse("2026-10-24T12:00:00Z"))) {
            val first = now.atZone(ZoneId.of("UTC")).toLocalDate();val rows = Schedules.project(d,first,first.plusDays(14),now,ZoneId.of("UTC"))
            val expected = rows.filter { it.reminderInstant?.isAfter(now) == true }.minBy { it.reminderInstant!! }
            assertEquals(listOf(expected),Schedules.nextReminders(d,now,ZoneId.of("UTC")))
        }
        val start = LocalDate.parse("2026-10-10");val skips = (0 until 600).map { OccurrenceOverride(OccurrenceKey("skip",start.plusWeeks(it.toLong()).toString()),OverrideOperation.SKIP) }
        val weekly = rule.copy(startDate = start.toString(),weekdays = setOf(6),zoneId = "UTC")
        val skipped = Dataset(schedule = ScheduleData(series = listOf(ScheduleSeries("skip",listOf(ScheduleRevision(weekly.startDate,weekly)))),overrides = skips))
        validate(skipped)
        assertEquals(start.plusWeeks(600),Schedules.nextReminders(skipped,Instant.parse("2026-10-09T00:00:00Z"),ZoneId.of("UTC")).single().date)
        val farRule = weekly.copy(weekdays = emptySet(),extraDates = setOf("9999-12-30"))
        val far = skipped.copy(schedule = ScheduleData(series = listOf(ScheduleSeries("far",listOf(ScheduleRevision(farRule.startDate,farRule))))))
        assertEquals(LocalDate.parse("9999-12-30"),Schedules.nextReminders(far,Instant.parse("2026-10-09T00:00:00Z"),ZoneId.of("UTC")).single().date)
    }
    @Test fun concurrentReceiversAndReentrantRendererCannotOverwriteOrRepeatReceipts() {
        val clock = clock();val t = Tracker(MemoryStore(),clock);add(t)
        val state = ScheduleDeliveryFixtureStore();val platform = ScheduleDeliveryFixturePlatform()
        val delivery = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") };val batch = delivery.synchronize().next!!;clock.advance(30*60_000)
        val entered = java.util.concurrent.CountDownLatch(1);val finish = java.util.concurrent.CountDownLatch(1)
        platform.postAction = { _,_ -> entered.countDown();check(finish.await(5,java.util.concurrent.TimeUnit.SECONDS));true }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<ScheduleDeliveryReport> { delivery.onAlarm(batch.hash) }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val other = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") }
            val b = pool.submit<ScheduleDeliveryReport> { other.onAlarm(batch.hash) }
            finish.countDown();a.get(5,java.util.concurrent.TimeUnit.SECONDS);b.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1,platform.postCalls.size);assertEquals(1,state.persisted.attempts.size)
        } finally { finish.countDown();pool.shutdownNow();pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS) }

        val clock2 = clock();val t2 = Tracker(MemoryStore(),clock2);add(t2,"a");add(t2,"b")
        val state2 = ScheduleDeliveryFixtureStore();val platform2 = ScheduleDeliveryFixturePlatform()
        val delivery2 = ScheduleDelivery(t2,state2,platform2) { ZoneId.of("UTC") };val ties = delivery2.synchronize().next!!;clock2.advance(30*60_000)
        var reentered = false
        platform2.prepareAction = { _,_ -> if(!reentered) { reentered = true;delivery2.onAlarm(ties.hash) } }
        delivery2.onAlarm(ties.hash)
        assertEquals(2,platform2.postCalls.size);assertEquals(2,state2.persisted.attempts.size)
        assertTrue(state2.persisted.attempts.all { it.status == ScheduleAttemptStatus.POSTED })
    }
    @Test fun independentPlatformFailuresStillCancelAlarmAndDisableWakeupsWithoutConsumingReceipts() {
        for(point in listOf("availability","visible","cancel-notice","cancel-alarm","schedule","wakeups")) {
            val clock = clock();val t = Tracker(MemoryStore(),clock);add(t)
            val state = ScheduleDeliveryFixtureStore();val delegate = ScheduleDeliveryFixturePlatform()
            var fail = false;var cancellations = 0;val wakeups = mutableListOf<Boolean>()
            val platform = object: ScheduleDeliveryPlatform by delegate {
                override fun availability(): ScheduleDeliveryAvailability {
                    if(fail && point == "availability") throw IOException("fixture availability")
                    return delegate.availability()
                }
                override fun visible(): List<ScheduleReminderTicket> {
                    if(fail && point == "visible") throw IOException("fixture visible")
                    return delegate.visible()
                }
                override fun cancelNotice(ticket: ScheduleReminderTicket) {
                    if(fail && point == "cancel-notice") throw IOException("fixture notice cancellation")
                    delegate.cancelNotice(ticket)
                }
                override fun cancelAlarm() {
                    cancellations++
                    if(fail && point == "cancel-alarm") throw IOException("fixture alarm cancellation")
                    delegate.cancelAlarm()
                }
                override fun schedule(batch: ScheduleAlarmBatch) {
                    if(fail && point == "schedule") throw IOException("fixture scheduling")
                    delegate.schedule(batch)
                }
                override fun configureWakeups(enabled: Boolean) {
                    wakeups += enabled
                    if(fail && point == "wakeups") throw IOException("fixture hook update")
                }
            }
            val delivery = ScheduleDelivery(t,state,platform) { ZoneId.of("UTC") };val armed = delivery.synchronize().next!!
            // An obsolete notice requires cancellation while tomorrow's valid alarm remains.
            delegate.notices["old"] = armed.tickets.single().copy(seriesRevision = 99)
            fail = true;cancellations = 0;wakeups.clear()
            val result = delivery.synchronize()
            assertTrue(ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE in result.availability.blockers,point)
            assertEquals(1,cancellations,point);assertEquals(listOf(point == "wakeups"),wakeups,point)
            assertEquals(point == "wakeups",result.alarmAccepted,point)
            assertTrue(state.persisted.attempts.isEmpty(),point);assertTrue(t.data().sessions.isEmpty(),point)
            if(point == "availability") {
                clock.advance(30*60_000);assertTrue(delivery.onAlarm(armed.hash).attempted.isEmpty())
                assertTrue(delegate.postCalls.isEmpty())
            }
            fail = false;val restored = delivery.synchronize();assertTrue(restored.alarmAccepted,point)
        }
    }
    @Test fun receiptCapacityRefusalPreservesEveryReceiptDisarmsAndNeverPosts() {
        val clock = clock();val t = Tracker(MemoryStore(),clock);add(t)
        val delegate = ScheduleDeliveryFixturePlatform();val state = ScheduleDeliveryFixtureStore()
        var limit = CopyMetadataBytes.MAX_BYTES;var disabled = 0
        val bounded = object: ScheduleDeliveryStore by state {
            override fun writeDurably(value: ScheduleDeliveryState) {
                CopyMetadataBytes.check(codec.encodeToString(value),limit);state.writeDurably(value)
            }
        }
        val platform = object: ScheduleDeliveryPlatform by delegate {
            override fun configureWakeups(enabled: Boolean) { if(!enabled) disabled++ }
        }
        val delivery = ScheduleDelivery(t,bounded,platform) { ZoneId.of("UTC") };val armed = delivery.synchronize().next!!
        val template = armed.tickets.single()
        val old = (1..128).map { n -> ScheduleReminderAttempt(template.copy(key = OccurrenceKey("retired-$n","2026-10-09")),clock.wall,
            ScheduleAttemptStatus.entries[n%ScheduleAttemptStatus.entries.size]) }
        state.persisted = state.persisted.copy(attempts = old)
        val saved = state.read();limit = CopyMetadataBytes.encode(codec.encodeToString(saved)).size
        clock.advance(30*60_000)
        repeat(2) {
            assertEquals(ErrorCode.STORAGE,assertFailsWith<CoreError> { delivery.onAlarm(armed.hash) }.code)
            assertEquals(saved,state.persisted);assertTrue(delegate.postCalls.isEmpty());assertNull(delegate.pending)
        }
        assertEquals(2,disabled);assertEquals(128,delivery.attempts().size)
        limit = CopyMetadataBytes.MAX_BYTES // Explicitly provisioned test-store capacity; no receipt removal.
        assertEquals(ScheduleAttemptStatus.POSTED,delivery.onAlarm(armed.hash).attempted.single().status)
        assertEquals(old,state.persisted.attempts.take(old.size));assertEquals(1,delegate.postCalls.size)
        delivery.onAlarm(armed.hash);assertEquals(1,delegate.postCalls.size)
        assertTrue(t.data().sessions.isEmpty())
    }
    @Test fun compactOperationalJsonReadsLegacyWithoutChangingReceiptsOrCallbackHashes() {
        val clock = clock();val tracker = Tracker(MemoryStore(),clock);add(tracker)
        val state = ScheduleDeliveryFixtureStore();val platform = ScheduleDeliveryFixturePlatform()
        val batch = ScheduleDelivery(tracker,state,platform) { ZoneId.of("UTC") }.synchronize().next!!
        val before = state.persisted.copy(attempts = ScheduleAttemptStatus.entries.mapIndexed { n,status ->
            ScheduleReminderAttempt(batch.tickets.single().copy(key = OccurrenceKey("old-$n","2026-10-09")),clock.wall,status)
        })
        val legacy = codec.encodeToString(before);val compact = ScheduleDeliveryJson.encode(before)
        assertTrue(compact.length < legacy.length)
        assertEquals(before,ScheduleDeliveryJson.decode(legacy));assertEquals(before,ScheduleDeliveryJson.decode(compact))
        assertEquals(batch.hash,ScheduleDeliveryJson.decode(compact).pending!!.hash)
        assertEquals(before.attempts.map { it.ticket.identity },ScheduleDeliveryJson.decode(compact).attempts.map { it.ticket.identity })
        assertEquals(before,codec.decodeFromString<ScheduleDeliveryState>(compact))
    }
}
