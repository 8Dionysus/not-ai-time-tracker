package dev.notai.tracker

import java.time.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class FunctionalCustomizationTest {
    @Test fun changingDayPolicyDuringRenderingCannotPostAStaleRelativeDayCaption() {
        val clock = FakeClock(wall = Instant.parse("2026-10-10T09:00:00Z").toEpochMilli());val t = Tracker(MemoryStore(),clock)
        val rule = ScheduleRule(Classification(),"2026-10-10",extraDates = setOf("2026-10-10"),localTime = "10:00",reminderTime = "09:30",alertsEnabled = true)
        t.scheduleCommand(ScheduleCommand("create",t.snapshot().token,ScheduleAction.CREATE,"a",rule = rule))
        val receipts = ScheduleDeliveryFixtureStore();val platform = ScheduleDeliveryFixturePlatform()
        platform.prepareAction = { _,projection ->
            assertEquals(OccurrenceState.TODAY,projection.state)
            t.configureSchedule(t.snapshot().token,ScheduleSettings(ScheduleDayPolicy(boundary = "10:00")))
        }
        val delivery = ScheduleDelivery(t,receipts,platform) { ZoneId.of("UTC") };val batch = delivery.synchronize().next!!
        clock.advance(30*60_000)
        assertEquals(ScheduleAttemptStatus.NOT_POSTED,delivery.onAlarm(batch.hash).attempted.single().status)
        assertTrue(platform.postCalls.isEmpty());assertTrue(t.data().sessions.isEmpty())
        assertEquals(1,t.data().schedule.series.single().revision) // A ticket revision alone could not detect this change.
    }
    @Test fun largeOwnHierarchyCanBeSelectedWithoutFactsAndKeepsExactClosure() {
        val types = (0 until 2000).map { n -> ActivityType("own-$n","Личный тип $n",parentId = if(n == 0) null else "own-${n-1}") }
        val tracker = Tracker(MemoryStore(),FakeClock());tracker.addCatalog(tracker.snapshot().token,VocabularyData(types))
        val transfer = SelectiveTransfer(tracker)
        val all = transfer.export(TransferSelection(typeIds = types.map { it.id }.toSet()))
        assertEquals(1,all.groups.size);assertEquals(types,all.groups.single().content.types)
        val leaf = transfer.export(TransferSelection(typeIds = setOf(types.last().id)))
        assertEquals(types,leaf.groups.single().content.types)
        assertEquals(setOf(types.last().id),leaf.groups.single().content.catalogRoots.typeIds)
        assertEquals(1,transfer.inspect(leaf).groups.size);assertTrue(tracker.data().sessions.isEmpty())
    }
    @Test fun functionalValidationUsesTypedReasonsAndStableFieldPaths() {
        val tracker = Tracker(MemoryStore(),FakeClock())
        val invalid = assertFailsWith<CoreError> { tracker.configureSchedule(tracker.snapshot().token,ScheduleSettings(ScheduleDayPolicy(boundary = "24:00"))) }
        assertEquals(ErrorReason.SCHEDULE_DAY_INVALID,invalid.reason)
        assertEquals("tracker.error.schedule_day_invalid",invalid.localized.resourceKey)
        assertEquals("settings.schedule.day.boundary",invalid.fieldPath)
        assertEquals(ErrorArgument.Text("24:00"),invalid.arguments["value"])
        assertEquals(ErrorArgument.Text("INVALID_LOCAL_TIME"),invalid.arguments["rule"])
        val missing = assertFailsWith<CoreError> { SelectiveTransfer(tracker).export(TransferSelection(cardIds = setOf("absent-card"))) }
        assertEquals(ErrorReason.CATALOG_ROOT_INVALID,missing.reason)
        assertEquals("transfer.selection.cardIds",missing.fieldPath)
        assertEquals(ErrorArgument.Identifier("absent-card"),missing.arguments["rootId"])
        assertTrue(tracker.data().cards.isEmpty())
    }
    @Test fun legacySelectiveHashesRemainExactAndNewRootSetsHaveCanonicalIdentity() {
        // Captured from compiled d0888a5 before adding these optional fields, not from this implementation.
        assertEquals("98da5d0cbf280af87cbb991dbaec57298cd7954d13ea843ba29badb1b127c8dd",SelectiveTransfer.contentHash(TransferContent()))
        val card = Card("owned-card","Заратустра")
        assertEquals("f3bc982e330f410584ceb6b4b845d7a54b128589fff25127783df390e851e2c5",SelectiveTransfer.contentHash(TransferContent(cards = listOf(card))))
        assertFalse("catalogRoots" in codec.encodeToJsonElement(TransferContent()).jsonObject)
        // Captured by a Java serializer probe against compiled 8aaa890, before manualConfirmation existed.
        val legacy = TransferContent(sessions = listOf(Session("legacy-session",SessionStatus.STOPPED)),
            segments = listOf(Segment("legacy-segment","legacy-session",1000,2000,1000)))
        assertEquals("d6d96de142abc2d24549c36758d5e4a1757e6b9c6bdf46e2212a4ee03c9634bd",SelectiveTransfer.contentHash(legacy))
        assertFalse("manualConfirmation" in codec.encodeToJsonElement(legacy.segments.single()).jsonObject)
        val other = card.copy(id = "other")
        val a = TransferContent(cards = listOf(card,other),catalogRoots = TransferCatalogRoots(linkedSetOf(card.id,other.id)))
        val b = a.copy(catalogRoots = TransferCatalogRoots(linkedSetOf(other.id,card.id)))
        assertEquals(SelectiveTransfer.contentHash(a),SelectiveTransfer.contentHash(b))
        val group = SelectiveTransfer.group("catalog","origin",1,a)
        val importer = SelectiveTransfer(Tracker(MemoryStore(),FakeClock()))
        val value = SelectivePackage(sourceDatasetId = "source",groups = listOf(group))
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { importer.inspect(value) }.code)
        assertEquals(1,importer.inspect(value.copy(formatVersion = 2,minimumReader = 2)).groups.size)
        assertFailsWith<CoreError> { codec.decodeFromString<TransferCatalogRoots>("""{"cardIds":["a","a"],"typeIds":[]}""") }
        val missing = a.copy(catalogRoots = TransferCatalogRoots(setOf("missing")))
        assertFailsWith<CoreError> { importer.inspect(value.copy(formatVersion = 2,minimumReader = 2,groups = listOf(SelectiveTransfer.group("catalog","origin",1,missing)))) }
    }
    @Test fun nonMidnightDayBoundaryUsesOneResolvedInstantAcrossDstGapAndFold() {
        val zone = ZoneId.of("Europe/Berlin");val boundary = LocalTime.of(2,30)
        val cases = listOf(
            "2026-03-29T00:59:59Z" to "2026-03-28", "2026-03-29T01:00:00Z" to "2026-03-29",
            "2026-10-25T00:29:59Z" to "2026-10-24", "2026-10-25T00:30:00Z" to "2026-10-25",
            "2026-10-25T01:00:00Z" to "2026-10-25", "2026-10-25T01:29:59Z" to "2026-10-25")
        cases.forEach { (at,date) -> assertEquals(LocalDate.parse(date),ScheduleDayResolver.date(Instant.parse(at),zone,boundary),at) }
    }
    @Test fun legacyArchiveCannotSmuggleNewSettingsOrCatalogRootReceipts() {
        val clock = FakeClock();val plain = Backups.encode(Dataset(),clock.now())
        fun asV9(text: String): String = JsonObject(codec.parseToJsonElement(text).jsonObject+("formatVersion" to JsonPrimitive(9))).toString()
        assertEquals(CURRENT_BACKUP_FORMAT,Backups.decode(asV9(plain)).formatVersion)
        assertEquals(CURRENT_BACKUP_FORMAT,StreamingBackup.read { asV9(plain).byteInputStream() }.formatVersion)
        val settings = Settings(schedule = ScheduleSettings(ScheduleDayPolicy(boundary = "04:00"),true))
        val card = Card("owned-card","Заратустра");val content = TransferContent(cards = listOf(card),catalogRoots = TransferCatalogRoots(setOf(card.id)))
        val hash = SelectiveTransfer.contentHash(content)
        val origin = TransferOrigin("owned-origin",hash,content,hash)
        for(d in listOf(Dataset(settings = settings),Dataset(cards = listOf(card),transfer = TransferState(origins = listOf(origin))))) {
            val legacy = asV9(Backups.encode(d,clock.now()))
            assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { Backups.decode(legacy) }.code)
            assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { StreamingBackup.read { legacy.byteInputStream() } }.code)
        }
    }
}
