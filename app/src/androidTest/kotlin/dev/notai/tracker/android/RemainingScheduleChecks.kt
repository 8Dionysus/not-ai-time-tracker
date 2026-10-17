package dev.notai.tracker.android

import android.app.*
import android.content.*
import android.os.Handler
import android.os.Looper
import dev.notai.tracker.*
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Technical renderer is process-local only. Default production composition stays explicitly
 * unconfigured; Application/channel lifecycle is backend-owned and is not replaced by this fixture. */
object RemainingScheduleChecks {
    private fun hash(data: Dataset,at: Long,id: String): String {
        val md=MessageDigest.getInstance("SHA-256")
        DigestOutputStream(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}},md).use { StreamingBackup.write(Backup(formatVersion=14,backupId=id,asOf=at,data=data),it) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun deliver(c: Context,hash: String) {
        val intent=Intent(c,ScheduleAlarmReceiver::class.java).setAction(AndroidScheduleDeliveryPlatform.ACTION).setData(android.net.Uri.parse("notai://schedule/reminder")).putExtra(AndroidScheduleDeliveryPlatform.HASH,hash)
        val p=PendingIntent.getBroadcast(c,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);val done=CountDownLatch(1)
        p.send(c,0,null,{_,_,_,_,_->done.countDown()},Handler(Looper.getMainLooper()));check(done.await(30,TimeUnit.SECONDS))
    }
    private fun create(t:Tracker,id:String,minutes:Long): ScheduleAlarmBatch {
        var due=Instant.ofEpochMilli(t.clock.now().wall).atZone(ZoneId.of("UTC")).plusMinutes(minutes).withSecond(0).withNano(0)
        if(due.hour==23 && due.minute==59)due=due.plusMinutes(1)
        val date=due.toLocalDate().toString();val time=DateTimeFormatter.ofPattern("HH:mm")
        t.scheduleCommand(ScheduleCommand("remaining-create-$id",t.snapshot().token,ScheduleAction.CREATE,id,rule=ScheduleRule(Classification(name="Synthetic native schedule $id"),date,extraDates=setOf(date),localTime=due.plusMinutes(1).format(time),zonePolicy=ScheduleZonePolicy.FIXED,zoneId="UTC",alertsEnabled=true,reminderTime=due.format(time))))
        return TrackerRuntimeHolder.runtime!!.scheduleDelivery.synchronize().next!!.also { check(it.tickets.single().key.seriesId==id) }
    }
    private object TrackerRuntimeHolder {var runtime:TrackerRuntime?=null}
    fun run(i: android.app.Instrumentation,c:Context,mode:String,tag:String):JsonObject {
        check(c.packageName=="dev.notai.tracker" && tag.matches(Regex("[a-zA-Z0-9-]+")))
        val manager=c.getSystemService(NotificationManager::class.java);val component=ComponentName(c,SchedulePlatformReceiver::class.java)
        if(mode=="remaining-schedule-cleanup") {
            val original=codec.parseToJsonElement(RemainingNativeChecks.text(c,"$tag.schedule-original.json")).jsonObject
            val saved=codec.parseToJsonElement(RemainingNativeChecks.text(c,"$tag.schedule-cold.json")).jsonObject
            val runtime=TrackerRuntime.get(c);val t=runtime.tracker;val id=saved.getValue("seriesId").jsonPrimitive.content
            check(t.data().schedule.series.single { it.id==id }.state==ScheduleState.ARCHIVED)
            val store=AndroidScheduleDeliveryStore(c);store.locked { check(store.control().pending==null) }
            check(runtime.scheduleDelivery.coordinator.attempts().none {it.ticket.key.seriesId==id})
            c.packageManager.setComponentEnabledSetting(component,original.getValue("receiverState").jsonPrimitive.int,android.content.pm.PackageManager.DONT_KILL_APP)
            if(original.getValue("channelWasAbsent").jsonPrimitive.boolean)manager.deleteNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL)
            return buildJsonObject { put("schema","remaining_schedule_native_v1");put("mode",mode);put("passed",true);put("priorFixturePlansAlreadyArchived",true);put("pendingClearedUnderRequiredGate",true);put("noColdAttemptConsumed",true);put("originalComponentAndOwnChannelRestored",true) }
        }
        if(mode=="remaining-schedule-warm") {
            check(c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled())
            val oldChannel=manager.getNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL);val oldComponent=c.packageManager.getComponentEnabledSetting(component)
            RemainingNativeChecks.save(c,"$tag.schedule-original.json",buildJsonObject { put("channelWasAbsent",oldChannel==null);put("receiverState",oldComponent) }.toString())
            if(oldChannel==null)manager.createNotificationChannel(NotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL,"Synthetic native schedule fixture",NotificationManager.IMPORTANCE_DEFAULT))
            check(ScheduleNoticeBinding.factory==null)
            val runtime=TrackerRuntime.get(c);TrackerRuntimeHolder.runtime=runtime;val t=runtime.tracker
            check(t.snapshot().mode==Mode.IDLE && t.snapshot().focus==null)
            check(t.data().schedule.series.none { it.state==ScheduleState.ACTIVE && it.revisions.last().rule.alertsEnabled }) { "Existing active schedule reminder refused" }
            val unbound=runtime.scheduleDelivery.synchronize();check(ScheduleDeliveryBlocker.RENDERER_UNAVAILABLE in unbound.availability.blockers)
            ScheduleNoticeBinding.install(ScheduleNoticeFactory { context,_,_ -> Notification.Builder(context,AndroidScheduleDeliveryPlatform.CHANNEL).setSmallIcon(android.R.drawable.ic_menu_today).setContentTitle("Synthetic native schedule completion").setVisibility(Notification.VISIBILITY_PRIVATE).build() })
            val warmId="remaining-warm-$tag";val batch=create(t,warmId,1);val armed=t.data();val identity=batch.tickets.single().identity
            RemainingNativeChecks.save(c,"$tag.schedule-warm-armed.json",buildJsonObject {put("hash",batch.hash);put("at",batch.at);put("identity",identity)}.toString())
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180)
            while(manager.activeNotifications.none { it.id==84 && it.tag=="schedule:$identity" }) { check(System.nanoTime()<deadline) { "No real inexact schedule notification observed" };Thread.sleep(250) }
            val posted=manager.activeNotifications.single { it.id==84 && it.tag=="schedule:$identity" }.postTime
            check(armed==t.data());val attempts=runtime.scheduleDelivery.coordinator.attempts().filter {it.ticket.key.seriesId==warmId};check(attempts.size==1 && attempts.single().status==ScheduleAttemptStatus.POSTED)
            repeat(2){deliver(c,batch.hash)};check(runtime.scheduleDelivery.coordinator.attempts().count {it.ticket.key.seriesId==warmId}==1);check(manager.activeNotifications.single {it.id==84 && it.tag=="schedule:$identity"}.postTime==posted);check(armed==t.data())
            t.scheduleCommand(ScheduleCommand("remaining-archive-$warmId",t.snapshot().token,ScheduleAction.SET_STATE,warmId,expectedSeriesRevision=1,state=ScheduleState.ARCHIVED))
            val coldId="remaining-cold-$tag";val cold=create(t,coldId,2);val at=t.clock.now().wall;val backupId="native-schedule-$tag";val digest=hash(t.data(),at,backupId)
            RemainingNativeChecks.save(c,"$tag.schedule-cold.json",buildJsonObject {put("hash",cold.hash);put("at",cold.at);put("seriesId",coldId);put("datasetSha256",digest);put("asOf",at);put("backupId",backupId)}.toString())
            return buildJsonObject {put("schema","remaining_schedule_native_v1");put("mode",mode);put("passed",true);put("realInexactAlarmPosted",true);put("noFactsFabricated",true);put("twoRepeatedBroadcastsSingleReceiptAndSamePostTime",true);put("postTime",posted);put("coldPendingForNewProcess",true);put("rendererScope","temporary technical process-local factory; production Application unmodified")}
        }
        check(mode=="remaining-schedule-cold")
        val saved=codec.parseToJsonElement(RemainingNativeChecks.text(c,"$tag.schedule-cold.json")).jsonObject;val original=codec.parseToJsonElement(RemainingNativeChecks.text(c,"$tag.schedule-original.json")).jsonObject
        check(ScheduleNoticeBinding.factory==null)
        val instance=TrackerRuntime::class.java.getDeclaredField("instance").apply { isAccessible=true };check(instance.get(null)==null) { "Runtime was already acquired before cold receiver" }
        val at=saved.getValue("at").jsonPrimitive.long;val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180)
        while(System.currentTimeMillis()<=at+500){check(System.nanoTime()<deadline);Thread.sleep(100)}
        deliver(c,saved.getValue("hash").jsonPrimitive.content)
        val runtime=TrackerRuntime.get(c);val t=runtime.tracker;val report=runtime.scheduleDelivery.synchronize();val coldId=saved.getValue("seriesId").jsonPrimitive.content
        check(ScheduleDeliveryBlocker.RENDERER_UNAVAILABLE in report.availability.blockers);check(runtime.scheduleDelivery.coordinator.attempts().none {it.ticket.key.seriesId==coldId});check(manager.activeNotifications.none {it.id==84 && it.tag?.startsWith("schedule:")==true})
        check(hash(t.data(),saved.getValue("asOf").jsonPrimitive.long,saved.getValue("backupId").jsonPrimitive.content)==saved.getValue("datasetSha256").jsonPrimitive.content)
        check(report.next?.hash==saved.getValue("hash").jsonPrimitive.content)
        RemainingNativeChecks.save(c,"$tag.schedule-cold-proof.json",buildJsonObject {put("coldReceiverBeforeRuntimeAcquisition",true);put("defaultFactoryAbsent",true);put("availabilityBlockers",JsonArray(report.availability.blockers.map { JsonPrimitive(it.name) }));put("pendingHash",report.next?.hash);put("fullDatasetStreamingHashExact",true);put("noColdAttemptConsumed",true);put("pid",android.os.Process.myPid())}.toString())
        t.scheduleCommand(ScheduleCommand("remaining-archive-$coldId",t.snapshot().token,ScheduleAction.SET_STATE,coldId,expectedSeriesRevision=1,state=ScheduleState.ARCHIVED));runtime.scheduleDelivery.synchronize();val finalStore=AndroidScheduleDeliveryStore(c);finalStore.locked { check(finalStore.control().pending==null) }
        c.packageManager.setComponentEnabledSetting(component,original.getValue("receiverState").jsonPrimitive.int,android.content.pm.PackageManager.DONT_KILL_APP)
        if(original.getValue("channelWasAbsent").jsonPrimitive.boolean)manager.deleteNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL)
        return buildJsonObject {put("schema","remaining_schedule_native_v1");put("mode",mode);put("passed",true);put("coldReceiverBeforeRuntimeAcquisition",true);put("coldDefaultFactoryAbsentObserved",true);put("pendingRetainedNoAttemptConsumed",true);put("fullDatasetHashExactBeforeCleanup",true);put("fixturePlansArchivedAndPendingCleared",true);put("originalReceiverStateRestored",true);put("exactProductBindingBlocker","TrackerApplication invokes backend ScheduleNoticeBinding.initialize before runtime; scheduleNoticeConfiguration still returns null. Supply production rendering/channel resource inputs through that hook. Technical renderer is not a production binding.")}
    }
}
