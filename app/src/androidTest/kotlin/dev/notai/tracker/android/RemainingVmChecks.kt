package dev.notai.tracker.android

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.notai.tracker.*
import dev.notai.tracker.android.ui.*
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Native Android lifecycle + real small Room graph; default history is never opened or replaced. */
object RemainingVmChecks {
    private fun waitFor(label: String,condition: ()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(!condition()) { check(System.nanoTime()<end) { "Native VM deadline: $label" };Thread.sleep(20) }
    }
    private fun prefSnapshot(c: Context): JsonObject = buildJsonObject {
        c.getSharedPreferences("ui",0).all.forEach { (key,value) -> put(key,buildJsonObject {
            put("type",when(value){is String->"string";is Boolean->"bool";is Int->"int";is Long->"long";is Float->"float";is Set<*>->"set";else->error("Unsupported preference type")})
            put("value",when(value){is String->JsonPrimitive(value);is Boolean->JsonPrimitive(value);is Int->JsonPrimitive(value);is Long->JsonPrimitive(value);is Float->JsonPrimitive(value);is Set<*>->JsonArray(value.map { JsonPrimitive(it as String) }.sortedBy { it.content });else->error("Unsupported preference type")})
        }) }
    }
    private fun restorePrefs(c: Context,tag: String) {
        val snapshot=codec.parseToJsonElement(RemainingNativeChecks.text(c,"$tag.prefs.json")).jsonObject
        val editor=c.getSharedPreferences("ui",0).edit().clear()
        snapshot.forEach { (key,node) -> val o=node.jsonObject;val v=o.getValue("value");when(o.getValue("type").jsonPrimitive.content){
            "string"->editor.putString(key,v.jsonPrimitive.content);"bool"->editor.putBoolean(key,v.jsonPrimitive.boolean);"int"->editor.putInt(key,v.jsonPrimitive.int);"long"->editor.putLong(key,v.jsonPrimitive.long);"float"->editor.putFloat(key,v.jsonPrimitive.float);"set"->editor.putStringSet(key,v.jsonArray.map { it.jsonPrimitive.content }.toSet())
        } };check(editor.commit());check(prefSnapshot(c)==snapshot)
    }
    private class Fixture(val i: Instrumentation,val c: Context,val tag: String,val reopen: Boolean=false) {
        val app=c.applicationContext as Application
        val prefs=c.getSharedPreferences("ui",0)
        val db=TrackerDatabase.open(c,"remaining-vm-$tag.db")
        val tracker=Tracker(RoomStore(db,{RemainingNativeChecks.sample(tag).data},allowInitialize=!reopen),AndroidClock(app))
        private val previous=UiBackends.open
        private val stores=mutableListOf<ViewModelStore>()
        val backend=object: UiBackend {
            override val tracker=this@Fixture.tracker
            override val backups=Backups(tracker,PrivateRollbackStore(c))
            override val focusDue=FocusDueSignals()
            override val runtime: TrackerRuntime?=null
            override fun prepareCatalog(): CatalogPreview=error("Not a catalog fixture")
            override fun applyCatalog(preview: CatalogPreview): Tracker.Change=error("Not a catalog fixture")
            override fun submitShortcut(action: SurfaceAction): Deferred<Result<Tracker.Change>> = error("Not a shortcut fixture")
        }
        init {
            check(c.packageName=="dev.notai.tracker");check(DocumentExportCoordination.writing.isEmpty())
            if(!reopen) {
                val old=prefs.getString("documentExportId",null)
                check(old==null || ExportOperations(c).read(old).state in setOf("COMPLETE","CANCELLED")) { "Active preexisting document export refused" }
                RemainingNativeChecks.save(c,"$tag.prefs.json",prefSnapshot(c).toString())
                check(prefs.edit().remove("documentExportId").remove("documentExportLaunched").remove("documentExportOwner").commit())
            }
            UiBackends.open={backend}
        }
        fun model(): Pair<TrackerViewModel,ViewModelStore> {
            lateinit var vm: TrackerViewModel;val store=ViewModelStore()
            i.runOnMainSync { vm=TrackerViewModel(app);store.put("native",vm) };stores+=store;waitFor("VM boot") { vm.boot.value is TrackerViewModel.Boot.Ready };return vm to store
        }
        fun request(vm: TrackerViewModel): String {
            i.runOnMainSync { vm.requestExport() };waitFor("staged export") { !vm.exportPreparing.value && vm.documentExportId()!=null };return vm.documentExportId()!!
        }
        fun finish() {
            DocumentExportCoordination.afterClaimCommit=null;DocumentExportCoordination.afterResultCommit=null;DocumentExportCoordination.afterWriteComplete=null
            i.runOnMainSync { stores.forEach { it.clear() } };UiBackends.open=previous;restorePrefs(c,tag);db.close()
        }
    }
    fun run(i: Instrumentation,c: Context,mode: String,input: String): JsonObject {
        val pieces=input.split(':');val tag=pieces[0];check(tag.matches(Regex("[a-zA-Z0-9-]+")));val phase=pieces.getOrElse(1){""}
        val values=linkedMapOf<String,JsonElement>("schema" to JsonPrimitive("remaining_vm_native_v1"),"mode" to JsonPrimitive(mode),"tag" to JsonPrimitive(tag),"pid" to JsonPrimitive(android.os.Process.myPid()))
        if(mode=="remaining-export-late") {
            val f=Fixture(i,c,tag);var activity: MainActivity?=null
            try {
                activity=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                lateinit var vm: TrackerViewModel;i.runOnMainSync { vm=ViewModelProvider(activity)[TrackerViewModel::class.java] };waitFor("activity VM boot") { vm.boot.value is TrackerViewModel.Boot.Ready }
                val launched=MainActivity::class.java.getDeclaredField("launchedDocumentExport").apply { isAccessible=true }
                val ownerField=MainActivity::class.java.getDeclaredField("launchedDocumentExportOwner").apply { isAccessible=true }
                val old=f.request(vm);waitFor("real old chooser launch") { launched.get(activity)==old }
                val oldOwner=ownerField.get(activity) as String;val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
                while(i.uiAutomation.rootInActiveWindow?.packageName?.toString()!="com.android.documentsui") { check(System.nanoTime()<end);Thread.sleep(50) }
                val oldHash=ExportOperations(c).read(old).sha256
                if(phase=="same-id")vm.releaseDocumentExport(old,oldOwner,IllegalStateException("Synthetic old chooser owner released"))
                else { i.runOnMainSync { vm.writeExport(old,null) };check(ExportOperations(c).read(old).state=="CANCELLED") }
                val newOwner=newId()
                val newer=if(phase=="same-id") {
                    // A separate successor model owns the retry; the old Activity must
                    // keep its old registered chooser witness while stopped.
                    val (successor,_)=f.model();runBlocking { successor.recoverDocumentExport(null).join() }
                    waitFor("same-stage successor receives retry") { successor.documentExport.value==old }
                    check(runBlocking { successor.claimDocumentExport(old,newOwner) });old
                } else { val id=f.request(vm);check(id!=old);check(runBlocking { vm.claimDocumentExport(id,newOwner) });id }
                val before=ExportOperations(c).read(newer);check(before.state=="READY" && !before.resultReceived);check(launched.get(activity)==old)
                RemainingNativeChecks.save(c,"$tag.late-before.json",buildJsonObject { put("oldId",old);put("oldOwner",oldOwner);put("newId",newer);put("newOwner",newOwner);put("oldActivityWitnessStillOld",true);put("realDocumentsUI",true) }.toString())
                repeat(4) {
                    if(launched.get(activity)==old && i.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.documentsui") {
                        i.uiAutomation.executeShellCommand("input keyevent 4").use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
                        Thread.sleep(600)
                    }
                }
                waitFor("old result callback") { launched.get(activity)==null };Thread.sleep(300)
                val after=ExportOperations(c).read(newer);val passed=after==before && vm.documentExportId()==newer && f.prefs.getString("documentExportOwner",null)==newOwner
                values["passed"]=JsonPrimitive(passed);values["oldId"]=JsonPrimitive(old);values["newId"]=JsonPrimitive(newer);values["newerStateBefore"]=JsonPrimitive(before.state);values["newerStateAfterOldOSCancel"]=JsonPrimitive(after.state);values["newerResultReceivedAfter"]=JsonPrimitive(after.resultReceived);values["newerPointerRetained"]=JsonPrimitive(vm.documentExportId()==newer);values["oldBytesExact"]=JsonPrimitive(ExportOperations(c).read(old).sha256==oldHash);values["actualOSDocumentsCancel"]=JsonPrimitive(true)
                RemainingNativeChecks.save(c,"$tag.late-observed.json",JsonObject(values).toString())
                if(vm.documentExportId()==newer)i.runOnMainSync { vm.writeExport(newer,null) }
            } finally {
                // A failed fixture must not leave a system chooser attached to a dead owner.
                if(i.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.documentsui")
                    i.uiAutomation.executeShellCommand("am force-stop com.android.documentsui").use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
                i.waitForIdleSync();i.runOnMainSync { activity?.finish() };f.finish()
            }
            return JsonObject(values)
        }
        if(mode=="remaining-vm-local") {
            val f=Fixture(i,c,tag);val rows=mutableListOf<JsonElement>()
            try {
                for(which in listOf("cleared-before-call","clear-after-result-commit","open-output-failure")) {
                    val (vm,store)=f.model();val id=f.request(vm);val hash=ExportOperations(c).read(id).sha256;check(runBlocking { vm.claimDocumentExport(id,newId()) })
                    val dest=if(which=="open-output-failure") File(RemainingNativeChecks.root(c),"absent-$id/destination.json")else File(RemainingNativeChecks.root(c),"$id.local.json")
                    if(which=="cleared-before-call")i.runOnMainSync { store.clear() }
                    if(which=="clear-after-result-commit")DocumentExportCoordination.afterResultCommit={store.clear()}
                    var elapsed=0L;i.runOnMainSync { val start=System.nanoTime();vm.writeExport(id,Uri.fromFile(dest));elapsed=System.nanoTime()-start }
                    DocumentExportCoordination.afterResultCommit=null
                    waitFor("write terminal/cancellation") { id !in DocumentExportCoordination.writing }
                    val operation=ExportOperations(c).read(id);check(operation.state=="READY" && operation.resultReceived && operation.sha256==hash && !dest.exists());check(vm.documentExportId()==id && !f.prefs.getBoolean("documentExportLaunched",true));check(!f.prefs.contains("documentExportOwner"))
                    val (successor,_)=f.model();runBlocking { successor.recoverDocumentExport(id,"stale-registered-owner").join() };waitFor("same staged recovery") { successor.documentExport.value==id };check(runBlocking { successor.claimDocumentExport(id,newId()) })
                    val good=File(RemainingNativeChecks.root(c),"$id.retry.json");i.runOnMainSync { successor.writeExport(id,Uri.fromFile(good)) };waitFor("retry complete") { ExportOperations(c).read(id).state=="COMPLETE" && successor.documentExportId()==null };check(fileHash(good)==hash)
                    rows+=buildJsonObject { put("branch",which);put("onMainThreadCallNs",elapsed);put("resultDurableBeforeCancelledIO",true);put("sameStagedHashAfterFailure",true);put("newModelRetryComplete",true) }
                }
                // A cancelled old claim must not release a successor's durable owner.
                val (old,_)=f.model();val id=f.request(old);val entered=CountDownLatch(1);val release=CountDownLatch(1);val oldOwner=newId();val newOwner=newId()
                DocumentExportCoordination.afterClaimCommit={entered.countDown();check(release.await(20,TimeUnit.SECONDS))}
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
                val claimant=scope.launch { try { old.claimDocumentExport(id,oldOwner) }catch(e: CancellationException){old.releaseDocumentExport(id,oldOwner,e);throw e} }
                check(entered.await(20,TimeUnit.SECONDS));claimant.cancel();DocumentExportCoordination.afterClaimCommit=null
                val (next,_)=f.model();runBlocking { next.recoverDocumentExport(null).join() };waitFor("successor receives old stage") { next.documentExport.value==id };check(runBlocking { next.claimDocumentExport(id,newOwner) });release.countDown();runBlocking { claimant.join() };scope.cancel()
                check(f.prefs.getString("documentExportOwner",null)==newOwner && f.prefs.getBoolean("documentExportLaunched",false));i.runOnMainSync { next.writeExport(id,null) };check(ExportOperations(c).read(id).state=="CANCELLED")
                values["rows"]=JsonArray(rows);values["cancelledClaimCannotReleaseNewOwner"]=JsonPrimitive(true);values["passed"]=JsonPrimitive(true);values["timingScope"]=JsonPrimitive("one functional main-thread call per branch; no benchmark series or before/after claim")
            } finally { f.finish() }
            return JsonObject(values)
        }
        if(mode=="remaining-vm-death") {
            check(phase in setOf("claim","result","complete"));val f=Fixture(i,c,tag);val (vm,_)=f.model();val id=f.request(vm);val owner=newId();val op=ExportOperations(c).read(id);RemainingNativeChecks.save(c,"$tag.vm.json",buildJsonObject { put("id",id);put("owner",owner);put("phase",phase);put("sha256",op.sha256);put("bytes",op.bytes) }.toString())
            if(phase=="claim")DocumentExportCoordination.afterClaimCommit={RemainingNativeChecks.marker(c,tag,phase)}
            check(runBlocking { vm.claimDocumentExport(id,owner) })
            if(phase=="result")DocumentExportCoordination.afterResultCommit={RemainingNativeChecks.marker(c,tag,phase)}
            if(phase=="complete")DocumentExportCoordination.afterWriteComplete={RemainingNativeChecks.marker(c,tag,phase)}
            val dest=File(RemainingNativeChecks.root(c),"$tag.vm-destination.json");i.runOnMainSync { vm.writeExport(id,Uri.fromFile(dest)) }
            Thread.sleep(90_000);error("Death marker not consumed")
        }
        if(mode=="remaining-vm-check") {
            val saved=codec.parseToJsonElement(RemainingNativeChecks.text(c,"$tag.vm.json")).jsonObject;val id=saved.getValue("id").jsonPrimitive.content;val owner=saved.getValue("owner").jsonPrimitive.content;val phaseSaved=saved.getValue("phase").jsonPrimitive.content;val hash=saved.getValue("sha256").jsonPrimitive.content;val f=Fixture(i,c,tag,reopen=true)
            try {
                val op=ExportOperations(c).read(id);check(op.sha256==hash)
                val (vm,_)=f.model()
                runBlocking { if(phaseSaved=="claim")vm.recoverDocumentExport(null).join()else vm.recoverDocumentExport(id,owner).join() }
                if(phaseSaved=="complete") { check(op.state=="COMPLETE");waitFor("complete pointer recovered") { vm.documentExportId()==null };check(vm.lastBackup.value?.verified==true);check(fileHash(File(RemainingNativeChecks.root(c),"$tag.vm-destination.json"))==hash) }
                else {
                    check(op.state=="READY" && op.resultReceived==(phaseSaved=="result"));waitFor("same-stage retry exposed") { vm.documentExport.value==id };check(runBlocking { vm.claimDocumentExport(id,newId()) });val dest=File(RemainingNativeChecks.root(c),"$tag.vm-retry.json");i.runOnMainSync { vm.writeExport(id,Uri.fromFile(dest)) };waitFor("retry complete") { ExportOperations(c).read(id).state=="COMPLETE" && vm.documentExportId()==null };check(fileHash(dest)==hash)
                }
                values["passed"]=JsonPrimitive(true);values["phase"]=JsonPrimitive(phaseSaved);values["sameStagedSha256"]=JsonPrimitive(hash);values["durableOperationRecovered"]=JsonPrimitive(true);values["uiPointerRecovered"]=JsonPrimitive(true)
            } finally { f.finish() }
            return JsonObject(values)
        }
        error("Unknown VM native mode $mode")
    }
}
