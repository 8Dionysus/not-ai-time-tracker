package dev.notai.tracker.android

import android.content.Context
import android.content.SharedPreferences
import dev.notai.tracker.*
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** A small device-local write-ahead intent; it is never part of Dataset or JSON15. */
@Serializable
private data class PendingReportZone(val expected: Token,val zone: String,val startMinute: Int,val automatic: Boolean)

class ReportZonePreference(context: Context,private val tracker: Tracker,newStore: Boolean,
    private val deviceZone: () -> String = { ZoneId.systemDefault().id }) {
    private val prefs = context.applicationContext.getSharedPreferences("report-zone",Context.MODE_PRIVATE)
    internal var writePreference: (SharedPreferences.Editor) -> Boolean = { it.commit() }
    @Volatile var recoveryProblem: String? = null
        private set
    @Volatile private var pending: PendingReportZone? = null
    private var unreadablePending = false
    private var observedReplacement = tracker.data().let { it.datasetId to it.controlBarrier }
    init {
        if(!prefs.contains("automatic")) save(prefs.edit().putBoolean("automatic",newStore))
        try { pending = prefs.getString("pending",null)?.let { codec.decodeFromString(PendingReportZone.serializer(),it) } }
        catch(_: Exception) { unreadablePending = true;recoveryProblem = "Не удалось восстановить выбор пояса. Выбери пояс ещё раз" }
    }
    // A confirmed durable intent wins over the previous mode even if final acknowledgement failed.
    val automatic: Boolean get() = pending?.automatic ?: prefs.getBoolean("automatic",false)
    private fun save(editor: SharedPreferences.Editor) {
        try { ensure(writePreference(editor),"Report zone preference could not be saved",ErrorCode.STORAGE) }
        catch(e: java.util.concurrent.CancellationException) { throw e }
        catch(e: CoreError) { throw e }
        catch(_: Exception) { throw CoreError(ErrorCode.STORAGE,"Report zone preference could not be saved") }
    }
    private fun finish(intent: PendingReportZone) {
        try { save(prefs.edit().putBoolean("automatic",intent.automatic).remove("pending")) }
        catch(e: Exception) {
            recoveryProblem = "Пояс изменён. Подтверждение настройки не сохранилось; приложение повторит его при открытии"
            throw e
        }
        pending = null;unreadablePending = false;recoveryProblem = null
    }
    private fun configure(expected: Token,automatic: Boolean,zone: String): Tracker.Change {
        val view = tracker.view()
        ensure(view.snapshot().token == expected,"Report zone view changed",ErrorCode.STALE)
        val policy = ReportingDayPolicy(zone,view.data.settings.reportDayStartMinute);policy.validate()
        val intent = PendingReportZone(expected,zone,policy.startMinute,automatic)
        // Fail before the core write unless this intent has been acknowledged by the local store.
        save(prefs.edit().putString("pending",codec.encodeToString(PendingReportZone.serializer(),intent)))
        pending = intent;unreadablePending = false;recoveryProblem = null
        val change = try { tracker.configureReportingDay(expected,policy) }
        catch(e: Exception) {
            // A domain refusal is known not to have committed. A storage failure/cancellation
            // may have lost the acknowledgement; retain its durable intent for observation/recovery.
            if(e is CoreError && e.code != ErrorCode.STORAGE) {
                try { save(prefs.edit().remove("pending"));pending = null }
                catch(_: Exception) { recoveryProblem = "Не удалось завершить выбор пояса. Проверь текущий пояс и выбери его ещё раз" }
            } else recoveryProblem = "Результат смены пояса требует проверки. Приложение проверит его при открытии"
            throw e
        }
        finish(intent)
        return change
    }
    @Synchronized fun setAutomatic(expected: Token,value: Boolean): Tracker.Change =
        configure(expected,value,if(value) deviceZone() else tracker.data().settings.reportZone)
    @Synchronized fun setFixed(expected: Token,zone: String): Tracker.Change = configure(expected,false,zone)

    /** Restore changes the barrier even when restoring a backup of the same dataset. */
    @Synchronized internal fun reconcileReplacement(view: TrackerView): TrackerView {
        val replacement = view.data.let { it.datasetId to it.controlBarrier }
        if(replacement == observedReplacement) return view
        reconcile()
        observedReplacement = replacement
        return tracker.view() // Publish the reconciled zone rather than the captured restore view.
    }

    /** Retry only the original token or acknowledge its observed result. Conflicts require a fresh explicit choice. */
    @Synchronized fun reconcile(): Tracker.Change? {
        if(unreadablePending) return null
        var recovered: Tracker.Change? = null
        pending?.let { intent ->
            try {
                val view = tracker.view();val data = view.data
                when {
                    view.snapshot().token == intent.expected -> recovered = tracker.configureReportingDay(intent.expected,
                        ReportingDayPolicy(intent.zone,intent.startMinute))
                    data.datasetId == intent.expected.datasetId && data.revision > intent.expected.revision &&
                        data.settings.reportZone == intent.zone && data.settings.reportDayStartMinute == intent.startMinute -> Unit
                    else -> {
                        recoveryProblem = "Выбор пояса не завершён, а настройки успели измениться. Проверь пояс и выбери его ещё раз"
                        return null
                    }
                }
                finish(intent)
            } catch(e: Exception) {
                if(e is java.util.concurrent.CancellationException) throw e
                recoveryProblem = recoveryProblem ?: "Не удалось завершить выбор пояса. Повтори выбор в настройках"
                return recovered
            }
        }
        if(!automatic) return recovered
        val view = tracker.view();val data = view.data;val zone = deviceZone()
        if(data.settings.reportZone == zone) return recovered
        return tracker.configureReportingDay(view.snapshot().token,ReportingDayPolicy(zone,data.settings.reportDayStartMinute))
    }
}
