package dev.notai.tracker.android

import dev.notai.tracker.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomScenariosTest: ScenarioContract() {
    private val databases = mutableMapOf<StateStore,Pair<String,TrackerDatabase>>()
    override fun createStore(): StateStore = open("test-${newId()}.db")
    private fun open(name: String): StateStore {
        val db = androidx.room.Room.databaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java,name).addMigrations(TrackerDatabase.MIGRATION_1_2,TrackerDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        return RoomStore(db).also { databases[it] = name to db }
    }
    override fun reopen(store: StateStore): StateStore {
        val (name,db) = databases.remove(store)!!; db.close(); return open(name)
    }
    override fun migrateStoredV1(json: String): Dataset {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-${newId()}.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val legacy = codec.parseToJsonElement(json).jsonObject.getValue("data").jsonObject
        val schemaFile = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!.resolve("app/schemas/dev.notai.tracker.android.TrackerDatabase/1.json")
        val schema = codec.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val e = entity.jsonObject
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",e.getValue("tableName").jsonPrimitive.content))
                e["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",e.getValue("tableName").jsonPrimitive.content))
                }
            }
            val arrays = mapOf("types" to "activity_types","cards" to "cards","sessions" to "sessions","segments" to "segments","favorites" to "favorites","measurements" to "measurements")
            arrays.forEach { (key,table) -> legacy.getValue(key).jsonArray.forEachIndexed { i,element ->
                val row = element.jsonObject; val values = android.content.ContentValues()
                values.put("id",row.getValue("id").jsonPrimitive.content); values.put("position",i); values.put("payload",row.toString())
                if(key == "segments") {
                    values.put("sessionId",row.getValue("sessionId").jsonPrimitive.content)
                    if(row["end"] is JsonNull) values.put("openSlot",1) else values.putNull("openSlot")
                }
                db.insertOrThrow(table,null,values)
            } }
            val meta = JsonObject(legacy.toMutableMap().apply { arrays.keys.forEach { put(it,JsonArray(emptyList())) } })
            db.execSQL("INSERT INTO metadata(id,payload) VALUES(1,?)",arrayOf(meta.toString()))
            db.version = 1
        }
        val store = open(name)
        org.junit.Assert.assertEquals(3,databases.getValue(store).second.openHelper.readableDatabase.version)
        return store.read().also { validate(it) }
    }
    override fun assertImportRollback(tracker: Tracker,json: String,disk: RollbackStore) {
        val db = databases.getValue(tracker.store).second
        // Metadata is written last, after child tables were replaced: force a real SQLite write failure.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")
        try {
            val backups = Backups(tracker,disk)
            val error = org.junit.Assert.assertThrows(CoreError::class.java) { backups.apply(backups.prepare(json)) }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,error.code)
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_import") }
    }
    override fun assertTransferFailure(transfer: HistoryTransfer,preview: HistoryPreview,tracker: Tracker) {
        val db = databases.getValue(tracker.store).second.openHelper.writableDatabase
        val before = tracker.data()
        db.execSQL("CREATE TRIGGER fail_transfer BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected transfer failure'); END")
        try {
            val e = org.junit.Assert.assertThrows(CoreError::class.java) { transfer.apply(preview) }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,e.code)
        } finally { db.execSQL("DROP TRIGGER fail_transfer") }
        org.junit.Assert.assertEquals(before,tracker.data())
    }
    override fun surfaceActions() {
        val focusClock = FakeClock(); val focused = Tracker(createStore(),focusClock)
        focused.startFocus(focused.snapshot().token,plan = FocusPlan(1_000,1_000))
        org.junit.Assert.assertEquals(TimerCommand.PAUSE,TimerSurfaces.primary(focused.snapshot()))
        focusClock.advance(1_000); org.junit.Assert.assertNull(TimerSurfaces.primary(focused.snapshot()))
        focused.focusNext(focused.snapshot().token)
        org.junit.Assert.assertEquals(TimerCommand.PAUSE,TimerSurfaces.primary(focused.snapshot()))
        focused.pauseFocus(focused.snapshot().token)
        org.junit.Assert.assertEquals(TimerCommand.RESUME,TimerSurfaces.primary(focused.snapshot()))
        focused.resumeFocus(focused.snapshot().token)
        val beforeRecovery = focused.data(); focusClock.process = "recreated-process"
        org.junit.Assert.assertNull(TimerSurfaces.primary(focused.snapshot()))
        val blocked = org.junit.Assert.assertThrows(CoreError::class.java) {
            TimerSurfaces.execute(focused,SurfaceAction(Surface.SHORTCUT,TimerCommand.PAUSE,focused.snapshot().token))
        }
        org.junit.Assert.assertEquals(ErrorCode.RECOVERY_REQUIRED,blocked.code)
        org.junit.Assert.assertEquals(beforeRecovery,focused.data())
        val context = RuntimeEnvironment.getApplication()
        val tracker = Tracker(createStore(),FakeClock())
        val idle = tracker.snapshot()
        val action = SurfaceAction(Surface.WIDGET,TimerCommand.START,idle.token)
        org.junit.Assert.assertEquals(action,TimerSurfaces.parse(TimerSurfaces.intent(context,action)))
        org.junit.Assert.assertNull(TimerSurfaces.parse(android.content.Intent()))
        val before = tracker.data()
        org.junit.Assert.assertThrows(CoreError::class.java) { TimerSurfaces.execute(tracker,action) }
        org.junit.Assert.assertEquals(before,tracker.data())
        tracker.settings(idle.token,Settings(surfaces = SurfaceSettings(true,true,true)))
        val fresh = action.copy(token = tracker.snapshot().token)
        val widgetPending = TimerSurfaces.pending(context,fresh)
        TimerSurfaces.execute(tracker,fresh)
        val started = tracker.data()
        val stale = org.junit.Assert.assertThrows(CoreError::class.java) { TimerSurfaces.execute(tracker,fresh.copy(source = Surface.NOTIFICATION)) }
        org.junit.Assert.assertEquals(ErrorCode.STALE,stale.code)
        org.junit.Assert.assertEquals(started,tracker.data())
        val pausedAction = SurfaceAction(Surface.OVERLAY,TimerCommand.PAUSE,tracker.snapshot().token)
        org.junit.Assert.assertThrows(CoreError::class.java) { TimerSurfaces.execute(tracker,pausedAction,permission = false) }
        org.junit.Assert.assertEquals(started,tracker.data())
        TimerSurfaces.execute(tracker,pausedAction)
        val latest = tracker.snapshot()
        org.junit.Assert.assertNotEquals(widgetPending,TimerSurfaces.pending(context,fresh.copy(token = latest.token)))
        val rendered = TimerSurfaces.widget(context,latest,true).apply(context,null)
        org.junit.Assert.assertEquals("RESUME",rendered.findViewById<android.widget.Button>(R.id.widget_primary).text.toString())
        val disabled = TimerSurfaces.widget(context,latest,false).apply(context,null)
        org.junit.Assert.assertEquals("Открыть",disabled.findViewById<android.widget.Button>(R.id.widget_primary).text.toString())
        val notification = TimerSurfaces.notification(context,latest)
        org.junit.Assert.assertEquals(latest.safeLabel,notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        org.junit.Assert.assertEquals(2,notification.actions.size)
        org.junit.Assert.assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notification.visibility)
        TimerSurfaces.shortcut(context,latest)
        val launcher = context.getSystemService(android.content.pm.ShortcutManager::class.java).dynamicShortcuts.single()
        val launcherAction = requireNotNull(TimerSurfaces.parse(launcher.intent!!))
        org.junit.Assert.assertEquals(Surface.SHORTCUT,launcherAction.source)
        org.junit.Assert.assertEquals(latest.token,launcherAction.token)
        val savedLaunch = android.os.Bundle()
        TimerSurfaces.saveShortcut(context,savedLaunch,launcherAction) // no runtime yet
        val parcel = android.os.Parcel.obtain()
        try {
            savedLaunch.writeToParcel(parcel,0); parcel.setDataPosition(0)
            val recreated = android.os.Bundle.CREATOR.createFromParcel(parcel)
            org.junit.Assert.assertEquals(launcherAction,TimerSurfaces.restoredShortcut(recreated,android.content.Intent()))
            org.junit.Assert.assertNull(TimerSurfaces.restoredShortcut(android.os.Bundle(),launcher.intent!!)) // already submitted, do not replay launch intent
        } finally { parcel.recycle() }
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.junit.Assert.assertFalse(TimerSurfaces.notificationsAllowed(context))
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        org.junit.Assert.assertFalse(TimerSurfaces.overlayAllowed(context))
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            org.junit.Assert.assertTrue(FloatingTimerService.fromVisibleActivity(activity.get(),SurfaceSettings(overlay = true)).contains("не предоставлено"))
            org.junit.Assert.assertNull(org.robolectric.Shadows.shadowOf(context).nextStartedService)
        } finally { activity.pause().stop().destroy() }
        // Turning surfaces off preserves the paused session. Old intents are gated after reopen.
        tracker.settings(latest.token,tracker.data().settings.copy(surfaces = SurfaceSettings()))
        val saved = tracker.data(); val reopened = Tracker(reopen(tracker.store),tracker.clock)
        org.junit.Assert.assertThrows(CoreError::class.java) { TimerSurfaces.execute(reopened,pausedAction.copy(command = TimerCommand.RESUME,token = reopened.snapshot().token)) }
        org.junit.Assert.assertEquals(saved,reopened.data())
        org.junit.Assert.assertEquals(Mode.PAUSED,reopened.snapshot().mode)
        // Reproduce recreation while the launcher command is waiting immediately before its transaction.
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val blocking = object: StateStore by reopened.store {
            override fun <T> checkedTransaction(block: (CheckedState)->CheckedCommit<T>): T {
                entered.countDown(); check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                return reopened.store.checkedTransaction(block)
            }
        }
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val applicationScope = CoroutineScope(SupervisorJob()+dispatcher)
        val activityScope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val actions = ShortcutCommands(Tracker(blocking,reopened.clock),applicationScope)
            val resume = SurfaceAction(Surface.SHORTCUT,TimerCommand.RESUME,reopened.snapshot().token)
            val submitted = actions.submit(resume)
            val observer = activityScope.launch(start = CoroutineStart.UNDISPATCHED) { submitted.await() }
            org.junit.Assert.assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            activityScope.cancel() // onDestroy before persistence
            org.junit.Assert.assertTrue(observer.isCancelled)
            org.junit.Assert.assertEquals(Mode.PAUSED,reopened.snapshot().mode)
            release.countDown()
            runBlocking {
                org.junit.Assert.assertTrue(submitted.await().isSuccess)
                org.junit.Assert.assertEquals(Mode.RUNNING,reopened.snapshot().mode)
                val duplicate = actions.submit(resume).await().exceptionOrNull() as CoreError
                org.junit.Assert.assertEquals(ErrorCode.STALE,duplicate.code)
            }
        } finally { release.countDown(); activityScope.cancel(); applicationScope.cancel(); dispatcher.close() }
    }
    override fun storageWorkload() {
        val store = createStore(); val db = databases.getValue(store).second
        val clock = FakeClock(); val tracker = Tracker(store,clock)
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!
        val vocabulary = codec.decodeFromString<VocabularyData>(root.resolve("evidence/full-projection/runtime-vocabulary.json").readText())
        tracker.addCatalog(tracker.snapshot().token,vocabulary)
        benchmarkCheckedPath(tracker.data())
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE io_audit(table_name TEXT, operation TEXT)")
        for(table in listOf("activity_types","cards","sessions","segments","metadata","favorites","measurements","vocabulary","store_generation")) {
            for(op in listOf("INSERT","UPDATE","DELETE")) sql.execSQL("CREATE TRIGGER audit_${table}_${op} AFTER $op ON $table BEGIN INSERT INTO io_audit VALUES('$table','$op'); END")
        }
        val started = System.nanoTime()
        tracker.start(tracker.snapshot().token); clock.advance(1000); tracker.pause(tracker.snapshot().token)
        val elapsed = System.nanoTime()-started
        val rows = mutableListOf<String>()
        sql.query("SELECT table_name,operation,COUNT(*) FROM io_audit GROUP BY table_name,operation ORDER BY table_name,operation").use { c ->
            while(c.moveToNext()) rows += "${c.getString(0)} ${c.getString(1)} ${c.getLong(2)}"
        }
        root.resolve("build/storage-workload.txt").writeText("Start + Pause; ${tracker.data().types.size} stored types / ${tracker.data().axes.size} axes (1918 imported types plus existing seed); Robolectric JVM, not device\nElapsed ns: $elapsed\n"+rows.joinToString("\n")+"\n")
        for(table in listOf("activity_types","cards","sessions","segments","metadata","favorites","measurements","vocabulary","store_generation"))
            for(op in listOf("INSERT","UPDATE","DELETE")) sql.execSQL("DROP TRIGGER audit_${table}_${op}")
        org.junit.Assert.assertFalse(rows.any { it.startsWith("activity_types ") || it.startsWith("vocabulary ") })
        sql.execSQL("DROP TABLE io_audit")
        val anotherStore = open(databases.getValue(store).first) // independent RoomDatabase / SQLite handle
        val another = Tracker(anotherStore,clock)
        another.saveCard(another.snapshot().token,Card(name = "Other store instance"))
        org.junit.Assert.assertEquals(another.data(),tracker.data()) // generation invalidates first cache
        val beforeFailure = tracker.data()
        sql.execSQL("CREATE TRIGGER fail_timer BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected timer failure'); END")
        try {
            val error = org.junit.Assert.assertThrows(CoreError::class.java) { tracker.resume(tracker.snapshot().token) }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,error.code)
        } finally { sql.execSQL("DROP TRIGGER fail_timer") }
        org.junit.Assert.assertEquals(beforeFailure,tracker.data())
        tracker.resume(tracker.snapshot().token); clock.advance(1000)
        val beforeSwitch = tracker.data(); tracker.switch(tracker.snapshot().token,Classification())
        clock.advance(1000); tracker.undo(tracker.snapshot().token)
        org.junit.Assert.assertEquals(beforeSwitch.sessions,tracker.data().sessions)
        org.junit.Assert.assertEquals(beforeSwitch.segments,tracker.data().segments)
        org.junit.Assert.assertEquals(3000L,tracker.snapshot().trustedDurationMs)
        assertV2Migration(tracker.data())
        val saved = tracker.data(); val reopened = reopen(store)
        org.junit.Assert.assertEquals(saved,reopened.read())
    }
    private fun benchmarkCheckedPath(catalog: Dataset) {
        val baseClock = FakeClock()
        val sessions = (0 until 2_000).map { Session(status = SessionStatus.STOPPED,selection = Classification()) }
        val segments = sessions.mapIndexed { i,s -> Segment(sessionId = s.id,start = baseClock.wall-4_000_000+i*2_000L,
            end = baseClock.wall-4_000_000+i*2_000L+1_000,durationMs = 1_000,selection = Classification()) }
        val input = catalog.copy(sessions = sessions,segments = segments)
        fun sample(fullFallback: Boolean): List<Long> {
            val store = createStore(); store.transaction { input to Unit }
            val use = if(!fullFallback) store else object: StateStore {
                override fun read() = store.read()
                override val changes get() = store.changes
                override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T = store.transaction(block)
            }
            val clock = FakeClock(); val tracker = Tracker(use,clock)
            return (0 until 9).map { iteration ->
                val start = System.nanoTime()
                tracker.start(tracker.snapshot().token); clock.advance(1_000); tracker.pause(tracker.snapshot().token)
                tracker.resume(tracker.snapshot().token); clock.advance(1_000); tracker.stop(tracker.snapshot().token)
                val elapsed = System.nanoTime()-start
                validate(tracker.data()) // independent full oracle, outside timed interval
                elapsed
            }.drop(2)
        }
        val fast = sample(false); val full = sample(true)
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!
        root.resolve("build/checked-path-benchmark.txt").writeText(
            "Robolectric JVM; 2000 closed history segments; ${catalog.types.size} types / ${catalog.axes.size} axes.\n"+
            "One sample: Start/Pause/Resume/Stop, including snapshot reads. Two warm-up cycles discarded; 7 samples.\n"+
            "Optimized checked Room path ns: $fast; median ${fast.sorted()[3]}\n"+
            "Safe default full-validation StateStore adapter over the same Room store ns: $full; median ${full.sorted()[3]}\n"+
            "Not an old-commit comparison, device measurement, animation test or latency guarantee. No timing assertion.\n")
    }
    private fun assertV2Migration(data: Dataset) {
        val context = RuntimeEnvironment.getApplication(); val name = "migration-v2-${newId()}.db"
        val schemaFile = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!.resolve("app/schemas/dev.notai.tracker.android.TrackerDatabase/2.json")
        val schema = codec.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val graph = codec.parseToJsonElement(codec.encodeToString(data)).jsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use { sql ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val e = entity.jsonObject
                sql.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",e.getValue("tableName").jsonPrimitive.content))
                e["indices"]?.jsonArray?.forEach { index -> sql.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",e.getValue("tableName").jsonPrimitive.content)) }
            }
            val tables = mapOf("types" to "activity_types","cards" to "cards","sessions" to "sessions","segments" to "segments","favorites" to "favorites","measurements" to "measurements")
            for((key,table) in tables) graph.getValue(key).jsonArray.forEachIndexed { i,item ->
                val row = item.jsonObject; val values = android.content.ContentValues()
                values.put("id",row.getValue("id").jsonPrimitive.content); values.put("position",i); values.put("payload",item.toString())
                if(key == "segments") {
                    values.put("sessionId",row.getValue("sessionId").jsonPrimitive.content)
                    if(row["end"] is JsonNull) values.put("openSlot",1) else values.putNull("openSlot")
                }
                sql.insertOrThrow(table,null,values)
            }
            val meta = JsonObject(graph.toMutableMap().apply { tables.keys.forEach { put(it,JsonArray(emptyList())) } })
            sql.execSQL("INSERT INTO metadata(id,payload) VALUES(1,?)",arrayOf(meta.toString())); sql.version = 2
        }
        val migrated = open(name)
        org.junit.Assert.assertEquals(data,migrated.read())
    }
    @After fun close() { databases.values.forEach { (name,db) -> db.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }; databases.clear() }
}
