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
    private val originalDirectorySync = DirectoryDurability.sync
    private val originalRename = DirectoryDurability.rename
    private val originalLink = DirectoryDurability.link
    @org.junit.Before fun directoryDurability() {
        DirectoryDurability.rename = { source,target -> java.nio.file.Files.move(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);Unit }
        DirectoryDurability.link = { source,target -> java.nio.file.Files.createLink(target.toPath(),source.toPath());Unit }
        DirectoryDurability.sync = { directory ->
            java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
    }
    @org.junit.After fun restoreDirectoryDurability() { DirectoryDurability.sync = originalDirectorySync;DirectoryDurability.rename = originalRename;DirectoryDurability.link = originalLink }
    private val databases = mutableMapOf<StateStore,Pair<String,TrackerDatabase>>()
    @org.junit.Test fun coldPayloadIdentityMismatchBlocksWithoutRepair() {
        val store = createStore();val t = Tracker(store,FakeClock())
        val card = Card(id = "row-owner",name = "Fixture card")
        t.saveCard(t.snapshot().token,card)
        val db = databases.getValue(store).second
        val changed = codec.encodeToString(card.copy(id = "contradictory-payload"))
        db.openHelper.writableDatabase.execSQL("UPDATE cards SET payload=? WHERE id=?",arrayOf(changed,card.id))
        val failure = kotlin.runCatching { RoomStore(db,allowInitialize = false).read() }.exceptionOrNull()
        org.junit.Assert.assertEquals(ErrorCode.STORAGE,(failure as? CoreError)?.code)
        db.openHelper.readableDatabase.query("SELECT id,payload FROM cards").use { c ->
            org.junit.Assert.assertTrue(c.moveToFirst());org.junit.Assert.assertEquals(card.id,c.getString(0))
            org.junit.Assert.assertEquals(changed,c.getString(1));org.junit.Assert.assertFalse(c.moveToNext())
        }
    }
    @org.junit.Test fun transferRetentionLargeMetadataShrinksAndReopens() {
        val clock = FakeClock();val store = createStore();val note = "large private fixture"+"🙂".repeat(8192)
        store.transaction { d ->
            val contents = (0 until 32).map { i ->
                val session = Session(id = "retention-large-$i",status = SessionStatus.STOPPED,note = note)
                TransferContent(sessions = listOf(session),segments = listOf(Segment(id = "retention-large-seg-$i",sessionId = session.id,start = 1000L+i*2000,end = 2000L+i*2000,durationMs = 1000)))
            }
            val groups = contents.mapIndexed { i,c -> SelectiveTransfer.group("large-group-$i","large-origin-$i",0,c) }
            val op = TransferOperation("large-retention","a".repeat(128),d.datasetId,d.controlBarrier,1,groups.map { TransferGroupProgress(it,TransferConflictPolicy.BLOCK,TransferGroupState.APPLIED,inserted = it.content) })
            d.copy(revision = 1,sessions = contents.flatMap { it.sessions },segments = contents.flatMap { it.segments },transfer = TransferState(groups.map { TransferOrigin(it.originId,it.hash,it.content,it.hash) },listOf(op))) to Unit
        }
        fun size(s: StateStore): Long = databases.getValue(s).second.openHelper.readableDatabase.query("SELECT length(CAST(payload AS BLOB)) FROM metadata WHERE id=1").use { c -> c.moveToFirst();c.getLong(0) }
        val beforeBytes = size(store);org.junit.Assert.assertTrue(beforeBytes > 2*1024*1024)
        val t = Tracker(store,clock);val before = t.data();val preview = t.previewTransferRetention(TransferRetentionSelection(operationIds = setOf("large-retention")))
        org.junit.Assert.assertEquals(33,preview.targets.size);org.junit.Assert.assertTrue(preview.canApply)
        t.applyTransferRetention(preview,"large-compact",preview.requiredAcknowledgements)
        val after = t.data();val afterBytes = size(store);org.junit.Assert.assertTrue(afterBytes < beforeBytes/4)
        org.junit.Assert.assertEquals(before,after.copy(revision = before.revision,transfer = before.transfer))
        val reopened = reopen(store);org.junit.Assert.assertEquals(after,reopened.read());org.junit.Assert.assertEquals(afterBytes,size(reopened))
        org.junit.Assert.assertFalse(codec.encodeToString(after.transfer).contains("large private fixture"))
        val out = java.io.ByteArrayOutputStream();Backups(Tracker(reopened,clock),MemoryRollback()).write(out)
        val decoded = StreamingBackup.read { out.toByteArray().inputStream() };org.junit.Assert.assertEquals(after.transfer,decoded.data.transfer)
        org.junit.Assert.assertEquals(note,decoded.data.sessions.first().note)
        val artifact = java.io.File(requireNotNull(System.getProperty("tracker.artifacts")),"retention-metadata-sizes.json");artifact.parentFile!!.mkdirs()
        artifact.writeText("{\"beforeMetadataBytes\":$beforeBytes,\"afterMetadataBytes\":$afterBytes,\"scope\":\"current metadata row only, no physical file/WAL reclaim claim\"}")
    }
    @org.junit.Test fun transferRetentionV6MigrationPreservesPayloads() {
        val clock = FakeClock();var store = createStore();val source = Tracker(createStore(),clock)
        source.saveGoal(source.snapshot().token,Goal(id = "migration-goal",name = "Retained migration original",metric = GoalMetric.DURATION_MS,target = 1000))
        val p = SelectiveTransfer(source).export(TransferSelection(goalIds = setOf("migration-goal")))
        val t = Tracker(store,clock);val importer = SelectiveTransfer(t);importer.apply(importer.plan(p,p.groups.map { it.id }.toSet()),"migration-op")
        val expected = t.data();val (name,db) = databases.remove(store)!!;db.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath(name).path,null,0).use { sql ->
            val meta = sql.rawQuery("SELECT payload FROM metadata WHERE id=1",null).use { c -> c.moveToFirst();codec.parseToJsonElement(c.getString(0)).jsonObject.toMutableMap() }
            val transfer = meta.getValue("transfer").jsonObject.toMutableMap();transfer.remove("compactions")
            transfer["origins"] = JsonArray(transfer.getValue("origins").jsonArray.map { JsonObject(it.jsonObject.filterKeys { k -> k != "compaction" }) })
            transfer["operations"] = JsonArray(transfer.getValue("operations").jsonArray.map { JsonObject(it.jsonObject.filterKeys { k -> k != "payloadsPruned" }) })
            meta["transfer"] = JsonObject(transfer);sql.execSQL("UPDATE metadata SET payload=? WHERE id=1",arrayOf(JsonObject(meta).toString()));sql.version = 6
        }
        store = open(name);org.junit.Assert.assertEquals(7,databases.getValue(store).second.openHelper.readableDatabase.version)
        org.junit.Assert.assertEquals(expected,store.read());store = reopen(store);org.junit.Assert.assertEquals(expected,store.read())
        org.junit.Assert.assertFalse(store.read().transfer.operations.single().payloadsPruned)
        org.junit.Assert.assertTrue(codec.encodeToString(store.read().transfer).contains("Retained migration original"))
    }

    @org.junit.Test fun largeTransferMetadataReopensWithoutWholeCursorRows() {
        val store = createStore();val note = "🙂".repeat(8192)
        store.transaction { d ->
            val contents = (0 until 32).map { i ->
                val session = Session(id = "large-$i",status = SessionStatus.STOPPED,note = note)
                TransferContent(sessions = listOf(session),segments = listOf(Segment(id = "large-seg-$i",sessionId = session.id,start = 1000L+i*2000,end = 2000L+i*2000,durationMs = 1000)))
            }
            val groups = contents.mapIndexed { i,c -> SelectiveTransfer.group("group-$i","origin-$i",0,c) }
            val op = TransferOperation("large-op","a".repeat(128),d.datasetId,d.controlBarrier,1,groups.map { TransferGroupProgress(it,TransferConflictPolicy.BLOCK,TransferGroupState.APPLIED,inserted = it.content) })
            val origins = groups.map { TransferOrigin(it.originId,it.hash,it.content,it.hash) }
            d.copy(revision = 1,sessions = contents.flatMap { it.sessions },segments = contents.flatMap { it.segments },transfer = TransferState(origins,listOf(op))) to Unit
        }
        val expected = store.read();val db = databases.getValue(store).second
        val bytes = db.openHelper.readableDatabase.query("SELECT length(CAST(payload AS BLOB)) FROM metadata WHERE id=1").use { c -> c.moveToFirst();c.getLong(0) }
        org.junit.Assert.assertTrue(bytes > 2*1024*1024)
        val reopened = reopen(store);org.junit.Assert.assertEquals(expected,reopened.read())
        val tracker = Tracker(reopened,FakeClock());tracker.settings(tracker.snapshot().token,Settings("Europe/Berlin"))
        org.junit.Assert.assertEquals(note,tracker.data().sessions.first().note)
        val output = java.io.ByteArrayOutputStream();Backups(tracker,MemoryRollback()).write(output)
        val restored = StreamingBackup.read { output.toByteArray().inputStream() }
        org.junit.Assert.assertEquals(tracker.data().transfer,restored.data.transfer)
    }
    @org.junit.Test fun v5MigrationPreservesStoppedPausedRunningAndRecovery() {
        val context = RuntimeEnvironment.getApplication()
        for(status in SessionStatus.entries) {
            val clock = FakeClock();var store = createStore();val tracker = Tracker(store,clock)
            tracker.start(tracker.snapshot().token);clock.advance(1000)
            if(status == SessionStatus.PAUSED) tracker.pause(tracker.snapshot().token)
            if(status == SessionStatus.STOPPED) tracker.stop(tracker.snapshot().token)
            if(status == SessionStatus.RECOVERY) store.transaction { d -> d.copy(sessions = d.sessions.map { it.copy(status = SessionStatus.RECOVERY) },segments = d.segments.map { it.copy(anchor = null,uncertain = true) }) to Unit }
            val expected = store.read();val (name,db) = databases.remove(store)!!;db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,0).use { sql ->
                val old = sql.rawQuery("SELECT payload FROM metadata WHERE id=1",null).use { c -> c.moveToFirst();codec.parseToJsonElement(c.getString(0)).jsonObject }
                val legacy = JsonObject(old.filterKeys { it !in setOf("schedule","transfer") })
                sql.execSQL("UPDATE metadata SET payload=? WHERE id=1",arrayOf(legacy.toString()));sql.version = 5
            }
            store = open(name);org.junit.Assert.assertEquals(expected,store.read())
            org.junit.Assert.assertEquals(7,databases.getValue(store).second.openHelper.readableDatabase.version)
            store = reopen(store);org.junit.Assert.assertEquals(expected,store.read())
            val migrated = Tracker(store,clock);val archive = Backups(migrated,MemoryRollback()).export()
            val destination = Tracker(createStore(),clock);val backups = Backups(destination,MemoryRollback())
            backups.apply(backups.prepare(archive))
            org.junit.Assert.assertEquals(Backups.decode(archive).data.sessions.map { if(it.status == SessionStatus.RUNNING) it.copy(status = SessionStatus.RECOVERY) else it },destination.data().sessions)
            org.junit.Assert.assertEquals(expected.types,destination.data().types)
            org.junit.Assert.assertEquals(expected.cards,destination.data().cards)
            org.junit.Assert.assertEquals(expected.segments.map { it.id },destination.data().segments.map { it.id })
        }
    }
    @org.junit.Test fun validClassificationPayloadCannotChangeUnderItsOldContentId() {
        for(reopenFirst in listOf(false,true)) {
            var store = createStore();val tracker = Tracker(store,FakeClock());tracker.start(tracker.snapshot().token)
            val db = databases.getValue(store).second
            val original = db.dao().classifications().single()
            val changed = codec.encodeToString(Classification(name = "Valid but altered classification"))
            org.junit.Assert.assertNotEquals(original.id,HistoryColumns.key(changed))
            db.openHelper.writableDatabase.execSQL("UPDATE history_classifications SET payload=? WHERE id=?",arrayOf(changed,original.id))
            if(reopenFirst) store = reopen(store)
            else db.openHelper.writableDatabase.execSQL("UPDATE store_generation SET generation=generation+1")
            val error = org.junit.Assert.assertThrows(CoreError::class.java) { store.read() }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,error.code)
            org.junit.Assert.assertTrue(error.message!!.contains("content hash"))
            val after = databases.getValue(store).second.dao().classification(original.id)!!
            org.junit.Assert.assertEquals(changed,after.payload)
            org.junit.Assert.assertEquals(original.id,after.id)
            // Refusal neither rewrites the payload nor guesses which history is right.
            org.junit.Assert.assertEquals(1,databases.getValue(store).second.dao().sessions().size)
        }
    }
    @org.junit.Test fun repeatedArchivesShareOnlyVerifiedImmutableCopies() {
        val context = RuntimeEnvironment.getApplication();val name = "archive-share-${newId()}.db"
        val originals = DatabaseFiles.paths(context,name).associateWith { file -> ("synthetic preserved original "+file.name).toByteArray() }
        originals.forEach { (file,bytes) -> file.parentFile!!.mkdirs();file.writeBytes(bytes) }
        val first = DatabaseArchives.capture(context,name);val second = DatabaseArchives.capture(context,name)
        org.junit.Assert.assertEquals(first,second)
        val separate = DatabaseArchives.capture(context,name,forceSeparate = true)
        org.junit.Assert.assertNotEquals(first,separate)
        for((file,bytes) in originals) {
            val a = java.io.File(first,file.name);val b = java.io.File(second,file.name)
            org.junit.Assert.assertTrue(java.nio.file.Files.isSameFile(a.toPath(),b.toPath()))
            org.junit.Assert.assertTrue(java.nio.file.Files.isSameFile(a.toPath(),java.io.File(separate,file.name).toPath()))
            org.junit.Assert.assertFalse(java.nio.file.Files.isSameFile(file.toPath(),a.toPath()))
            file.appendText(" live mutation")
            org.junit.Assert.assertArrayEquals(bytes,a.readBytes());org.junit.Assert.assertArrayEquals(bytes,b.readBytes())
        }
        val changed = DatabaseArchives.capture(context,name)
        for(file in originals.keys) org.junit.Assert.assertArrayEquals(file.readBytes(),java.io.File(changed,file.name).readBytes())
        // A damaged candidate cannot be admitted on its manifest hash alone.
        val victim = originals.keys.first();java.io.File(changed,victim.name).appendText(" archive damage")
        DirectoryDurability.link = { _,_ -> throw java.io.IOException("hard links unavailable") }
        val fallback = DatabaseArchives.capture(context,name)
        for(file in originals.keys) {
            val preserved = java.io.File(fallback,file.name)
            org.junit.Assert.assertArrayEquals(file.readBytes(),preserved.readBytes())
            org.junit.Assert.assertFalse(java.nio.file.Files.isSameFile(file.toPath(),preserved.toPath()))
        }
        // A backend reporting failure after creating a link must never truncate it.
        DirectoryDurability.link = { source,target -> java.nio.file.Files.createLink(target.toPath(),source.toPath());throw java.io.IOException("late failure") }
        val lateFailure = DatabaseArchives.capture(context,name,forceSeparate = true)
        for(file in originals.keys) org.junit.Assert.assertArrayEquals(file.readBytes(),java.io.File(lateFailure,file.name).readBytes())
    }
    private fun legacyStore(name: String,input: Dataset,version: Int = 4) {
        val context = RuntimeEnvironment.getApplication();val path = context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val schemaFile = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!
            .resolve("app/schemas/dev.notai.tracker.android.TrackerDatabase/$version.json")
        val schema = codec.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use { sql ->
            for(entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject;val table = e.getValue("tableName").jsonPrimitive.content
                sql.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))
                for(index in e["indices"]?.jsonArray.orEmpty()) sql.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))
            }
            val tables = linkedMapOf("activity_types" to input.types.map { it.id to codec.encodeToString(it) },"cards" to input.cards.map { it.id to codec.encodeToString(it) },
                "sessions" to input.sessions.map { it.id to codec.encodeToString(it) },"segments" to input.segments.map { it.id to codec.encodeToString(it) },
                "favorites" to input.favorites.map { it.id to codec.encodeToString(it) },"measurements" to input.measurements.map { it.id to codec.encodeToString(it) })
            for((table,rows) in tables) rows.forEachIndexed { i,(id,payload) ->
                val v = android.content.ContentValues();v.put("id",id);v.put("position",i);v.put("payload",payload)
                if(table == "segments") { val seg = input.segments[i];v.put("sessionId",seg.sessionId);if(seg.end == null) v.put("openSlot",1) else v.putNull("openSlot") }
                sql.insertOrThrow(table,null,v)
            }
            val meta = input.copy(types = emptyList(),cards = emptyList(),sessions = emptyList(),segments = emptyList(),favorites = emptyList(),measurements = emptyList(),axes = emptyList(),metrics = emptyList(),units = emptyList())
            sql.execSQL("INSERT INTO metadata(id,payload) VALUES(1,?)",arrayOf(codec.encodeToString(meta)))
            sql.execSQL("INSERT INTO vocabulary(id,payload) VALUES(1,?)",arrayOf(codec.encodeToString(VocabularyData(emptyList(),input.axes,input.metrics,input.units))))
            sql.execSQL("INSERT INTO store_generation(id,generation) VALUES(1,7)");sql.version = version
        }
    }
    @org.junit.Test fun indexedPagingAndV3MigrationPreserveEveryRow() {
        val context = RuntimeEnvironment.getApplication()
        for(count in listOf(0,1,511,512,513,1024,1025)) {
            val sessions = (0 until count).map { i -> Session(id = listOf("a","é","😀")[i%3]+"-$i",status = SessionStatus.STOPPED,note = "page boundary $i") }
            val segments = sessions.mapIndexed { i,s -> Segment(id = "segment-${s.id}",sessionId = s.id,start = 1_000L+i*2_000L,end = 2_000L+i*2_000L,durationMs = 1_000) }
            val input = Dataset(sessions = sessions,segments = segments);val name = "legacy-page-${newId()}.db"
            legacyStore(name,input,if(count%2 == 0) 3 else 4)
            val positions = mutableMapOf<String,List<Pair<String,Int>>>()
            val expected = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,0).use { sql ->
                for((table,ids) in listOf("sessions" to sessions.map { it.id },"segments" to segments.map { it.id })) {
                    ids.forEachIndexed { i,id -> sql.execSQL("UPDATE $table SET position=? WHERE id=?",arrayOf<Any>(i/3-2,id)) }
                    positions[table] = sql.rawQuery("SELECT id,position FROM $table ORDER BY position,id",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
                }
                input.copy(sessions = positions.getValue("sessions").map { sessions.associateBy { it.id }.getValue(it.first) },
                    segments = positions.getValue("segments").map { segments.associateBy { it.id }.getValue(it.first) })
            }
            var store = open(name)
            org.junit.Assert.assertEquals(expected,store.read())
            org.junit.Assert.assertEquals(count,store.read().sessions.map { it.id }.toSet().size)
            val migrated = databases.getValue(store).second.openHelper.readableDatabase
            org.junit.Assert.assertEquals(TRACKER_DATABASE_VERSION,migrated.version)
            for(table in listOf("sessions","segments")) {
                val after = migrated.query("SELECT id,position FROM $table ORDER BY position,id").use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
                org.junit.Assert.assertEquals(positions[table],after)
                migrated.query("EXPLAIN QUERY PLAN SELECT id,position FROM $table ORDER BY position,id LIMIT 512").use { c ->
                    org.junit.Assert.assertTrue(c.moveToFirst());org.junit.Assert.assertTrue(c.getString(3).contains("index_${table}_position_id"))
                }
            }
            store = reopen(store);org.junit.Assert.assertEquals(expected,store.read())
            val tracker = Tracker(store,FakeClock());tracker.start(tracker.snapshot().token)
            org.junit.Assert.assertEquals(1,tracker.data().segments.count { it.end == null })
            org.junit.Assert.assertThrows(CoreError::class.java) { tracker.start(tracker.snapshot().token) }
        }
    }
    @org.junit.Test fun normalizedMigrationRejectsContradictionsWithoutChangingLegacyRows() {
        val context = RuntimeEnvironment.getApplication()
        val session = Session(id = "legacy-one",status = SessionStatus.STOPPED,note = "retain note")
        val input = Dataset(sessions = listOf(session),segments = listOf(Segment(id = "legacy-segment",sessionId = session.id,start = 1000,end = 2000,durationMs = 1000)))
        for(case in listOf("id","sessionId","openSlot","payload")) {
            val name = "invalid-normalized-$case-${newId()}.db";legacyStore(name,input)
            val path = context.getDatabasePath(name)
            fun rows(sql: android.database.sqlite.SQLiteDatabase) = listOf("metadata","sessions","segments","vocabulary","store_generation").associateWith { table ->
                sql.rawQuery("SELECT * FROM $table ORDER BY id",null).use { c -> buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) } }
            }
            val before = android.database.sqlite.SQLiteDatabase.openDatabase(path.path,null,0).use { sql ->
                when(case) { "id" -> sql.execSQL("UPDATE sessions SET id='contradictory-id'");"sessionId" -> sql.execSQL("UPDATE segments SET sessionId='missing'");"openSlot" -> sql.execSQL("UPDATE segments SET openSlot=1");
                    else -> sql.execSQL("UPDATE segments SET payload=?",arrayOf(codec.encodeToString(input.segments.single().copy(durationMs = 999999)))) }
                rows(sql)
            }
            org.junit.Assert.assertThrows(Exception::class.java) { open(name) }
            android.database.sqlite.SQLiteDatabase.openDatabase(path.path,null,0).use { sql -> org.junit.Assert.assertEquals(4,sql.version);org.junit.Assert.assertEquals(before,rows(sql)) }
        }
    }
    @org.junit.Test fun normalizedMigrationRollsBackAfterReplacementFailure() {
        val context = RuntimeEnvironment.getApplication();val name = "late-migration-${newId()}.db"
        val session = Session(id = "retain-session",status = SessionStatus.STOPPED,note = "retain exactly")
        val input = Dataset(sessions = listOf(session),segments = listOf(Segment(id = "retain-segment",sessionId = session.id,start = 1000,end = 2000,durationMs = 1000)))
        legacyStore(name,input)
        fun snapshot() = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,0).use { sql ->
            listOf("sessions","segments","metadata","vocabulary","store_generation").associateWith { table -> sql.rawQuery("SELECT * FROM $table ORDER BY id",null).use { c ->
                buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) }
            } }
        }
        val before = snapshot()
        val fault = object: androidx.room.migration.Migration(4,5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                migrateNormalizedHistory(object: androidx.sqlite.db.SupportSQLiteDatabase by db {
                    override fun execSQL(sql: String) {
                        if(sql == "CREATE INDEX index_sessions_position_id ON sessions(position,id)") throw android.database.sqlite.SQLiteException("injected after rebuild/readback")
                        db.execSQL(sql)
                    }
                })
            }
        }
        val db = androidx.room.Room.databaseBuilder(context,TrackerDatabase::class.java,name).addMigrations(fault,TrackerDatabase.MIGRATION_5_6,TrackerDatabase.MIGRATION_6_7).allowMainThreadQueries().build()
        try { org.junit.Assert.assertThrows(Exception::class.java) { RoomStore(db) } } finally { db.close() }
        org.junit.Assert.assertEquals(before,snapshot())
        org.junit.Assert.assertEquals(input,open(name).read())
    }
    @org.junit.Test fun healthyMainWithDamagedSidecarsPreservesEveryOriginalBeforeSQLite() {
        val context = RuntimeEnvironment.getApplication();val root = java.io.File(context.noBackupFilesDir,"database-archives")
        for(suffixes in listOf(emptyList(),listOf("-wal"),listOf("-shm"),listOf("-wal","-shm"),listOf("-journal"))) {
            val name = "valid-main-sidecars-${newId()}.db";val store = open(name);val expected = store.read()
            databases.remove(store)!!.second.close()
            for(suffix in suffixes) context.getDatabasePath(name+suffix).writeText("damaged sidecar $suffix")
            val originals = DatabaseFiles.paths(context,name).filter { it.exists() }.associate { it.name to it.readBytes() }
            val before = root.listFiles().orEmpty().map { it.name }.toSet()
            val db = TrackerDatabase.open(context,name)
            val archives = root.listFiles().orEmpty().filter { it.name !in before }
            if(suffixes.isEmpty()) org.junit.Assert.assertTrue(archives.isEmpty())
            else {
                val preserved = archives.single();val manifest = codec.decodeFromString<DatabaseArchive>(java.io.File(preserved,"manifest.json").readText())
                org.junit.Assert.assertEquals(originals.keys,manifest.files.map { it.name }.toSet())
                for((filename,bytes) in originals) org.junit.Assert.assertArrayEquals(bytes,java.io.File(preserved,filename).readBytes())
            }
            // Opening behavior may differ by SQLite version; preservation is checked
            // before opening, even when SQLite later discards the damaged WAL.
            try { org.junit.Assert.assertEquals(expected,RoomStore(db).read()) } catch(_: Exception) { /* explicit recovery is allowed */ }
            finally { db.close() }
        }
    }
    @org.junit.Test fun orphanSidecarsNeverInitialize() {
        val context = RuntimeEnvironment.getApplication()
        val suffixes = listOf("-wal","-shm","-journal")
        fun check(name: String,selected: List<String>,open: ()->Unit) {
            val main = context.getDatabasePath(name)
            org.junit.Assert.assertFalse(main.exists())
            org.junit.Assert.assertFalse(DatabaseFiles.exists(context,name))
            val originals = selected.associate { suffix -> name+suffix to ("Orphan fixture $name$suffix").toByteArray() }
            originals.forEach { (filename,bytes) -> context.getDatabasePath(filename).apply { parentFile!!.mkdirs();writeBytes(bytes) } }
            org.junit.Assert.assertTrue(DatabaseFiles.exists(context,name))
            val root = java.io.File(context.noBackupFilesDir,"database-archives")
            val before = root.listFiles().orEmpty().map { it.name }.toSet()
            val error = org.junit.Assert.assertThrows(CoreError::class.java,open)
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,error.code)
            org.junit.Assert.assertTrue(error.message!!.contains("explicit recovery"))
            org.junit.Assert.assertFalse("SQLite must not create a main file",main.exists())
            originals.forEach { (filename,bytes) -> org.junit.Assert.assertArrayEquals(bytes,context.getDatabasePath(filename).readBytes()) }
            val archive = root.listFiles().orEmpty().single { it.name !in before }
            val manifest = codec.decodeFromString<DatabaseArchive>(java.io.File(archive,"manifest.json").readText())
            org.junit.Assert.assertEquals(name,manifest.database)
            org.junit.Assert.assertEquals(originals.keys,manifest.files.map { it.name }.toSet())
            manifest.files.forEach { entry ->
                val preserved = java.io.File(archive,entry.name)
                org.junit.Assert.assertArrayEquals(originals.getValue(entry.name),preserved.readBytes())
                org.junit.Assert.assertEquals(entry.bytes,preserved.length())
                org.junit.Assert.assertEquals(entry.sha256,fileHash(preserved))
            }
        }
        for(mask in 1..7) {
            val name = "orphan-$mask-${newId()}.db"
            check(name,suffixes.filterIndexed { i,_ -> mask and (1 shl i) != 0 }) { TrackerDatabase.open(context,name) }
        }
        // Exercise the real runtime's newStore decision as well as the named open boundary.
        check("tracker.db",suffixes) { runBlocking { withContext(Dispatchers.IO) { TrackerRuntime.get(context) } } }
        val freshName = "genuinely-new-${newId()}.db"
        org.junit.Assert.assertFalse(DatabaseFiles.exists(context,freshName))
        val fresh = TrackerDatabase.open(context,freshName)
        try { runBlocking { withContext(Dispatchers.IO) { org.junit.Assert.assertTrue(RoomStore(fresh).read().sessions.isEmpty()) } } }
        finally { fresh.close() }
    }

    @org.junit.Test fun sessionCommandsIgnoreStaleSelectedCard() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = runBlocking { withContext(Dispatchers.IO) { TrackerRuntime.get(context) } }
        val tracker = runtime.tracker;val clock = FakeClock()
        // Inject the deterministic clock at the test boundary; buttons execute the real runtime/Room.
        val clockField = Tracker::class.java.getDeclaredField("clock").apply { isAccessible = true }
        val originalClock = clockField.get(tracker);clockField.set(tracker,clock)
        val controller = org.robolectric.Robolectric.buildActivity(TechnicalActivity::class.java).create()
        val activity = controller.get()
        fun <T> io(block: ()->T): T = runBlocking { withContext(Dispatchers.IO) { block() } }
        fun field(name: String) = TechnicalActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun input(name: String) = field(name).get(activity) as android.widget.EditText
        fun button(label: String): android.widget.Button? {
            val body = field("body").get(activity) as android.widget.LinearLayout
            return (0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<android.widget.Button>().firstOrNull { it.text.toString() == label }
        }
        fun await(condition: ()->Boolean) {
            val limit = System.nanoTime()+5_000_000_000L
            while(!condition() && System.nanoTime() < limit) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(5) }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            org.junit.Assert.assertTrue("UI command did not complete",condition())
        }
        fun form(): Map<String,String?> = listOf("name","details","note","from","until","zone").associateWith { input(it).text.toString() } +
            ("selectedCardId" to (field("selectedCardId").get(activity) as String?))
        fun show() { field("shown").set(activity,io { tracker.snapshot() }) }
        fun success(label: String) {
            val before = form();val revision = io { tracker.data().revision };show()
            val previous = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            org.junit.Assert.assertTrue(button(label)!!.performClick())
            await { io { tracker.data().revision } > revision || org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.let { it !== previous && it.isShowing } == true }
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            org.junit.Assert.assertTrue("$label was refused: ${dialog?.let { org.robolectric.Shadows.shadowOf(it).message }}",io { tracker.data().revision } > revision)
            org.junit.Assert.assertEquals(revision+1,io { tracker.data().revision })
            org.junit.Assert.assertEquals("Command must retain the entire unrelated form",before,form())
        }
        fun selectionRefused(label: String) {
            val before = form();val data = io { tracker.data() };show()
            val previous = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            button(label)!!.performClick()
            await { org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.let { it !== previous && it.isShowing } == true }
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            org.junit.Assert.assertTrue(org.robolectric.Shadows.shadowOf(dialog).message.toString().contains("Selected card is missing or archived"))
            org.junit.Assert.assertEquals(data,io { tracker.data() });org.junit.Assert.assertEquals(before,form());dialog.dismiss()
        }
        try {
            await { button("Отменить доступную правку") != null && button("Начать Focus") != null }
            for(archived in listOf(false,true)) {
                val baseline = io { tracker.data() }
                val staleCard = io { Card(name = "Unrelated form card").also { tracker.saveCard(tracker.snapshot().token,it) } }
                val staleId = staleCard.id
                field("selectedCardId").set(activity,staleId)
                mapOf("name" to "Retained name","details" to "retained activity","note" to "Retained note","from" to "unused start","until" to "unused end","zone" to "America/Los_Angeles")
                    .forEach { (key,value) -> input(key).setText(value) }
                val beforeExternalChange = form()
                io {
                    if(archived) tracker.saveCard(tracker.snapshot().token,staleCard.copy(archived = true))
                    else {
                        val backups = Backups(tracker,MemoryRollback())
                        backups.apply(backups.prepare(Backups.encode(baseline,clock.now())))
                    }
                }
                org.junit.Assert.assertEquals(beforeExternalChange,form())
                org.junit.Assert.assertTrue(io { if(archived) tracker.data().cards.single { it.id == staleId }.archived else tracker.data().cards.none { it.id == staleId } })
                selectionRefused("Start");selectionRefused("Начать Focus")
                io { tracker.start(tracker.snapshot().token,Classification(name = "Saved history"));clock.advance(1000);tracker.stop(tracker.snapshot().token) }
                success("Повторить последнее записанное (сохранённые, включая архивные параметры)")
                org.junit.Assert.assertEquals("Saved history",io { tracker.snapshot().session!!.selection.name })
                org.junit.Assert.assertTrue(io { tracker.snapshot().session!!.selection.details.isEmpty() })
                success("PAUSE");success("RESUME");clock.advance(1000);success("STOP")
                val marker = io { tracker.manual(tracker.snapshot().token,clock.wall-3*86_400_000L,clock.wall-3*86_400_000L+1000,
                    Classification(name = "Undo marker")).changedSessionIds.single() }
                success("Отменить доступную правку")
                org.junit.Assert.assertTrue(io { tracker.data().sessions.none { it.id == marker } })
                io { tracker.start(tracker.snapshot().token);clock.advance(1000);clock.boot = newId() }
                org.junit.Assert.assertEquals(Mode.RECOVERY,io { tracker.snapshot().mode })
                input("from").setText(java.time.Instant.ofEpochMilli(io { tracker.snapshot().active!!.start }).toString())
                input("until").setText(java.time.Instant.ofEpochMilli(clock.wall).toString())
                success("Подтвердить хвост указанным интервалом")
                io { tracker.start(tracker.snapshot().token);clock.advance(1000);clock.boot = newId() }
                success("Исключить неопределённый хвост")
                val live = io { Card(name = "Focus selected card").also { tracker.saveCard(tracker.snapshot().token,it) } }
                field("selectedCardId").set(activity,live.id);success("Начать Focus")
                org.junit.Assert.assertEquals(live.id,io { tracker.snapshot().session!!.selection.cardId })
                org.junit.Assert.assertEquals("retained activity",io { tracker.snapshot().session!!.selection.details["activity"] })
                field("selectedCardId").set(activity,staleId)
                clock.advance(io { tracker.data().focus!!.durationMs });success("Следующий этап Focus")
                success("Пропустить этап Focus");clock.advance(1000);success("Завершить Focus")
                org.junit.Assert.assertNull(io { tracker.data().focus })
                // Persist the still-stale ID and all fields just as a recreation would.
                val state = android.os.Bundle();val beforeSave = form();controller.saveInstanceState(state)
                val saved = FormFiles(context).read(state.getString("formReference")!!)
                org.junit.Assert.assertEquals(staleId,saved.selectedCardId)
                org.junit.Assert.assertEquals(beforeSave,listOf("name","details","note","from","until","zone").zip(listOf(saved.name,saved.details,saved.note,saved.start,saved.end,saved.zone)).toMap() + ("selectedCardId" to saved.selectedCardId))
            }
        } finally {
            controller.destroy();clockField.set(tracker,originalClock)
            TrackerRuntime.beginRecovery();TrackerRuntime.endRecovery()
        }
    }
    @org.junit.Test fun backendPickerWidgetAndOverlayContracts() {
        val context = RuntimeEnvironment.getApplication()
        val shadowApp = org.robolectric.Shadows.shadowOf(context)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val clock = FakeClock();var tracker = Tracker(createStore(),clock)
        tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(surfaces = SurfaceSettings(notification = true,overlay = true)))
        val card = Card(id = "android-surface-choice",name = "Android choice")
        tracker.saveCard(tracker.snapshot().token,card)
        tracker.saveFavorite(tracker.snapshot().token,Favorite(id = "android-surface-favorite",label = "Choice",selection = card.classification()))
        val route = SurfaceChoices.route(Surface.NOTIFICATION,tracker.snapshot())
        val intent = SurfaceChoices.intent(context,route)
        val pi = SurfaceChoices.pending(context,route)
        org.junit.Assert.assertTrue(pi.isActivity);org.junit.Assert.assertTrue(pi.isImmutable)
        org.junit.Assert.assertEquals(route,SurfaceChoices.parse(org.robolectric.Shadows.shadowOf(pi).savedIntent))
        val other = SurfaceChoices.route(Surface.NOTIFICATION,tracker.snapshot())
        org.junit.Assert.assertNotEquals(pi,SurfaceChoices.pending(context,other))
        val saved = android.os.Bundle();SurfaceChoices.save(saved,route)
        org.junit.Assert.assertEquals(route,SurfaceChoices.restore(saved,android.content.Intent()))
        org.junit.Assert.assertNull(SurfaceChoices.parse(android.content.Intent()))
        tracker = Tracker(reopen(tracker.store),clock)
        val state = SurfaceChoices.read(tracker,SurfaceChoices.restore(saved,intent)!!)
        org.junit.Assert.assertEquals(1,state.choices.size)
        SurfaceChoices.choose(context,tracker,route,state.choices.single().reference)
        org.junit.Assert.assertEquals(TimerCommand.PAUSE,TimerSurfaces.primary(tracker.snapshot()))
        org.junit.Assert.assertEquals(TimerCommand.STOP,TimerSurfaces.overlayPrimary(tracker.snapshot()))
        org.junit.Assert.assertThrows(CoreError::class.java) { SurfaceChoices.choose(context,tracker,route,state.choices.single().reference) }
        clock.advance(1_000)
        val switchRoute = SurfaceChoices.route(Surface.NOTIFICATION,tracker.snapshot())
        org.junit.Assert.assertEquals(ChoiceApplication.SWITCH,switchRoute.application)
        val first = tracker.snapshot().session!!.id
        SurfaceChoices.choose(context,tracker,switchRoute,SurfaceChoices.read(tracker,switchRoute).choices.single().reference)
        org.junit.Assert.assertNotEquals(first,tracker.snapshot().session!!.id)
        clock.advance(1_000)
        val overlay = SurfaceAction(Surface.OVERLAY,TimerCommand.STOP,tracker.snapshot().token,tracker.snapshot().timerTarget)
        val stopped = TimerSurfaces.execute(tracker,overlay,true)
        org.junit.Assert.assertNotNull(stopped.assignment)
        org.junit.Assert.assertEquals(TimerCommand.START,TimerSurfaces.overlayPrimary(stopped.state))
        org.junit.Assert.assertThrows(CoreError::class.java) { TimerSurfaces.execute(tracker,overlay,true) }
        val oldAssignmentRoute = SurfaceChoices.route(Surface.NOTIFICATION,stopped.state,stopped.assignment)
        tracker.start(tracker.snapshot().token)
        val currentBefore = tracker.snapshot()
        val dataBefore = tracker.data()
        org.junit.Assert.assertThrows(CoreError::class.java) { SurfaceChoices.read(tracker,oldAssignmentRoute) }
        org.junit.Assert.assertThrows(CoreError::class.java) { SurfaceChoices.choose(context,tracker,oldAssignmentRoute,state.choices.single().reference) }
        org.junit.Assert.assertEquals(dataBefore,tracker.data())
        tracker = Tracker(reopen(tracker.store),clock)
        val freshRoute = SurfaceChoices.reopen(context,tracker,Surface.NOTIFICATION,tracker.snapshot().token,stopped.assignment!!.sessionId)
        org.junit.Assert.assertNotEquals(oldAssignmentRoute.assignmentId,freshRoute.assignmentId)
        val freshState = SurfaceChoices.read(tracker,freshRoute)
        SurfaceChoices.choose(context,tracker,freshRoute,freshState.choices.single().reference,"Saved record")
        org.junit.Assert.assertEquals(currentBefore.session,tracker.snapshot().session)
        org.junit.Assert.assertEquals(currentBefore.timerTarget,tracker.snapshot().timerTarget)
        org.junit.Assert.assertEquals(currentBefore.trustedDurationMs,tracker.snapshot().trustedDurationMs)
        org.junit.Assert.assertThrows(CoreError::class.java) { SurfaceChoices.choose(context,tracker,freshRoute,freshState.choices.single().reference) }
        clock.advance(1_000);tracker.stop(tracker.snapshot().token)
        val controller = WidgetPlacementController(context)
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val widgets = org.robolectric.Shadows.shadowOf(manager)
        widgets.setRequestPinAppWidgetSupported(false)
        org.junit.Assert.assertEquals(WidgetPlacement.UNSUPPORTED,controller.read().placement)
        widgets.setRequestPinAppWidgetSupported(true)
        val prefs = context.getSharedPreferences("widget-placement",android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("request","no-callback").putString("state",WidgetPlacement.PENDING.name).commit()
        org.junit.Assert.assertEquals(WidgetPlacement.PENDING,controller.read().placement)
        org.junit.Assert.assertEquals(WidgetPlacement.UNCONFIRMED,controller.read(reconcileForeground = true).placement)
        controller.callback("foreign");org.junit.Assert.assertEquals("no-callback",controller.read().requestId)
        val activity = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        try {
            val pinned = controller.request(activity.get())
            org.junit.Assert.assertNotEquals(SurfaceProblem.FOREGROUND_REQUIRED,pinned.problem)
            org.junit.Assert.assertTrue(controller.read(reconcileForeground = true).ids.isNotEmpty())
            org.junit.Assert.assertEquals(WidgetPlacement.PINNED,controller.read().placement)
            val capability = SurfaceSettingsBackend.read(context,SurfaceSettings(widget = false))
            org.junit.Assert.assertFalse(capability.widget.enabled);org.junit.Assert.assertEquals(WidgetPlacement.PINNED,capability.widgetPlacement.placement)
        } finally { activity.pause().stop().destroy() }
        tracker.startFocus(tracker.snapshot().token,plan = FocusPlan(1_000,1_000))
        org.junit.Assert.assertEquals(TimerCommand.PAUSE,TimerSurfaces.primary(tracker.snapshot()))
        org.junit.Assert.assertEquals(TimerCommand.STOP,TimerSurfaces.overlayPrimary(tracker.snapshot()))
        clock.advance(1_000)
        org.junit.Assert.assertNull(TimerSurfaces.primary(tracker.snapshot()))
        org.junit.Assert.assertEquals(TimerCommand.STOP,TimerSurfaces.overlayPrimary(tracker.snapshot()))
    }
    override fun createStore(): StateStore = open("test-${newId()}.db")
    private fun open(name: String): StateStore {
        val db = androidx.room.Room.databaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java,name).addMigrations(TrackerDatabase.MIGRATION_1_2,TrackerDatabase.MIGRATION_2_3,TrackerDatabase.MIGRATION_3_4,TrackerDatabase.MIGRATION_4_5,TrackerDatabase.MIGRATION_5_6,TrackerDatabase.MIGRATION_6_7).allowMainThreadQueries().build()
        return try { RoomStore(db).also { databases[it] = name to db } } catch(e: Exception) { db.close();throw e }
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
        org.junit.Assert.assertEquals(7,databases.getValue(store).second.openHelper.readableDatabase.version)
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
        org.junit.Assert.assertEquals(source.data().copy(revision = before.revision+1,controlBarrier = receiver.data().controlBarrier),receiver.data())
        org.junit.Assert.assertEquals(before,Backups.decode(rollback.read(result.rollbackReference)).data)
        val stale = prepare(json.toByteArray(Charsets.UTF_8))
        receiver.settings(receiver.snapshot().token,Settings("Europe/Berlin"))
        val changed = receiver.data()
        val error = org.junit.Assert.assertThrows(CoreError::class.java) { backups.apply(stale) }
        org.junit.Assert.assertEquals(ErrorCode.STALE,error.code)
        org.junit.Assert.assertEquals(changed,receiver.data())
    }
    private fun bundledCatalogInput() {
        val context = RuntimeEnvironment.getApplication()
        val ready = ReadyCatalog.read { context.assets.open("catalog/$it") }
        val fresh = newInstallData(ready,"America/Los_Angeles")
        org.junit.Assert.assertEquals("America/Los_Angeles",fresh.settings.reportZone)
        org.junit.Assert.assertEquals(ready.vocabulary.types,fresh.types)
        org.junit.Assert.assertEquals(ready.vocabulary.axes,fresh.axes)
        org.junit.Assert.assertEquals(ready.manifest.catalogVersion,fresh.catalogVersion)
        org.junit.Assert.assertEquals(java.time.ZoneId.systemDefault().id,newInstallData(ready).settings.reportZone)
        val installDb = TrackerDatabase.open(context,"fresh-${newId()}.db")
        try { runBlocking { withContext(Dispatchers.IO) {
            val installed = RoomStore(installDb,{ fresh }).read()
            org.junit.Assert.assertEquals(fresh,installed)
            org.junit.Assert.assertEquals(fresh,RoomStore(installDb,{ throw AssertionError("Existing install must not initialize again") }).read())
        } } } finally { installDb.close() }

        val tracker = Tracker(createStore(),FakeClock())
        val importer = CatalogImports(tracker) { ready }
        val before = tracker.data();val preview = importer.prepare()
        org.junit.Assert.assertTrue(preview.canApply);org.junit.Assert.assertEquals(before,tracker.data())
        importer.apply(preview);val installed = tracker.data()
        importer.apply(importer.prepare())
        org.junit.Assert.assertEquals(installed.types,tracker.data().types)
        org.junit.Assert.assertEquals(installed.axes,tracker.data().axes)
        org.junit.Assert.assertEquals(tracker.data(),reopen(tracker.store).read())
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
    override fun assertRetentionWriteFailure(t: Tracker,preview: TransferRetentionPreview) {
        val db = databases.getValue(t.store).second.openHelper.writableDatabase;val before = t.data()
        db.execSQL("CREATE TRIGGER fail_retention BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'synthetic retention abort'); END")
        try {
            val failure = org.junit.Assert.assertThrows(CoreError::class.java) { t.applyTransferRetention(preview,"compact",preview.requiredAcknowledgements) }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,failure.code)
        } finally { db.execSQL("DROP TRIGGER fail_retention") }
        org.junit.Assert.assertEquals(before,t.data());org.junit.Assert.assertNull(t.transferRetentionReceipt("compact"))
    }
    override fun assertTransferFailure(transfer: HistoryTransfer,preview: HistoryPreview,tracker: Tracker) {
        val db = databases.getValue(tracker.store).second.openHelper.writableDatabase
        val before = tracker.data()
        db.execSQL("CREATE TRIGGER fail_transfer BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected transfer failure'); END")
        try {
            val e = org.junit.Assert.assertThrows(CoreError::class.java) { transfer.apply(preview) }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,e.code)
            org.junit.Assert.assertEquals(ImportIssueCode.APPLY_STORAGE_FAILED,e.importIssue!!.code)
        } finally { db.execSQL("DROP TRIGGER fail_transfer") }
        org.junit.Assert.assertEquals(before,tracker.data())
    }
    override fun surfaceActions() {
        bundledCatalogInput()
        calendarDiscovery()
        focusBackgroundSignals()
        privateFilesAndMetadata()
        corruptDatabaseRescue()
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
        val beforeRecovery = focused.data(); focusClock.process = "recreated-process";focusClock.boot = "recreated-boot"
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
        // Shared row outside the app: start/stop is the main button, pause/resume sits beside it.
        val rendered = TimerSurfaces.widget(context,tracker.view(),true).apply(context,null)
        org.junit.Assert.assertEquals("Стоп",rendered.findViewById<android.widget.ImageButton>(R.id.surface_primary).contentDescription.toString())
        org.junit.Assert.assertEquals("Продолжить",rendered.findViewById<android.widget.ImageButton>(R.id.surface_secondary).contentDescription.toString())
        val disabled = TimerSurfaces.widget(context,tracker.view(),false).apply(context,null)
        org.junit.Assert.assertEquals(android.view.View.GONE,disabled.findViewById<android.view.View>(R.id.surface_primary).visibility)
        org.junit.Assert.assertEquals(android.view.View.GONE,disabled.findViewById<android.view.View>(R.id.surface_secondary).visibility)
        val notification = TimerSurfaces.notification(context,tracker.view())
        org.junit.Assert.assertEquals(latest.safeLabel,notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        org.junit.Assert.assertEquals(0,notification.actions?.size ?: 0) // buttons live in the custom row, visible collapsed
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
        val controller = org.robolectric.Robolectric.buildActivity(TechnicalActivity::class.java,android.content.Intent(shared)).create()
        val activity = controller.get()
        val noteField = TechnicalActivity::class.java.getDeclaredField("note").apply { isAccessible = true }
        val note = noteField.get(activity) as android.widget.EditText
        org.junit.Assert.assertEquals(text,note.text.toString())
        org.junit.Assert.assertEquals(android.content.Intent.ACTION_MAIN,activity.intent.action)
        org.junit.Assert.assertFalse(activity.intent.hasExtra(android.content.Intent.EXTRA_PROCESS_TEXT))
        note.setText("Edited draft")
        val fieldValues = mapOf("name" to "Draft name","details" to "listening","from" to "2026-10-01T00:00:00Z","until" to "2026-10-01T00:01:00Z","zone" to "America/Los_Angeles")
        fieldValues.forEach { (key,value) -> (TechnicalActivity::class.java.getDeclaredField(key).apply { isAccessible = true }.get(activity) as android.widget.EditText).setText(value) }
        val cardField = TechnicalActivity::class.java.getDeclaredField("selectedCardId").apply { isAccessible = true };cardField.set(activity,"two")
        controller.newIntent(android.content.Intent(shared).putExtra(android.content.Intent.EXTRA_PROCESS_TEXT,"Other text"))
        org.junit.Assert.assertEquals("Edited draft",note.text.toString()) // explicit text input never silently destroys an existing form
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            .getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        val state = android.os.Bundle()
        controller.saveInstanceState(state)
        val stateStatus = TechnicalActivity::class.java.getDeclaredField("status").apply { isAccessible = true }.get(activity) as android.widget.TextView
        org.junit.Assert.assertNotNull("Form save failed: ${stateStatus.text}",state.getString("formReference"))
        controller.destroy()
        val recreated = org.robolectric.Robolectric.buildActivity(TechnicalActivity::class.java,android.content.Intent(shared)).create(state)
        try {
            org.junit.Assert.assertEquals("Edited draft",(noteField.get(recreated.get()) as android.widget.EditText).text.toString())
            fieldValues.forEach { (key,value) -> org.junit.Assert.assertEquals(value,(TechnicalActivity::class.java.getDeclaredField(key).apply { isAccessible = true }.get(recreated.get()) as android.widget.EditText).text.toString()) }
            org.junit.Assert.assertEquals("two",cardField.get(recreated.get()))
        }
        finally { recreated.destroy() }
    }
    private fun focusBackgroundSignals() {
        val context = RuntimeEnvironment.getApplication();val clock = FakeClock();val t = Tracker(createStore(),clock)
        t.startFocus(t.snapshot().token,plan = FocusPlan(1000,1000));clock.advance(1000)
        t.settings(t.snapshot().token,t.data().settings.copy(surfaces = SurfaceSettings(focusSignal = true)))
        val due = t.snapshot().focus!!;val before = t.data()
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.junit.Assert.assertFalse(FocusSignals.deliver(context,t))
        org.junit.Assert.assertEquals(FocusDeliveryStatus.BLOCKED_BEFORE_POST,FocusSignals.deliverResult(context,t).status)
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.junit.Assert.assertTrue(FocusSignals.deliver(context,t))
        org.junit.Assert.assertFalse(FocusSignals.deliver(context,t))
        org.junit.Assert.assertEquals(FocusDeliveryStatus.PREVIOUS_RECEIPT,FocusSignals.deliverResult(context,t).status)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        fun noticeVisible() = manager.activeNotifications.any { it.id == 43 }
        org.junit.Assert.assertTrue(noticeVisible())
        org.junit.Assert.assertTrue(context.getSharedPreferences("focus-signals",0).getStringSet("delivered",emptySet())!!.contains(due.signalKey))
        org.junit.Assert.assertEquals(before,t.data())
        val oldProjection = t.view()
        val delayedRefresh = { FocusSignals.refresh(context,t) }
        t.focusNext(t.snapshot().token)
        FocusSignals.refresh(context,t)
        org.junit.Assert.assertEquals(FocusDeliveryStatus.NOT_DUE,FocusSignals.deliverResult(context,t).status)
        val alarms = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        fun focusAlarmPhase(): Long = alarms.scheduledAlarms.mapNotNull { alarm ->
            alarm.operation?.let { org.robolectric.Shadows.shadowOf(it).savedIntent }
        }.single { it.action == "dev.notai.tracker.FOCUS_DUE" }.getLongExtra("index",-1)
        org.junit.Assert.assertEquals(0L,oldProjection.snapshot().focus!!.phaseIndex)
        org.junit.Assert.assertEquals(1L,focusAlarmPhase())
        delayedRefresh() // Callback A was queued before B advanced/scheduled phase 1.
        org.junit.Assert.assertEquals(1L,focusAlarmPhase())
        org.junit.Assert.assertFalse(noticeVisible())
        org.junit.Assert.assertFalse(FocusSignals.deliver(context,t)) // delayed old DUE callback reads the current run
        clock.advance(1000)
        org.junit.Assert.assertTrue(FocusSignals.deliver(context,t))
        org.junit.Assert.assertEquals(1000L,t.snapshot().trustedDurationMs)
        org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        FocusSignals.refresh(context,t) // permission revoked; no history transition
        org.junit.Assert.assertEquals(FocusMode.DUE,t.snapshot().focus!!.mode)
        org.junit.Assert.assertFalse(noticeVisible())
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        t.focusNext(t.snapshot().token);clock.advance(1000);FocusSignals.deliver(context,t)
        org.junit.Assert.assertTrue(noticeVisible())
        t.settings(t.snapshot().token,t.data().settings.copy(surfaces = SurfaceSettings()))
        FocusSignals.refresh(context,t);org.junit.Assert.assertFalse(noticeVisible())
        org.junit.Assert.assertFalse(FocusSignals.deliver(context,t))
        t.stop(t.snapshot().token);FocusSignals.refresh(context,t)
        org.junit.Assert.assertFalse(noticeVisible())
    }
    private fun privateFilesAndMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val cards = listOf(Card(id = "one",name = "Same",details = mapOf("variant" to "one")),Card(id = "two",name = "Same",details = mapOf("variant" to "two")))
        val data = Dataset(cards = cards)
        org.junit.Assert.assertEquals("two",CardInputs.selection(data,"two").details["variant"])
        org.junit.Assert.assertEquals("two",CardInputs.selection(data,"two").cardId)
        val subjectClock = FakeClock();val subjectTracker = Tracker(createStore(),subjectClock)
        subjectTracker.settings(subjectTracker.snapshot().token,Settings(surfaces = SurfaceSettings(widget = true)))
        subjectTracker.start(subjectTracker.snapshot().token);subjectClock.advance(1000)
        val shown = subjectTracker.snapshot();val stop = SurfaceAction(Surface.WIDGET,TimerCommand.STOP,shown.token,shown.timerTarget)
        org.junit.Assert.assertEquals(stop,TimerSurfaces.parse(TimerSurfaces.intent(context,stop)))
        subjectTracker.settings(shown.token,subjectTracker.data().settings.copy(reportZone = "Europe/Berlin"))
        TimerSurfaces.execute(subjectTracker,stop)
        subjectTracker.start(subjectTracker.snapshot().token)
        val active = subjectTracker.data()
        org.junit.Assert.assertEquals(ErrorCode.STALE,org.junit.Assert.assertThrows(CoreError::class.java) { TimerSurfaces.execute(subjectTracker,stop) }.code)
        org.junit.Assert.assertEquals(active,subjectTracker.data())
        val exports = ExportOperations(context);val op = exports.begin("application/json","test.json")
        val bytes = "{\"complete\":true}".toByteArray()
        exports.prepare(op.id) { it.write(bytes) };exports.cancel(op.id)
        val reopened = ExportOperations(context);org.junit.Assert.assertEquals("CANCELLED",reopened.read(op.id).state)
        val destination = java.io.File(context.noBackupFilesDir,"export-test-${newId()}.json")
        org.junit.Assert.assertThrows(Exception::class.java) { reopened.write(op.id,android.net.Uri.fromFile(java.io.File(context.noBackupFilesDir,"missing-parent/out.json"))) }
        org.junit.Assert.assertEquals("READY",reopened.read(op.id).state)
        val result = reopened.write(op.id,android.net.Uri.fromFile(destination))
        org.junit.Assert.assertArrayEquals(bytes,destination.readBytes());org.junit.Assert.assertTrue(result.verified)
        for(table in listOf("metadata","vocabulary","store_generation")) {
            val store = createStore();val db = databases.getValue(store).second;val tracker = Tracker(store,FakeClock())
            tracker.saveType(tracker.snapshot().token,ActivityType("personal","Personal"))
            tracker.start(tracker.snapshot().token,Classification(activityTypeId = "personal"));tracker.stop(tracker.snapshot().token)
            tracker.saveFavorite(tracker.snapshot().token,Favorite(label = "Personal",selection = tracker.data().sessions.single().selection))
            db.openHelper.writableDatabase.execSQL("DELETE FROM $table")
            fun counts(): List<Long> = listOf("activity_types","sessions","segments","favorites","metadata","vocabulary","store_generation").map { name ->
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $name").use { it.moveToFirst();it.getLong(0) }
            }
            fun payloadHashes(): List<String> = listOf("activity_types","sessions","segments","favorites","metadata","vocabulary","store_generation").map { name ->
                db.openHelper.readableDatabase.query("SELECT * FROM $name ORDER BY id").use { c ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    while(c.moveToNext()) for(i in 0 until c.columnCount) digest.update((c.getString(i) ?: "<NULL>").toByteArray())
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
            val hashes = payloadHashes();val before = counts()
            org.junit.Assert.assertThrows(CoreError::class.java) { RoomStore(db) }
            org.junit.Assert.assertThrows(CoreError::class.java) { store.read() }
            org.junit.Assert.assertThrows(CoreError::class.java) { tracker.start(tracker.snapshot().token) }
            org.junit.Assert.assertEquals(before,counts());org.junit.Assert.assertEquals(hashes,payloadHashes())
            val name = databases.getValue(store).first;val archive = DatabaseArchives.capture(context,name)
            val manifest = codec.decodeFromString<DatabaseArchive>(java.io.File(archive,"manifest.json").readText())
            org.junit.Assert.assertTrue(manifest.files.isNotEmpty())
            for(file in manifest.files) org.junit.Assert.assertEquals(file.sha256,fileHash(java.io.File(archive,file.name)))
        }
    }
    private fun corruptDatabaseRescue() {
        val context = RuntimeEnvironment.getApplication()
        TrackerRuntime.beginRecovery();TrackerRuntime.endRecovery() // retire only this test application's singleton
        val dbFile = context.getDatabasePath("tracker.db").apply { parentFile!!.mkdirs() }
        val originals = listOf("tracker.db","tracker.db-wal","tracker.db-shm").associateWith { ("corrupt fixture: "+it).toByteArray() }
        originals.forEach { (name,bytes) -> context.getDatabasePath(name).writeBytes(bytes) }
        org.junit.Assert.assertThrows(Exception::class.java) { runBlocking { withContext(Dispatchers.IO) { TrackerRuntime.get(context) } } }
        originals.forEach { (name,bytes) -> org.junit.Assert.assertArrayEquals(bytes,context.getDatabasePath(name).readBytes()) }

        val clock = FakeClock();val tracker = Tracker(createStore(),clock)
        tracker.saveCard(tracker.snapshot().token,Card(name = "Rescued card"))
        tracker.start(tracker.snapshot().token);clock.advance(1000);tracker.stop(tracker.snapshot().token)
        val backup = StreamingBackup.portable(tracker.data(),clock.now())
        val source = java.io.File(context.noBackupFilesDir,"rescue-source.json")
        source.outputStream().use { StreamingBackup.write(backup,it) }
        val preview = DatabaseRecovery.prepare(source)
        source.appendText("invalid")
        org.junit.Assert.assertThrows(CoreError::class.java) { DatabaseRecovery.apply(context,preview) }
        originals.forEach { (name,bytes) -> org.junit.Assert.assertArrayEquals(bytes,context.getDatabasePath(name).readBytes()) }
        source.outputStream().use { StreamingBackup.write(backup,it) }
        val rename = DirectoryDurability.rename
        DirectoryDurability.rename = { old,new ->
            if(old.name.startsWith("rescue-") && new.name == "tracker.db") throw java.io.IOException("Injected interrupted installation")
            rename(old,new)
        }
        try { org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            runBlocking { withContext(Dispatchers.IO) { DatabaseRecovery.apply(context,DatabaseRecovery.prepare(source)) } }
        } } finally { DirectoryDurability.rename = rename }
        org.junit.Assert.assertTrue(DatabaseRecovery.needsAttention(context))
        org.junit.Assert.assertThrows(CoreError::class.java) { TrackerRuntime.get(context) }
        val archives = java.io.File(context.noBackupFilesDir,"database-archives").listFiles().orEmpty()
        val preserved = archives.first { java.io.File(it,"tracker.db").exists() && java.io.File(it,"tracker.db-wal").exists() && java.io.File(it,"tracker.db-shm").exists() }
        originals.forEach { (name,bytes) -> org.junit.Assert.assertArrayEquals(bytes,java.io.File(preserved,name).readBytes()) }
        source.appendText("changed")
        org.junit.Assert.assertThrows(CoreError::class.java) { DatabaseRecovery.resume(context) }
        org.junit.Assert.assertTrue(DatabaseRecovery.needsAttention(context))
        source.outputStream().use { StreamingBackup.write(backup,it) }
        val result = runBlocking { withContext(Dispatchers.IO) { DatabaseRecovery.resume(context) } }
        val archive = java.io.File(context.noBackupFilesDir,"database-archives/${result.archive}")
        org.junit.Assert.assertTrue(java.io.File(archive,"manifest.json").exists())
        org.junit.Assert.assertFalse(DatabaseRecovery.needsAttention(context))
        val db = TrackerDatabase.open(context)
        try {
            val data = runBlocking { withContext(Dispatchers.IO) { RoomStore(db).read() } }
            org.junit.Assert.assertEquals(backup.data.sessions,data.sessions)
            org.junit.Assert.assertEquals(backup.data.segments,data.segments)
            org.junit.Assert.assertEquals(backup.data.cards,data.cards)
        } finally { db.close() }
        org.junit.Assert.assertTrue(source.exists());org.junit.Assert.assertTrue(dbFile.exists())
    }
    private fun calendarDiscovery() {
        var permission = false;var queries = 0
        val refs = listOf(CalendarOccurrenceRef(2,0,6000),CalendarOccurrenceRef(1,1000,1800),CalendarOccurrenceRef(1,2000,3000))
        val calendar = CalendarOccurrences({ permission },{ _: android.net.Uri,columns: Array<String>,where: String,args: Array<String>,order: String? ->
            queries++
            org.junit.Assert.assertEquals("begin ASC, event_id ASC, end ASC",order)
            val cursor = android.database.MatrixCursor(columns)
            val from = args[0].toLong();val until = args[1].toLong()
            val after = if(args.size > 2) CalendarOccurrenceRef(args[4].toLong(),args[2].toLong(),args[7].toLong()) else null
            val comparison = compareBy<CalendarOccurrenceRef> { it.begin }.thenBy { it.eventId }.thenBy { it.end }
            refs.filter { it.end > from && it.begin < until && (after == null || comparison.compare(it,after)>0) }
                .forEach { cursor.addRow(arrayOf<Any>(it.eventId,it.begin,it.end,"Occurrence",if(it.eventId==2L) 1 else 0)) }
            org.junit.Assert.assertTrue(where.contains("end > ?"));cursor
        })
        org.junit.Assert.assertEquals(CalendarReadIssue.DISABLED,calendar.list(0,6000).issue)
        org.junit.Assert.assertEquals(CalendarReadIssue.PERMISSION_REQUIRED,calendar.list(0,6000,true).issue)
        org.junit.Assert.assertEquals(0,queries);permission = true
        val first = calendar.list(0,6000,true,limit = 2)
        org.junit.Assert.assertEquals(refs.take(2),first.occurrences.map { it.reference })
        org.junit.Assert.assertNull(first.occurrences.first().draft().interval)
        val second = calendar.list(0,6000,true,limit = 2,cursor = first.next)
        org.junit.Assert.assertEquals(listOf(refs.last()),second.occurrences.map { it.reference })
        org.junit.Assert.assertNull(second.next)
        org.junit.Assert.assertEquals(refs.first(),calendar.nearest(1500,true).occurrence!!.reference)
        org.junit.Assert.assertThrows(CoreError::class.java) { calendar.list(0,7000,true,cursor = first.next) }
        permission = false;org.junit.Assert.assertEquals(CalendarReadIssue.PERMISSION_REQUIRED,calendar.nearest(1500,true).issue)
        val revoked = CalendarOccurrences({ true },{ _: android.net.Uri,_: Array<String>,_: String,_: Array<String>,_: String? -> throw SecurityException() })
        org.junit.Assert.assertEquals(CalendarReadIssue.PERMISSION_REQUIRED,revoked.list(0,6000,true).issue)
        val missing = CalendarOccurrences({ true },{ _: android.net.Uri,_: Array<String>,_: String,_: Array<String>,_: String? -> null })
        org.junit.Assert.assertEquals(CalendarReadIssue.PROVIDER_UNAVAILABLE,missing.list(0,6000,true).issue)
    }
    override fun storageWorkload() {
        if(System.getProperty("tracker.cold.profile") == "true") coldLoadHypotheses()
        if(System.getProperty("tracker.scale") == "true") realisticHistoryBenchmark()
        val store = createStore(); val db = databases.getValue(store).second
        val clock = FakeClock(); val tracker = Tracker(store,clock)
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!
        val vocabulary = codec.decodeFromString<VocabularyData>(root.resolve("catalog/runtime-catalog.json").readText())
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
    private fun realisticHistoryInput(clock: FakeClock): Dataset {
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!
        val vocabulary = codec.decodeFromString<VocabularyData>(root.resolve("catalog/runtime-catalog.json").readText())
        val base = Dataset(types = vocabulary.types,axes = vocabulary.axes,metrics = vocabulary.metrics,units = vocabulary.units)
        val selected = vocabulary.types.filter { it.selectable }.take(3).map { base.classify(Classification(name = it.name,activityTypeId = it.id,
            tags = setOf("synthetic-benchmark"),details = mapOf("fixture" to "ten-years"))) }
        val count = 73_000;val first = clock.wall-3650L*86_400_000
        val sessions = (0 until count).map { Session(id = "scale-session-$it",status = SessionStatus.STOPPED,selection = selected[it%selected.size],
            note = "Synthetic entry $it "+"x".repeat(128)) }
        val segments = sessions.mapIndexed { i,s -> val start = first+(i/20)*86_400_000L+(i%20)*40*60_000L
            Segment(id = "scale-segment-$i",sessionId = s.id,start = start,end = start+30*60_000,durationMs = 30*60_000,
                selection = s.selection) }
        return base.copy(sessions = sessions,segments = segments)
    }
    private fun coldLoadHypotheses() {
        val clock = FakeClock();val input = realisticHistoryInput(clock)
        var store = createStore();store.transaction { input to Unit }
        val variant = System.getProperty("tracker.cold.variant","normalized")!!
        require(variant in setOf("normalized","normalized-unpaged"))
        val initialDb = databases.getValue(store).second
        val plans = listOf("sessions","segments").associateWith { table -> initialDb.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT id,position FROM $table ORDER BY position,id").use { c -> buildList { while(c.moveToNext()) add(c.getString(3)) } } }
        val samples = mutableListOf<JsonObject>()
        for(iteration in 0 until 5) {
            val (name,old) = databases.remove(store)!!;old.close()
            if(iteration == 0) System.getProperty("tracker.android.fixture")?.let { destination ->
                val directory = java.io.File(destination).apply { mkdirs() }
                RuntimeEnvironment.getApplication().getDatabasePath(name).copyTo(java.io.File(directory,"history.db"),overwrite = false)
                java.io.File(directory,"history.json").outputStream().use { StreamingBackup.write(StreamingBackup.portable(input,clock.now()),it) }
            }
            val phases = linkedMapOf<String,Long>()
            val start = System.nanoTime()
            val db = androidx.room.Room.databaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java,name)
                .addMigrations(TrackerDatabase.MIGRATION_1_2,TrackerDatabase.MIGRATION_2_3,TrackerDatabase.MIGRATION_3_4,TrackerDatabase.MIGRATION_4_5,TrackerDatabase.MIGRATION_5_6,TrackerDatabase.MIGRATION_6_7).allowMainThreadQueries().build()
            val reopened = RoomStore(db,onLoadPhase = { key,ns -> phases[key] = (phases[key] ?: 0)+ns },loadPageSize = if(variant == "normalized") 512 else null)
            databases[reopened] = name to db;store = reopened
            val initDone = System.nanoTime()-start
            val tracker = Tracker(store,clock)
            org.junit.Assert.assertEquals(Mode.IDLE,tracker.snapshot().mode)
            val ready = System.nanoTime()-start
            println("COLD_PHASE_SAMPLE "+buildJsonObject { put("iteration",iteration);put("actionReadyNs",ready);put("phases",JsonObject(phases.mapValues { JsonPrimitive(it.value) })) })
            org.junit.Assert.assertEquals(input,tracker.data())
            val actionStart = System.nanoTime();tracker.start(tracker.snapshot().token);val action = System.nanoTime()-actionStart
            clock.advance(1000);tracker.stop(tracker.snapshot().token)
            val date = java.time.Instant.ofEpochMilli(clock.wall-86_400_000L).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
            val reportStart = System.nanoTime();val report = tracker.view().day(date);val reportNs = System.nanoTime()-reportStart
            org.junit.Assert.assertEquals(20*30*60_000L,report.recordedMs)
            samples += buildJsonObject {
                put("iteration",iteration);put("storeInitializeNs",initDone);put("actionReadyNs",ready);put("historyGraphReadyNs",ready)
                put("firstStartNs",action);put("firstDayReportNs",reportNs)
                put("phasesNs",JsonObject(phases.mapValues { JsonPrimitive(it.value) }))
            }
            store.transaction { input to Unit };clock.wall = FakeClock().wall;clock.elapsed = FakeClock().elapsed
        }
        val directory = java.io.File(requireNotNull(System.getProperty("tracker.artifacts"))).apply { mkdirs() }
        java.io.File(directory,"cold-load-profile.json").writeText(buildJsonObject {
            put("schema","tracker_cold_phase_profile_v1");put("execution","Robolectric API35 JVM Room; not Android process startup; no OS page-cache eviction")
            put("sessions",73000);put("segments",73000);put("variant",variant);put("plans",JsonObject(plans.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }));put("dataIdenticalAcrossSamples",true);put("samples",JsonArray(samples));put("maxHeapBytes",Runtime.getRuntime().maxMemory())
        }.toString())
    }
    private fun realisticHistoryBenchmark() {
        fun heap(): Long { System.gc();Thread.sleep(100);return Runtime.getRuntime().let { it.totalMemory()-it.freeMemory() } }
        val heapBeforeFixture = heap()
        val clock = FakeClock();val input = realisticHistoryInput(clock);val count = input.sessions.size
        val heapWithInputFixture = heap()
        fun time(block: ()->Unit): Long { val start = System.nanoTime();block();return System.nanoTime()-start }
        val validation = time { validate(input) };var store = createStore()
        val persisted = time { store.transaction { input to Unit } }
        val cold = time { store = reopen(store);store.readChecked() }
        val tracker = Tracker(store,clock)
        val heapWithColdStore = heap()
        val window = HistoryWindow(clock.wall-30L*86_400_000,clock.wall+1)
        tracker.view().choices().quick(window)
        val scanBefore = tracker.view().choices().indexStats().historyScans
        val liveChoiceSamples = (0 until 7).map { iteration ->
            time {
                tracker.start(tracker.snapshot().token);tracker.view().choices().quick(window)
                clock.advance(1000);tracker.pause(tracker.snapshot().token);tracker.view().choices().quick(window)
                tracker.resume(tracker.snapshot().token);tracker.view().choices().quick(window)
            }.also { _ ->
                org.junit.Assert.assertEquals(scanBefore+iteration,tracker.view().choices().indexStats().historyScans)
                clock.advance(1000);tracker.stop(tracker.snapshot().token);tracker.view().choices().quick(window)
            }
        }
        val timers = (0 until 7).map {
            time { tracker.start(tracker.snapshot().token);clock.advance(1000);tracker.pause(tracker.snapshot().token)
                tracker.resume(tracker.snapshot().token);clock.advance(1000);tracker.stop(tracker.snapshot().token) }
        }
        val date = java.time.Instant.ofEpochMilli(clock.wall-86_400_000).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
        val view = tracker.view()
        val daily = (0 until 7).map { time { val report = view.day(date);org.junit.Assert.assertEquals(20*30*60_000L,report.recordedMs) } }
        val calendar = time { view.calendar(date.minusDays(29),30) }
        val heapWithIndexesAndFixture = heap()
        val directory = java.io.File(requireNotNull(System.getProperty("tracker.artifacts"))).apply { mkdirs() }
        val result = buildJsonObject {
            put("schema","tracker_scale_benchmark_v1");put("execution","Room/SQLite in Robolectric JVM API35; synthetic, not phone")
            put("sessions",count);put("segments",count);put("years",10);put("entriesPerDay",20)
            put("fullValidationNs",validation);put("initialRoomWriteNs",persisted);put("coldReopenReadNs",cold)
            put("timerCycleNs",JsonArray(timers.map(::JsonPrimitive)));put("dailyReportNs",JsonArray(daily.map(::JsonPrimitive)))
            put("calendar30DaysNs",calendar);put("maxHeapBytes",Runtime.getRuntime().maxMemory())
            put("liveTimerWithQuickChoiceNs",JsonArray(liveChoiceSamples.map(::JsonPrimitive)))
            put("javaHeapBytesBeforeFixture",heapBeforeFixture);put("javaHeapBytesWithInputFixture",heapWithInputFixture)
            put("javaHeapBytesWithColdStoreAndFixture",heapWithColdStore);put("javaHeapBytesWithIndexesAndFixture",heapWithIndexesAndFixture)
            put("heapMethod","JVM totalMemory-freeMemory after best-effort explicit GC; includes Robolectric, retained input fixture and graph(s); excludes native PSS. No phone/battery claim.")
        }
        java.io.File(directory,"scale-benchmark.json").writeText(result.toString())
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
