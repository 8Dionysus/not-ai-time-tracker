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
            var wall = System.currentTimeMillis()-60_000; var elapsed = 100_000L
            val clock = Clock { Moment(wall,elapsed,"qa-backend-boot") }
            val tracker = Tracker(store,clock)
            if(mode == "backend-create") {
                val card = Card(id = "qa-backend-card",name = "Synthetic backend choice")
                tracker.saveCard(tracker.snapshot().token,card)
                tracker.saveFavorite(tracker.snapshot().token,Favorite(id = "qa-backend-favorite",label = "Synthetic",selection = card.classification()))
                tracker.start(tracker.snapshot().token)
                wall += 1000;elapsed += 1000
                val offer = tracker.stopForAssignment(tracker.snapshot().timerTarget!!).assignment!!
                val choice = tracker.choices().reference(tracker.choices().resolve("card:${card.id}")!!)
                atomicText(receipt,buildJsonObject { put("offer",codec.encodeToJsonElement(offer));put("choice",codec.encodeToJsonElement(choice)) }.toString())
                check(tracker.data().segments.single().durationMs == 1000L)
            } else {
                check(mode == "backend-reopen")
                val saved = codec.parseToJsonElement(receipt.readText()).jsonObject
                val offer = codec.decodeFromJsonElement<StoppedAssignment>(saved.getValue("offer"))
                val choice = codec.decodeFromJsonElement<ChoiceReference>(saved.getValue("choice"))
                check(tracker.pendingAssignments().single() == offer)
                tracker.assignStopped(offer.id,choice)
                check(tracker.data().sessions.single().selection.cardId == "qa-backend-card")
                check(tracker.data().segments.single().durationMs == 1000L)
                check(runCatching { tracker.assignStopped(offer.id,choice) }.exceptionOrNull().let { it is CoreError && it.code == ErrorCode.STALE })
                val id = tracker.data().sessions.single().id
                tracker.measure(tracker.snapshot().token,Measurement(fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = id,value = 3))
                tracker.measure(tracker.snapshot().token,Measurement(fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = tracker.data().segments.single().id,value = 2))
                val before = tracker.data()
                val preview = tracker.previewDeleteSession(id);check(preview.measurementCount == 2)
                tracker.applyDeleteSession(preview);check(tracker.data().sessions.isEmpty() && tracker.data().measurements.isEmpty())
                tracker.undo(tracker.snapshot().token)
                check(before.sessions == tracker.data().sessions && before.segments == tracker.data().segments && before.measurements == tracker.data().measurements)
                tracker.start(tracker.snapshot().token);wall += 1000;elapsed += 1000
                val old = tracker.stopForAssignment(tracker.snapshot().timerTarget!!).assignment!!
                tracker.start(tracker.snapshot().token)
                val current = tracker.snapshot().session!!.id
                check(runCatching { tracker.assignStopped(old.id,choice) }.exceptionOrNull().let { it is CoreError && it.code == ErrorCode.STALE })
                check(tracker.snapshot().session!!.id == current)
                wall += 1000;elapsed += 1000;tracker.stop(tracker.snapshot().token)
                val route = SurfaceChoices.route(Surface.NOTIFICATION,tracker.snapshot())
                val pending = SurfaceChoices.pending(context,route);check(pending.isActivity && pending.isImmutable)
                val savedRoute = android.os.Bundle();SurfaceChoices.save(savedRoute,route)
                check(route == SurfaceChoices.restore(savedRoute,android.content.Intent()))
                check(tracker.data().sessions.size == 3)
            }
            check(live == liveDigest(context)) { "Existing live records changed" }
            val widget = WidgetPlacementController(context).read()
            return buildJsonObject {
                put("schema","tracker_backend_native_v1");put("mode",mode);put("isolatedDatabase",name)
                put("liveRecordsUnchanged",true);put("liveDigest",live);put("sessions",tracker.data().sessions.size)
                put("segments",tracker.data().segments.size);put("measurements",tracker.data().measurements.size)
                put("pendingAssignments",tracker.pendingAssignments().size);put("widgetPinSupported",widget.supported)
                put("widgetInstanceCount",widget.ids.size);put("widgetPlacement",widget.placement.name)
                put("platformPinRequested",false);put("processId",android.os.Process.myPid())
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
