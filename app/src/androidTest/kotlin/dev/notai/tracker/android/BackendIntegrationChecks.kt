package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.*

/** Explicit instrumentation only: fresh UUID-owned files, no live dataset or external document. */
object BackendIntegrationChecks {
    fun run(context: Context,mode: String,tag: String): JsonObject {
        require(tag.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        val create = mode == "backend-integration-create"
        check(create || mode == "backend-integration-reopen")
        val live = context.getDatabasePath("tracker.db")
        val liveBefore = if(live.exists()) fileHash(live) else "absent"
        val root = File(context.noBackupFilesDir,"qa-integration-$tag")
        if(create) { check(!root.exists());check(root.mkdir());syncDirectory(root.parentFile!!) } else check(root.isDirectory)
        val state = File(root,"fixture-state.json")
        val saved = if(create) null else codec.parseToJsonElement(state.readText()).jsonObject
        val rootStat = android.system.Os.lstat(context.noBackupFilesDir.canonicalPath)
        // Real Context binder, including /data search-only ancestors. Never releases the legacy witness.
        PrivateCopyBackend.openSession(context).use { it.inventory();it.operations() }
        val index = IndexedScheduleDeliveryStore(Any(),PrivateFileMaintenance.metadata(File(root,"delivery.json")),
            PrivateFileMaintenance.metadata(File(root,"delivery-v1.json"))) { AndroidScheduleReceiptSql(File(root,"receipts.sqlite"),it) }
        val legacyRows = (0 until 32).map { n -> ScheduleReminderAttempt(ScheduleReminderTicket(
            "qa-dataset-$tag","qa-barrier",OccurrenceKey("qa-series-$n","2026-10-10"),1,null,"UTC",1791624600000),
            1791622800000,ScheduleAttemptStatus.entries[n%4]) }
        val legacyText = ScheduleDeliveryJson.encode(ScheduleDeliveryState(revision = 42,attempts = legacyRows))
        val reserved = legacyRows.first().copy(ticket = legacyRows.first().ticket.copy(key = OccurrenceKey("qa-new","2026-10-11")),status = ScheduleAttemptStatus.RESERVED)
        if(create) PrivateFileMaintenance.metadata(File(root,"delivery.json")).writeDurably(legacyText)
        index.locked {
            if(create) {
                check(index.read().attempts == legacyRows)
                check(index.reserve(index.control().revision,reserved))
                index.complete(reserved,reserved.copy(status = ScheduleAttemptStatus.POSTED))
            }
            check(index.read().attempts == legacyRows+reserved.copy(status = ScheduleAttemptStatus.POSTED))
            check(!index.reserve(index.control().revision,reserved))
            check(index.page(30,3).attempts == (legacyRows+reserved.copy(status = ScheduleAttemptStatus.POSTED)).drop(30))
        }
        check(File(root,"delivery-v1.json").readText() == legacyText)
        val dbName = "qa-backend-$tag.db"
        if(create) check(!context.getDatabasePath(dbName).exists())
        val db = TrackerDatabase.open(context,dbName)
        var domainHash: String
        try {
            var wall = saved?.getValue("wall")?.jsonPrimitive?.long ?: System.currentTimeMillis()-60_000
            var elapsed = saved?.getValue("elapsed")?.jsonPrimitive?.long ?: 100_000L
            val tracker = Tracker(RoomStore(db),Clock { Moment(wall,elapsed,"qa-integration-boot") })
            if(create) {
                tracker.saveType(tracker.snapshot().token,ActivityType("qa-reading","Чтение"))
                tracker.saveType(tracker.snapshot().token,ActivityType("qa-philosophy","Философия",parentId = "qa-reading"))
                val card = Card("qa-book","Заратустра",activityTypeId = "qa-philosophy")
                tracker.saveCard(tracker.snapshot().token,card)
                val shown = tracker.snapshot()
                tracker.settings(shown.token,tracker.data().settings.copy(surfaces = SurfaceSettings(shortcut = false)))
                val disabled = tracker.data()
                check(runCatching { TimerSurfaces.execute(tracker,SurfaceAction(Surface.SHORTCUT,TimerCommand.START,shown.token,shown.timerTarget)) }.exceptionOrNull() is CoreError)
                check(disabled == tracker.data())
                tracker.start(tracker.snapshot().token,card.classification())
                wall += 1000;elapsed += 1000;tracker.pause(tracker.snapshot().token)
                wall += 1000;elapsed += 1000;tracker.resume(tracker.snapshot().token)
                wall += 2000;elapsed += 2000;tracker.stop(tracker.snapshot().token)
                check(tracker.data().segments.sumOf { it.durationMs } == 3000L)
                tracker.configureSchedule(tracker.snapshot().token,ScheduleSettings(ScheduleDayPolicy(boundary = "04:00"),true))
                val rule = ScheduleRule(card.classification(),"2026-10-10",extraDates = setOf("2026-10-10"))
                tracker.scheduleCommand(ScheduleCommand("qa-create",tracker.snapshot().token,ScheduleAction.CREATE,"qa-date-only",rule = rule))
                val transfer = SelectiveTransfer(tracker)
                val pkg = transfer.export(TransferSelection(cardIds = setOf(card.id)))
                val destinationName = "qa-backend-target-$tag.db"
                check(!context.getDatabasePath(destinationName).exists())
                val destinationDb = TrackerDatabase.open(context,destinationName)
                try {
                    val destination = Tracker(RoomStore(destinationDb),Clock { Moment(wall,elapsed,"qa-integration-boot") })
                    var documents = SelectiveDocuments(context,destination)
                    val input = documents.stage(codec.encodeToString(pkg).byteInputStream())
                    val reviewed = documents.plan(input.id,input.revision,pkg.groups.map { it.id }.toSet())
                    documents = SelectiveDocuments(context,destination) // saved file/review, no new engine or operation ID
                    val applied = documents.apply(input.id,reviewed.draft.revision,reviewed.operationId)
                    check(applied.groups.all { it.state == TransferGroupState.APPLIED })
                    check(documents.apply(input.id,0,reviewed.operationId) == applied)
                    check(PrivateFileMaintenance.forContext(context).state().lifetimes.none { it.id == input.id })
                    check(destination.data().types == tracker.data().types && destination.data().cards == tracker.data().cards)
                    check(destination.data().sessions.isEmpty())
                } finally { destinationDb.close() }
                val backup = Backups(tracker,PrivateRollbackStore(context))
                val output = File(root,"full-archive.json")
                output.outputStream().use { backup.write(it) }
                val before = tracker.data();val preview = backup.prepare { output.inputStream() }
                check(before == tracker.data())
                val result = backup.apply(preview)
                val after = tracker.data()
                check(after.datasetId == before.datasetId && after.controlBarrier != before.controlBarrier)
                check(after.cards == before.cards && after.types == before.types && after.sessions == before.sessions && after.segments == before.segments)
                check(after.settings == before.settings && after.schedule == before.schedule)
                check(File(context.noBackupFilesDir,"rollback/${result.rollbackReference}").isFile)
                atomicText(File(root,"clock.json"),buildJsonObject { put("wall",wall);put("elapsed",elapsed) }.toString())
            }
            domainHash = sha256(codec.encodeToString(tracker.data()).toByteArray())
            check(!tracker.data().settings.surfaces.shortcut)
            if(!create) check(domainHash == saved!!.getValue("domainSha256").jsonPrimitive.content)
        } finally { db.close() }
        val exportId = "00000000-0000-0000-0000-000000000001"
        val relative = "exports/$exportId";val itemId = sha256(relative.toByteArray())
        val sentinel = File(root,"unselected-sentinel")
        DescriptorCopyFiles(root.canonicalPath).use { files ->
            val gate = CopyMaintenance(files.metadata())
            val storage = RegisteredCopyStorage(files,gate)
            if(create) {
                sentinel.writeText("unselected synthetic fixture")
                val directory = File(root,relative).apply { check(mkdirs()) }
                File(directory,"operation.json").writeText("""{"id":"$exportId","mime":"text/plain","title":"fixture","state":"CANCELLED","bytes":0,"sha256":null,"error":null,"resultReceived":true}""")
                File(directory,"data.part").writeText("unfinished own synthetic staging")
                PrivateCopyCatalog(files,gate).registerExport(exportId)
                val lease = gate.acquire("qa-active-writer",itemId,CopyBlocker.EXPORT_WRITING)
                check(!storage.inventory().items.single().selectable)
                gate.release(lease)
                var unlinked = false
                val interrupted = object: PrivateCopyStorage by storage {
                    override fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String) {
                        storage.removeMember(item,member,operationId)
                        if(!unlinked) { unlinked = true;throw IOException("QA interruption after own first unlink before progress receipt") }
                    }
                }
                val cleanup = PrivateCopyCleanup(interrupted);val inventory = cleanup.inventory()
                val preview = cleanup.preview(inventory.revision,setOf(itemId))
                check(CopyWarning.INCOMPLETE_EXPORT_STAGING in preview.items.single().confirmableWarnings)
                val confirmation = CleanupConfirmation(preview.id,preview.revision,preview.items.associate { it.id to it.sha256 },
                    preview.items.flatMap { i -> i.confirmableWarnings.map { WarningAcknowledgement(i.id,it) } }.toSet(),emptySet(),"qa-interrupted-delete")
                check(cleanup.confirm(confirmation).phase == CleanupPhase.PARTIAL)
                atomicText(File(root,"cleanup-confirmation.json"),codec.encodeToString(confirmation))
            } else {
                val cleanup = PrivateCopyCleanup(storage)
                val partial = cleanup.operations().single()
                check(partial.phase == CleanupPhase.PARTIAL && partial.items.single().deletedMemberIds.size == 1)
                check(storage.remaining(partial.items.single().item,partial.operationId).size == 1)
                check(runCatching { cleanup.restoreSurvivingGroup(partial.operationId,itemId) }.isFailure)
                val done = cleanup.resume(partial.operationId,cleanup.remainingHash(partial))
                check(done.phase == CleanupPhase.DONE && !File(root,relative).exists())
                val confirmation = codec.decodeFromString<CleanupConfirmation>(File(root,"cleanup-confirmation.json").readText())
                check(cleanup.confirm(confirmation).phase == CleanupPhase.DONE)
            }
            check(sentinel.readText() == "unselected synthetic fixture")
        }
        check(liveBefore == if(live.exists()) fileHash(live) else "absent")
        val clock = codec.parseToJsonElement(File(root,"clock.json").readText()).jsonObject
        val report = buildJsonObject {
            put("schema","tracker_android_backend_integration_v1");put("mode",mode);put("fixtureTag",tag)
            put("processId",android.os.Process.myPid());put("sdk",android.os.Build.VERSION.SDK_INT)
            put("roomVersion",TRACKER_DATABASE_VERSION);put("fullJsonFormat",Backups.decode(File(root,"full-archive.json").readText()).formatVersion)
            put("shortcutDisabledPersisted",true);put("shortcutCommandRefused",true)
            put("abi",android.os.Build.SUPPORTED_ABIS.first());put("privateRootMode",Integer.toOctalString(rootStat.st_mode))
            put("contextDescriptorBinding",true);put("liveDatasetUnchanged",true);put("liveDatasetDigest",liveBefore)
            put("indexedReceiptCount",33);put("legacyBytesPreserved",true);put("duplicateReserveRejected",true)
            put("domainSha256",domainHash);put("wall",clock.getValue("wall"));put("elapsed",clock.getValue("elapsed"))
            put("roomReopen",!create);put("cleanupPhase",if(create) "PARTIAL" else "DONE")
            put("unselectedBytesUnchanged",true);put("automaticDeleteOnReopen",false)
            put("selectiveCatalogClosure",true);put("fullReplacementVerifiedRollback",true)
        }
        if(create) atomicText(state,report.toString())
        atomicText(File(root,if(create) "create-report.json" else "reopen-report.json"),report.toString())
        return report
    }
}
