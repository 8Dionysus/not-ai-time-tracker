package dev.notai.tracker.android.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.notai.tracker.*
import dev.notai.tracker.android.ExportOperations
import dev.notai.tracker.android.FocusSignals
import dev.notai.tracker.android.FocusDeliveryStatus
import dev.notai.tracker.android.SurfaceAction
import dev.notai.tracker.android.TrackerRuntime
import dev.notai.tracker.android.stageRestore
import dev.notai.tracker.android.CopyLifetimes
import dev.notai.tracker.android.RestoreReviewOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Shorter unrecorded stretches between two records are ordinary transitions, not worth a row. */
internal const val GAP_MIN_MS = 15 * 60_000L

/** What the picker does with the chosen activity. */
enum class PickMode { START, SWITCH, LABEL, EARLIER, MANUAL, FOCUS }

data class EntryFormTarget(val id: String,val entry: EntryUi?,val day: DayUi,
    val saving: Boolean = false,val error: String? = null,val reviewNonce: Long = 0,val gap: GapUi? = null)
/** A stretch of a day without a recording, between two recorded entries. Never a claim of inactivity. */
data class GapUi(val start: Long,val end: Long,val kind: HistoryGapKind)
/** Today's planned activity; starting it is an ordinary Start linked to this occurrence. */
data class PlanItem(val key: OccurrenceKey,val seriesRevision: Long,val overrideRevision: Long?,val title: String,val glyph: Int,val slot: Int?,
                    val time: String?,val started: Boolean)
data class PlanSeriesUi(val id: String,val revision: Long,val label: Label,val selection: Classification,val weekdays: Set<Int>,val date: LocalDate?,
                        val time: java.time.LocalTime?,val summary: String)
data class IncomingDraft(val id: String,val draft: EntryDraft)

data class Notice(val text: String, val undo: Token? = null, val id: Long = System.nanoTime())

data class EntryUi(val segmentId: String, val sessionId: String, val start: Long, val end: Long, val durationMs: Long,
                   val label: Label, val note: String?, val selection: Classification)
data class DayUi(val date: LocalDate, val start: Long, val end: Long, val recordedMs: Long, val incomplete: Boolean, val entries: List<EntryUi>,
                 val ribbon: List<RibbonSlot>, val live: List<RibbonSlot>, val gaps: List<GapUi> = emptyList(),
                 val sessions: List<SessionDayRow> = emptyList()) {
    /** One line per record of the day: its segments (newest last) and the pauses between them, all from the core projection. */
    val records: List<RecordUi> get() {
        val bySession = entries.groupBy { it.sessionId }
        return sessions.mapNotNull { row ->
            val parts = bySession[row.sessionId]?.sortedBy { it.start } ?: return@mapNotNull null
            RecordUi(row.sessionId, parts.last(), row.start, row.end, row.recordedMs, parts, row.pauses, row.mixedSelection)
        }
    }
}
/** [entry] is the latest segment: edit, repeat and delete act on the whole record through it, as before. */
data class RecordUi(val sessionId: String, val entry: EntryUi, val start: Long, val end: Long, val recordedMs: Long,
                    val segments: List<EntryUi>, val pauses: List<DraftInterval>, val mixed: Boolean)
data class QuickStart(val title: String, val slot: Int?, val entry: ChoiceEntry, val favorite: Boolean, val glyph: Int, val reference: ChoiceReference, val expected: Token)
data class HomeData(val today: LocalDate, val zone: ZoneId, val days: List<DayUi>, val quick: List<QuickStart>,
                    val currentLabel: Label?, val currentStart: Long?, val canLoadMore: Boolean, val focusMinutes: Long,
                    val plan: List<PlanItem> = emptyList())

data class PickerItem(val entry: ChoiceEntry, val label: Label, val count: Int? = null, val whole: Boolean = false,
                      val reference: ChoiceReference? = null, val expected: Token? = null)
internal fun PickerItem.wholeArea() = copy(label = label.copy(title = "Вся область",detail = label.title.lowercase()),whole = true)
data class PickerData(val recents: List<PickerItem>, val favorites: List<PickerItem>, val areas: List<PickerItem>, val moreAreas: List<PickerItem>)

enum class Period(val days: Int, val title: String) { DAY(1, "День"), WEEK(7, "7 дней"), MONTH(30, "30 дней") }
data class AreaTotal(val name: String, val slot: Int?, val ms: Long, val glyph: Int = ActivityGlyphs.custom, val id: String? = null, val deeper: Boolean = false)
/** One level of an area's time over the shown period: sections and activities below it, newest tree. */
data class Breakdown(val title: String, val totalMs: Long, val parts: List<AreaTotal>)
/** A saved report as Overview lists it, and its opened result: total, window and one line per own activity. */
data class SavedReportUi(val query: SavedReportQuery, val window: String, val totalMs: Long, val incomplete: Boolean,
    val parts: List<Pair<String, Long>>, val missing: Boolean, val filterLabel: Label?)
data class DayColumn(val date: LocalDate, val parts: List<Pair<Int?, Long>>, val totalMs: Long, val incomplete: Boolean)
data class GoalUi(val name: String, val value: String, val status: String, val ok: Boolean, val fraction: Float, val id: String = "")
/** What the goal form edits. The calendar (period, zone, day start) is fixed once a goal exists. */
data class GoalDraft(val id: String?,val name: String,val selection: Classification?,val label: Label?,val metric: GoalMetric,
                     val direction: GoalDirection,val period: GoalPeriod,val target: Long,
                     val originalFilter: Filter? = null,val activityChanged: Boolean = false)
data class OverviewData(val period: Period, val first: LocalDate, val last: LocalDate, val totalMs: Long, val incomplete: Boolean,
                        val differenceMs: Long?, val previousMs: Long?, val previousFirst: LocalDate, val previousLast: LocalDate, val areas: List<AreaTotal>,
                        val columns: List<DayColumn>, val ribbon: List<RibbonSlot>, val dayStart: Long, val dayEnd: Long,
                        val goals: List<GoalUi>, val unresolved: Int, val zone: ZoneId = ZoneId.of("UTC"))
data class RibbonSlot(val start: Long, val end: Long, val slot: Int?)

data class CorrectionDraft(val sessionId: String, val start: Long, val end: Long, val selection: Classification?, val note: String?)
data class CorrectionState(val preview: Tracker.CorrectionPreview?, val beforeMs: Long, val afterMs: Long, val error: String?,
                           val freeFrom: Long?, val freeUntil: Long?)

data class BackupInfo(val at: Long, val bytes: Long, val verified: Boolean)
/** A suggested everyday activity on first launch. */
data class Starter(val title: String, val entry: ChoiceEntry, val glyph: Int, val slot: Int?)
private val STARTERS = listOf(
    "activity.work.focused_work" to "Работа", "activity.learning.focused_study" to "Учёба", "activity.language.practice" to "Язык",
    "activity.reading.book" to "Чтение", "activity.writing.draft" to "Письмо", "activity.movement.strength" to "Силовая",
    "activity.movement.cardio" to "Кардио", "activity.leisure.outdoor_leisure" to "Прогулка", "activity.household.cook" to "Готовка",
    "activity.household.clean" to "Уборка", "activity.media.watch_film" to "Кино и сериалы", "activity.leisure.unstructured_rest" to "Отдых",
)

sealed interface DeleteRequest {
    data object Applied : DeleteRequest
    data object Failed : DeleteRequest
    /** Counts include retained correction rows, not only the currently effective set/value. */
    class Confirm(val preview: Tracker.DeletePreview, val range: String, val segments: Int, val measurements: Int) : DeleteRequest {
        val subjectLinks get() = preview.subjectLinkCount
        val exerciseSets get() = preview.exerciseSetCount
        val eventQuantities get() = preview.eventQuantityCount
        val distanceTimes get() = preview.distanceTimeCount
        val subjectCorrections get() = preview.subjectCorrectionCount
        val consequences get() = buildList {
            if(segments > 1) add(Format.plural(segments,"отрезок","отрезка","отрезков")+" между паузами")
            if(measurements > 0) add(Format.plural(measurements,"измерение","измерения","измерений"))
            if(subjectLinks > 0) add(Format.plural(subjectLinks,"связь с предметом","связи с предметами","связей с предметами"))
            if(exerciseSets > 0) add(Format.plural(exerciseSets,"запись подхода","записи подходов","записей подходов"))
            if(eventQuantities > 0) add(Format.plural(eventQuantities,"запись событийной величины","записи событийных величин","записей событийных величин"))
            if(distanceTimes > 0) add(Format.plural(distanceTimes,"основание темпа","основания темпа","оснований темпа"))
        }
    }
}
internal object DocumentExportCoordination {
    val lock = Any()
    val writing = mutableSetOf<String>()
    // Test-only suspension boundary after a durable commit; never set by product code.
    var afterClaimCommit: (() -> Unit)? = null
    var afterResultCommit: (() -> Unit)? = null
    var afterWriteComplete: (() -> Unit)? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerViewModel internal constructor(private val app: Application,private val waitForBoundary: suspend (Long) -> Unit) : AndroidViewModel(app), BatchDeletionUiHost, ReportingZoneUiHost, LauncherIconsUiHost {
    constructor(app: Application): this(app,{ delay(it) })
    private val standaloneRestoreReviews = RestoreReviewOwner<RestorePreview>(::releaseRestorePreviewOnIo)
    internal fun releaseRestorePreviewOnIo(preview: AutoCloseable) { CopyLifetimes.get(app).releasePreview(preview) }
    override fun onCleared() { standaloneRestoreReviews.close();super.onCleared() }
    sealed interface Boot { data object Loading : Boot; data class Ready(val backend: UiBackend) : Boot; data class Failed(val message: String) : Boot }

    private val _boot = MutableStateFlow<Boot>(Boot.Loading)
    val boot: StateFlow<Boot> = _boot
    private val runtime get() = (boot.value as? Boot.Ready)?.backend
    /** Wall time from the core clock, so every screen agrees with the tracker about "now". */
    fun now(): Long = runtime?.tracker?.clock?.now()?.wall ?: System.currentTimeMillis()
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _reportZoneAutomatic = MutableStateFlow(false)
    override val reportZoneAutomatic: StateFlow<Boolean> = _reportZoneAutomatic
    private val _reportZoneProblem = MutableStateFlow<String?>(null)
    val reportZoneProblem: StateFlow<String?> = _reportZoneProblem
    override fun useAutomaticReportZone() { setReportZoneAutomatic(true) }
    private val _launcherIcons = MutableStateFlow(LauncherIconsUiState(null,emptyList()))
    override val launcherIcons: StateFlow<LauncherIconsUiState> = _launcherIcons
    private val launcherMutex = kotlinx.coroutines.sync.Mutex()
    private val launcherChanging = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun launcherState(problem: String? = null): LauncherIconsUiState {
        val controller = dev.notai.tracker.android.LauncherIconController(app)
        val observed = controller.read()
        val options = controller.availableOptions().map { entry ->
            LauncherIconUiOption(entry.option.key,entry.option.label(app),entry.iconResource,entry.compatibleKeys)
        }
        return LauncherIconsUiState(observed.selected,options,launcherChanging.get(),problem ?: if(observed.selected == null)
            "Не удалось определить выбранный значок. Выбери доступный вариант" else null)
    }
    private fun refreshLauncherIcons() = viewModelScope.launch(Dispatchers.IO) {
        launcherMutex.withLock {
            if(!launcherChanging.get()) try { _launcherIcons.value = launcherState() }
            catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                _launcherIcons.value = _launcherIcons.value.copy(problem = "Не удалось проверить значок приложения")
            }
        }
    }
    override fun chooseLauncherIcon(key: String) {
        if(!launcherChanging.compareAndSet(false,true)) return
        _launcherIcons.update { it.copy(changing = true,problem = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                launcherMutex.withLock {
                    val result = dev.notai.tracker.android.LauncherIconController(app).select(key)
                    _launcherIcons.value = launcherState(if(result.shortcutsRebound) null else "Значок выбран, но ярлыки пока не обновились")
                }
            } catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                _launcherIcons.value = try { launcherState("Не удалось переключить значок") }
                    catch(_: Exception) { _launcherIcons.value.copy(selectedKey = null,problem = "Не удалось проверить результат переключения") }
            } finally {
                launcherChanging.set(false)
                _launcherIcons.update { it.copy(changing = false) }
            }
        }
    }

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices
    private fun notice(text: String, undo: Token? = null) { _notices.tryEmit(Notice(text, undo)) }

    private val _theme = MutableStateFlow(runCatching { ThemeChoice.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeChoice.SYSTEM))
    val theme: StateFlow<ThemeChoice> = _theme
    fun setTheme(choice: ThemeChoice) {
        val edit = prefs.edit().putString("theme", choice.name)
        if (choice in setOf(ThemeChoice.DARK, ThemeChoice.AMOLED, ThemeChoice.NEON)) edit.putString("lastDark", choice.name)
        edit.apply(); _theme.value = choice
    }
    /** The header moon/sun: back to the dark variant the person chose last (black or neon stay theirs). */
    fun toggleTheme(darkNow: Boolean) = setTheme(if (darkNow) ThemeChoice.LIGHT
        else runCatching { ThemeChoice.valueOf(prefs.getString("lastDark", "DARK")!!) }.getOrDefault(ThemeChoice.DARK))
    private val _appearance = MutableStateFlow(Appearance.read(prefs))
    val appearance: StateFlow<Appearance> = _appearance
    fun setAppearance(change: (Appearance) -> Appearance) { val next = change(_appearance.value); next.write(prefs); _appearance.value = next }

    private val _draft = MutableStateFlow<EntryDraft?>(null)
    /** Text another app explicitly sent. Nothing is written until the person picks and confirms. */
    val draft: StateFlow<EntryDraft?> = _draft
    private val draftLock = Any()
    private var currentDraftId: String? = null
    private val _incomingDrafts = MutableStateFlow<List<IncomingDraft>>(emptyList())
    val incomingDrafts: StateFlow<List<IncomingDraft>> = _incomingDrafts
    fun receiveDraft(d: EntryDraft) = synchronized(draftLock) {
        if(_draft.value == null) { currentDraftId = newId();_draft.value = d } else _incomingDrafts.value = _incomingDrafts.value+IncomingDraft(newId(),d)
    }
    fun resolveIncomingDraft(id: String,replace: Boolean) = synchronized(draftLock) {
        val incoming = _incomingDrafts.value.firstOrNull() ?: return@synchronized
        if(incoming.id != id) return@synchronized
        if(replace) { currentDraftId = incoming.id;_draft.value = incoming.draft }
        _incomingDrafts.value = _incomingDrafts.value.drop(1)
    }
    fun dropDraft() = synchronized(draftLock) {
        val next = _incomingDrafts.value.firstOrNull()
        currentDraftId = next?.id
        _draft.value = next?.draft
        if(next != null) _incomingDrafts.value = _incomingDrafts.value.drop(1)
    }
    private fun readDraft(): IncomingDraft? = synchronized(draftLock) { _draft.value?.let { IncomingDraft(requireNotNull(currentDraftId),it) } }
    private fun consumeDraft(id: String) = synchronized(draftLock) { if(currentDraftId == id) dropDraft() }
    // Retained ViewModel owns only the open form target. Editable fields use Compose saved
    // state; CorrectionPreview stays transient and is recomputed from current data.
    private val _entryForm = MutableStateFlow<EntryFormTarget?>(null)
    val entryForm: StateFlow<EntryFormTarget?> = _entryForm
    fun openEntryForm(entry: EntryUi?,day: DayUi,gap: GapUi? = null) { _entryForm.value = EntryFormTarget(newId(),entry,day,gap = gap) }
    fun closeEntryForm() { _entryForm.value = null }
    /** A result belongs to its form identity; an old completion cannot close a new editor. */
    fun submitEntryForm(id: String,start: Long,end: Long,selection: Classification,note: String?,preview: Tracker.CorrectionPreview?) {
        val target = _entryForm.value?.takeIf { it.id == id && !it.saving } ?: return
        if(!_entryForm.compareAndSet(target,target.copy(saving = true,error = null))) return
        val completed: (Result<Tracker.Change>) -> Unit = { result ->
            _entryForm.update { current ->
                if(current?.id != id) current
                else if(result.isSuccess) null
                else current.copy(saving = false,error = Format.error(requireNotNull(result.exceptionOrNull())),reviewNonce = current.reviewNonce+1)
            }
        }
        val gap = target.gap
        if(target.entry == null && gap != null && gap.start == start && gap.end == end) recordGap(gap,selection,note,completed)
        else if(target.entry == null) manual(start,end,selection,note,completed)
        else if(preview != null) applyCorrection(preview,completed)
        else completed(Result.failure(CoreError(ErrorCode.STALE,"Correction needs a current preview")))
    }

    init { load() }
    fun load() {
        _boot.value = Boot.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _boot.value = try { Boot.Ready(UiBackends.open(app).also {
                _reportZoneAutomatic.value = it.reportZone?.automatic ?: false
                _reportZoneProblem.value = it.reportZone?.recoveryProblem
            }) }
            catch (e: Exception) { Boot.Failed(e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Committed revisions only. The UI keeps one view until the next emission. */
    val view: StateFlow<TrackerView?> = boot.flatMapLatest { b -> (b as? Boot.Ready)?.backend?.tracker?.views ?: flowOf<TrackerView?>(null) }
        .catch { e -> notice(Format.error(e)); emit(null) }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Display ticks: once a second only while something visible is counting, and only while collected. */
    val snapshot: StateFlow<Snapshot?> = view.flatMapLatest { v ->
        if (v == null) flowOf<Snapshot?>(null) else flow<Snapshot?> {
            while (true) {
                val s = v.snapshot(); emit(s)
                if (!s.ticks()) break
                delay(1000L - System.currentTimeMillis() % 1000L + 5)
            }
        }
    }.onEach { s: Snapshot? -> s?.focus?.let { f -> runtime?.focusDue?.consume(f) { due -> deliverFocus(due) } } }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun deliverFocus(due: FocusSnapshot) {
        val tracker = runtime?.tracker ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = FocusSignals.deliverResult(app,tracker)
                val current = result.focus
                if(result.status == FocusDeliveryStatus.BLOCKED_BEFORE_POST && current?.mode == FocusMode.DUE && current.signalKey == due.signalKey)
                    notice(current.safeLabel)
            } catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                notice(Format.error(e))
            }
        }
    }

    private val shownDays = MutableStateFlow(7)
    fun showMoreDays() { shownDays.update { minOf(it + 14, 62) } }

    /** Display invalidation only; report-day meaning comes from the core policy. It stays live
     * while an idle home/overview is collected, without writing timer or Dataset state. */
    val reportingDate: StateFlow<LocalDate?> = view.flatMapLatest { v ->
        if(v == null) flowOf<LocalDate?>(null) else flow<LocalDate?> {
            val policy = ReportingDayPolicy(v.data.settings.reportZone,v.data.settings.reportDayStartMinute)
            while(true) {
                val wall = now();val date = policy.dateAt(wall);emit(date)
                val next = runCatching { policy.boundary(date.plusDays(1)) }.getOrNull()
                waitForBoundary(next?.let { (it-wall).coerceIn(1L,60_000L) } ?: 60_000L)
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),null)

    /** History by report day. Revision/date changes invalidate idle data; open sessions also tick. */
    val home: StateFlow<HomeData?> = combine(view, shownDays, reportingDate) { v, n, _ -> v to n }.flatMapLatest { (v, n) ->
        if (v == null) flowOf<HomeData?>(null) else flow<HomeData?> {
            while (true) {
                emit(homeData(v, n))
                if (current(v.data) == null) break
                delay(60_000)
            }
        }
    }.catch { e -> notice(Format.error(e)) }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun current(d: Dataset) = d.sessions.firstOrNull { it.status != SessionStatus.STOPPED }

    /** Core quick source (favourites, then frequency in the trailing 30 days); the notification shows the same list. */
    private fun quickChoices(v: TrackerView, limit: Int = 6): List<QuickChoice> {
        val end = minOf(MAX_TIME, now() + 1)
        return v.choices().quick(HistoryWindow(maxOf(0L, end - 30L * 24 * 60 * 60 * 1000), end), limit, current(v.data)?.selection)
    }
    private fun QuickChoice.toQuickStart(d: Dataset,expected: Token): QuickStart {
        val l = d.label(entry.selection)
        return QuickStart(if (entry.kind == ChoiceKind.FAVORITE) entry.title else l.title, l.slot, entry, entry.kind == ChoiceKind.FAVORITE, l.glyph, reference,expected)
    }

    private fun homeData(v: TrackerView, days: Int): HomeData {
        val d = v.data
        val zone = ZoneId.of(d.settings.reportZone)
        val today = ReportingDayPolicy(d.settings.reportZone,d.settings.reportDayStartMinute).dateAt(now())
        val calendar = v.calendar(today.minusDays(days - 1L), days)
        val open = current(d)
        val notes = d.sessions.associate { it.id to it.note }
        // One review for the whole window. Only stretches between two recorded entries of the same day are offered:
        // the night or the hours before the first record are not "missing", they are simply not recorded.
        val review = calendar.days.firstOrNull()?.let { first -> runCatching { v.historyGaps(first.start, calendar.days.last().end) }.getOrNull() }
        val dayList = calendar.days.asReversed().mapNotNull { day ->
            val entries = day.rows.filter { it.sessionId != open?.id }.sortedByDescending { it.start }.map { r ->
                EntryUi(r.segmentId, r.sessionId, r.start, r.end, r.durationMs, d.label(r.selection), r.note ?: notes[r.sessionId], r.selection)
            }
            val lo = day.rows.minOfOrNull { it.start }; val hi = day.rows.maxOfOrNull { it.end }
            val gaps = if (lo == null || hi == null) emptyList() else review?.gaps.orEmpty().filter { g ->
                g.start >= lo && g.end <= hi && g.start >= day.start && g.end <= day.end &&
                    (g.kind == HistoryGapKind.RECOVERY_UNCERTAIN || g.end - g.start >= GAP_MIN_MS)
            }.map { GapUi(it.start, it.end, it.kind) }
            if (entries.isEmpty() && day.date != today && day.recordedMs == 0L) null
            else DayUi(day.date, day.start, day.end, day.recordedMs, day.incomplete, entries,
                entries.map { RibbonSlot(it.start, it.end, it.label.slot) },
                day.rows.filter { it.sessionId == open?.id }.map { RibbonSlot(it.start, it.end, null) }, gaps,
                day.copy(rows = day.rows.filter { it.sessionId != open?.id }).sessionRows(d))
        }
        // Same bounded source as the notification: favourites, then the most frequent of the last 30 days.
        // Every favourite (up to twelve) plus frequent ones to a minimum of five.
        val quick = quickChoices(v, d.favorites.size.coerceIn(5, 12)).map { q -> q.toQuickStart(d,v.snapshot().token) }
        return HomeData(today, zone, dayList, quick, open?.let { d.label(it.selection) }, open?.let { s -> d.segments.filter { it.sessionId == s.id }.minOfOrNull { it.start } },
            // Older days only when something older exists: a new install has nothing to load.
            days < 62 && (d.segments.minOfOrNull { it.start } ?: Long.MAX_VALUE) < (calendar.days.firstOrNull()?.start ?: Long.MIN_VALUE),
            d.settings.focusPlan.workMs / 60_000L, todayPlan(v))
    }

    // ---------- plan for days ----------

    private fun deviceZone(): ZoneId = ZoneId.systemDefault()

    /** Today's occurrences only. Seeing the plan never creates a fact; skipped ones simply disappear. */
    private fun todayPlan(v: TrackerView): List<PlanItem> {
        if (v.data.schedule.series.none { it.state == ScheduleState.ACTIVE }) return emptyList()
        val state = runCatching { v.scheduleState(deviceZone()) }.getOrNull() ?: return emptyList()
        return state.occurrences.filter { it.date == state.day.date && it.state in setOf(OccurrenceState.TODAY, OccurrenceState.MOVED, OccurrenceState.STARTED) }
            .map { p ->
                val l = v.data.label(p.selection)
                PlanItem(p.key, p.seriesRevision, p.overrideRevision, l.title, l.glyph, l.slot,
                    p.localTime?.let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" }, p.state == OccurrenceState.STARTED)
            }
    }

    /** Every schedule change goes through the durable workspace when the app runtime exists (a lost reply repeats the same key). */
    private fun schedule(rt: UiBackend, command: ScheduleCommand): ScheduleCommandResult {
        val workspace = rt.runtime?.schedule ?: return rt.tracker.scheduleCommand(command)
        val draft = workspace.prepare(command)
        return workspace.execute(draft.id, draft.revision)
    }
    private fun planCommand(success: String?, block: (UiBackend, Snapshot) -> Unit) {
        val rt = runtime ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { block(rt, rt.tracker.snapshot()); success?.let { notice(it) } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice(Format.error(e)) }
        }
    }

    /** Idle: an ordinary Start linked to the occurrence. Tracking: the current record ends now, then the planned one starts. */
    fun startPlanned(item: PlanItem) = planCommand(null) { rt, s ->
        ensure(s.focus == null, "Finish Focus first")
        schedule(rt, ScheduleCommand(newId(), s.token, ScheduleAction.START_OR_SWITCH, item.key.seriesId, item.seriesRevision,
            occurrence = item.key, expectedOverrideRevision = item.overrideRevision, deviceZone = deviceZone().id))
    }
    fun skipPlanned(item: PlanItem) = planCommand("Убрано из плана на сегодня") { rt, s ->
        schedule(rt, ScheduleCommand(newId(), s.token, ScheduleAction.OVERRIDE, item.key.seriesId, item.seriesRevision,
            occurrence = item.key, override = OccurrenceOverride(item.key, OverrideOperation.SKIP), expectedOverrideRevision = item.overrideRevision,
            deviceZone = deviceZone().id))
    }

    fun planSeries(v: TrackerView?): List<PlanSeriesUi> {
        val d = v?.data ?: return emptyList()
        return d.schedule.series.filter { it.state == ScheduleState.ACTIVE }.mapNotNull { s ->
            val rule = s.revisions.lastOrNull()?.rule ?: return@mapNotNull null
            val once = rule.weekdays.isEmpty() && rule.extraDates.size == 1
            val date = if (once) LocalDate.parse(rule.extraDates.single()) else null
            val time = rule.localTime?.let(java.time.LocalTime::parse)
            PlanSeriesUi(s.id, s.revision, d.label(rule.selection), rule.selection, rule.weekdays, date, time, Format.planRule(rule.weekdays, date, time))
        }.sortedWith(compareBy({ it.date ?: LocalDate.MIN }, { it.time ?: java.time.LocalTime.MAX }, { it.label.title }))
    }
    fun scheduleToday(): LocalDate = view.value?.scheduleDay(deviceZone())?.date ?: LocalDate.now(deviceZone())

    /** Weekdays (1 = Monday) or one date; time is optional. A changed weekly plan applies from today on, earlier days keep their meaning. */
    fun savePlan(existing: PlanSeriesUi?, selection: Classification, weekdays: Set<Int>, date: LocalDate?, time: java.time.LocalTime?) =
        planCommand(if (existing == null) "Добавлено в план" else "План изменён") { rt, s ->
            val zone = deviceZone(); val today = rt.tracker.scheduleDay(zone).date
            val once = date != null
            val start = (date ?: today).toString()
            val rule = ScheduleRule(selection, start, if (once) emptySet() else weekdays, if (once) setOf(start) else emptySet(),
                time?.toString(), until = if (once) start else null)
            if (existing == null) { schedule(rt, ScheduleCommand(newId(), s.token, ScheduleAction.CREATE, newId(), rule = rule, deviceZone = zone.id)); return@planCommand }
            val series = rt.tracker.data().schedule.series.single { it.id == existing.id }
            val weekly = !once && existing.date == null
            if (weekly) {
                val from = maxOf(today, LocalDate.parse(series.revisions.last().effectiveFrom))
                val kept = rule.copy(startDate = series.revisions.first().rule.startDate)
                schedule(rt, ScheduleCommand(newId(), s.token, ScheduleAction.UPDATE_FUTURE, existing.id, existing.revision, rule = kept,
                    effectiveFrom = from.toString(), deviceZone = zone.id))
            } else {
                // Archive and replacement share the same transaction and replay receipt.
                schedule(rt, ScheduleCommand(newId(), s.token, ScheduleAction.REPLACE, existing.id, existing.revision,
                    rule = rule, deviceZone = zone.id, replacementSeriesId = newId()))
            }
        }
    fun removePlan(existing: PlanSeriesUi) = planCommand("Убрано из плана") { rt, s ->
        schedule(rt, ScheduleCommand(newId(), s.token, ScheduleAction.SET_STATE, existing.id, existing.revision, state = ScheduleState.ARCHIVED, deviceZone = deviceZone().id))
    }

    /** Renderer-facing backend state; Compose screens are supplied separately by Claude. */
    private val _surfacePicker = MutableStateFlow<dev.notai.tracker.android.SurfacePickerState?>(null)
    val surfacePicker: StateFlow<dev.notai.tracker.android.SurfacePickerState?> = _surfacePicker
    private val pickerLock = Any()
    @Volatile private var pickerRoute: dev.notai.tracker.android.SurfacePickerRoute? = null
    fun surfacePickerRoute() = pickerRoute
    fun receiveSurfacePicker(route: dev.notai.tracker.android.SurfacePickerRoute?) {
        synchronized(pickerLock) { pickerRoute = route; _surfacePicker.value = null }
        viewModelScope.launch(Dispatchers.IO) {
            val rt = waitRuntime() ?: return@launch
            try {
                val next = route?.let { dev.notai.tracker.android.SurfaceChoices.read(rt.tracker,it) }
                synchronized(pickerLock) { if(pickerRoute == route) _surfacePicker.value = next }
            } catch(e: Exception) { if(clearSurfacePicker(route)) notice(Format.error(e)) }
        }
    }
    private fun clearSurfacePicker(route: dev.notai.tracker.android.SurfacePickerRoute?) = synchronized(pickerLock) {
        if(pickerRoute != route) false else { pickerRoute = null; _surfacePicker.value = null; true }
    }
    fun chooseSurface(reference: ChoiceReference,note: String? = null,expectedRoute: dev.notai.tracker.android.SurfacePickerRoute? = null) {
        val route = pickerRoute ?: return
        if(expectedRoute != null && route != expectedRoute) { notice("Выбор занятия устарел"); return }
        command({ "Записано" }) { rt,_ ->
            dev.notai.tracker.android.SurfaceChoices.choose(app,rt.tracker,route,reference,note).also { clearSurfacePicker(route) }
        }
    }
    /** Launcher favourite: the route is taken without loading the picker; the choice applies once with that route's target. */
    fun chooseFromShortcut(route: dev.notai.tracker.android.SurfacePickerRoute, reference: ChoiceReference) {
        synchronized(pickerLock) { pickerRoute = route; _surfacePicker.value = null }
        chooseSurface(reference, expectedRoute = route)
    }
    /** Create in the same route: an assignment stays an assignment, never a new timer. */
    fun createSurfaceCard(route: dev.notai.tracker.android.SurfacePickerRoute,name: String,activityTypeId: String? = null) {
        val trimmed = name.trim().replaceFirstChar { it.uppercase() }
        if(trimmed.isEmpty() || pickerRoute != route) return
        command({ "Занятие «$trimmed» создано" }) { rt,s ->
            val card = Card(name = trimmed,activityTypeId = activityTypeId)
            dev.notai.tracker.android.SurfaceChoices.createAndChoose(app,rt.tracker,route,s.token,card).also { clearSurfacePicker(route) }
        }
    }
    fun cancelSurfacePicker() {
        val route = pickerRoute
        if(route?.assignmentId == null) { clearSurfacePicker(route); return }
        command { rt,_ -> rt.tracker.cancelAssignment(route.assignmentId).also { clearSurfacePicker(route) } }
    }
    fun reopenStoppedAssignment(sessionId: String,source: dev.notai.tracker.android.Surface) {
        val rt = runtime ?: return
        val shown = snapshot.value ?: view.value?.snapshot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { receiveSurfacePicker(dev.notai.tracker.android.SurfaceChoices.reopen(app,rt.tracker,source,shown.token,sessionId)) }
            catch(e: Exception) { notice(Format.error(e)) }
        }
    }
    suspend fun pendingStoppedAssignments(): List<StoppedAssignment> = withContext(Dispatchers.IO) { waitRuntime()?.tracker?.pendingAssignments().orEmpty() }
    private val _capabilities = MutableStateFlow<dev.notai.tracker.android.SettingsCapabilities?>(null)
    val capabilities: StateFlow<dev.notai.tracker.android.SettingsCapabilities?> = _capabilities
    private val _capabilityProblem = MutableStateFlow<dev.notai.tracker.android.SurfaceProblem?>(null)
    val capabilityProblem: StateFlow<dev.notai.tracker.android.SurfaceProblem?> = _capabilityProblem
    private val _widgetRequest = MutableStateFlow<dev.notai.tracker.android.WidgetState?>(null)
    val widgetRequest: StateFlow<dev.notai.tracker.android.WidgetState?> = _widgetRequest
    init {
        viewModelScope.launch(Dispatchers.IO) {
            boot.flatMapLatest { ready -> (ready as? Boot.Ready)?.backend?.tracker?.views ?: emptyFlow() }.collect {
                val route = pickerRoute
                if(route != null) receiveSurfacePicker(route)
                refreshCapabilities()
            }
        }
    }
    fun refreshCapabilities(reconcileForeground: Boolean = false) {
        refreshLauncherIcons()
        viewModelScope.launch(Dispatchers.IO) {
            val rt = waitRuntime() ?: return@launch
            try {
                if(reconcileForeground) rt.reportZone?.reconcile()
                _reportZoneAutomatic.value = rt.reportZone?.automatic ?: false
                _reportZoneProblem.value = rt.reportZone?.recoveryProblem
                _capabilities.value = dev.notai.tracker.android.SurfaceSettingsBackend.read(app,rt.tracker.data().settings.surfaces,reconcileForeground)
                _capabilityProblem.value = null
            } catch(e: Exception) { _capabilityProblem.value = dev.notai.tracker.android.SurfaceProblem.PLATFORM_FAILURE; notice(Format.error(e)) }
        }
    }
    /** System confirmation remains mandatory; this never changes the widget control preference. */
    fun requestWidgetPlacement(activity: android.app.Activity): dev.notai.tracker.android.WidgetState {
        val result = dev.notai.tracker.android.WidgetPlacementController(app).request(activity)
        _widgetRequest.value = result
        refreshCapabilities()
        return result
    }
    fun setSurfaceEnabled(surface: dev.notai.tracker.android.Surface,enabled: Boolean) = setSurfaces { settings -> when(surface) {
        dev.notai.tracker.android.Surface.WIDGET -> settings.copy(widget = enabled)
        dev.notai.tracker.android.Surface.NOTIFICATION -> settings.copy(notification = enabled)
        dev.notai.tracker.android.Surface.OVERLAY -> settings.copy(overlay = enabled)
        dev.notai.tracker.android.Surface.SHORTCUT -> settings.copy(shortcut = enabled)
    } }
    suspend fun previewDeleteSession(id: String): Tracker.DeletePreview? = withContext(Dispatchers.IO) {
        val rt = waitRuntime() ?: return@withContext null
        rt.tracker.previewDeleteSession(id)
    }
    fun applyDeleteSession(preview: Tracker.DeletePreview) = command({ "Запись удалена" }) { rt,_ -> rt.tracker.applyDeleteSession(preview) }

    /**
     * One entrance for the row menu, swipe and the entry sheet. A single-segment record without dependent facts is
     * deleted at once (Undo stays available); anything larger returns the preview for an explicit confirmation.
     */
    suspend fun requestDelete(sessionId: String): DeleteRequest {
        val preview = try { previewDeleteSession(sessionId) } catch (e: Exception) {
            notice(if ((e as? CoreError)?.code == ErrorCode.STALE) Format.error(e) else "Удалить можно только завершённую запись. Идущая запись и текущий Фокус не удаляются.")
            return DeleteRequest.Failed
        } ?: return DeleteRequest.Failed
        if (preview.segmentCount > 1 || preview.hasDependentFacts) {
            val zone = ZoneId.of(preview.before.settings.reportZone)
            val segments = preview.before.segments.filter { it.sessionId == sessionId }
            val first = segments.minOf { it.start }
            val last = segments.mapNotNull { it.end }.maxOrNull() ?: first
            return DeleteRequest.Confirm(preview, Format.range(first, last, zone), preview.segmentCount, preview.measurementCount)
        }
        applyDeleteSession(preview)
        return DeleteRequest.Applied
    }

    // ---------- commands ----------

    private fun command(success: ((Tracker.Change) -> String?)? = null,completed: ((Result<Tracker.Change>) -> Unit)? = null,block: (UiBackend, Snapshot) -> Tracker.Change) {
        val rt = runtime
        val shown = snapshot.value ?: view.value?.snapshot()
        if(rt == null || shown == null) {
            completed?.invoke(Result.failure(CoreError(ErrorCode.STALE,"Command needs a current view")));return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try { Result.success(block(rt,shown)) }
            catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;Result.failure(e) }
            completed?.invoke(result)
            result.fold(onSuccess = { change -> success?.invoke(change)?.let { notice(it,if(change.undoAvailable) change.state.token else null) } },
                onFailure = { notice(Format.error(it)) })
        }
    }

    fun startBlank() = command { rt, s -> rt.tracker.start(s.token) }
    fun pause() = timer(TimerDirective.PAUSE)
    fun resume() = timer(TimerDirective.RESUME)
    fun stop() = command({ c -> "Записано" }) { rt, s ->
        val target = s.timerTarget ?: fail(ErrorCode.STALE, "Timer target unavailable")
        rt.tracker.timerCommand(target, TimerDirective.STOP)
    }
    private fun timer(directive: TimerDirective) = command { rt, s ->
        rt.tracker.timerCommand(s.timerTarget ?: fail(ErrorCode.STALE, "Timer target unavailable"), directive)
    }
    fun undo(token: Token) = command({ "Отменено" }) { rt, _ -> rt.tracker.undo(token) }

    /** One decision point for every picked activity, so Start, Switch and labelling stay explicit. */
    fun choose(entry: ChoiceEntry, mode: PickMode, earlierBoundary: Long? = null,reference: ChoiceReference? = null,expected: Token? = null) =
        command({ if (mode == PickMode.SWITCH) "Новая запись начата" else null }) { rt, _ ->
            val shown = reference ?: fail(ErrorCode.STALE,"Choice needs its displayed reference")
            val view = rt.tracker.view();val state = view.snapshot()
            ensure(expected == state.token,"Displayed choice revision changed",ErrorCode.STALE)
            val resolved = view.choices().resolve(shown)
            ensure(resolved == entry,"Displayed choice changed",ErrorCode.STALE)
            applyChoice(rt,state,resolved.selection,resolved,mode,earlierBoundary)
        }

    private fun applyChoice(rt: UiBackend, s: Snapshot, selection: Classification, entry: ChoiceEntry?, mode: PickMode, boundary: Long?): Tracker.Change {
        val t = rt.tracker
        val draftInput = readDraft();val draft = draftInput?.draft
        val idle = s.mode == Mode.IDLE && s.focus == null
        return when {
            mode == PickMode.EARLIER -> t.switchedEarlier(s.token, boundary ?: fail(ErrorCode.INVALID_INPUT, "Boundary missing"), selection)
            mode == PickMode.FOCUS -> t.startFocus(s.token, selection)
            mode == PickMode.LABEL -> t.labelCurrent(s.token, selection, s.session?.note)
            draft != null && idle -> t.startDraft(s.token, draft.copy(selection = selection)).also { consumeDraft(requireNotNull(draftInput).id) }
            idle && entry?.kind == ChoiceKind.FAVORITE -> t.startFavorite(s.token, entry.sourceId)
            idle && entry?.kind == ChoiceKind.HISTORY -> t.repeatSegment(s.token,requireNotNull(entry.sourceSessionId),requireNotNull(entry.sourceSegmentId))
            idle -> t.start(s.token, selection)
            draft != null -> t.switchDraft(s.token,draft.copy(selection = selection)).also { consumeDraft(requireNotNull(draftInput).id) }
            entry?.kind == ChoiceKind.HISTORY -> t.repeatSegment(s.token,requireNotNull(entry.sourceSessionId),requireNotNull(entry.sourceSegmentId),ChoiceApplication.SWITCH)
            else -> {
                val change = t.switch(s.token, selection)
                change
            }
        }
    }

    /** Create and apply atomically; a refused Start/Switch/Label/Focus/Earlier leaves no card. */
    fun createAndChoose(name: String, mode: PickMode, earlierBoundary: Long? = null, activityTypeId: String? = null) {
        val trimmed = name.trim().replaceFirstChar { it.uppercase() }
        if (trimmed.isEmpty()) return
        command({ "Занятие «$trimmed» создано" }) { rt, s ->
            val card = Card(name = trimmed, activityTypeId = activityTypeId)
            val t = rt.tracker
            when(mode) {
                PickMode.EARLIER -> t.createCardForEarlierSwitch(s.token,card,earlierBoundary ?: fail(ErrorCode.INVALID_INPUT,"Boundary missing"))
                PickMode.FOCUS -> t.createCardForFocus(s.token,card)
                PickMode.LABEL -> t.createCardForLabel(s.token,card,s.session?.note)
                else -> {
                    val input = readDraft()
                    val application = if(s.mode == Mode.IDLE && s.focus == null) ChoiceApplication.START else ChoiceApplication.SWITCH
                    t.createCardForChoice(s.token,s.timerTarget ?: fail(ErrorCode.STALE,"Timer target unavailable"),card,application,draft = input?.draft)
                        .also { input?.let { consumeDraft(it.id) } }
                }
            }
        }
    }

    /** Saves a personal activity for a form that is still open; nothing is started. */
    fun createCard(name: String, activityTypeId: String? = null, onCreated: (Classification, Label) -> Unit) {
        val trimmed = name.trim().replaceFirstChar { it.uppercase() }
        if (trimmed.isEmpty()) return
        val rt = runtime ?: return
        val shown = snapshot.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val card = Card(name = trimmed, activityTypeId = activityTypeId)
                val change = rt.tracker.saveCard(shown.token, card)
                val label = change.data.label(card.classification())
                withContext(Dispatchers.Main) { onCreated(card.classification(), label) }
            } catch (e: Exception) { notice(Format.error(e)) }
        }
    }

    fun startDraftBlank() = command { rt, s ->
        val draftInput = readDraft() ?: fail(ErrorCode.INVALID_INPUT, "Draft missing");val d = draftInput.draft
        rt.tracker.startDraft(s.token, d.copy(selection = Classification())).also { consumeDraft(draftInput.id) }
    }

    /** History row's play button: repeat when idle, switch to it while tracking. */
    fun repeat(entry: EntryUi) = command({ c -> null }) { rt, s ->
        val data = rt.tracker.data()
        val segment = data.segments.singleOrNull { it.id == entry.segmentId && it.sessionId == entry.sessionId }
        val source = data.sessions.singleOrNull { it.id == entry.sessionId }
        ensure(segment?.selection == entry.selection && source != null && source.note == entry.note,"Shown history entry changed",ErrorCode.STALE)
        rt.tracker.repeatSegment(s.token,entry.sessionId,entry.segmentId,if(s.mode == Mode.IDLE && s.focus == null) ChoiceApplication.START else ChoiceApplication.SWITCH)
    }

    fun resolveTail(start: Long, end: Long) = command({ "Время уточнено" }) { rt, s ->
        if (s.focus != null) rt.tracker.resolveFocus(s.token, start, end) else rt.tracker.resolveTail(s.token, start, end)
    }
    fun excludeTail() = command({ "Неизвестный отрезок не учтён" }) { rt, s ->
        if (s.focus != null) rt.tracker.resolveFocus(s.token, null, null) else rt.tracker.resolveTail(s.token, null, null)
    }
    fun startFocus() = command { rt, s -> rt.tracker.startFocus(s.token) }
    /** Save the lengths, then start with the token that save returned: two explicit steps, never a guessed state. */
    fun configureAndStartFocus(workMin: Int, breakMin: Int) = command { rt, s ->
        val saved = rt.tracker.configureFocus(s.token, FocusPlan(workMin * 60_000L, breakMin * 60_000L))
        rt.tracker.startFocus(saved.state.token)
    }
    fun focusNext() = command { rt, s -> rt.tracker.focusNext(s.token) }
    fun focusSkip() = command { rt, s -> rt.tracker.focusSkip(s.token) }
    fun configureFocus(workMin: Int, breakMin: Int) = command({ "Отрезки изменены" }) { rt, s ->
        rt.tracker.configureFocus(s.token, FocusPlan(workMin * 60_000L, breakMin * 60_000L))
    }

    fun saveFavorite(title: String, selection: Classification) = command({ "Добавлено в избранное" }) { rt, s ->
        rt.tracker.addFavorite(s.token, Favorite(label = title, selection = selection))
    }
    fun removeFavorite(id: String) = command({ "Убрано из избранного" }) { rt, s -> rt.tracker.removeFavorite(s.token, id) }

    /** Favourites in their saved order (personal order first, then as added). */
    fun favorites(v: TrackerView?): List<FavoriteUi> {
        val d = v?.data ?: return emptyList()
        return d.favorites.withIndex().sortedWith(compareBy({ it.value.personal.order ?: Int.MAX_VALUE }, { it.index })).map { (_, f) ->
            FavoriteUi(f.id, f.label, d.label(f.selection), f.selection)
        }
    }
    /** Every favourite pointing at this activity: one tap on a filled star removes them all (old duplicates included). */
    fun favoriteIds(selection: Classification): List<String> {
        val key = selectionIdentity(selection)
        return view.value?.data?.favorites.orEmpty().filter { selectionIdentity(it.selection) == key }.map { it.id }
    }
    fun removeFavorites(ids: List<String>) = command({ if (ids.size > 1) "Убрано из избранного вместе с повторами" else "Убрано из избранного" }) { rt, s ->
        var token = s.token; var last: Tracker.Change? = null
        ids.forEach { id -> last = rt.tracker.removeFavorite(token, id).also { token = it.state.token } }
        last ?: fail(ErrorCode.STALE, "Nothing to remove")
    }
    private val _editor = MutableStateFlow<EditTarget?>(null)
    val editor: StateFlow<EditTarget?> = _editor
    fun openEditor(target: EditTarget?) { _editor.value = target }
    private val _favoritesOpen = MutableStateFlow(false)
    val favoritesOpen: StateFlow<Boolean> = _favoritesOpen
    fun openFavorites(open: Boolean) { _favoritesOpen.value = open }

    /** The displayed revision is required: never silently apply a reorder/edit to a newer list. */
    fun orderFavorites(expected: Token, ids: List<String>, completed: ((Result<Tracker.Change>) -> Unit)? = null) =
        command({ "Порядок сохранён" }, completed) { rt,_ -> rt.tracker.orderChoices(expected, ChoiceKind.FAVORITE, ids) }
    fun editFavorite(expected: Token, favorite: Favorite, completed: ((Result<Tracker.Change>) -> Unit)? = null) =
        command({ "Избранное изменено" }, completed) { rt,_ ->
            ensure(rt.tracker.data().favorites.any { it.id == favorite.id }, "Favorite no longer exists", ErrorCode.STALE)
            rt.tracker.saveFavorite(expected, favorite)
        }
    fun saveCard(expected: Token, card: Card, completed: ((Result<Tracker.Change>) -> Unit)? = null) =
        command({ "Занятие сохранено" }, completed) { rt,_ -> rt.tracker.saveCard(expected, card) }
    fun saveType(expected: Token, type: ActivityType, completed: ((Result<Tracker.Change>) -> Unit)? = null) =
        command({ "Категория сохранена" }, completed) { rt,_ -> rt.tracker.saveType(expected, type) }

    private val _focusEditor = MutableStateFlow<FocusPlan?>(null)
    val focusEditor: StateFlow<FocusPlan?> = _focusEditor.asStateFlow()
    /** Defaults/next phase only; configureFocus preserves the current phase's duration. */
    fun openFocusEditor() { _focusEditor.value = view.value?.data?.settings?.focusPlan }
    fun closeFocusEditor() { _focusEditor.value = null }
    private val _correctionStepMinutes = MutableStateFlow(1)
    val correctionStepMinutes: StateFlow<Int> = _correctionStepMinutes.asStateFlow()
    fun setCorrectionStepMinutes(minutes: Int) {
        require(minutes in listOf(1, 5, 15))
        _correctionStepMinutes.value = minutes
    }
    fun stepCorrectionTime(value: Long, forward: Boolean): Long =
        (value + (if(forward) 1 else -1) * _correctionStepMinutes.value * 60_000L).coerceIn(0L, MAX_TIME)

    // ---------- first launch: a few own activities for one-tap start ----------

    private val _starterDismissed = MutableStateFlow(prefs.getBoolean("starterDismissed", false))
    val starterDismissed: StateFlow<Boolean> = _starterDismissed
    fun dismissStarter() { prefs.edit().putBoolean("starterDismissed", true).apply(); _starterDismissed.value = true }

    /** Everyday catalog types with short names; only those present and startable in this catalog are offered. */
    suspend fun starterChoices(): List<Starter> = withContext(Dispatchers.IO) {
        val v = view.value ?: waitRuntime()?.tracker?.view() ?: return@withContext emptyList()
        val d = v.data; val choices = v.choices()
        STARTERS.mapNotNull { (id, title) ->
            val entry = runCatching { choices.resolve("type:$id") }.getOrNull()?.takeIf { it.canStart } ?: return@mapNotNull null
            val l = d.label(entry.selection)
            Starter(title, entry, l.glyph, l.slot)
        }
    }

    /** One explicit "Done": each favourite is written with the token of the change before it, never a silent retry. */
    fun saveStarters(picked: List<Starter>) {
        val rt = runtime ?: return
        val shown = snapshot.value ?: view.value?.snapshot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var token = shown.token
                picked.forEach { st -> token = rt.tracker.addFavorite(token, Favorite(label = st.title, selection = st.entry.selection)).state.token }
                notice(if (picked.size == 1) "«${picked.single().title}» — теперь старт в одно касание" else "Готово: ${Format.plural(picked.size, "занятие", "занятия", "занятий")} для быстрого старта")
            } catch (e: Exception) { notice(Format.error(e)) }
        }
    }

    // ---------- picker ----------

    suspend fun pickerData(): PickerData = withContext(Dispatchers.IO) {
        val v = view.value ?: runtime?.tracker?.view() ?: return@withContext PickerData(emptyList(), emptyList(), emptyList(), emptyList())
        val d = v.data; val choices = v.choices()
        val recents = choices.suggestions(SuggestionQuery(enabled = true), pageSize = 5).items.map { PickerItem(it.entry, d.label(it.entry.selection),reference = choices.reference(it.entry),expected = v.snapshot().token) }
        val favoriteQuery = ChoiceQuery(kinds = setOf(ChoiceKind.FAVORITE), includeArchived = true)
        val favorites = generateSequence(choices.search(favoriteQuery, pageSize = 100)) { page ->
            page.next?.let { choices.search(favoriteQuery, it, 100) }
        }.flatMap { it.items }.map { PickerItem(it.entry, d.label(it.entry.selection),reference = choices.reference(it.entry),expected = v.snapshot().token) }.toList()
        val roots = generateSequence(choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.TYPE), scope = CatalogScope.ROOTS), pageSize = 100)) { p ->
            p.next?.let { choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.TYPE), scope = CatalogScope.ROOTS), it, 100) }
        }.flatMap { it.items }.filter { d.types.any { t -> t.parentId == it.entry.sourceId && !t.archived } }.map { hit ->
            val count = choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.TYPE), scope = CatalogScope.CHILDREN, parentId = hit.entry.sourceId), pageSize = 1).total
            PickerItem(hit.entry, d.label(hit.entry.selection).copy(slot = Areas.slot(hit.entry.sourceId)), count,reference = choices.reference(hit.entry),expected = v.snapshot().token)
        }.toList()
        val order = Areas.everyday
        val everyday = roots.filter { it.entry.sourceId in order }.sortedBy { order.indexOf(it.entry.sourceId) }
        val more = roots.filter { it.entry.sourceId !in order }.sortedBy { it.entry.title }
        PickerData(recents, favorites, everyday, more)
    }

    suspend fun children(parentId: String): List<PickerItem> = withContext(Dispatchers.IO) {
        val v = view.value ?: return@withContext emptyList()
        val choices = v.choices(); val q = ChoiceQuery(kinds = setOf(ChoiceKind.TYPE), scope = CatalogScope.CHILDREN, parentId = parentId)
        generateSequence(choices.search(q, pageSize = 100)) { p -> p.next?.let { choices.search(q, it, 100) } }.flatMap { it.items }.map { hit ->
            val kids = if (hit.entry.availability == ChoiceAvailability.GROUP)
                choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.TYPE), scope = CatalogScope.CHILDREN, parentId = hit.entry.sourceId), pageSize = 1).total else null
            PickerItem(hit.entry, v.data.label(hit.entry.selection), kids,reference = choices.reference(hit.entry),expected = v.snapshot().token)
        }.toList()
    }

    suspend fun search(text: String): List<PickerItem> = withContext(Dispatchers.IO) {
        val v = view.value ?: return@withContext emptyList()
        val choices = v.choices()
        choices.search(ChoiceQuery(text = text.take(256), kinds = setOf(ChoiceKind.FAVORITE, ChoiceKind.CARD, ChoiceKind.TYPE)), pageSize = 40)
            .items.map { PickerItem(it.entry, v.data.label(it.entry.selection),reference = choices.reference(it.entry),expected = v.snapshot().token) }
    }

    /** Re-project the current breadcrumb without changing the picker navigation stack. */
    suspend fun refreshPickerItem(item: PickerItem): PickerItem? = withContext(Dispatchers.IO) {
        val v = view.value ?: return@withContext null;val choices = v.choices()
        val entry = choices.resolve(item.entry.id) ?: return@withContext null
        item.copy(entry = entry,label = v.data.label(entry.selection),reference = choices.reference(entry),expected = v.snapshot().token)
    }

    // ---------- corrections ----------

    /** Read-only preview: before/after day totals come from the core's own reports at one moment. */
    suspend fun previewCorrection(draft: CorrectionDraft, dayStart: Long, dayEnd: Long): CorrectionState = withContext(Dispatchers.IO) {
        val rt = runtime ?: return@withContext CorrectionState(null, 0, 0, "Нет данных", null, null)
        val d = rt.tracker.data()
        val own = d.segments.filter { it.sessionId == draft.sessionId }.sortedBy { it.start }
        val others = d.segments.filter { it.sessionId != draft.sessionId && it.end != null && !it.uncertain }
        val freeFrom = others.filter { it.end!! <= (own.firstOrNull()?.start ?: draft.start) }.maxOfOrNull { it.end!! }
        val freeUntil = others.filter { it.start >= (own.lastOrNull()?.end ?: draft.end) }.minOfOrNull { it.start }
        try {
            val edits = own.mapIndexed { i, seg ->
                // A new activity applies to the whole entry; otherwise each segment keeps its own saved details.
                Tracker.SegmentEdit(seg.id, if (i == 0) draft.start else seg.start, if (i == own.lastIndex) draft.end else seg.end!!, draft.selection ?: seg.selection)
            }
            val preview = rt.tracker.previewCorrection(draft.sessionId, edits, draft.note?.takeIf { it.isNotBlank() })
            val (before, after) = preview.reports(dayStart, dayEnd)
            CorrectionState(preview, before.recordedMs, after.recordedMs, null, freeFrom, freeUntil)
        } catch (e: Exception) { CorrectionState(null, 0, 0, Format.error(e), freeFrom, freeUntil) }
    }

    fun applyCorrection(preview: Tracker.CorrectionPreview,completed: ((Result<Tracker.Change>) -> Unit)? = null) = command({ "Запись исправлена" },completed) { rt, _ -> rt.tracker.applyCorrection(preview) }

    fun manual(start: Long, end: Long, selection: Classification, note: String?,completed: ((Result<Tracker.Change>) -> Unit)? = null) = command({ "Запись добавлена" },completed) { rt, s ->
        rt.tracker.manual(s.token, start, end, selection, note?.takeIf { it.isNotBlank() })
    }

    /** The unchanged gap goes through the core's own draft: a fresh review of exactly that stretch must still find it free. */
    private fun recordGap(gap: GapUi,selection: Classification,note: String?,completed: (Result<Tracker.Change>) -> Unit) = command({ "Промежуток записан" },completed) { rt,s ->
        val review = rt.tracker.historyGaps(gap.start,gap.end)
        val found = review.gaps.singleOrNull { it.start == gap.start && it.end == gap.end && it.kind != HistoryGapKind.RECOVERY_UNCERTAIN }
            ?: fail(ErrorCode.STALE,"The gap is no longer free")
        rt.tracker.recordDraft(s.token,rt.tracker.gapDraft(review,found.id,selection,note?.takeIf { it.isNotBlank() }))
    }

    // ---------- the current record started earlier ----------

    /** null asks for "from the end of the previous record". Nothing is written: the sheet shows the proposal first. */
    suspend fun previewStartEarlier(start: Long?): Result<StartBackfillPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val rt = runtime ?: fail(ErrorCode.STALE,"No backend")
            val s = rt.tracker.snapshot();val id = s.session?.id ?: fail(ErrorCode.INVALID_INPUT,"No current record")
            if(start == null) rt.tracker.previewStartAtPreviousEnd(s.token,id) else rt.tracker.previewStartBackfill(s.token,id,start)
        }
    }
    fun applyStartEarlier(preview: StartBackfillPreview,completed: (Result<Tracker.Change>) -> Unit) =
        command({ "Начало записи перенесено" },completed) { rt,_ -> rt.tracker.applyStartBackfill(preview) }

    fun session(sessionId: String): Pair<Session, List<Segment>>? {
        val d = view.value?.data ?: return null
        val s = d.sessions.firstOrNull { it.id == sessionId } ?: return null
        return s to d.segments.filter { it.sessionId == sessionId }.sortedBy { it.start }
    }

    // ---------- overview ----------

    suspend fun overview(period: Period): OverviewData? = withContext(Dispatchers.IO) {
        val rt = runtime ?: return@withContext null
        val v = view.value ?: rt.tracker.view()
        val d = v.data
        val zone = ZoneId.of(d.settings.reportZone)
        val today = ReportingDayPolicy(d.settings.reportZone,d.settings.reportDayStartMinute).dateAt(now())
        val first = today.minusDays(period.days - 1L)
        val calendar = v.calendar(first, period.days)
        val window = calendar.window
        val rootIds = d.types.filter { it.parentId == null }.map { it.id }.toSet()
        fun areaParts(report: Report): List<Pair<String?, Long>> = report.byCurrentRoot(d.types).filter { (k, ms) -> ms > 0 && (k == null || k in rootIds) }
            .toList().sortedWith(compareBy({ Areas.slot(it.first) ?: 99 }, { it.first ?: "~" }))
        val names = d.types.associate { it.id to it.name }
        val areas = areaParts(window).sortedByDescending { it.second }.map { (id, ms) ->
            AreaTotal(id?.let { names[it] } ?: "Без области", Areas.slot(id), ms,
                id?.let { d.glyph(Classification(activityTypeId = it)) } ?: ActivityGlyphs.unclassified, id, id != null)
        }
        val columns = if (period == Period.DAY) emptyList() else calendar.days.map { day ->
            val parts = areaParts(v.report(day.start, day.end)).map { Areas.slot(it.first) to it.second }
            DayColumn(day.date, parts, day.recordedMs, day.incomplete)
        }
        val ribbon = if (period == Period.DAY) calendar.days.single().rows.map { RibbonSlot(it.start, it.end, d.label(it.selection).slot) } else emptyList()
        val comparison = runCatching {
            Reports.compareCalendarDays(d, rt.tracker.clock.now(), first, first.minusDays(period.days.toLong()), period.days)
        }.getOrNull()
        // The definition in effect for the shown period, not the stored head: a scheduled edit must not hide or relabel today.
        val goals = d.goals.mapNotNull { g -> runCatching { v.goalProgress(g.id) }.getOrNull()?.takeIf { it.goal.enabled }?.let(::goalUi) }
        val day = calendar.days.first()
        OverviewData(period, first, today, window.recordedMs, window.incomplete, comparison?.absoluteDifferenceMs, comparison?.previous?.recordedMs,
            first.minusDays(period.days.toLong()), first.minusDays(1), areas, columns, ribbon, day.start, calendar.days.last().end,
            goals, window.unresolved.size,zone)
    }

    private fun goalUi(p: GoalProgress): GoalUi {
        val g = p.goal
        fun amount(x: Long) = Format.goalAmount(g.metric, x)
        val name = g.name + ", " + Format.goalRule(g.metric, g.direction, g.period, g.target)
        val (status, ok) = when (p.recordedStatus) {
            GoalStatus.MINIMUM_PENDING -> "осталось ${amount(p.remaining)}" to false
            GoalStatus.MINIMUM_REACHED -> "есть" to true
            GoalStatus.WITHIN_LIMIT -> "в пределах" to true
            GoalStatus.LIMIT_EXCEEDED -> "больше на ${amount(p.excess)}" to false
        }
        val value = if (g.metric == GoalMetric.DISTINCT_RECORDED_DAYS) "${amount(p.actual)} с записями" else amount(p.actual)
        return GoalUi(name, value, status + if (p.uncertain) ", есть неизвестное время" else "", ok,
            if (g.target > 0) (p.actual.toFloat() / g.target).coerceIn(0f, 1f) else 0f, g.id)
    }

    // ---------- goals ----------

    /** The definition in effect today, for the form. */
    fun goalDraft(id: String): GoalDraft? {
        val v = view.value ?: return null
        val g = runCatching { v.goalDefinition(id) }.getOrNull() ?: return null
        val selection = when { g.filter.cardId != null -> v.data.cards.firstOrNull { it.id == g.filter.cardId }?.classification()
            g.filter.activityTypeId != null -> Classification(activityTypeId = g.filter.activityTypeId); else -> null }
        return GoalDraft(g.id, g.name, selection, selection?.let { v.data.label(it) }, g.metric, g.direction, g.period, g.target, originalFilter = g.filter)
    }

    /** A new goal takes the report day of today. An edit keeps its calendar and says when it applies. */
    fun saveGoal(draft: GoalDraft, mode: GoalEditMode) = command({ if (draft.id == null) "Цель поставлена" else "Цель изменена" }) { rt, s ->
        val d = rt.tracker.data()
        val filter = if (!draft.activityChanged && draft.originalFilter != null) draft.originalFilter else when { draft.selection?.cardId != null -> Filter(cardId = draft.selection.cardId)
            draft.selection?.activityTypeId != null -> Filter(activityTypeId = draft.selection.activityTypeId); else -> Filter() }
        val stored = draft.id?.let { id -> d.goals.single { it.id == id } }
        val goal = stored?.copy(name = draft.name.trim(), metric = draft.metric, direction = draft.direction, target = draft.target, filter = filter, revisions = stored.revisions)
            ?: Goal(name = draft.name.trim(), metric = draft.metric, target = draft.target, direction = draft.direction, period = draft.period,
                zone = d.settings.reportZone, filter = filter, dayStartMinute = d.settings.reportDayStartMinute)
        rt.tracker.saveGoal(s.token, goal, mode)
    }
    fun deleteGoal(id: String) = command({ "Цель убрана" }) { rt, s -> rt.tracker.deleteGoal(s.token, id) }

    // ---------- shareable summary ----------

    /** Plain text from the same core report as the overview: totals by area, unknown time apart, notes only when asked. */
    /**
     * What one area consists of over the period, one level at a time, by today's tree: a moved section brings its past
     * time along. Below the last section, records are told apart by their own names. Durations are the core's per-row values.
     */
    suspend fun breakdown(period: Period, parentId: String): Breakdown? = withContext(Dispatchers.IO) {
        val rt = runtime ?: return@withContext null
        val v = view.value ?: rt.tracker.view()
        val d = v.data
        val today = ReportingDayPolicy(d.settings.reportZone, d.settings.reportDayStartMinute).dateAt(now())
        val report = v.calendar(today.minusDays(period.days - 1L), period.days).window
        val parents = d.types.associate { it.id to it.parentId }
        fun chain(leaf: String?): List<String> { val out = ArrayList<String>(); var cur = leaf; var guard = 0
            while (cur != null && guard++ < 64) { out.add(0, cur); cur = parents[cur] }; return out }
        val rows = report.rows.distinctBy { it.segmentId }.mapNotNull { r ->
            val c = chain(r.selection.activityTypeId ?: r.selection.ancestorPathAtWrite.lastOrNull())
            val at = c.indexOf(parentId); if (at < 0) null else Triple(r, c.getOrNull(at + 1), c)
        }
        if (rows.isEmpty()) return@withContext Breakdown(d.types.find { it.id == parentId }?.name ?: "", 0, emptyList())
        val names = d.types.associate { it.id to it.name }
        val hasChildren = d.types.mapNotNull { it.parentId }.toSet()
        val parts = rows.groupBy { (r, child, _) -> child?.let { "t:$it" } ?: "n:" + d.label(r.selection).title }.map { (key, group) ->
            val ms = group.sumOf { it.first.durationMs }
            if (key.startsWith("t:")) { val id = key.removePrefix("t:")
                AreaTotal(names[id] ?: id, Areas.slot(group.first().third.first()), ms, d.glyph(Classification(activityTypeId = id)), id,
                    id in hasChildren || group.map { d.label(it.first.selection).title }.distinct().size > 1)
            } else AreaTotal(key.removePrefix("n:"), d.label(group.first().first.selection).slot, ms, d.glyph(group.first().first.selection))
        }.sortedByDescending { it.ms }
        Breakdown(names[parentId] ?: "", parts.sumOf { it.ms }, parts)
    }

    suspend fun savedReports(): List<SavedReportUi> = withContext(Dispatchers.IO) {
        val rt = runtime ?: return@withContext emptyList()
        val v = view.value ?: rt.tracker.view(); val d = v.data; val now = rt.tracker.clock.now()
        d.savedReports.mapNotNull { q -> runCatching {
            val r = SavedReports.open(d, now, q.id)
            val zone = ZoneId.of(r.report.zone)
            val first = r.report.dayPolicy.dateAt(r.report.start); val last = r.report.dayPolicy.dateAt(r.report.end - 1)
            val names = d.cards.associate { it.id to it.name }
            val parts = r.groupedMs.filterValues { it > 0 }.map { (k, ms) -> (k?.let { names[it] } ?: "Без своего занятия") to ms }.sortedByDescending { it.second }
            val filterSel = q.filter.cardId?.let { Classification(cardId = it) } ?: q.filter.activityTypeId?.let { Classification(activityTypeId = it) }
            SavedReportUi(q, Format.period(first, last), r.report.recordedMs, r.report.incomplete, parts,
                r.references.any { it.status != ReportReferenceStatus.AVAILABLE }, filterSel?.let { d.label(it) })
        }.getOrNull() }.sortedBy { it.query.name.lowercase() }
    }
    fun saveReport(existing: SavedReportQuery?, name: String, selection: Classification?, window: ReportWindow) = command({ _ -> if (existing == null) "Отчёт сохранён" else "Отчёт изменён" }) { rt, s ->
        val d = rt.tracker.data()
        val filter = when { selection?.cardId != null -> Filter(cardId = selection.cardId); selection?.activityTypeId != null -> Filter(activityTypeId = selection.activityTypeId); else -> Filter() }
        rt.tracker.saveReportQuery(s.token, (existing ?: SavedReportQuery(name = name)).copy(name = name, window = window, filter = filter,
            grouping = ReportGrouping.CARD, groupKey = null, policy = d.settings.reportDayPolicy))
    }
    fun deleteReport(id: String) = command({ _ -> "Отчёт удалён" }) { rt, s -> rt.tracker.deleteReportQuery(s.token, id) }

    suspend fun summary(period: Period, notes: Boolean): String? = withContext(Dispatchers.IO) {
        val v = view.value ?: return@withContext null
        val d = v.data
        val today = d.settings.reportDayPolicy.dateAt(now())
        val first = today.minusDays(period.days - 1L)
        val report = v.calendar(first, period.days).window
        val rootIds = d.types.filter { it.parentId == null }.map { it.id }.toSet()
        val names = d.types.associate { it.id to it.name }
        buildString {
            append(if (period == Period.DAY) "Сегодня, ${Format.dayMonth(today)}" else Format.period(first, today)).append('\n')
            append("Всего ").append(Format.duration(report.recordedMs)).append(if (report.incomplete) ", день ещё идёт" else "").append('\n')
            val areas = report.byCurrentRoot(d.types).filter { (k, ms) -> ms > 0 && (k == null || k in rootIds) }.toList().sortedByDescending { it.second }
            if (areas.isNotEmpty()) append('\n')
            areas.forEach { (id, ms) -> append(id?.let { names[it] } ?: "Без области").append(" — ").append(Format.duration(ms)).append('\n') }
            if (report.unresolved.isNotEmpty()) append('\n').append("Неизвестное время не входит в итог: ")
                .append(Format.plural(report.unresolved.size, "отрезок", "отрезка", "отрезков")).append('\n')
            if (notes) {
                val noted = report.rows.filter { !it.note.isNullOrBlank() }.distinctBy { it.sessionId }.sortedBy { it.start }
                if (noted.isNotEmpty()) append('\n').append("Заметки").append('\n')
                noted.forEach { r ->
                    val date = report.dayPolicy.dateAt(r.start)
                    append("• ").append(Format.dayMonth(date)).append(", ").append(d.label(r.selection).title).append(": ").append(r.note).append('\n')
                }
            }
        }.trimEnd()
    }

    // ---------- data screen ----------

    fun setSurfaces(change: (SurfaceSettings) -> SurfaceSettings) = command { rt, s ->
        val d = rt.tracker.data(); rt.tracker.settings(s.token, d.settings.copy(surfaces = change(d.settings.surfaces)))
    }
    fun setReportZone(zone: String) = command({ "Часовой пояс отчётов: $zone" }) { rt, s ->
        ZoneId.of(zone)
        try { rt.reportZone?.setFixed(s.token,zone) ?: rt.tracker.configureReportingDay(s.token,
            ReportingDayPolicy(zone,rt.tracker.data().settings.reportDayStartMinute)) }
        finally {
            _reportZoneAutomatic.value = rt.reportZone?.automatic ?: false
            _reportZoneProblem.value = rt.reportZone?.recoveryProblem
        }
    }
    fun setReportZoneAutomatic(value: Boolean) = command({ if(value) "Часовой пояс отчётов следует за устройством. Записи могут перейти на другой день" else "Часовой пояс отчётов закреплён" }) { rt,s ->
        val preference = rt.reportZone ?: fail(ErrorCode.INVALID_INPUT,"Device report zone preference unavailable")
        try { preference.setAutomatic(s.token,value) }
        finally { _reportZoneAutomatic.value = preference.automatic;_reportZoneProblem.value = preference.recoveryProblem }
    }
    /** Only day labels move; recorded facts and existing goals keep their own boundaries. */
    fun setDayStart(minute: Int) = command({ "День начинается в ${minute / 60}:${(minute % 60).toString().padStart(2, '0')}" }) { rt, s ->
        rt.tracker.configureReportingDay(s.token, ReportingDayPolicy(rt.tracker.data().settings.reportZone, minute))
    }

    // ---------- long-record check-in ----------

    private val _longRecord = MutableStateFlow<LongRecordState?>(null)
    /** Null when this backend has no Android reminder runtime (then the row is read-only). */
    val longRecord: StateFlow<LongRecordState?> = _longRecord
    fun refreshLongRecord() {
        viewModelScope.launch(Dispatchers.IO) {
            val controller = waitRuntime()?.longRecord ?: return@launch
            _longRecord.value = runCatching { controller.synchronize() }.getOrNull()
        }
    }
    fun configureLongRecord(value: LongRecordPreference) {
        viewModelScope.launch(Dispatchers.IO) {
            val rt = waitRuntime() ?: return@launch
            val controller = rt.longRecord ?: return@launch
            try {
                val current = _longRecord.value ?: controller.synchronize()
                _longRecord.value = controller.configure(rt.tracker.snapshot().token, value, current.controlRevision)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice(Format.error(e)); refreshLongRecord() }
        }
    }
    /** A restored copy carries the preference, not consent on this phone: one explicit tap confirms it here. */
    fun confirmLongRecord() {
        viewModelScope.launch(Dispatchers.IO) {
            val rt = waitRuntime() ?: return@launch
            val controller = rt.longRecord ?: return@launch
            try {
                val current = _longRecord.value ?: controller.synchronize()
                _longRecord.value = controller.confirm(rt.tracker.snapshot().token, current.controlRevision)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice(Format.error(e)); refreshLongRecord() }
        }
    }

    private val exports by lazy { ExportOperations(app) }
    val exportPreparing = MutableStateFlow(false)
    private val _documentExport = MutableStateFlow<String?>(null)
    val documentExport: StateFlow<String?> = _documentExport
    internal var documentExportRecovery: kotlinx.coroutines.Job? = null
        private set
    /** Activity saved state is the witness that a chooser was actually launched.
     * A disk claim by itself cannot suppress recovery after a process death. */
    fun recoverDocumentExport(registeredId: String?,registeredOwner: String? = null) =
        viewModelScope.launch(Dispatchers.IO) { try { synchronized(DocumentExportCoordination.lock) {
            val id = documentExportId() ?: return@synchronized
            val operation = exports.read(id)
            if(operation.state in setOf("COMPLETE","CANCELLED")) {
                clearDocumentExport(id)
                if(operation.state == "COMPLETE" && operation.mime == "application/json") recordBackup(operation.bytes,operation.error == null)
            } else if(id !in DocumentExportCoordination.writing &&
                (registeredId != id || registeredOwner != prefs.getString("documentExportOwner",null) ||
                    operation.resultReceived || !prefs.getBoolean("documentExportLaunched",false))) {
                check(prefs.edit().putBoolean("documentExportLaunched",false).remove("documentExportOwner").commit())
                _documentExport.value = id
            }
        } } catch(e: Exception) { notice(Format.error(e)) } }.also { documentExportRecovery = it }
    fun releaseDocumentExport(id: String,owner: String,error: Exception) {
        synchronized(DocumentExportCoordination.lock) {
        if(documentExportId() == id && prefs.getString("documentExportOwner",null) == owner) {
            prefs.edit().putBoolean("documentExportLaunched",false).apply()
            _documentExport.value = null // retry by the original action, no launch loop
        }
        }
        notice(Format.error(error))
    }
    /** Operation lifetime is the Activity, not a disposable tab. No new retry UI. */
    @Synchronized fun requestExport(csv: Boolean = false) {
        if(exportPreparing.value) return
        documentExportId()?.let { id ->
            if(!prefs.getBoolean("documentExportLaunched",false)) _documentExport.value = id
            return
        }
        exportPreparing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = if(csv) prepareCsv() else prepareBackup()
                if(id != null) {
                    check(prefs.edit().putString("documentExportId",id).putBoolean("documentExportLaunched",false).commit())
                    _documentExport.value = id
                }
            } catch(e: Exception) { notice(Format.error(e)) }
            finally { exportPreparing.value = false }
        }
    }
    fun documentExportId(): String? = prefs.getString("documentExportId",null)
    private fun clearDocumentExport(id: String) {
        if(documentExportId() == id) {
            check(prefs.edit().remove("documentExportId").remove("documentExportLaunched").remove("documentExportOwner").commit())
            _documentExport.value = null
        }
    }
    private fun recordBackup(bytes: Long,verified: Boolean) {
        val at = now()
        prefs.edit().putLong("backupAt",at).putLong("backupBytes",bytes).putBoolean("backupVerified",verified).apply()
        lastBackup.value = BackupInfo(at,bytes,verified)
    }
    suspend fun claimDocumentExport(id: String,owner: String): Boolean = withContext(Dispatchers.IO) {
        val claimed = synchronized(DocumentExportCoordination.lock) {
            if(_documentExport.value != id || documentExportId() != id || prefs.getBoolean("documentExportLaunched",false)) false else {
                check(prefs.edit().putBoolean("documentExportLaunched",true).putString("documentExportOwner",owner).commit())
                exports.awaitResult(id)
                _documentExport.value = null;true
            }
        }
        if(claimed) DocumentExportCoordination.afterClaimCommit?.invoke()
        claimed
    }
    val lastBackup = MutableStateFlow(prefs.getLong("backupAt", 0L).takeIf { it > 0 }?.let {
        BackupInfo(it, prefs.getLong("backupBytes", 0), prefs.getBoolean("backupVerified", false))
    })

    /** Prepares a private staged copy first; the system file picker only receives finished bytes. */
    suspend fun prepareBackup(): String? = withContext(Dispatchers.IO) {
        val rt = runtime ?: return@withContext null
        try {
            val stamp = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            val op = exports.begin("application/json", "not-ai-backup-$stamp.json")
            val prepared = exports.prepare(op.id) { rt.backups.write(it) }
            if (prepared.state == "READY") op.id else null
        } catch (e: Exception) { notice(Format.error(e)); null }
    }
    suspend fun prepareCsv(): String? = withContext(Dispatchers.IO) {
        val rt = runtime ?: return@withContext null
        try {
            val v = rt.tracker.view()
            val report = v.report(0, rt.tracker.clock.now().wall + 1)
            val op = exports.begin("text/csv", "not-ai-history-${Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate()}.csv")
            val prepared = exports.prepare(op.id) { out ->
                val writer = out.bufferedWriter(Charsets.UTF_8)
                Csv.write(report,writer);writer.flush() // ExportOperations owns close/fsync.
            }
            if (prepared.state == "READY") op.id else null
        } catch (e: Exception) { notice(Format.error(e)); null }
    }
    fun exportTitle(id: String) = exports.read(id).title
    fun exportMime(id: String) = exports.read(id).mime
    /** Only the chooser that owns the durable claim may consume its result.
     * A previous chooser can return after a retry claims the same staged bytes. */
    fun receiveDocumentExportResult(id: String?,owner: String?,uri: Uri?): Boolean =
        synchronized(DocumentExportCoordination.lock) {
            if(id == null || owner == null || documentExportId() != id ||
                prefs.getString("documentExportOwner",null) != owner ||
                !prefs.getBoolean("documentExportLaunched",false)) false
            else { writeExport(id,uri);true }
        }
    fun writeExport(id: String, uri: Uri?) {
        synchronized(DocumentExportCoordination.lock) {
            if(!DocumentExportCoordination.writing.add(id)) return
            try { exports.receiveResult(id,uri == null) }
            catch(e: Exception) {
                DocumentExportCoordination.writing.remove(id)
                prefs.edit().putBoolean("documentExportLaunched",false).commit()
                notice(Format.error(e));return
            }
        }
        DocumentExportCoordination.afterResultCommit?.invoke()
        if(uri == null) {
            synchronized(DocumentExportCoordination.lock) { clearDocumentExport(id);DocumentExportCoordination.writing.remove(id) }
            notice("Сохранение отменено");return
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            var completed = false
            try {
                val result = exports.write(id, uri)
                completed = true
                DocumentExportCoordination.afterWriteComplete?.invoke()
                if (exports.read(id).mime == "application/json") recordBackup(result.bytes,result.verified)
                notice(if (result.verified) "Файл сохранён и проверен" else "Файл сохранён. Проверить его чтением нельзя")
            } catch (e: Exception) { notice(Format.error(e)) }
            finally {
                synchronized(DocumentExportCoordination.lock) {
                    DocumentExportCoordination.writing.remove(id)
                    if(completed) clearDocumentExport(id)
                    else if(documentExportId() == id) prefs.edit().putBoolean("documentExportLaunched",false).remove("documentExportOwner").commit()
                }
            }
        }
        job.invokeOnCompletion {
            // Also runs if the ViewModel was cleared before the IO coroutine started.
            synchronized(DocumentExportCoordination.lock) {
                if(DocumentExportCoordination.writing.remove(id) && documentExportId() == id)
                    prefs.edit().putBoolean("documentExportLaunched",false).remove("documentExportOwner").commit()
            }
        }
    }

    suspend fun prepareRestore(uri: Uri,owner: RestoreReviewOwner<RestorePreview> = standaloneRestoreReviews): RestorePreview? {
        val request = owner.begin() ?: return null
        try {
            return withContext(Dispatchers.IO) {
                val rt = runtime ?: return@withContext null
                val file = app.contentResolver.openInputStream(uri)?.use { stageRestore(app, it) } ?: fail(ErrorCode.STORAGE, "Source unavailable")
                owner.prepared(request,rt.backups.prepare { file.inputStream() })
            }
        } catch(e: kotlinx.coroutines.CancellationException) { owner.cancel(request);throw e }
        catch(e: Exception) { owner.cancel(request);notice(Format.error(e));return null }
    }
    fun applyRestore(preview: RestorePreview,owner: RestoreReviewOwner<RestorePreview>? = null) {
        if(owner != null && !owner.claim(preview)) { notice(Format.error(CoreError(ErrorCode.STALE,"Restore review changed or closed")));return }
        if(owner == null) standaloneRestoreReviews.claim(preview)
        val rt = runtime
        if(rt == null) { releaseRestorePreviewOnIo(preview);notice(Format.error(CoreError(ErrorCode.STALE,"Restore backend changed")));return }
        val applying = viewModelScope.launch(Dispatchers.IO) {
            try { rt.backups.apply(preview); notice("Данные восстановлены. Прежние сохранены отдельно") }
            catch (e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;notice(Format.error(e)) }
        }
        // Runs even when cancellation wins before the coroutine body starts. After claim,
        // cancel/dispose/replacement no longer owns this preview or its active apply lease.
        applying.invokeOnCompletion { releaseRestorePreviewOnIo(preview) }
    }

    val calendarAvailable: Boolean get() = runtime?.calendar != null
    /** Timed events of one reporting day, for filling a new record. Nothing is stored; all-day events are left out. */
    suspend fun calendarEvents(from: Long, until: Long): dev.notai.tracker.android.CalendarPage? = withContext(Dispatchers.IO) {
        runCatching { runtime?.calendar?.list(from, until, enabled = true, limit = 50) }.getOrNull()
    }

    // What a Focus work interval does to the phone. Device-local; the core controller applies and releases it.
    data class FocusEffectsUi(val choice: dev.notai.tracker.android.FocusEffects.Choice, val dndAccess: Boolean, val dndSupported: Boolean)
    private val _focusEffects = MutableStateFlow(FocusEffectsUi(dev.notai.tracker.android.FocusEffects.read(app),
        dev.notai.tracker.android.FocusEffects.dndAccess(app), dev.notai.tracker.android.FocusEffects.dndSupported()))
    val focusEffects: StateFlow<FocusEffectsUi> = _focusEffects
    fun refreshFocusEffects() { _focusEffects.value = FocusEffectsUi(dev.notai.tracker.android.FocusEffects.read(app),
        dev.notai.tracker.android.FocusEffects.dndAccess(app), dev.notai.tracker.android.FocusEffects.dndSupported()) }
    fun setFocusEffects(change: (dev.notai.tracker.android.FocusEffects.Choice) -> dev.notai.tracker.android.FocusEffects.Choice) {
        dev.notai.tracker.android.FocusEffects.write(app, change(dev.notai.tracker.android.FocusEffects.read(app))); refreshFocusEffects()
    }
    fun chooseFocusAudio(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val name = runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
            dev.notai.tracker.android.FocusEffects.importAudio(app, uri, name); refreshFocusEffects()
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice("Не удалось взять этот файл. Попробуй другой, до 64 МБ") }
    }

    // Several records at once: choose them in the history, then give them one activity or note. Preview first, Undo after.
    private val _picked = MutableStateFlow<Set<String>>(emptySet())
    val picked: StateFlow<Set<String>> = _picked
    private val pickedLock = Any()
    private var deletingPicked = false
    fun togglePicked(sessionId: String) = synchronized(pickedLock) {
        if(!deletingPicked) _picked.value = _picked.value.let { if(sessionId in it) it-sessionId else it+sessionId }
    }
    fun clearPicked() = synchronized(pickedLock) { if(!deletingPicked) _picked.value = emptySet() }
    suspend fun previewBatchDelete(): Result<Tracker.BatchDeletePreview> = withContext(Dispatchers.IO) {
        try {
            val rt = runtime ?: fail(ErrorCode.STALE,"No backend")
            Result.success(rt.tracker.previewDeleteSessions(_picked.value))
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }
    override suspend fun previewPickedDeletion(): Result<PickedDeletionReview> {
        val ids = synchronized(pickedLock) { _picked.value.toSet() }
        return withContext(Dispatchers.IO) {
            try {
                val preview = (runtime ?: fail(ErrorCode.STALE,"No backend")).tracker.previewDeleteSessions(ids)
                val consequences = buildList {
                    add("Итоги уменьшатся на ${Format.duration(preview.recordedMs)}")
                    if(preview.segmentCount > preview.sessionCount) add(Format.plural(preview.segmentCount,"отрезок","отрезка","отрезков")+" между паузами")
                    if(preview.measurementCount > 0) add(Format.plural(preview.measurementCount,"измерение","измерения","измерений"))
                    if(preview.subjectLinkCount > 0) add(Format.plural(preview.subjectLinkCount,"связь с предметом","связи с предметами","связей с предметами"))
                    if(preview.exerciseSetCount > 0) add(Format.plural(preview.exerciseSetCount,"запись подхода","записи подходов","записей подходов"))
                    if(preview.eventQuantityCount > 0) add(Format.plural(preview.eventQuantityCount,"запись событийной величины","записи событийной величины","записей событийной величины"))
                    if(preview.distanceTimeCount > 0) add(Format.plural(preview.distanceTimeCount,"основание темпа","основания темпа","оснований темпа"))
                    if(preview.subjectCorrectionCount > 0) add("В том числе ${Format.plural(preview.subjectCorrectionCount,"исправление","исправления","исправлений")}")
                }
                Result.success(PickedDeletionReview(preview.sessionIds,consequences) { commitPickedDeletion(preview) })
            } catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }
    }
    /** The confirmed operation and Undo notice belong to this VM even when BatchBar leaves composition. */
    private suspend fun commitPickedDeletion(preview: Tracker.BatchDeletePreview): Result<Unit> {
        currentCoroutineContext().ensureActive()
        return viewModelScope.async(Dispatchers.IO) {
            var acquired = false
            try {
                synchronized(pickedLock) {
                    ensure(!deletingPicked && _picked.value == preview.sessionIds,"Selection changed; preview again",ErrorCode.STALE)
                    deletingPicked = true;acquired = true
                }
                val change = (runtime ?: fail(ErrorCode.STALE,"No backend")).tracker.applyDeleteSessions(preview)
                synchronized(pickedLock) { _picked.value = emptySet() }
                notice("Удалено ${Format.plural(preview.sessionCount,"запись","записи","записей")}",if(change.undoAvailable) change.state.token else null)
                Result.success(Unit)
            } catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            } finally {
                if(acquired) synchronized(pickedLock) { deletingPicked = false }
            }
        }.await()
    }
    fun applyBatchDelete(preview: Tracker.BatchDeletePreview) = viewModelScope.launch {
        commitPickedDeletion(preview).onFailure { notice(Format.error(it)) }
    }
    suspend fun previewBatch(selection: Classification?, note: MetadataNoteEdit?, replace: Boolean): Result<BatchMetadataPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val rt = runtime ?: fail(ErrorCode.STALE, "No backend")
            rt.tracker.previewMetadata(BatchMetadataRequest(_picked.value.toList(), selection,
                if (replace) MetadataFillPolicy.REPLACE else MetadataFillPolicy.EMPTY_ONLY, note))
        }
    }
    fun applyBatch(preview: BatchMetadataPreview) = command({ _ ->
        val n = preview.changes.map { it.sessionId }.distinct().size
        _picked.value = emptySet()
        "Изменено ${Format.plural(n, "запись", "записи", "записей")}"
    }) { rt, _ -> rt.tracker.applyMetadata(preview) }

    // CSV history: inspect → confirm columns and time → map source activities → preview → append. Nothing is written before apply.
    private val _csv = MutableStateFlow<CsvImportUi?>(null)
    val csvImport: StateFlow<CsvImportUi?> = _csv
    fun closeCsv() { _csv.value = null }
    fun openCsv(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val name = runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
            val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (out.size() <= MAX_CSV_BYTES) { val n = input.read(buffer, 0, minOf(buffer.size, MAX_CSV_BYTES + 1 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: fail(ErrorCode.STORAGE, "Source unavailable")
            val inspection = HistoryFiles.inspect(bytes)
            when (inspection.format) {
                ImportFormat.DELIMITED_TEXT -> _csv.value = CsvImportUi(inspection, name)
                ImportFormat.BACKUP_JSON -> notice("Это файл резервной копии. Восстанови его в разделе «Резервная копия»")
                ImportFormat.UNSUPPORTED -> notice("Этот файл не таблица CSV. ${inspection.problems.firstOrNull().orEmpty()}".trim())
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice(Format.importError(e)) }
    }
    /** Parse with the confirmed choices; the file's distinct activities come back for mapping. Errors stay inside the sheet. */
    suspend fun parseCsv(ui: CsvImportUi, delimiter: Char, mapping: CsvMapping, durationFormat: DurationFormat?): Result<TransferFile> = withContext(Dispatchers.IO) {
        runCatching { ui.inspection.confirmCsv(delimiter, mapping, CsvTimeChoice(mapping.localPattern, mapping.zone), durationFormat) }
    }
    fun launchParse(ui: CsvImportUi, delimiter: Char, mapping: CsvMapping, durationFormat: DurationFormat?, done: (Result<TransferFile>) -> Unit) =
        viewModelScope.launch { done(parseCsv(ui, delimiter, mapping, durationFormat)) }
    suspend fun previewCsv(file: TransferFile, targets: Map<SourceActivity, Classification>): Result<HistoryPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val transfer = runtime?.historyTransfer ?: fail(ErrorCode.STORAGE, "History transfer unavailable")
            transfer.preview(file, file.activities.associateWith { a -> targets[a] ?: Classification(name = a.activity.takeIf { it.isNotBlank() }) })
        }
    }
    fun applyCsv(preview: HistoryPreview) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val transfer = runtime?.historyTransfer ?: return@launch
            val result = transfer.apply(preview)
            _csv.value = null
            notice("Добавлено ${Format.plural(result.added, "запись", "записи", "записей")}" + if (result.duplicates > 0) ", повторы пропущены" else "")
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice(Format.importError(e)) }
    }

    // Automatic copies into a chosen folder. Status lives on this phone, outside the records; every call is on IO.
    private val _autoBackup = MutableStateFlow<AutoBackupUi?>(null)
    val autoBackup: StateFlow<AutoBackupUi?> = _autoBackup
    private val autoBackend get() = runtime?.autoBackup
    private suspend fun autoAction(block: (AutoBackupBackend) -> Unit) = withContext(Dispatchers.IO) {
        val b = autoBackend ?: return@withContext
        try { block(b) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice(autoBackupError(e)) }
        refreshAutoBackupNow()
    }
    private fun refreshAutoBackupNow() {
        val b = autoBackend ?: run { _autoBackup.value = null; return }
        val state = runCatching { b.status() }.getOrNull() ?: return
        _autoBackup.value = AutoBackupUi(state, state.destination?.let { d -> _autoBackup.value?.takeIf { it.state.destination == d }?.folder ?: b.folderName(d) })
    }
    suspend fun refreshAutoBackup() = withContext(Dispatchers.IO) { refreshAutoBackupNow() }
    fun backupFolderIntent(): android.content.Intent? = autoBackend?.selectionIntent()
    fun chooseBackupFolder(uri: Uri, flags: Int) = viewModelScope.launch {
        autoAction { b -> b.select(uri, flags, b.status().policy) }
        if (_autoBackup.value?.state?.enabled == true) notice("Папка выбрана. Первая копия появится через пару минут")
    }
    fun setAutoBackup(on: Boolean) = viewModelScope.launch {
        autoAction { b -> val s = b.status(); if (on) s.destination?.let { b.enable(it, s.policy) } else b.disable() }
    }
    fun setBackupRetention(keep: Int) = viewModelScope.launch {
        autoAction { b -> val s = b.status(); val d = s.destination; if (s.enabled && d != null) b.enable(d, s.policy.copy(retain = keep.coerceIn(1, 100))) }
    }
    fun backupNow() = viewModelScope.launch { autoAction { it.requestBackup() } }
    fun retryAutoBackup() = viewModelScope.launch { autoAction { it.retry() } }
    fun skipUncertainBackup(operationId: String) = viewModelScope.launch { autoAction { it.abandonPending(operationId) } }
    suspend fun prepareRestoreVersion(operationId: String, owner: RestoreReviewOwner<RestorePreview>): RestorePreview? {
        val request = owner.begin() ?: return null
        try {
            return withContext(Dispatchers.IO) {
                val b = autoBackend ?: return@withContext null
                owner.prepared(request, b.prepareRestore(operationId))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { owner.cancel(request); throw e }
        catch (e: Exception) { owner.cancel(request); notice(autoBackupError(e)); return null }
    }
    private fun autoBackupError(e: Exception): String =
        (e as? dev.notai.tracker.android.BackupDestinationException)?.reason?.let { Format.backupProblem(it) } ?: Format.error(e)

    suspend fun bundledCatalogVersion(): String? = withContext(Dispatchers.IO) { runCatching { runtime?.bundledCatalogVersion }.getOrNull() }
    suspend fun prepareCatalog(): CatalogPreview? = withContext(Dispatchers.IO) {
        try { runtime?.prepareCatalog() } catch (e: Exception) { notice(Format.error(e)); null }
    }
    fun applyCatalog(preview: CatalogPreview) {
        val rt = runtime ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { rt.applyCatalog(preview); notice("Каталог обновлён") } catch (e: Exception) { notice(Format.error(e)) }
        }
    }

    /** Launcher shortcut: executed by the process runtime, so it survives this screen closing. */
    fun submitShortcut(action: SurfaceAction) {
        viewModelScope.launch(Dispatchers.IO) {
            val rt = waitRuntime() ?: return@launch
            rt.submitShortcut(action).await().onFailure { notice(Format.error(it)) }
        }
    }
    private suspend fun waitRuntime(): UiBackend? = boot.first { it !is Boot.Loading }.let { (it as? Boot.Ready)?.backend }

    fun refreshSurfaces(block: suspend (TrackerRuntime, TrackerView) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val rt = waitRuntime()?.runtime ?: return@launch
            runCatching { block(rt, rt.tracker.view()) }
        }
    }
}

/** Seconds move only while time is actually being counted. */
fun Snapshot.ticks(): Boolean = focus?.let { it.mode == FocusMode.RUNNING } ?: (mode == Mode.RUNNING)
