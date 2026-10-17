package dev.notai.tracker.android

import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.notai.tracker.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Explicit fixtures only. Base data is never replaced; rescue/surface fixtures require the fresh QA package. */
object RemainingNativeChecks {
    fun root(c: Context) = File(c.noBackupFilesDir,"remaining-qa").apply { mkdirs() }
    fun text(c: Context,n: String) = File(root(c),n).readText()
    fun save(c: Context,n: String,s: String) {
        val f=File(root(c),n);FileOutputStream(f).use { it.write(s.toByteArray());it.fd.sync() };DirectoryDurability.sync(f.parentFile!!)
    }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun marker(c: Context,tag: String,stage: String) {
        save(c,"$tag.marker.json",buildJsonObject { put("schema","remaining_native_marker_v1");put("tag",tag);put("stage",stage);put("pid",android.os.Process.myPid());put("package",c.packageName) }.toString())
        Thread.sleep(90_000)
        error("Owner did not kill at verified fixture marker")
    }
    fun sample(tag: String): Backup {
        val selection=Classification(name="Synthetic restored $tag")
        val d=Dataset(datasetId="remaining-$tag",sessions=listOf(Session(id="session-$tag",status=SessionStatus.STOPPED,selection=selection,note="Synthetic remaining fixture")),
            segments=listOf(Segment(id="segment-$tag",sessionId="session-$tag",start=1000,end=2000,durationMs=1000,selection=selection)))
        return Backup(formatVersion=14,backupId="backup-$tag",asOf=3000,data=d)
    }
    fun source(c: Context,tag: String): File = File(root(c),"$tag.input.json").also { file ->
        if(!file.exists()) FileOutputStream(file).use { StreamingBackup.write(sample(tag),it);it.fd.sync() }
    }
    fun requireFresh(c: Context) { check(c.packageName=="dev.notai.tracker.preuifresh") { "Destructive fixture requires own fresh suffix, never primary package" } }
    fun run(i: Instrumentation,c: Context,mode: String,input: String): JsonObject {
        val pieces=input.split(':');val tag=pieces[0];check(tag.matches(Regex("[a-zA-Z0-9-]+")));val stage=pieces.getOrElse(1){""}
        val values=linkedMapOf<String,JsonElement>("schema" to JsonPrimitive("remaining_native_v1"),"mode" to JsonPrimitive(mode),"tag" to JsonPrimitive(tag),"pid" to JsonPrimitive(android.os.Process.myPid()),"package" to JsonPrimitive(c.packageName))
        when(mode) {
            "remaining-saf-rearm" -> {
                check(c.packageName=="dev.notai.tracker")
                val id="1e6528bc-8522-437e-b4b1-3faf9d2af001";val ops=ExportOperations(c);val old=ops.read(id)
                check(old.bytes==106806531L && old.state in setOf("COMPLETE","CANCELLED","READY"))
                val staged=File(c.noBackupFilesDir,"exports/$id/data");check(fileHash(staged)==old.sha256)
                ops.cancel(id);check(ops.latestPending()?.id==id)
                values["id"]=JsonPrimitive(id);values["bytes"]=JsonPrimitive(old.bytes);values["sha256"]=JsonPrimitive(old.sha256);values["sameStageRearmed"]=JsonPrimitive(true)
            }
            "remaining-saf-check" -> {
                check(c.packageName=="dev.notai.tracker")
                val id="1e6528bc-8522-437e-b4b1-3faf9d2af001";val op=ExportOperations(c).read(id)
                check(fileHash(File(c.noBackupFilesDir,"exports/$id/data"))==op.sha256)
                values["id"]=JsonPrimitive(id);values["state"]=JsonPrimitive(op.state);values["bytes"]=JsonPrimitive(op.bytes);values["sha256"]=JsonPrimitive(op.sha256);values["resultReceived"]=JsonPrimitive(op.resultReceived);values["error"]=JsonPrimitive(op.error)
            }
            "remaining-channel-probe" -> {
                check(c.packageName=="dev.notai.tracker")
                val t=TrackerRuntime.get(c).tracker;val before=t.data()
                kotlinx.coroutines.runBlocking { TimerSurfaces.refresh(c,t) }
                val nm=c.getSystemService(android.app.NotificationManager::class.java)
                val capability=SurfaceSettingsBackend.read(c,t.data().settings.surfaces)
                values["channelImportance"]=JsonPrimitive(nm.getNotificationChannel(TimerSurfaces.CONTROL_CHANNEL)?.importance?:-1)
                values["notificationsEnabled"]=JsonPrimitive(nm.areNotificationsEnabled())
                values["runtimeGranted"]=JsonPrimitive(c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED)
                values["problem"]=JsonPrimitive(capability.notification.problem?.name)
                values["permitted"]=JsonPrimitive(capability.notification.permitted)
                values["timerNoticePresent"]=JsonPrimitive(nm.activeNotifications.any {it.id==TimerSurfaces.CONTROL_ID})
                check(before==t.data());values["datasetExact"]=JsonPrimitive(true)
            }
            "remaining-restore-create" -> {
                check(stage in setOf("rollback","after-commit","after-return"));val name="remaining-restore-$tag.db";check(!c.getDatabasePath(name).exists())
                val db=TrackerDatabase.open(c,name);val base=RoomStore(db);var tick=0L;val clock=Clock { Moment(1_800_000_000_000L+tick,1000+tick,"remaining-clock") }
                val witness=Tracker(base,clock);witness.start(witness.snapshot().token,note="Committed pre-restore witness");tick+=1000;witness.stop(witness.snapshot().token)
                save(c,"$tag.expected.json",codec.encodeToString(Dataset.serializer(),witness.data()))
                var armed=false
                val store=object: StateStore by base {
                    override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
                        val result=base.transaction(block)
                        if(armed && stage=="after-commit") { save(c,"$tag.expected.json",codec.encodeToString(Dataset.serializer(),base.read()));marker(c,tag,stage) }
                        return result
                    }
                }
                val real=PrivateRollbackStore(c)
                val rollback=object: RollbackStore by real {
                    override fun saveSnapshot(backup: Backup): String {
                        val ref=real.saveSnapshot(backup);save(c,"$tag.rollback.txt",ref)
                        if(stage=="rollback") marker(c,tag,stage)
                        return ref
                    }
                }
                val tracker=Tracker(store,clock);val backups=Backups(tracker,rollback);val inputFile=source(c,tag);val preview=backups.prepare { inputFile.inputStream() };armed=true
                backups.apply(preview)
                if(stage=="after-return") { save(c,"$tag.expected.json",codec.encodeToString(Dataset.serializer(),base.read()));marker(c,tag,stage) }
                error("Fixture did not suspend")
            }
            "remaining-restore-check" -> {
                val db=TrackerDatabase.open(c,"remaining-restore-$tag.db");val actual=RoomStore(db).read();val expected=codec.decodeFromString(Dataset.serializer(),text(c,"$tag.expected.json"));check(actual==expected);db.close()
                val ref=text(c,"$tag.rollback.txt");val restored=PrivateRollbackStore(c).readSnapshot(ref);check(restored.data.sessions.size==1)
                values["datasetExact"]=JsonPrimitive(true);values["rollbackReadable"]=JsonPrimitive(true);values["datasetId"]=JsonPrimitive(actual.datasetId);values["sessions"]=JsonPrimitive(actual.sessions.size)
            }
            "remaining-rescue-create" -> {
                requireFresh(c);check(stage in setOf("journal","archive","installed","completion"));check(!DatabaseRecovery.needsAttention(c))
                if(DatabaseFiles.exists(c)) { val prior=DatabaseArchives.capture(c,forceSeparate=true);PrivateCopyBackend.completeArchive(c,prior.name) } // Preserve previous valid fixture too.
                for(suffix in listOf("","-wal","-shm")) FileOutputStream(c.getDatabasePath("tracker.db$suffix")).use { it.write("Synthetic damaged $tag member$suffix".toByteArray());it.fd.sync() }
                DirectoryDurability.sync(c.getDatabasePath("tracker.db").parentFile!!)
                val inputFile=source(c,tag);save(c,"$tag.input.sha256",fileHash(inputFile));val preview=DatabaseRecovery.prepare(c,inputFile)
                val previousSync=DirectoryDurability.sync;val previousCompletion=DatabaseRecovery.afterCompletionDurable
                fun job() = File(c.noBackupFilesDir,"recovery-pending.json").takeIf { it.exists() }?.readText()?.let { codec.parseToJsonElement(it).jsonObject }
                DirectoryDurability.sync = { directory ->
                    previousSync(directory)
                    val j=job()
                    if(j!=null) {
                        val archive=j["archive"]?.jsonPrimitive?.contentOrNull
                        val candidate=j["candidate"]?.jsonPrimitive?.contentOrNull
                        if(stage=="journal" && directory==c.noBackupFilesDir && archive==null) marker(c,tag,stage)
                        if(stage=="archive" && directory==c.noBackupFilesDir && archive!=null && candidate!=null && !c.getDatabasePath(candidate).exists()) marker(c,tag,stage)
                        val target=c.getDatabasePath("tracker.db")
                        if(stage=="installed" && directory==target.parentFile && candidate!=null && target.isFile && target.inputStream().use { it.readNBytes(16).contentEquals("SQLite format 3\u0000".toByteArray()) }) marker(c,tag,stage)
                    }
                }
                DatabaseRecovery.afterCompletionDurable = { if(stage=="completion") marker(c,tag,stage) }
                try { DatabaseRecovery.apply(c,preview);error("Fixture did not suspend") }
                finally { DirectoryDurability.sync=previousSync;DatabaseRecovery.afterCompletionDurable=previousCompletion }
            }
            "remaining-rescue-check" -> {
                requireFresh(c);check(DatabaseRecovery.needsAttention(c));val job=File(c.noBackupFilesDir,"recovery-pending.json").readText();save(c,"$tag.job-before-resume.json",job)
                val completed=codec.parseToJsonElement(job).jsonObject["completedDatasetSha256"]?.jsonPrimitive?.contentOrNull
                val before=if(completed!=null) TrackerDatabase.open(c).let { db -> try { RoomStore(db,allowInitialize=false).read() }finally { db.close() } }else null
                val result=DatabaseRecovery.resume(c);check(!DatabaseRecovery.needsAttention(c));val db=TrackerDatabase.open(c);val actual=try { RoomStore(db).read() }finally { db.close() }
                val expected=Backups.restoredData(sample(tag),sample(tag).data.revision+1).copy(controlBarrier=actual.controlBarrier)
                check(actual==expected);if(before!=null)check(before==actual) { "Completion reconciliation replaced domain data" }
                check(fileHash(source(c,tag))==text(c,"$tag.input.sha256"))
                val archives=File(c.noBackupFilesDir,"database-archives").listFiles().orEmpty().filter { it.isDirectory }
                for(suffix in listOf("","-wal","-shm")) {
                    val bytes="Synthetic damaged $tag member$suffix".toByteArray();check(archives.any { a -> File(a,"tracker.db$suffix").takeIf { it.isFile }?.readBytes()?.contentEquals(bytes)==true })
                    check(archives.any { a -> File(a,"retired/tracker.db$suffix").takeIf { it.isFile }?.readBytes()?.contentEquals(bytes)==true })
                }
                values["resumed"]=JsonPrimitive(true);values["allRestoredDomainFieldsExact"]=JsonPrimitive(true);values["rawMainWalShmArchiveAndRetiredExact"]=JsonPrimitive(true);values["sourceExact"]=JsonPrimitive(true);values["journalCleared"]=JsonPrimitive(true);values["completionReconciledWithoutReplacement"]=JsonPrimitive(before!=null);values["archive"]=JsonPrimitive(result.archive)
            }
            "remaining-restore-stale-surfaces" -> {
                requireFresh(c);val runtime=TrackerRuntime.get(c);val tracker=runtime.tracker
                if(tracker.snapshot().mode==Mode.RECOVERY)tracker.resolveTail(tracker.snapshot().token,null,null)
                check(tracker.snapshot().mode==Mode.IDLE && tracker.snapshot().focus==null)
                tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(surfaces=tracker.data().settings.surfaces.copy(widget=true)))
                val idle=tracker.snapshot();val oldStart=TimerSurfaces.pending(c,SurfaceAction(Surface.WIDGET,TimerCommand.START,idle.token,idle.timerTarget))
                tracker.start(tracker.snapshot().token,note="Restored same session identity witness");val running=tracker.snapshot();val oldStop=TimerSurfaces.pending(c,SurfaceAction(Surface.WIDGET,TimerCommand.STOP,running.token,running.timerTarget))
                val file=File(root(c),"$tag.portable.json");FileOutputStream(file).use { runtime.backups.write(it);it.fd.sync() }
                val stalePreview=runtime.backups.prepare { file.inputStream() };val applying=runtime.backups.prepare { file.inputStream() };runtime.backups.apply(applying)
                val before=tracker.data();check(before.datasetId==running.token.datasetId && before.controlBarrier!=running.timerTarget!!.barrier)
                val rows=mutableListOf<JsonElement>()
                for((name,pending)in listOf("oldStart" to oldStart,"oldStop" to oldStop)) { deliver(c,pending);check(before==tracker.data());val code=c.getSharedPreferences("surface-results",0).getString("lastCode",null);check(code=="STALE");rows+=buildJsonObject { put("command",name);put("code",code);put("datasetExact",true) } }
                val rollbackBefore=PrivateRollbackStore(c).files().map { it.name }.toSet();var stale=false
                try { runtime.backups.apply(stalePreview) }catch(e: CoreError){stale=e.code==ErrorCode.STALE}finally{stalePreview.close()};check(stale && before==tracker.data());check(rollbackBefore==PrivateRollbackStore(c).files().map { it.name }.toSet())
                tracker.resolveTail(tracker.snapshot().token,null,null)
                values["rows"]=JsonArray(rows);values["sameDatasetIdNewBarrier"]=JsonPrimitive(true);values["stalePreviewRefusedBeforeAnotherRollback"]=JsonPrimitive(true)
            }
            "remaining-repeated-surfaces" -> {
                requireFresh(c);val t=TrackerRuntime.get(c).tracker;check(t.snapshot().mode==Mode.IDLE && t.snapshot().focus==null);t.settings(t.snapshot().token,t.data().settings.copy(surfaces=t.data().settings.surfaces.copy(widget=true)))
                val rows=mutableListOf<JsonElement>();var oldStop: PendingIntent?=null
                for((command,expected)in listOf(TimerCommand.START to Mode.RUNNING,TimerCommand.PAUSE to Mode.PAUSED,TimerCommand.RESUME to Mode.RUNNING,TimerCommand.STOP to Mode.IDLE)) {
                    val s=t.snapshot();val pending=TimerSurfaces.pending(c,SurfaceAction(Surface.WIDGET,command,s.token,s.timerTarget));if(command==TimerCommand.STOP)oldStop=pending
                    deliver(c,pending);check(t.snapshot().mode==expected);val after=t.data();deliver(c,pending);check(after==t.data()) { "Repeated $command changed dataset" }
                    rows+=buildJsonObject { put("command",command.name);put("modeAfterFirst",expected.name);put("duplicateDatasetExact",true);put("duplicateCode",c.getSharedPreferences("surface-results",0).getString("lastCode",null)) }
                }
                t.start(t.snapshot().token,note="Successor after old STOP");val successor=t.data();deliver(c,oldStop!!);check(successor==t.data());check(c.getSharedPreferences("surface-results",0).getString("lastCode",null)=="STALE");t.stop(t.snapshot().token)
                values["rows"]=JsonArray(rows);values["oldStopAfterSuccessorExact"]=JsonPrimitive(true)
            }
            else -> error("Unknown remaining mode $mode")
        }
        return JsonObject(values)
    }
    fun deliver(c: Context,pending: PendingIntent) {
        c.getSharedPreferences("surface-results",0).edit().remove("lastCode").commit();val finished=CountDownLatch(1)
        pending.send(c,0,null,{ _,_,_,_,_ -> finished.countDown() },Handler(Looper.getMainLooper()))
        check(finished.await(30,TimeUnit.SECONDS)) { "Broadcast completion not observed" };SystemClock.sleep(100)
    }
}
