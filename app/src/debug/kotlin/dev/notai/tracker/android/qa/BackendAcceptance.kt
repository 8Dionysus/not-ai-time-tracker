package dev.notai.tracker.android.qa

import android.app.*
import android.content.*
import android.os.Bundle
import android.os.Process
import dev.notai.tracker.*
import dev.notai.tracker.android.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.viewModelScope
import dev.notai.tracker.android.ui.*

/** Debug-only self-instrumentation in the authorized primary APK. No extra package or UI.
 * All destructive fault injections target this own process and named tiny private fixtures. */
class BackendAcceptance: Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { args=arguments ?: Bundle();start() }
    private lateinit var c: Context
    private lateinit var tag: String
    private fun report(body: JsonObject) = finish(Activity.RESULT_OK,Bundle().apply { putString("report",body.toString()) })
    private fun save(file: File,value: JsonObject) = atomicText(file,value.toString())
    private fun read(file: File) = codec.parseToJsonElement(file.readText()).jsonObject
    private fun value(o: JsonObject,key: String) = o.getValue(key).jsonPrimitive.content
    private fun mark(root: File,phase: String,extra: JsonObject) {
        save(File(root,"marker.json"),buildJsonObject {
            put("schema","backend_acceptance_marker_v1");put("tag",tag);put("phase",phase)
            put("package",c.packageName);put("pid",Process.myPid());put("extra",extra)
        })
    }
    private fun fixture(): Pair<Context,File> {
        val requested=File(c.noBackupFilesDir,"backend-acceptance/$tag").apply { mkdirs() }
        // Use the same Android-owned-directory canonicalization as PrivateCopyBackend.
        // App mount namespaces can expose /data/user/0 as an alias even when shell sees a directory.
        val root=requested.canonicalFile
        save(File(root,"root-diagnostics.json"),buildJsonObject {
            put("requested",requested.path);put("canonical",root.path)
            var path: File?=requested
            val ancestors=buildJsonArray {
                while(path!=null) {
                    val current=path!!
                    val stat=android.system.Os.lstat(current.path)
                    add(buildJsonObject { put("path",current.path);put("mode",stat.st_mode);put("device",stat.st_dev);put("inode",stat.st_ino) })
                    path=current.parentFile
                }
            }
            put("requestedAncestors",ancestors)
        })
        val wrapped=object: ContextWrapper(c) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        return wrapped to root
    }
    override fun onStart() {
        c=targetContext;tag=args.getString("tag") ?: "missing"
        check(c.packageName=="dev.notai.tracker" && tag.matches(Regex("[a-zA-Z0-9-]+")))
        runOnMainSync { } // Application.onCreate and production bindings complete first.
        val mode=args.getString("mode") ?: "missing"
        try {
            val result=when(mode) {
                "startup" -> startup()
                "history-cursors" -> historyCursors()
                "focus-blocked" -> focusBlocked()
                "shortcut-controller" -> shortcutController()
                "guards" -> guards()
                "callback", "prequeue", "worker" -> callback(mode)
                "deathcheck" -> deathcheck()
                "coldarm" -> coldarm()
                "coldcheck" -> coldcheck(false)
                "coldcleanup" -> coldcheck(true)
                "coldabandon" -> coldcheck(true,false)
                "channelclose" -> channelclose()
                else -> error("Unknown bounded QA mode")
            }
            report(buildJsonObject { put("schema","backend_acceptance_native_v1");put("mode",mode);put("tag",tag);put("passed",true);put("result",result) })
        } catch(t: Throwable) {
            android.util.Log.e("BackendAcceptance","Own QA failed",t)
            report(buildJsonObject { put("schema","backend_acceptance_native_v1");put("mode",mode);put("tag",tag);put("passed",false);put("error",t.toString());put("stack",t.stackTraceToString()) })
        }
    }
    /** Shell-only, debug manifest protected by DUMP. Runs cold boundaries without instrumentation lifecycle. */
    internal fun runColdFromShell(context: Context,arguments: Bundle): JsonObject {
        c=context.applicationContext;args=arguments;tag=checkNotNull(args.getString("tag"))
        check(c.packageName=="dev.notai.tracker" && tag.matches(Regex("[a-zA-Z0-9-]+")))
        val mode=args.getString("mode")
        return try {
            val result=when(mode) {
                "coldarm" -> coldarm()
                "coldcheck" -> coldcheck(false)
                "coldcleanup" -> coldcheck(true)
                "coldabandon" -> coldcheck(true,false)
                "channelclose" -> channelclose()
                else -> error("Not a bounded cold QA mode")
            }
            buildJsonObject { put("mode",mode);put("tag",tag);put("passed",true);put("pid",Process.myPid());put("result",result) }
        } catch(t: Throwable) {
            android.util.Log.e("BackendAcceptance","Own cold QA failed",t)
            buildJsonObject { put("mode",mode);put("tag",tag);put("passed",false);put("error",t.toString());put("stack",t.stackTraceToString()) }
        }
    }
    private fun shortcutController(): JsonObject = runBlocking {
        val original=UiBackends.open;var cases=0
        val (fc,_) = fixture()
        for(room in listOf(false,true)) {
            val db=if(room) androidx.room.Room.inMemoryDatabaseBuilder(c,TrackerDatabase::class.java).build() else null
            val store=db?.let(::RoomStore) ?: object: StateStore {
                private var value=Dataset();override val changes=kotlinx.coroutines.flow.MutableStateFlow(value)
                override fun read()=value
                @Synchronized override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
                    val (next,result)=block(value);validate(next);value=next;changes.value=next;return result
                }
            }
            val t=Tracker(store,AndroidClock(c));t.start(t.snapshot().token);val before=t.data()
            UiBackends.open={ object: UiBackend {
                override val tracker=t
                override val backups=Backups(t,PrivateRollbackStore(fc))
                override val focusDue=FocusDueSignals()
                override val runtime: TrackerRuntime?=null
                override fun prepareCatalog(): CatalogPreview=error("Unused QA catalog")
                override fun applyCatalog(preview: CatalogPreview): Tracker.Change=error("Unused QA catalog")
                override fun submitShortcut(action: SurfaceAction)=CompletableDeferred<Result<Tracker.Change>>(Result.failure(IllegalStateException("Unused QA shortcut command")))
            } }
            lateinit var vm: TrackerViewModel
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            try {
                runOnMainSync { vm=TrackerViewModel(c.applicationContext as Application) }
                scope.launch { vm.snapshot.collect { } }
                suspend fun await(test: ()->Boolean) {
                    val until=System.nanoTime()+10_000_000_000L
                    while(!test()) { check(System.nanoTime()<until);delay(10) }
                }
                await { vm.boot.value is TrackerViewModel.Boot.Ready && vm.snapshot.value?.token==t.snapshot().token }
                runOnMainSync { vm.setSurfaceEnabled(Surface.SHORTCUT,false) }
                await { !t.data().settings.surfaces.shortcut }
                val disabled=t.data()
                check(disabled.settings==before.settings.copy(surfaces=before.settings.surfaces.copy(shortcut=false)))
                check(disabled.sessions==before.sessions && disabled.segments==before.segments && disabled.timerEpoch==before.timerEpoch)
                check(disabled.revision==before.revision+1)
                await { vm.snapshot.value?.token==t.snapshot().token }
                runOnMainSync { vm.setSurfaceEnabled(Surface.SHORTCUT,true) }
                await { t.data().settings.surfaces.shortcut }
                val after=t.data()
                check(after.settings==before.settings && after.sessions==before.sessions && after.segments==before.segments)
                check(after.timerEpoch==before.timerEpoch && after.controlBarrier==before.controlBarrier && after.revision==before.revision+2)
                cases++
            } finally { scope.cancel();runOnMainSync { vm.viewModelScope.cancel() };UiBackends.open=original;db?.close() }
        }
        buildJsonObject { put("memoryRoomActualViewModelCases",cases);put("liveDatabaseUntouched",true) }
    }
    private fun focusBlocked(): JsonObject {
        var cases=0
        for(room in listOf(false,true)) for(advance in listOf(false,true)) {
            val db=if(room) androidx.room.Room.inMemoryDatabaseBuilder(c,TrackerDatabase::class.java).build() else null
            try {
                val base=db?.let(::RoomStore) ?: object: StateStore {
                    private var value=Dataset();override val changes=kotlinx.coroutines.flow.MutableStateFlow(value)
                    override fun read()=value
                    @Synchronized override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
                        val (next,result)=block(value);validate(next);value=next;changes.value=next;return result
                    }
                }
                var hook: (() -> Unit)?=null
                val store=object: StateStore by base {
                    override fun readChecked(): CheckedState {
                        val captured=base.readChecked();val once=hook;hook=null;once?.invoke();return captured
                    }
                }
                val clock=object: dev.notai.tracker.Clock {
                    var elapsed=0L
                    override fun now()=Moment(1_790_000_000_000L+elapsed,elapsed,"r14-qa-boot","r14-qa-process")
                };val t=Tracker(store,clock)
                t.startFocus(t.snapshot().token,plan=FocusPlan(1000,1000));clock.elapsed+=1000
                val due=checkNotNull(t.snapshot().focus)
                hook={ if(advance) t.focusNext(t.snapshot().token) else t.stop(t.snapshot().token);Unit }
                val result=FocusSignals.deliverResult(c,t)
                check(result.status==FocusDeliveryStatus.BLOCKED_BEFORE_POST)
                check(result.focus==t.snapshot().focus && result.focus?.signalKey!=due.signalKey)
                cases++
            } finally { db?.close() }
        }
        return buildJsonObject { put("memoryRoomStopAdvanceCases",cases);put("liveDatabaseUntouched",true) }
    }
    /** Same existing synthetic DB, read-only comparisons; no fixture/database copies. */
    private fun historyCursors(): JsonObject {
        check(TrackerRuntime::class.java.getDeclaredField("instance").apply { isAccessible=true }.get(null)==null)
        check(DatabaseFiles.exists(c))
        val rows=mutableListOf<JsonObject>();var expected: Dataset?=null
        for(round in 0 until 7) for(paged in if(round%2==0) listOf(true,false) else listOf(false,true)) {
            val phases=linkedMapOf<String,Long>()
            val db=TrackerDatabase.open(c)
            try {
                val start=System.nanoTime()
                val store=RoomStore(db,allowInitialize=false,onLoadPhase={ k,n -> phases[k]=n },loadPageSize=if(paged) 512 else null)
                val init=System.nanoTime()-start
                val loaded=store.readChecked().data;val elapsed=System.nanoTime()-start
                check(loaded.sessions.size==73015 && loaded.segments.size==73020)
                if(expected==null) expected=loaded else check(expected==loaded)
                rows+=buildJsonObject {
                    put("round",round);put("paged",paged);put("initializeNs",init);put("checkedLoadNs",elapsed)
                    put("phasesNs",JsonObject(phases.mapValues { JsonPrimitive(it.value) }))
                    put("sessions",loaded.sessions.size);put("segments",loaded.segments.size);put("exactDatasetEqual",true)
                    put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                }
            } finally { db.close() }
            System.gc()
        }
        return buildJsonObject {
            put("kind","native Room cursor hypothesis; not process cold startup")
            put("alternatingOrder",true);put("warmupsDiscarded",0);put("databaseCopies",0)
            put("expectedDatasetRetainedForEquality",true);put("samples",JsonArray(rows))
        }
    }
    private fun startup(): JsonObject {
        check(ScheduleNoticeBinding.factory===ProductionReminderNotices.schedule)
        check(LongRecordNoticeBinding.factory===ProductionReminderNotices.longRecord)
        val absent=TrackerRuntime::class.java.getDeclaredField("instance").apply { isAccessible=true }.get(null)==null
        check(absent)
        val manager=c.getSystemService(NotificationManager::class.java)
        return buildJsonObject {
            put("runtimeAbsentBeforeAnyAcquisition",absent);put("realProductionFactories",true)
            for(id in listOf(AndroidScheduleDeliveryPlatform.CHANNEL,AndroidLongRecordPlatform.CHANNEL)) {
                val ch=checkNotNull(manager.getNotificationChannel(id));put(id,buildJsonObject {
                    put("importance",ch.importance);put("sound",ch.sound?.toString());put("vibrate",ch.shouldVibrate());put("badge",ch.canShowBadge())
                })
            }
        }
    }
    private fun projection(): ScheduleProjection {
        val date=LocalDate.of(2026,10,17);val instant=date.atTime(10,0).atZone(ZoneId.of("UTC")).toInstant()
        return ScheduleProjection(OccurrenceKey("native-guard","2026-10-17"),1,null,date,date,LocalTime.of(10,0),Classification(),"UTC",instant,DstResolution.EXACT,instant.minusSeconds(60),null,OccurrenceState.SCHEDULED)
    }
    private fun guards(): JsonObject {
        val manager=c.getSystemService(NotificationManager::class.java)
        val p=projection();val ticket=ScheduleReminderTicket("native-guard","barrier",p.key,1,null,"UTC",p.reminderInstant!!.toEpochMilli())
        val schedule=ScheduleNoticeBinding.factory;val ordinary=LongRecordNoticeBinding.factory
        val snap=snapshot(Dataset(),AndroidClock(c).now())
        val longTicket=LongRecordTicket(TimerTarget("native-guard","session",0,"barrier"),"segment","boot",0,0,1)
        try {
            val s=AndroidScheduleDeliveryPlatform(c);check(s.availability().ready)
            val staleS=checkNotNull(s.prepare(ticket,p));ScheduleNoticeBinding.install(schedule)
            check(!staleS.post());check(manager.activeNotifications.none { it.id==84 })
            check(checkNotNull(s.prepare(ticket,p)).post())
            val posted=manager.activeNotifications.single { it.id==84 && it.tag=="schedule:${ticket.identity}" }
            check(posted.notification.visibility==Notification.VISIBILITY_PRIVATE && posted.notification.publicVersion!=null)
            check(posted.notification.actions.isNullOrEmpty());check(posted.notification.contentIntent.isImmutable)
            s.cancelNotice(ticket)
            val l=AndroidLongRecordPlatform(c);check(l.availability().ready)
            val staleL=checkNotNull(l.prepare(longTicket,snap));LongRecordNoticeBinding.factory=ordinary
            check(!staleL.post());check(manager.activeNotifications.none { it.id==85 })
            check(checkNotNull(l.prepare(longTicket,snap)).post());check(manager.activeNotifications.any { it.id==85 })
            l.cancelNotice()
            return buildJsonObject { put("sameFactoryNewRegistrationRejectedForBoth",true);put("freshProductionPreparedPostsVisibleInAndroid",true);put("noTimerActionsAndPrivatePublicVersion",true);put("productionColdReceiverProof",false) }
        } finally {
            manager.cancel("schedule:${ticket.identity}",84);manager.cancel(85)
            ScheduleNoticeBinding.install(schedule);LongRecordNoticeBinding.factory=ordinary
        }
    }
    private fun callback(mode: String): JsonObject {
        val (context,root)=fixture();val gate=PrivateFileMaintenance.forContext(context)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val blocker=CountDownLatch(1);val lifetimes=CopyLifetimes.get(context)
        if(mode=="prequeue") {
            lifetimes.releasePreview(AutoCloseable { entered.countDown();check(blocker.await(180,TimeUnit.SECONDS)) })
            check(entered.await(10,TimeUnit.SECONDS))
        }
        var firstId=""
        val registrationError=AtomicReference<Throwable?>()
        val registration: (Context,String)->Unit = { _,id ->
            try {
                DescriptorCopyFiles(root.path).use { files ->
                    val hook: () -> Unit = {
                        if(id==firstId && mode!="prequeue") { entered.countDown();check(release.await(180,TimeUnit.SECONDS)) }
                    }
                    // Internal test boundary from the core's own constructor, no product renderer replacement.
                    val constructor=PrivateCopyCatalog::class.java.declaredConstructors.single { it.parameterCount==3 }
                    val catalog=constructor.newInstance(files,gate,hook) as PrivateCopyCatalog
                    catalog.registerExportAfterHandoff(id)
                }
            } catch(t: Throwable) {
                registrationError.set(t)
                atomicText(File(root,"registration-error.txt"),t.stackTraceToString())
                android.util.Log.e("BackendAcceptance","Own registration fixture failed before/after validation hook",t)
                throw t
            } finally { if(id==firstId) finished.countDown() }
        }
        val exports=ExportOperations(context,registration)
        fun staged(): ExportOperation {
            val op=exports.begin("text/plain","Own native fixture $tag")
            exports.prepare(op.id) { it.write("retained native fixture bytes".toByteArray()) };exports.awaitResult(op.id);return op
        }
        val a=staged();firstId=a.id
        val b=if(mode=="callback") staged() else null
        val foreign=gate.acquire("foreign-native-owner:$tag",PrivateCopyBackend.itemId("exports/${a.id}"),CopyBlocker.LIVE_FORM_REFERENCE)
        val callbackNs=AtomicReference<Long>()
        runOnMainSync { val start=System.nanoTime();exports.receiveResult(a.id,true);callbackNs.set(System.nanoTime()-start) }
        if(mode!="prequeue" && !entered.await(10,TimeUnit.SECONDS)) {
            registrationError.get()?.let { throw it };error("Optional fixture never reached validation hook")
        }
        val persisted=exports.read(a.id);check(persisted.resultReceived && persisted.state=="CANCELLED")
        val durable=codec.decodeFromString<CopyMaintenanceState>(checkNotNull(PrivateFileMaintenance.metadata(File(root,"private-copy-maintenance.json")).read()))
        check(foreign in durable.references)
        check(durable.references.none { it.owner=="export-chooser:${a.id}" || it.owner=="export-result:${a.id}" })
        val bytes=File(root,"exports/${a.id}/data");val hash=fileHash(bytes)
        if(mode!="callback") {
            mark(root,mode,buildJsonObject {
                put("id",a.id);put("bytes",bytes.length());put("sha256",hash);put("mainCallbackNs",callbackNs.get())
                put("foreign",codec.parseToJsonElement(codec.encodeToString(foreign)))
                put("registrationStarted",mode=="worker");put("operation",codec.parseToJsonElement(codec.encodeToString(persisted)))
            })
            check(CountDownLatch(1).await(180,TimeUnit.SECONDS)) { "Host did not terminate own fixture process" }
        }
        val secondNs=AtomicReference<Long>();val returned=CountDownLatch(1);val error=AtomicReference<Throwable?>()
        Thread {
            try { runOnMainSync { val start=System.nanoTime();exports.receiveResult(checkNotNull(b).id,true);secondNs.set(System.nanoTime()-start) };returned.countDown() }
            catch(t:Throwable) { error.set(t);returned.countDown() }
        }.start()
        val progressed=returned.await(2,TimeUnit.SECONDS)
        release.countDown();check(finished.await(20,TimeUnit.SECONDS));check(progressed) { "Second main callback blocked behind optional validation" };error.get()?.let { throw it }
        check(exports.read(checkNotNull(b).id).resultReceived)
        check(fileHash(bytes)==hash)
        lifetimes.releasePreview(AutoCloseable { }).let { job -> kotlinx.coroutines.runBlocking { job.join() } }
        val final=gate.state();check(foreign in final.references)
        check(final.references.none { it.owner.startsWith("export-registration:") || it.owner.startsWith("export-result:") || it.owner.startsWith("export-chooser:") })
        check(final.copies.none { it.item.id==foreign.itemId }) // Competing callback invalidates A's snapshot.
        check(final.copies.any { it.item.id==PrivateCopyBackend.itemId("exports/${b.id}") })
        return buildJsonObject {
            put("actualMainLooperCallbackNs",callbackNs.get());put("nextMainLooperCallbackNsWhileValidationBlocked",secondNs.get())
            put("secondReturnedBeforeRelease",true);put("durableReceiptAndExactChooserRelease",true)
            put("nativeDescriptorValidationOutsideCommonGate",true);put("competingRevisionRefusedRegistration",true)
            put("foreignReferenceAndBytesRetained",true);put("timingScope","single bounded functional sample, not a speedup benchmark")
        }
    }
    private fun deathcheck(): JsonObject {
        val (context,root)=fixture();val marker=read(File(root,"marker.json"));val extra=marker.getValue("extra").jsonObject
        val op=ExportOperations(context).read(value(extra,"id"));check(op.resultReceived && op.state=="CANCELLED")
        check(codec.parseToJsonElement(codec.encodeToString(op))==extra.getValue("operation"))
        val data=File(root,"exports/${op.id}/data");check(fileHash(data)==value(extra,"sha256") && data.length()==extra.getValue("bytes").jsonPrimitive.long)
        val durable=codec.decodeFromString<CopyMaintenanceState>(checkNotNull(PrivateFileMaintenance.metadata(File(root,"private-copy-maintenance.json")).read()))
        val foreign=codec.decodeFromString<CopyReference>(extra.getValue("foreign").toString());check(foreign in durable.references)
        check(durable.references.none { it.owner=="export-chooser:${op.id}" || it.owner=="export-result:${op.id}" })
        check(durable.copies.none { it.item.id==foreign.itemId })
        val protection=durable.references.any { it.owner=="export-registration:${op.id}" }
        check(protection==extra.getValue("registrationStarted").jsonPrimitive.boolean)
        // The marker and pre-retry durable state are retained as evidence. This is a separate,
        // explicit completion by the exact read owner after byte/receipt checks, never expiry.
        DescriptorCopyFiles(root.path).use { files -> PrivateCopyCatalog(files,PrivateFileMaintenance.forContext(context)).registerExportAfterHandoff(op.id) }
        val after=PrivateFileMaintenance.forContext(context).state()
        check(foreign in after.references && after.references.none { it.owner=="export-registration:${op.id}" })
        check(after.copies.count { it.item.id==foreign.itemId }==1)
        return buildJsonObject { put("durableReceiptAndBytesExactAfterRealSIGKILL",true);put("chooserAndWriterReleased",true)
            put("foreignReferenceRetained",true);put("registrationProtectionRetainedBeforeExplicitRetry",protection);put("explicitOwnerRetryRevalidatedAndReleasedOnlyItsReadLease",true);put("noAutomaticExpiry",true);put("fixtureRoot",root.name) }
    }
    private fun coldFile() = File(c.noBackupFilesDir,"backend-acceptance/$tag/cold.json")
    private fun coldarm(): JsonObject {
        val manager=c.getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled())
        check(ScheduleNoticeBinding.factory===ProductionReminderNotices.schedule && LongRecordNoticeBinding.factory===ProductionReminderNotices.longRecord)
        val runtime=TrackerRuntime.get(c);val t=runtime.tracker
        check(t.snapshot().mode==Mode.IDLE && t.data().focus==null)
        check(t.data().schedule.series.none { it.state==ScheduleState.ACTIVE && it.revisions.last().rule.alertsEnabled })
        val prior=t.data().settings;check(!prior.longRecordReminder.enabled)
        val oldReceiver=c.packageManager.getComponentEnabledSetting(ComponentName(c,SchedulePlatformReceiver::class.java))
        val due=Instant.ofEpochMilli(t.clock.now().wall).atZone(ZoneId.of("UTC")).plusMinutes(2).truncatedTo(ChronoUnit.MINUTES)
        val id="native-production-$tag";val date=due.toLocalDate().toString();val format=DateTimeFormatter.ofPattern("HH:mm")
        t.scheduleCommand(ScheduleCommand("create-$id",t.snapshot().token,ScheduleAction.CREATE,id,rule=ScheduleRule(Classification(name="Synthetic production reminder $tag"),date,extraDates=setOf(date),localTime=due.plusMinutes(1).format(format),zonePolicy=ScheduleZonePolicy.FIXED,zoneId="UTC",alertsEnabled=true,reminderTime=due.format(format))))
        val batch=checkNotNull(runtime.scheduleDelivery.synchronize().next);check(batch.tickets.single().key.seriesId==id)
        t.start(t.snapshot().token,Classification(name="Synthetic native ordinary reminder $tag"))
        val ordinary=runtime.attention.ordinary.controller;val state=ordinary.synchronize()
        val configured=ordinary.configure(t.snapshot().token,LongRecordPreference(true,20_000,10_000),state.controlRevision)
        check(configured.alarmAccepted && configured.ticket!=null)
        val saved=buildJsonObject {
            put("seriesId",id);put("batchHash",batch.hash);put("identity",batch.tickets.single().identity);put("due",batch.at)
            put("ordinaryHash",configured.ticket!!.hash);put("ordinaryDue",configured.ticket!!.dueElapsed)
            put("sessionId",t.snapshot().session!!.id);put("priorSettings",codec.parseToJsonElement(codec.encodeToString(prior)))
            put("receiverState",oldReceiver);put("pid",Process.myPid());put("revision",t.data().revision)
        }
        save(coldFile(),saved);mark(coldFile().parentFile!!,"coldarm",saved)
        return saved
    }
    private fun coldcheck(cleanup: Boolean,requirePosted: Boolean = true): JsonObject {
        val saved=read(coldFile());val runtime=TrackerRuntime.get(c);val t=runtime.tracker
        val id=value(saved,"seriesId");val schedule=runtime.scheduleDelivery.coordinator.attempts().filter { it.ticket.key.seriesId==id }
        val ordinary=AndroidLongRecordStore(c).locked { AndroidLongRecordStore(c).read() }
        if(requirePosted) {
            check(schedule.size==1 && schedule.single().status==ScheduleAttemptStatus.POSTED)
            check(ordinary.attempt?.ticket?.hash==value(saved,"ordinaryHash") && ordinary.attempt?.status==ScheduleAttemptStatus.POSTED)
        }
        val manager=c.getSystemService(NotificationManager::class.java)
        if(requirePosted && !cleanup) {
            check(manager.activeNotifications.any { it.id==84 && it.tag=="schedule:${value(saved,"identity")}" })
            check(manager.activeNotifications.any { it.id==85 })
        }
        check(t.snapshot().session?.id==value(saved,"sessionId") && t.snapshot().mode==Mode.RUNNING)
        if(cleanup) {
            t.stop(t.snapshot().token)
            val prior=codec.decodeFromString<Settings>(saved.getValue("priorSettings").toString())
            check(t.data().settings.copy(longRecordReminder=prior.longRecordReminder)==prior)
            t.settings(t.snapshot().token,prior)
            val series=t.data().schedule.series.single { it.id==id }
            t.scheduleCommand(ScheduleCommand("archive-$id",t.snapshot().token,ScheduleAction.SET_STATE,id,expectedSeriesRevision=series.revision,state=ScheduleState.ARCHIVED))
            runtime.scheduleDelivery.synchronize();runtime.attention.ordinary.synchronize()
            check(t.snapshot().mode==Mode.IDLE && t.data().settings==prior)
            c.packageManager.setComponentEnabledSetting(ComponentName(c,SchedulePlatformReceiver::class.java),saved.getValue("receiverState").jsonPrimitive.int,android.content.pm.PackageManager.DONT_KILL_APP)
        }
        return buildJsonObject {
            put("realAndroidAlarmColdReceiverProductionSchedule",requirePosted);put("realAndroidAlarmColdReceiverProductionOrdinary",requirePosted)
            put("onePostedReceiptEach",requirePosted);put("factoriesRebuiltByActualApplication",true);put("cleanupCompleted",cleanup)
            put("revision",t.data().revision);put("sessions",t.data().sessions.size);put("segments",t.data().segments.size)
            put("allQaTimeAndArchivedPlanRetained",true)
        }
    }
    private fun channelclose(): JsonObject {
        val manager=c.getSystemService(NotificationManager::class.java)
        if(args.getString("scheduleDeleted")=="true") manager.deleteNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL)
        if(args.getString("ordinaryAbsent")=="true") manager.deleteNotificationChannel(AndroidLongRecordPlatform.CHANNEL)
        args.getString("receiverState")?.toInt()?.let { state ->
            check(state in 0..4)
            c.packageManager.setComponentEnabledSetting(ComponentName(c,SchedulePlatformReceiver::class.java),state,android.content.pm.PackageManager.DONT_KILL_APP)
        }
        return buildJsonObject { put("restoredPriorScheduleDeletion",args.getString("scheduleDeleted")=="true")
            put("removedOwnNewOrdinaryChannel",args.getString("ordinaryAbsent")=="true")
            put("androidMayRetainDeletedChannelTombstones",true) }
    }

}
