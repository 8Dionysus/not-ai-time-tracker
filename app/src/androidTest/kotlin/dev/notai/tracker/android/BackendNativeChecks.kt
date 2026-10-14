package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import kotlinx.serialization.json.*
import java.io.File

/** Isolated synthetic database only. Both phases retain all created files for inspection. */
object BackendNativeChecks {
    fun run(context: Context,mode: String,name: String): JsonObject {
        require(name.matches(Regex("qa-backend-[a-zA-Z0-9-]+\\.db")))
        val live = liveDigest(context)
        val path = context.getDatabasePath(name)
        val receipt = File(context.noBackupFilesDir,"$name.assignment.json")
        if(mode == "backend-create") { check(!path.exists());check(!receipt.exists()) }
        val db = TrackerDatabase.open(context,name)
        try {
            val store = RoomStore(db)
            val saved = if(mode == "backend-create") null else codec.parseToJsonElement(receipt.readText()).jsonObject
            var wall = saved?.getValue("wall")?.jsonPrimitive?.long ?: (System.currentTimeMillis()-60_000)
            var elapsed = saved?.getValue("elapsed")?.jsonPrimitive?.long ?: 100_000L
            val clock = Clock { Moment(wall,elapsed,"qa-backend-boot") }
            val tracker = Tracker(store,clock)
            if(mode == "backend-create") {
                val card = Card(id = "qa-backend-card",name = "Synthetic backend choice")
                tracker.saveCard(tracker.snapshot().token,card)
                tracker.saveFavorite(tracker.snapshot().token,Favorite(id = "qa-backend-favorite",label = "Synthetic",selection = card.classification()))
                tracker.start(tracker.snapshot().token)
                val oldId = tracker.snapshot().session!!.id
                tracker.measure(tracker.snapshot().token,Measurement(id = "qa-old-pages",fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = oldId,value = 3))
                tracker.measure(tracker.snapshot().token,Measurement(id = "qa-old-reps",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = tracker.snapshot().active!!.id,value = 2))
                wall += 1000;elapsed += 1000
                val stopped = tracker.stopForAssignment(tracker.snapshot().timerTarget!!)
                val offer = stopped.assignment!!
                val choice = tracker.choices().reference(tracker.choices().resolve("card:${card.id}")!!)
                tracker.start(tracker.snapshot().token,Classification(name = "Current must remain unchanged"))
                val currentId = tracker.snapshot().session!!.id
                tracker.measure(tracker.snapshot().token,Measurement(id = "qa-current-pages",fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = currentId,value = 7))
                tracker.measure(tracker.snapshot().token,Measurement(id = "qa-current-reps",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = tracker.snapshot().active!!.id,value = 9))
                wall += 2000;elapsed += 2000
                atomicText(receipt,buildJsonObject {
                    put("offer",codec.encodeToJsonElement(offer));put("choice",codec.encodeToJsonElement(choice))
                    put("stoppedTarget",codec.encodeToJsonElement(stopped.state.timerTarget!!))
                    put("oldRoute",codec.encodeToJsonElement(SurfaceChoices.route(Surface.NOTIFICATION,stopped.state,offer)))
                    put("wall",wall);put("elapsed",elapsed);put("currentId",currentId)
                }.toString())
                check(tracker.data().segments.single { it.sessionId == oldId }.durationMs == 1000L)
                check(tracker.snapshot().trustedDurationMs == 2000L && tracker.snapshot().mode == Mode.RUNNING)
                check(tracker.pendingAssignments().single() == offer)
            } else {
                check(mode == "backend-reopen")
                val original = codec.decodeFromJsonElement<StoppedAssignment>(saved!!.getValue("offer"))
                val choice = codec.decodeFromJsonElement<ChoiceReference>(saved.getValue("choice"))
                val oldTarget = codec.decodeFromJsonElement<TimerTarget>(saved.getValue("stoppedTarget"))
                val oldRoute = codec.decodeFromJsonElement<SurfacePickerRoute>(saved.getValue("oldRoute"))
                val currentId = saved.getValue("currentId").jsonPrimitive.content
                check(tracker.pendingAssignments().single() == original)
                check(tracker.snapshot().session!!.id == currentId && tracker.snapshot().mode == Mode.RUNNING)
                val before = tracker.data();val beforeSnapshot = tracker.snapshot()
                fun stale(block: ()->Unit) { check(runCatching(block).exceptionOrNull().let { it is CoreError && it.code == ErrorCode.STALE }) }
                stale { tracker.timerCommand(oldTarget,TimerDirective.START) }
                stale { SurfaceChoices.read(tracker,oldRoute) }
                stale { tracker.assignStopped(original.id,choice,requestTarget = oldTarget) }
                check(before == tracker.data())
                val renewed = tracker.reopenAssignment(tracker.snapshot().token,original.sessionId).assignment!!
                check(renewed.id != original.id)
                stale { tracker.assignStopped(original.id,choice) }
                val freshRoute = SurfaceChoices.route(Surface.NOTIFICATION,tracker.snapshot(),renewed)
                check(SurfaceChoices.read(tracker,freshRoute).assignment == renewed)
                tracker.assignStopped(renewed.id,choice,"Past record only",requestTarget = freshRoute.target)
                val after = tracker.data();val afterSnapshot = tracker.snapshot()
                check(after.sessions.single { it.id == original.sessionId }.selection.cardId == "qa-backend-card")
                check(before.sessions.single { it.id == currentId } == after.sessions.single { it.id == currentId })
                check(before.segments.filter { it.sessionId == currentId } == after.segments.filter { it.sessionId == currentId })
                check(before.segments.map { listOf(it.id,it.start,it.end,it.durationMs,it.anchor,it.uncertain,it.observedUntil,it.limitMs) } ==
                    after.segments.map { listOf(it.id,it.start,it.end,it.durationMs,it.anchor,it.uncertain,it.observedUntil,it.limitMs) })
                check(before.measurements == after.measurements)
                check(beforeSnapshot.session == afterSnapshot.session && beforeSnapshot.timerTarget == afterSnapshot.timerTarget)
                check(beforeSnapshot.trustedDurationMs == afterSnapshot.trustedDurationMs && afterSnapshot.trustedDurationMs == 2000L)
                stale { tracker.assignStopped(renewed.id,choice) }
                stale { tracker.reopenAssignment(tracker.snapshot().token,original.sessionId) }
                tracker.undo(tracker.snapshot().token)
                stale { tracker.reopenAssignment(tracker.snapshot().token,original.sessionId) }
                check(before.sessions == tracker.data().sessions && before.segments == tracker.data().segments && before.measurements == tracker.data().measurements)
                val preview = tracker.previewDeleteSession(original.sessionId);check(preview.measurementCount == 2)
                tracker.applyDeleteSession(preview)
                check(tracker.data().sessions.single().id == currentId && tracker.data().measurements.size == 2)
                tracker.undo(tracker.snapshot().token)
                check(before.sessions == tracker.data().sessions && before.segments == tracker.data().segments && before.measurements == tracker.data().measurements)
                val pending = SurfaceChoices.pending(context,freshRoute);check(pending.isActivity && pending.isImmutable)
                val bundle = android.os.Bundle();SurfaceChoices.save(bundle,freshRoute)
                check(freshRoute == SurfaceChoices.restore(bundle,android.content.Intent()))
            }
            check(live == liveDigest(context)) { "Existing live records changed" }
            val widget = WidgetPlacementController(context).read()
            return buildJsonObject {
                put("schema","tracker_backend_native_v2");put("mode",mode);put("isolatedDatabase",name)
                put("clock","Synthetic fixed wall/monotonic samples, real Android processes and Room/SQLite")
                put("liveRecordsUnchanged",true);put("liveDigest",live);put("sessions",tracker.data().sessions.size)
                put("segments",tracker.data().segments.size);put("measurements",tracker.data().measurements.size)
                put("pendingAssignments",tracker.pendingAssignments().size);put("timerMode",tracker.snapshot().mode.name)
                put("currentDurationMs",tracker.snapshot().trustedDurationMs)
                put("nextStartBeforeProcessDeath",true)
                put("freshAssignmentSucceeded",mode == "backend-reopen")
                if(mode == "backend-reopen") {
                    put("currentSessionUnchanged",true);put("allTimesUnchanged",true);put("allMeasurementsUnchanged",true)
                    put("oldSurfaceTargetRejected",true);put("rotatedNonceRejected",true);put("replayRejected",true)
                }
                put("widgetPinSupported",widget.supported);put("widgetInstanceCount",widget.ids.size)
                put("widgetPlacement",widget.placement.name);put("platformPinRequested",false);put("processId",android.os.Process.myPid())
            }
        } finally { db.close() }
    }
    private fun liveDigest(context: Context): String {
        val path = context.getDatabasePath("tracker.db")
        if(!path.exists()) return "absent"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        android.database.sqlite.SQLiteDatabase.openDatabase(path.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            for(table in listOf("metadata","activity_types","cards","favorites","sessions","segments","measurements","vocabulary","history_classifications")) {
                digest.update(table.toByteArray())
                db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use { cursor -> while(cursor.moveToNext()) {
                    for(i in 0 until cursor.columnCount) {
                        val value = if(cursor.isNull(i)) "<null>" else cursor.getString(i)
                        val bytes = value.toByteArray();digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
                    }
                } }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
