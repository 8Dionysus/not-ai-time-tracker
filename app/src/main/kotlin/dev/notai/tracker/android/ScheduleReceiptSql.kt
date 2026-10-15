package dev.notai.tracker.android

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import dev.notai.tracker.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Private no-backup SQLite; no AndroidX schema/runtime dependency or domain Room version bump.
 * Full synchronous rollback-journal commits, plus parent fsync, precede external effects.
 * Corruption never invokes the platform's destructive delete/recreate default handler.
 */
internal class AndroidScheduleReceiptSql(private val file: File,allowCreate: Boolean): ScheduleReceiptSql {
    private val database: SQLiteDatabase
    init {
        var main = false;var sidecar = false
        for(suffix in listOf("","-journal","-wal","-shm")) try {
            val a = Files.readAttributes(File(file.path+suffix).toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            check(a.isRegularFile) { "Unsafe reminder index file" }
            if(suffix.isEmpty()) main = true else sidecar = true
        } catch(_: NoSuchFileException) { }
        check(main || allowCreate && !sidecar) { "Indexed reminder database missing; authority preserved" }
        val flags = SQLiteDatabase.OPEN_READWRITE or (if(allowCreate) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        database = SQLiteDatabase.openDatabase(file.path,null,flags,DatabaseErrorHandler {
            throw IllegalStateException("Corrupt reminder index preserved; automatic reset refused")
        })
        try {
            database.rawQuery("PRAGMA journal_mode=DELETE",null).use { check(it.moveToFirst() && it.getString(0).equals("delete",true)) }
            database.execSQL("PRAGMA synchronous=FULL")
            database.rawQuery("PRAGMA synchronous",null).use { check(it.moveToFirst() && it.getInt(0) == 2) }
            syncDirectory(file.parentFile!!)
        } catch(e: Exception) { database.close();throw e }
    }
    override fun execute(sql: String,args: List<Any?>) {
        if(args.isEmpty()) database.execSQL(sql) else database.execSQL(sql,args.toTypedArray())
    }
    override fun query(sql: String,args: List<Any?>): List<List<String?>> = database.rawQuery(sql,args.map { it?.toString() }.toTypedArray()).use { cursor ->
        buildList { while(cursor.moveToNext()) add((0 until cursor.columnCount).map { if(cursor.isNull(it)) null else cursor.getString(it) }) }
    }
    override fun <T> transaction(block: ()->T): T {
        database.beginTransaction()
        val result = try { block().also { database.setTransactionSuccessful() } } finally { database.endTransaction() }
        syncDirectory(file.parentFile!!)
        return result
    }
    override fun close() = database.close()
}
