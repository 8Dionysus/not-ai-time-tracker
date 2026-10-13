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
    override fun backupDocumentInput() {
        val clock = FakeClock()
        val source = Tracker(createStore(),clock)
        source.saveCard(source.snapshot().token,Card(name = "Marker"))
        val json = Backups(source,MemoryRollback()).export()
        val receiver = Tracker(createStore(),clock)
        receiver.saveCard(receiver.snapshot().token,Card(name = "Local data"))
        val rollback = MemoryRollback()
        val backups = Backups(receiver,rollback)
        fun prepare(bytes: ByteArray) = java.io.ByteArrayInputStream(bytes).use { BackupDocuments.prepare(it,backups) }
        val before = receiver.data()
        val malformed = json.substringBefore("Marker").toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xC3.toByte(),0x28) + json.substringAfter("Marker").toByteArray(Charsets.UTF_8)
        for(bytes in listOf(malformed,byteArrayOf(0xFF.toByte()),json.dropLast(3).toByteArray(),ByteArray(MAX_BACKUP_BYTES+1))) {
            val error = org.junit.Assert.assertThrows(CoreError::class.java) { prepare(bytes) }
            org.junit.Assert.assertEquals(ErrorCode.UNSUPPORTED_IMPORT,error.code)
            org.junit.Assert.assertEquals(before,receiver.data())
            org.junit.Assert.assertTrue(rollback.saved.isEmpty())
        }
        val plain = prepare(json.toByteArray(Charsets.UTF_8))
        val bom = prepare(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())+json.toByteArray(Charsets.UTF_8))
        org.junit.Assert.assertEquals(plain.backupId,bom.backupId)
        org.junit.Assert.assertEquals(before,receiver.data()) // preview and cancellation do not replace data
        val result = backups.apply(bom)
        org.junit.Assert.assertEquals(source.data().copy(revision = before.revision+1),receiver.data())
        org.junit.Assert.assertEquals(before,Backups.decode(rollback.read(result.rollbackReference)).data)
        val stale = prepare(json.toByteArray(Charsets.UTF_8))
        receiver.settings(receiver.snapshot().token,Settings("Europe/Berlin"))
        val changed = receiver.data()
        val error = org.junit.Assert.assertThrows(CoreError::class.java) { backups.apply(stale) }
        org.junit.Assert.assertEquals(ErrorCode.STALE,error.code)
        org.junit.Assert.assertEquals(changed,receiver.data())
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
        externalInputAdapters()
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
    private fun externalInputAdapters() {
        val text = "  Selected text\nwithout trimming  "
        val shared = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT,text)
        val draft = ProcessTextDrafts.read(shared)!!
        org.junit.Assert.assertEquals(text,draft.note)
        org.junit.Assert.assertEquals(DraftSource.SELECTED_TEXT,draft.source)
        org.junit.Assert.assertNull(draft.interval)
        org.junit.Assert.assertNull(ProcessTextDrafts.read(android.content.Intent()))
        org.junit.Assert.assertThrows(CoreError::class.java) { ProcessTextDrafts.read(android.content.Intent(shared).setType("text/html")) }
        org.junit.Assert.assertThrows(CoreError::class.java) { ProcessTextDrafts.read(android.content.Intent(shared).putExtra(android.content.Intent.EXTRA_PROCESS_TEXT," ")) }
        org.junit.Assert.assertThrows(CoreError::class.java) { ProcessTextDrafts.read(android.content.Intent(shared).putExtra(android.content.Intent.EXTRA_PROCESS_TEXT,"x".repeat(16385))) }
        val reference = CalendarOccurrenceRef(7,1000,2000)
        var permission = false; var queries = 0; var allDay = false; var revoked = false
        val reader = CalendarOccurrences({ permission }) { uri,columns,where,args ->
            queries++
            org.junit.Assert.assertEquals(listOf("1000","2000"),uri.pathSegments.takeLast(2))
            org.junit.Assert.assertEquals(listOf("7","1000","2000"),args.toList())
            org.junit.Assert.assertTrue(where.contains("event_id = ?"))
            if(revoked) throw SecurityException("revoked")
            android.database.MatrixCursor(columns).apply { addRow(arrayOf<Any>(7L,1000L,2000L,"Calendar title",if(allDay) 1 else 0)) }
        }
        org.junit.Assert.assertEquals(CalendarReadIssue.DISABLED,reader.readSelected(reference).issue)
        org.junit.Assert.assertEquals(CalendarReadIssue.PERMISSION_REQUIRED,reader.readSelected(reference,enabled = true).issue)
        org.junit.Assert.assertEquals(0,queries)
        permission = true
        val timed = reader.readSelected(reference,enabled = true).occurrence!!.draft()
        org.junit.Assert.assertEquals(DraftInterval(1000,2000),timed.interval)
        org.junit.Assert.assertEquals("calendar:7:1000:2000",timed.sourceReference)
        allDay = true
        org.junit.Assert.assertNull(reader.readSelected(reference,enabled = true).occurrence!!.draft().interval)
        revoked = true
        org.junit.Assert.assertEquals(CalendarReadIssue.PERMISSION_REQUIRED,reader.readSelected(reference,enabled = true).issue)
        org.junit.Assert.assertEquals(CalendarReadIssue.PROVIDER_UNAVAILABLE,
            CalendarOccurrences({ true }) { _,_,_,_ -> null }.readSelected(reference,true).issue)
        org.junit.Assert.assertEquals(CalendarReadIssue.NOT_FOUND,
            CalendarOccurrences({ true }) { _,cols,_,_ -> android.database.MatrixCursor(cols) }.readSelected(reference,true).issue)
        org.junit.Assert.assertEquals(CalendarReadIssue.INVALID_OCCURRENCE,
            CalendarOccurrences({ true }) { _,cols,_,_ -> android.database.MatrixCursor(cols).apply {
                addRow(arrayOf<Any>(8L,1000L,2000L,"Different occurrence",0))
            } }.readSelected(reference,true).issue)
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock)
        val before = tracker.data()
        ProcessTextDrafts.read(shared); reader.readSelected(reference)
        org.junit.Assert.assertEquals(before,tracker.data())
        tracker.startDraft(tracker.snapshot().token,draft.copy(note = "Edited text"))
        clock.advance(1); tracker.stop(tracker.snapshot().token)
        org.junit.Assert.assertEquals("Edited text",tracker.data().sessions.last().note)
        val beforeInvalidDraft = tracker.data()
        org.junit.Assert.assertThrows(CoreError::class.java) { tracker.startDraft(tracker.snapshot().token,draft.copy(note = " ")) }
        org.junit.Assert.assertEquals(beforeInvalidDraft,tracker.data())
        org.junit.Assert.assertThrows(CoreError::class.java) { tracker.recordDraft(tracker.snapshot().token,
            CalendarOccurrence(reference,"All day",true).draft()) }
        org.junit.Assert.assertEquals(beforeInvalidDraft,tracker.data())
        tracker.recordDraft(tracker.snapshot().token,timed)
        org.junit.Assert.assertEquals("Calendar title",tracker.data().sessions.last().note)
        tracker.startFocus(tracker.snapshot().token,plan = FocusPlan(1000,1000))
        val signals = FocusDueSignals(); var cues = 0
        org.junit.Assert.assertFalse(signals.consume(tracker.snapshot().focus) { cues++ })
        clock.advance(1000); val work = tracker.snapshot().focus!!
        org.junit.Assert.assertTrue(signals.consume(work) {
            cues++
            org.junit.Assert.assertFalse(signals.consume(work) { cues++ }) // re-entrant delivery is also deduplicated
        })
        org.junit.Assert.assertFalse(signals.consume(work) { cues++ })
        tracker.focusNext(tracker.snapshot().token); clock.advance(1000)
        val pause = tracker.snapshot().focus!!
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { signals.consume(pause) { throw IllegalStateException("not delivered") } }
        org.junit.Assert.assertTrue(signals.consume(pause) { cues++ })
        org.junit.Assert.assertFalse(signals.consume(work) { cues++ })
        org.junit.Assert.assertEquals(2,cues)
        org.junit.Assert.assertEquals(1000L,tracker.snapshot().trustedDurationMs) // signal consumption never starts another phase/timer
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java,android.content.Intent(shared)).create()
        val activity = controller.get()
        val noteField = MainActivity::class.java.getDeclaredField("note").apply { isAccessible = true }
        val note = noteField.get(activity) as android.widget.EditText
        org.junit.Assert.assertEquals(text,note.text.toString())
        org.junit.Assert.assertEquals(android.content.Intent.ACTION_MAIN,activity.intent.action)
        org.junit.Assert.assertFalse(activity.intent.hasExtra(android.content.Intent.EXTRA_PROCESS_TEXT))
        note.setText("Edited draft")
        controller.newIntent(android.content.Intent(shared).putExtra(android.content.Intent.EXTRA_PROCESS_TEXT,"Other text"))
        org.junit.Assert.assertEquals("Edited draft",note.text.toString()) // explicit text input never silently destroys an existing form
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            .getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        val state = android.os.Bundle()
        controller.saveInstanceState(state).destroy()
        val recreated = org.robolectric.Robolectric.buildActivity(MainActivity::class.java,android.content.Intent(shared)).create(state)
        try { org.junit.Assert.assertEquals("Edited draft",(noteField.get(recreated.get()) as android.widget.EditText).text.toString()) }
        finally { recreated.destroy() }
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
