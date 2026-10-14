package dev.notai.tracker.android.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.notai.tracker.*
import dev.notai.tracker.android.ExportOperations
import dev.notai.tracker.android.FocusSignals
import dev.notai.tracker.android.SurfaceAction
import dev.notai.tracker.android.TrackerRuntime
import dev.notai.tracker.android.stageRestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the picker does with the chosen activity. */
enum class PickMode { START, SWITCH, LABEL, EARLIER, MANUAL, FOCUS }

data class Notice(val text: String, val undo: Token? = null, val id: Long = System.nanoTime())

data class EntryUi(val segmentId: String, val sessionId: String, val start: Long, val end: Long, val durationMs: Long,
                   val label: Label, val note: String?, val selection: Classification)
data class DayUi(val date: LocalDate, val start: Long, val end: Long, val recordedMs: Long, val incomplete: Boolean, val entries: List<EntryUi>,
                 val ribbon: List<RibbonSlot>, val live: List<RibbonSlot>)
data class QuickStart(val title: String, val slot: Int?, val entry: ChoiceEntry, val favorite: Boolean, val glyph: Int, val reference: ChoiceReference)
data class HomeData(val today: LocalDate, val zone: ZoneId, val days: List<DayUi>, val quick: List<QuickStart>,
                    val currentLabel: Label?, val currentStart: Long?, val canLoadMore: Boolean, val focusMinutes: Long)

data class PickerItem(val entry: ChoiceEntry, val label: Label, val count: Int? = null, val whole: Boolean = false)
data class PickerData(val recents: List<PickerItem>, val favorites: List<PickerItem>, val areas: List<PickerItem>, val moreAreas: List<PickerItem>)

enum class Period(val days: Int, val title: String) { DAY(1, "День"), WEEK(7, "7 дней"), MONTH(30, "30 дней") }
data class AreaTotal(val name: String, val slot: Int?, val ms: Long, val glyph: Int = ActivityGlyphs.custom)
data class DayColumn(val date: LocalDate, val parts: List<Pair<Int?, Long>>, val totalMs: Long, val incomplete: Boolean)
data class GoalUi(val name: String, val value: String, val status: String, val ok: Boolean, val fraction: Float)
data class OverviewData(val period: Period, val first: LocalDate, val last: LocalDate, val totalMs: Long, val incomplete: Boolean,
                        val differenceMs: Long?, val previousMs: Long?, val previousFirst: LocalDate, val previousLast: LocalDate, val areas: List<AreaTotal>,
                        val columns: List<DayColumn>, val ribbon: List<RibbonSlot>, val dayStart: Long, val dayEnd: Long,
                        val goals: List<GoalUi>, val unresolved: Int)
data class RibbonSlot(val start: Long, val end: Long, val slot: Int?)

data class CorrectionDraft(val sessionId: String, val start: Long, val end: Long, val selection: Classification?, val note: String?)
data class CorrectionState(val preview: Tracker.CorrectionPreview?, val beforeMs: Long, val afterMs: Long, val error: String?,
                           val freeFrom: Long?, val freeUntil: Long?)

data class BackupInfo(val at: Long, val bytes: Long, val verified: Boolean)
sealed interface DeleteRequest {
    data object Applied : DeleteRequest
    data object Failed : DeleteRequest
    /** Several segments or measurements go together with the record: ask once, then apply this exact preview. */
    class Confirm(val preview: Tracker.DeletePreview, val range: String, val segments: Int, val measurements: Int) : DeleteRequest
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
class TrackerViewModel(private val app: Application) : AndroidViewModel(app) {
    sealed interface Boot { data object Loading : Boot; data class Ready(val backend: UiBackend) : Boot; data class Failed(val message: String) : Boot }

    private val _boot = MutableStateFlow<Boot>(Boot.Loading)
    val boot: StateFlow<Boot> = _boot
    private val runtime get() = (boot.value as? Boot.Ready)?.backend
    /** Wall time from the core clock, so every screen agrees with the tracker about "now". */
    fun now(): Long = runtime?.tracker?.clock?.now()?.wall ?: System.currentTimeMillis()
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices
    private fun notice(text: String, undo: Token? = null) { _notices.tryEmit(Notice(text, undo)) }

    private val _theme = MutableStateFlow(runCatching { ThemeChoice.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeChoice.SYSTEM))
    val theme: StateFlow<ThemeChoice> = _theme
    fun setTheme(choice: ThemeChoice) { prefs.edit().putString("theme", choice.name).apply(); _theme.value = choice }

    private val _draft = MutableStateFlow<EntryDraft?>(null)
    /** Text another app explicitly sent. Nothing is written until the person picks and confirms. */
    val draft: StateFlow<EntryDraft?> = _draft
    fun receiveDraft(d: EntryDraft) { _draft.value = d }
    fun dropDraft() { _draft.value = null }

    init { load() }
    fun load() {
        _boot.value = Boot.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _boot.value = try { Boot.Ready(UiBackends.open(app)) }
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
        val enabled = view.value?.data?.settings?.surfaces?.focusSignal == true
        if (!(enabled && FocusSignals.allowed(app) && FocusSignals.deliver(app, due))) notice(due.safeLabel)
    }

    private val shownDays = MutableStateFlow(7)
    fun showMoreDays() { shownDays.update { minOf(it + 14, 62) } }

    /** History by local day. Recomputed per revision and once a minute while a session is open. */
    val home: StateFlow<HomeData?> = combine(view, shownDays) { v, n -> v to n }.flatMapLatest { (v, n) ->
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
        return v.choices().quick(HistoryWindow(maxOf(0L, end - 30L * 24 * 60 * 60 * 1000), end), limit)
    }
    private fun QuickChoice.toQuickStart(d: Dataset): QuickStart {
        val l = d.label(entry.selection)
        return QuickStart(if (entry.kind == ChoiceKind.FAVORITE) entry.title else l.title, l.slot, entry, entry.kind == ChoiceKind.FAVORITE, l.glyph, reference)
    }

    private fun homeData(v: TrackerView, days: Int): HomeData {
        val d = v.data
        val zone = ZoneId.of(d.settings.reportZone)
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        val calendar = v.calendar(today.minusDays(days - 1L), days)
        val open = current(d)
        val notes = d.sessions.associate { it.id to it.note }
        val dayList = calendar.days.asReversed().mapNotNull { day ->
            val entries = day.rows.filter { it.sessionId != open?.id }.sortedByDescending { it.start }.map { r ->
                EntryUi(r.segmentId, r.sessionId, r.start, r.end, r.durationMs, d.label(r.selection), r.note ?: notes[r.sessionId], r.selection)
            }
            if (entries.isEmpty() && day.date != today && day.recordedMs == 0L) null
            else DayUi(day.date, day.start, day.end, day.recordedMs, day.incomplete, entries,
                entries.map { RibbonSlot(it.start, it.end, it.label.slot) },
                day.rows.filter { it.sessionId == open?.id }.map { RibbonSlot(it.start, it.end, null) })
        }
        // Same bounded source as the notification: favourites, then the most frequent of the last 30 days.
        val quick = quickChoices(v, 8).filter { it.entry.canStart && !d.label(it.entry.selection).unclassified }.take(5).map { q -> q.toQuickStart(d) }
        return HomeData(today, zone, dayList, quick, open?.let { d.label(it.selection) }, open?.let { s -> d.segments.filter { it.sessionId == s.id }.minOfOrNull { it.start } },
            days < 62, d.settings.focusPlan.workMs / 60_000L)
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
    fun chooseSurface(reference: ChoiceReference,note: String? = null) {
        val route = pickerRoute ?: return
        command({ "Записано" }) { rt,_ ->
            dev.notai.tracker.android.SurfaceChoices.choose(app,rt.tracker,route,reference,note).also { clearSurfacePicker(route) }
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
        viewModelScope.launch(Dispatchers.IO) {
            val rt = waitRuntime() ?: return@launch
            try {
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
        dev.notai.tracker.android.Surface.SHORTCUT -> fail(ErrorCode.INVALID_INPUT,"Shortcut has no enable preference")
    } }
    suspend fun previewDeleteSession(id: String): Tracker.DeletePreview? = withContext(Dispatchers.IO) {
        val rt = waitRuntime() ?: return@withContext null
        rt.tracker.previewDeleteSession(id)
    }
    fun applyDeleteSession(preview: Tracker.DeletePreview) = command({ "Запись удалена" }) { rt,_ -> rt.tracker.applyDeleteSession(preview) }

    /**
     * One entrance for the row menu, swipe and the entry sheet. A single-segment record without measurements is
     * deleted at once (Undo stays available); anything larger returns the preview for an explicit confirmation.
     */
    suspend fun requestDelete(sessionId: String): DeleteRequest {
        val preview = try { previewDeleteSession(sessionId) } catch (e: Exception) {
            notice(if ((e as? CoreError)?.code == ErrorCode.STALE) Format.error(e) else "Удалить можно только завершённую запись. Идущая запись и текущий Фокус не удаляются.")
            return DeleteRequest.Failed
        } ?: return DeleteRequest.Failed
        if (preview.segmentCount > 1 || preview.measurementCount > 0) {
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

    private fun command(success: ((Tracker.Change) -> String?)? = null, block: (UiBackend, Snapshot) -> Tracker.Change) {
        val rt = runtime ?: return
        val shown = snapshot.value ?: view.value?.snapshot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val change = block(rt, shown)
                success?.invoke(change)?.let { notice(it, if (change.undoAvailable) change.state.token else null) }
            } catch (e: Exception) { notice(Format.error(e)) }
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
    fun choose(entry: ChoiceEntry, mode: PickMode, earlierBoundary: Long? = null) =
        command({ if (mode == PickMode.SWITCH) "Новая запись начата" else null }) { rt, s -> applyChoice(rt, s, entry.selection, entry, mode, earlierBoundary) }

    private fun applyChoice(rt: UiBackend, s: Snapshot, selection: Classification, entry: ChoiceEntry?, mode: PickMode, boundary: Long?): Tracker.Change {
        val t = rt.tracker
        val draft = _draft.value
        val idle = s.mode == Mode.IDLE && s.focus == null
        return when {
            mode == PickMode.EARLIER -> t.switchedEarlier(s.token, boundary ?: fail(ErrorCode.INVALID_INPUT, "Boundary missing"), selection)
            mode == PickMode.FOCUS -> t.startFocus(s.token, selection)
            mode == PickMode.LABEL -> t.labelCurrent(s.token, selection, s.session?.note)
            draft != null && idle -> t.startDraft(s.token, draft.copy(selection = selection)).also { _draft.value = null }
            idle && entry?.kind == ChoiceKind.FAVORITE -> t.startFavorite(s.token, entry.sourceId)
            idle && entry?.kind == ChoiceKind.HISTORY && entry.sourceSessionId != null -> t.repeat(s.token, entry.sourceSessionId!!)
            idle -> t.start(s.token, selection)
            else -> {
                val change = t.switch(s.token, selection)
                if (draft != null) t.labelCurrent(change.state.token, selection, draft.note).also { _draft.value = null } else change
            }
        }
    }

    /** A new personal activity: saved as a card, then used in the same action with the token that save returned. */
    fun createAndChoose(name: String, mode: PickMode, earlierBoundary: Long? = null) {
        val trimmed = name.trim().replaceFirstChar { it.uppercase() }
        if (trimmed.isEmpty()) return
        command({ "Занятие «$trimmed» создано" }) { rt, s ->
            val card = Card(name = trimmed)
            val saved = rt.tracker.saveCard(s.token, card)
            applyChoice(rt, saved.state, card.classification(), null, mode, earlierBoundary)
        }
    }

    /** Saves a personal activity for a form that is still open; nothing is started. */
    fun createCard(name: String, onCreated: (Classification, Label) -> Unit) {
        val trimmed = name.trim().replaceFirstChar { it.uppercase() }
        if (trimmed.isEmpty()) return
        val rt = runtime ?: return
        val shown = snapshot.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val card = Card(name = trimmed)
                val change = rt.tracker.saveCard(shown.token, card)
                val label = change.data.label(card.classification())
                withContext(Dispatchers.Main) { onCreated(card.classification(), label) }
            } catch (e: Exception) { notice(Format.error(e)) }
        }
    }

    fun startDraftBlank() = command { rt, s ->
        val d = _draft.value ?: fail(ErrorCode.INVALID_INPUT, "Draft missing")
        rt.tracker.startDraft(s.token, d.copy(selection = Classification())).also { _draft.value = null }
    }

    /** History row's play button: repeat when idle, switch to it while tracking. */
    fun repeat(entry: EntryUi) = command({ c -> null }) { rt, s ->
        if (s.mode == Mode.IDLE && s.focus == null) rt.tracker.repeat(s.token, entry.sessionId) else rt.tracker.switch(s.token, entry.selection)
    }

    fun resolveTail(start: Long, end: Long) = command({ "Время уточнено" }) { rt, s ->
        if (s.focus != null) rt.tracker.resolveFocus(s.token, start, end) else rt.tracker.resolveTail(s.token, start, end)
    }
    fun excludeTail() = command({ "Неизвестный отрезок не учтён" }) { rt, s ->
        if (s.focus != null) rt.tracker.resolveFocus(s.token, null, null) else rt.tracker.resolveTail(s.token, null, null)
    }
    fun startFocus() = command { rt, s -> rt.tracker.startFocus(s.token) }
    fun focusNext() = command { rt, s -> rt.tracker.focusNext(s.token) }
    fun focusSkip() = command { rt, s -> rt.tracker.focusSkip(s.token) }
    fun configureFocus(workMin: Int, breakMin: Int) = command({ "Отрезки изменены" }) { rt, s ->
        rt.tracker.configureFocus(s.token, FocusPlan(workMin * 60_000L, breakMin * 60_000L))
    }

    fun saveFavorite(title: String, selection: Classification) = command({ "Добавлено в избранное" }) { rt, s ->
        rt.tracker.saveFavorite(s.token, Favorite(label = title, selection = selection))
    }
    fun removeFavorite(id: String) = command({ "Убрано из избранного" }) { rt, s -> rt.tracker.removeFavorite(s.token, id) }

    // ---------- picker ----------

    suspend fun pickerData(): PickerData = withContext(Dispatchers.IO) {
        val v = view.value ?: runtime?.tracker?.view() ?: return@withContext PickerData(emptyList(), emptyList(), emptyList(), emptyList())
        val d = v.data; val choices = v.choices()
        val recents = choices.suggestions(SuggestionQuery(enabled = true), pageSize = 5).items.map { PickerItem(it.entry, d.label(it.entry.selection)) }
        val favorites = choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.FAVORITE)), pageSize = 30).items.map { PickerItem(it.entry, d.label(it.entry.selection)) }
        val roots = generateSequence(choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.TYPE), scope = CatalogScope.ROOTS), pageSize = 100)) { p ->
            p.next?.let { choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.TYPE), scope = CatalogScope.ROOTS), it, 100) }
        }.flatMap { it.items }.filter { it.entry.availability == ChoiceAvailability.GROUP || d.types.any { t -> t.parentId == it.entry.sourceId } }.map { hit ->
            val count = choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.TYPE), scope = CatalogScope.CHILDREN, parentId = hit.entry.sourceId), pageSize = 1).total
            PickerItem(hit.entry, d.label(hit.entry.selection).copy(slot = Areas.slot(hit.entry.sourceId)), count)
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
            PickerItem(hit.entry, v.data.label(hit.entry.selection), kids)
        }.toList()
    }

    suspend fun search(text: String): List<PickerItem> = withContext(Dispatchers.IO) {
        val v = view.value ?: return@withContext emptyList()
        v.choices().search(ChoiceQuery(text = text.take(256), kinds = setOf(ChoiceKind.FAVORITE, ChoiceKind.CARD, ChoiceKind.TYPE)), pageSize = 40)
            .items.map { PickerItem(it.entry, v.data.label(it.entry.selection)) }
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

    fun applyCorrection(preview: Tracker.CorrectionPreview) = command({ "Запись исправлена" }) { rt, _ -> rt.tracker.applyCorrection(preview) }

    fun manual(start: Long, end: Long, selection: Classification, note: String?) = command({ "Запись добавлена" }) { rt, s ->
        rt.tracker.manual(s.token, start, end, selection, note?.takeIf { it.isNotBlank() })
    }

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
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        val first = today.minusDays(period.days - 1L)
        val calendar = v.calendar(first, period.days)
        val window = calendar.window
        val rootIds = d.types.filter { it.parentId == null }.map { it.id }.toSet()
        fun areaParts(report: Report): List<Pair<String?, Long>> = report.byType().filter { (k, ms) -> ms > 0 && (k == null || k in rootIds) }
            .toList().sortedWith(compareBy({ Areas.slot(it.first) ?: 99 }, { it.first ?: "~" }))
        val names = d.types.associate { it.id to it.name }
        val areas = areaParts(window).sortedByDescending { it.second }.map { (id, ms) ->
            AreaTotal(id?.let { names[it] } ?: "Без области", Areas.slot(id), ms,
                id?.let { d.glyph(Classification(activityTypeId = it)) } ?: ActivityGlyphs.unclassified)
        }
        val columns = if (period == Period.DAY) emptyList() else calendar.days.map { day ->
            val parts = areaParts(v.report(day.start, day.end)).map { Areas.slot(it.first) to it.second }
            DayColumn(day.date, parts, day.recordedMs, day.incomplete)
        }
        val ribbon = if (period == Period.DAY) calendar.days.single().rows.map { RibbonSlot(it.start, it.end, d.label(it.selection).slot) } else emptyList()
        val comparison = runCatching {
            Reports.compareCalendarDays(d, rt.tracker.clock.now(), first, first.minusDays(period.days.toLong()), period.days)
        }.getOrNull()
        val goals = d.goals.filter { it.enabled }.mapNotNull { g -> runCatching { goalUi(v.goalProgress(g.id)) }.getOrNull() }
        val day = calendar.days.first()
        OverviewData(period, first, today, window.recordedMs, window.incomplete, comparison?.absoluteDifferenceMs, comparison?.previous?.recordedMs,
            first.minusDays(period.days.toLong()), first.minusDays(1), areas, columns, ribbon, day.start, calendar.days.last().end,
            goals, window.unresolved.size)
    }

    private fun goalUi(p: GoalProgress): GoalUi {
        val g = p.goal
        fun amount(x: Long) = if (g.metric == GoalMetric.DURATION_MS) Format.duration(x) else "$x раз"
        val per = when (g.period) { GoalPeriod.DAY -> " в день"; GoalPeriod.WEEK -> " в неделю"; GoalPeriod.MONTH -> " в месяц"; GoalPeriod.FIXED -> "" }
        val name = g.name + if (g.direction == GoalDirection.MINIMUM) ", от ${amount(g.target)}$per" else ", не больше ${amount(g.target)}$per"
        val (status, ok) = when (p.recordedStatus) {
            GoalStatus.MINIMUM_PENDING -> "осталось ${amount(p.remaining)}" to false
            GoalStatus.MINIMUM_REACHED -> "есть" to true
            GoalStatus.WITHIN_LIMIT -> "в пределах" to true
            GoalStatus.LIMIT_EXCEEDED -> "больше на ${amount(p.excess)}" to false
        }
        return GoalUi(name, amount(p.actual), status + if (p.uncertain) ", есть неизвестное время" else "", ok,
            if (g.target > 0) (p.actual.toFloat() / g.target).coerceIn(0f, 1f) else 0f)
    }

    // ---------- data screen ----------

    fun setSurfaces(change: (SurfaceSettings) -> SurfaceSettings) = command { rt, s ->
        val d = rt.tracker.data(); rt.tracker.settings(s.token, d.settings.copy(surfaces = change(d.settings.surfaces)))
    }
    fun setReportZone(zone: String) = command({ "Часовой пояс отчётов: $zone" }) { rt, s ->
        ZoneId.of(zone); val d = rt.tracker.data(); rt.tracker.settings(s.token, d.settings.copy(reportZone = zone))
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

    suspend fun prepareRestore(uri: Uri): RestorePreview? = withContext(Dispatchers.IO) {
        val rt = runtime ?: return@withContext null
        try {
            val file = app.contentResolver.openInputStream(uri)?.use { stageRestore(app, it) } ?: fail(ErrorCode.STORAGE, "Source unavailable")
            rt.backups.prepare { file.inputStream() }
        } catch (e: Exception) { notice(Format.error(e)); null }
    }
    fun applyRestore(preview: RestorePreview) {
        val rt = runtime ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { rt.backups.apply(preview); notice("Данные восстановлены. Прежние сохранены отдельно") }
            catch (e: Exception) { notice(Format.error(e)) }
        }
    }

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

    fun refreshSurfaces(block: (TrackerRuntime, TrackerView) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val rt = waitRuntime()?.runtime ?: return@launch
            runCatching { block(rt, rt.tracker.view()) }
        }
    }
}

/** Seconds move only while time is actually being counted. */
fun Snapshot.ticks(): Boolean = focus?.let { it.mode == FocusMode.RUNNING } ?: (mode == Mode.RUNNING)
