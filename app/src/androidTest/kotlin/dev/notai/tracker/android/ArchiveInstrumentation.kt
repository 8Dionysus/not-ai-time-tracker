package dev.notai.tracker.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.notai.tracker.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.*

/** No external test dependencies. Explicit adb invocation on the isolated QA AVD.
 * Reuses one owned reference directory, so five samples do not leave five DB copies.
 * Never opens/modifies/deletes the live store or existing archives. */
class ArchiveInstrumentation: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            val context = targetContext
            val sources = DatabaseFiles.paths(context).filter { it.exists() }
            check(sources.isNotEmpty())
            val baseline = sources.associate { it.name to (it.length() to fileHash(it)) }
            val directory = File(context.noBackupFilesDir,"qa-archive-reference/${newId()}").apply { check(mkdirs()) }
            fun legacyCopy() {
                val files = sources.map { source ->
                    val target = File(directory,source.name)
                    source.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it,64*1024);it.fd.sync() } }
                    val hash = fileHash(source)
                    check(source.length() == target.length() && hash == fileHash(target))
                    PreservedDatabaseFile(source.name,target.length(),hash)
                }
                atomicText(File(directory,"manifest.json"),codec.encodeToString(DatabaseArchive(directory.name,"tracker.db",files)))
                syncDirectory(directory);syncDirectory(directory.parentFile!!);syncDirectory(context.noBackupFilesDir)
            }
            val samples = mutableListOf<JsonObject>()
            for(mode in listOf("legacy-durable-copy-reference","verified-archive-reuse","verified-archive-reuse-reverse","legacy-durable-copy-reference-reverse")) {
                for(i in 0 until 5) {
                    val before = System.nanoTime()
                    val archive = if(mode.startsWith("legacy")) { legacyCopy();directory } else DatabaseArchives.capture(context)
                    val elapsed = System.nanoTime()-before
                    check(baseline == sources.associate { it.name to (it.length() to fileHash(it)) })
                    samples += buildJsonObject { put("mode",mode);put("iteration",i);put("durationNs",elapsed);put("archiveId",archive.name) }
                }
            }
            val report = buildJsonObject {
                put("schema","tracker_android_archive_comparison_v1");put("sameSourceBytes",true);put("sourceBytes",baseline.values.sumOf { it.first })
                put("execution","Actual API35 Android instrumentation, unchanged closed store; reference copies one owned target repeatedly")
                put("countPerBlock",5);put("blocks",4);put("noDiscardedWarmups",true);put("noOSPageCacheEviction",true)
                put("sourceFiles",JsonArray(baseline.map { (name,value) -> buildJsonObject { put("name",name);put("bytes",value.first);put("sha256",value.second) } }))
                put("samples",JsonArray(samples))
                put("referenceDirectory",directory.name)
            }
            atomicText(File(directory,"report.json"),report.toString())
            finish(Activity.RESULT_OK,Bundle().apply { putString("report",report.toString()) })
        } catch(e: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("error",e.javaClass.name+": "+e.message) })
        }
    }
}
