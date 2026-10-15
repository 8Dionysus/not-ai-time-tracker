package dev.notai.tracker

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.io.File

/** Existing cached SQLite JDBC component, tests only. Same schema/queries as Android port. */
class ScheduleIndexFixture: AutoCloseable {
    val root = Files.createTempDirectory("tracker-receipt-index-").toFile()
    val file = File(root,"receipts.sqlite")
    val gate = Any()
    var queryCount = 0;var returnedRows = 0;var executions = 0
    var afterCommit: (() -> Unit)? = null
    var beforeExecute: ((String)->Unit)? = null
    var beforeManifest: (() -> Unit)? = null
    var afterManifest: (() -> Unit)? = null
    fun metadata(name: String): CopyMetadata = object: CopyMetadata {
        private val path = File(root,name).toPath()
        override fun read(): String? = if(Files.exists(path)) Files.newInputStream(path).use { CopyMetadataBytes.read(it) } else null
        override fun writeDurably(value: String) {
            if(name == "delivery.json") beforeManifest?.invoke()
            val part = Files.createTempFile(root.toPath(),"fixture-",".part")
            java.io.FileOutputStream(part.toFile()).use { it.write(CopyMetadataBytes.encode(value));it.fd.sync() }
            Files.move(part,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(root.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            if(name == "delivery.json") afterManifest?.invoke()
        }
    }
    val manifest = metadata("delivery.json")
    val source = metadata("source-v1.json")
    fun sql(allowCreate: Boolean = true): ScheduleReceiptSql {
        check(allowCreate || file.isFile) { "Missing indexed authority" }
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:"+file.absolutePath)
        c.createStatement().use { it.execute("PRAGMA journal_mode=DELETE");it.execute("PRAGMA synchronous=FULL") }
        return object: ScheduleReceiptSql {
            override fun execute(sql: String,args: List<Any?>) {
                beforeExecute?.invoke(sql);executions++
                c.prepareStatement(sql).use { s -> args.forEachIndexed { i,a -> s.setObject(i+1,a) };s.executeUpdate() }
            }
            override fun query(sql: String,args: List<Any?>): List<List<String?>> {
                queryCount++
                return c.prepareStatement(sql).use { s -> args.forEachIndexed { i,a -> s.setObject(i+1,a) };s.executeQuery().use { rows ->
                    buildList { while(rows.next()) { returnedRows++;add((1..rows.metaData.columnCount).map { rows.getString(it) }) } }
                } }
            }
            override fun <T> transaction(block: ()->T): T {
                c.autoCommit = false
                val result = try { block().also { c.commit() } } catch(e: Throwable) { c.rollback();throw e } finally { c.autoCommit = true }
                java.nio.channels.FileChannel.open(root.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
                afterCommit?.invoke();return result
            }
            override fun close() = c.close()
        }
    }
    fun store() = IndexedScheduleDeliveryStore(gate,manifest,source,::sql)
    fun resetCounts() { queryCount = 0;returnedRows = 0;executions = 0 }
    override fun close() { root.deleteRecursively() } // Only this fixture's new temp directory.
}
