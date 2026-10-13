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
 * Archive mode never opens/modifies/deletes the live store or existing archives.
 * Explicit fixture modes change only QA settings/goals through the real Tracker.
 * Overlay mode observes owned windows and may invoke one observed action in QA. */
class ArchiveInstrumentation: Instrumentation() {
    private var mode = "archive"
    private var actionLabel: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);mode = arguments?.getString("mode") ?: "archive";actionLabel = arguments?.getString("actionLabel");start() }
    override fun onStart() {
        try {
            val context = targetContext
            if(mode == "overlay-window") {
                // Observe the already permitted QA overlay through public Android
                // accessibility APIs; the default CLI dump omits unfocused windows.
                startActivitySync(android.content.Intent(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                val tracker = TrackerRuntime.get(context).tracker
                waitForIdleSync();Thread.sleep(500)
                val automation = uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                }
                automation.waitForIdle(200,5_000)
                val windowDeadline = android.os.SystemClock.elapsedRealtime()+5_000
                while(automation.windows.isEmpty() && android.os.SystemClock.elapsedRealtime()<windowDeadline) Thread.sleep(100)
                val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun walk(node: android.view.accessibility.AccessibilityNodeInfo,depth: Int = 0) {
                    if(depth > 24 || nodes.size >= 2000) return
                    nodes += node
                    for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it,depth+1) }
                }
                val windows = automation.windows
                for(window in windows) window.root?.let { walk(it) }
                val observed = nodes.filter { it.packageName?.toString() == context.packageName && (!it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty()) }.map { node ->
                    val bounds = android.graphics.Rect();node.getBoundsInScreen(bounds)
                    buildJsonObject { put("text",node.text?.toString().orEmpty());put("description",node.contentDescription?.toString().orEmpty())
                        put("clickable",node.isClickable);put("enabled",node.isEnabled);put("bounds",bounds.flattenToString()) }
                }
                check(observed.isNotEmpty()) { "No owned QA window observed" }
                val before = tracker.snapshot().token
                actionLabel?.let { label ->
                    val matches = nodes.filter { it.packageName?.toString() == context.packageName && it.isClickable && it.isEnabled && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
                    check(matches.size == 1) { "Expected one observed enabled action '$label', found ${matches.size}" }
                    check(matches.single().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    val deadline = android.os.SystemClock.elapsedRealtime()+5_000
                    while(tracker.snapshot().token == before && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(20)
                    check(tracker.snapshot().token != before) { "Observed action did not commit" }
                }
                val report = buildJsonObject { put("mode",mode);put("observed",JsonArray(observed));put("action",actionLabel.orEmpty())
                    put("windowCount",windows.size)
                    put("timerMode",tracker.snapshot().mode.name);put("revisionChanged",tracker.snapshot().token != before) }
                for(node in nodes) @Suppress("DEPRECATION") node.recycle()
                finish(Activity.RESULT_OK,Bundle().apply { putString("report",report.toString()) });return
            }
            if(mode == "goal-fixture") {
                val tracker = TrackerRuntime.get(context).tracker;val before = tracker.data()
                for(goal in listOf(
                    Goal(id = "qa-minimum-60",name = "QA минимум 60 мин",metric = GoalMetric.DURATION_MS,target = 3_600_000,
                        direction = GoalDirection.MINIMUM,period = GoalPeriod.DAY,zone = "UTC"),
                    Goal(id = "qa-limit-10",name = "QA лимит 10 мин",metric = GoalMetric.DURATION_MS,target = 600_000,
                        direction = GoalDirection.LIMIT,period = GoalPeriod.DAY,zone = "UTC"))) {
                    tracker.saveGoal(tracker.snapshot().token,goal)
                }
                val after = tracker.data()
                check(before.sessions == after.sessions && before.segments == after.segments && before.cards == after.cards && before.types == after.types)
                val report = buildJsonObject {
                    put("mode",mode);put("historyUnchanged",true);put("sessions",after.sessions.size);put("goals",after.goals.size)
                    put("progress",JsonArray(listOf("qa-minimum-60","qa-limit-10").map { id ->
                        val p = tracker.view().goalProgress(id)
                        buildJsonObject { put("id",id);put("zone",p.goal.zone);put("actualMs",p.actual);put("targetMs",p.goal.target);put("status",p.recordedStatus.name) }
                    }))
                }
                finish(Activity.RESULT_OK,Bundle().apply { putString("report",report.toString()) });return
            }
            if(mode == "focus-plan") {
                // Only an explicitly invoked test runner on the isolated AVD uses
                // this fixture setup. Product APK has no such entry point.
                val tracker = TrackerRuntime.get(context).tracker;val before = tracker.data()
                tracker.settings(tracker.snapshot().token,before.settings.copy(focusPlan = FocusPlan(10_000,10_000)))
                val after = tracker.data();check(before.sessions == after.sessions && before.segments == after.segments)
                val report = buildJsonObject { put("mode",mode);put("workMs",after.settings.focusPlan.workMs);put("breakMs",after.settings.focusPlan.breakMs)
                    put("historyUnchanged",true);put("sessions",after.sessions.size);put("segments",after.segments.size) }
                finish(Activity.RESULT_OK,Bundle().apply { putString("report",report.toString()) });return
            }
            check(mode == "archive")
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
