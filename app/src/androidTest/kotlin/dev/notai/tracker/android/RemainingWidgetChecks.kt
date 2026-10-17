package dev.notai.tracker.android

import android.app.Instrumentation
import android.app.PendingIntent
import android.content.*
import android.os.Looper
import androidx.lifecycle.Lifecycle
import dev.notai.tracker.newId
import java.io.FileInputStream
import java.lang.reflect.Proxy
import kotlinx.serialization.json.*

object RemainingWidgetChecks {
    private class IsolatedPrefs(base: Context,val name: String,val fail: Boolean): ContextWrapper(base) {
        override fun getSharedPreferences(n: String,mode: Int): SharedPreferences {
            val real=super.getSharedPreferences("remaining-widget-$name-$n",mode)
            if(!fail)return real
            return object: SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor=real.edit()
                    return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,m,args ->
                        when(m.name) { "commit"->false;"apply"->error("Fixture forbids async replacement of durable receipt");else->{val value=m.invoke(editor,*(args?:emptyArray()));if(value is SharedPreferences.Editor)proxy else value} }
                    } as SharedPreferences.Editor
                }
            }
        }
    }
    private fun waitFor(condition: ()->Boolean) { val end=System.nanoTime()+30_000_000_000L;while(!condition()){check(System.nanoTime()<end);Thread.sleep(30)} }
    private fun back(i: Instrumentation) { i.uiAutomation.executeShellCommand("input keyevent 4").use { FileInputStream(it.fileDescriptor).use { input->input.readBytes() } } }
    private fun callback(c: Context,id: String) = PendingIntent.getBroadcast(c,0,Intent(c,WidgetPinReceiver::class.java).setAction("dev.notai.tracker.WIDGET_PIN").setData(android.net.Uri.parse("notai://widget-pin/$id")).putExtra("request",id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun run(i: Instrumentation,c: Context,mode: String,tag: String): JsonObject {
        check(c.packageName=="dev.notai.tracker");check(tag.matches(Regex("[a-zA-Z0-9-]+")))
        val activity=i.startActivitySync(Intent(c,TechnicalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TechnicalActivity
        waitFor { activity.hasWindowFocus() }
        try {
            if(mode=="remaining-widget-faults") {
                val rows=mutableListOf<JsonElement>()
                for(behavior in listOf("unsupported","receipt-false","refuse","throw","accept")) {
                    val ctx=IsolatedPrefs(c,"$tag-$behavior",behavior=="receipt-false");val prefs=ctx.getSharedPreferences("widget-placement",0);var called=0;var receiptSeen=false
                    val port=object: WidgetPlacementPlatform {
                        override val supported get()=behavior!="unsupported"
                        override fun ids(provider: ComponentName)=intArrayOf()
                        override fun request(provider: ComponentName,callback: PendingIntent): Boolean {
                            called++;receiptSeen=prefs.getString("state",null)=="PENDING" && !prefs.getString("request",null).isNullOrBlank();check(receiptSeen)
                            if(behavior=="throw")throw IllegalStateException("Explicit pin boundary fault")
                            return behavior=="accept"
                        }
                    }
                    val controller=WidgetPlacementController(ctx,port);lateinit var state: WidgetState;var ns=0L
                    i.runOnMainSync { val start=System.nanoTime();state=controller.request(activity);ns=System.nanoTime()-start }
                    when(behavior) {
                        "unsupported"->{check(state.placement==WidgetPlacement.UNSUPPORTED && called==0 && prefs.all.isEmpty())}
                        "receipt-false"->{check(state.problem==SurfaceProblem.RECEIPT_STORAGE && called==0 && prefs.all.isEmpty())}
                        "refuse"->{check(state.placement==WidgetPlacement.UNCONFIRMED && state.problem==SurfaceProblem.LAUNCHER_UNSUPPORTED && receiptSeen)}
                        "throw"->{check(state.placement==WidgetPlacement.FAILED && state.problem==SurfaceProblem.PLATFORM_FAILURE && receiptSeen)}
                        "accept"->{check(state.placement==WidgetPlacement.PENDING && receiptSeen);val before=called;i.runOnMainSync { check(controller.request(activity).requestId==state.requestId) };check(called==before)}
                    }
                    rows+=buildJsonObject { put("branch",behavior);put("placement",state.placement.name);put("problem",state.problem?.name);put("pinCalls",called);put("durablePendingBeforePlatform",receiptSeen);put("mainThreadCallNs",ns) }
                }
                val ctx=IsolatedPrefs(c,"$tag-background",false);var calls=0;val port=object:WidgetPlacementPlatform {override val supported=true;override fun ids(provider:ComponentName)=intArrayOf();override fun request(provider:ComponentName,callback:PendingIntent):Boolean {calls++;return true}}
                check(Looper.myLooper()!=Looper.getMainLooper());val background=WidgetPlacementController(ctx,port).request(activity);check(background.problem==SurfaceProblem.FOREGROUND_REQUIRED && calls==0 && ctx.getSharedPreferences("widget-placement",0).all.isEmpty())
                return buildJsonObject { put("schema","remaining_widget_native_v1");put("mode",mode);put("passed",true);put("rows",JsonArray(rows));put("foregroundGuardPreserved",true);put("faultInjectionScope","Native controller/native SharedPreferences; only platform response and commit=false are injected; public constructor stays real AppWidgetManager") }
            }
            check(mode=="remaining-widget-overlap");val prefs=c.getSharedPreferences("widget-placement",0);val original=prefs.all.toMap();check(original.values.all { it is String });val controller=WidgetPlacementController(c);check(controller.read().supported && controller.read().ids.isEmpty());val dataBefore=TrackerRuntime.get(c).tracker.data();val ids=mutableListOf<String>()
            try {
                repeat(3) { index ->
                    waitFor { activity.hasWindowFocus() }
                    i.runOnMainSync { controller.read(true) }
                    lateinit var state:WidgetState;i.runOnMainSync { state=controller.request(activity) };RemainingNativeChecks.save(c,"$tag.request-$index.json",buildJsonObject { put("state",state.placement.name);put("problem",state.problem?.name);put("id",state.requestId);put("lifecycle",if(activity.hasWindowFocus()) "FOCUSED" else "NOT_FOCUSED");put("windowFocus",activity.hasWindowFocus()) }.toString());check(state.placement==WidgetPlacement.PENDING) { state.toString() };i.runOnMainSync { check(controller.request(activity).requestId==state.requestId) }
                    val id=state.requestId!!;check(id !in ids);ids+=id
                    if(index>0) {
                        val before=prefs.all.toMap();RemainingNativeChecks.deliver(c,callback(c,ids[index-1]));check(before==prefs.all)
                    }
                    waitFor { i.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.launcher3" }
                    RemainingNativeChecks.save(c,"$tag.pin-dialog-$index.txt",i.uiAutomation.rootInActiveWindow?.toString().orEmpty())
                    back(i);waitFor { activity.hasWindowFocus() }
                    i.runOnMainSync { check(controller.read(true).placement==WidgetPlacement.UNCONFIRMED) }
                    RemainingNativeChecks.deliver(c,callback(c,id));check(prefs.getString("request",null)==id && prefs.getString("state",null)=="UNCONFIRMED")
                }
                val before=prefs.all.toMap();RemainingNativeChecks.deliver(c,callback(c,ids.first()));check(before==prefs.all)
                check(controller.read().ids.isEmpty());check(dataBefore==TrackerRuntime.get(c).tracker.data())
                return buildJsonObject { put("schema","remaining_widget_native_v1");put("mode",mode);put("passed",true);put("actualAppWidgetManagerRequests",3);put("requestIds",JsonArray(ids.map(::JsonPrimitive)));put("repeatedPendingRequestKeepsIdentity",true);put("staleCallbackCannotReplaceNewRequest",true);put("currentCallbackWithoutActualWidgetIsUnconfirmed",true);put("defaultDatasetExact",true);put("noWidgetAcceptedOrRemoved",true) }
            } finally {
                val edit=prefs.edit().clear();original.forEach { (k,v)->edit.putString(k,v as String) };check(edit.commit());check(prefs.all==original)
            }
        } finally { i.runOnMainSync { activity.finish() } }
    }
}
