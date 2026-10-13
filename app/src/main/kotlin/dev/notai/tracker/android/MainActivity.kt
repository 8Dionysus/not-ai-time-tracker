package dev.notai.tracker.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import dev.notai.tracker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import java.time.ZoneId

/** Disposable technical harness. No domain transitions, totals or duration arithmetic live here. */
class MainActivity: Activity() {
    private val scope = MainScope()
    private lateinit var runtime: TrackerRuntime
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var name: EditText
    private lateinit var details: EditText
    private lateinit var note: EditText
    private lateinit var from: EditText
    private lateinit var until: EditText
    private lateinit var zone: EditText
    private var visible = false
    private var resumed = false
    private lateinit var surfaceStatus: TextView
    private val surfaceToggles = mutableMapOf<Surface,CheckBox>()
    private var renderingSurfaceSettings = false
    private var shortcutPending: SurfaceAction? = null
    private var observation: Job? = null
    private var reportText = ""
    private var shown: Snapshot? = null
    private var pendingExportId: String? = null
    private var selectedCardId: String? = null
    private var formReference: String? = null
    private val exportOperations by lazy { ExportOperations(applicationContext) }
    private val formFiles by lazy { FormFiles(applicationContext) }
    private var pendingSelectedText: String? = null
    private var inputDraft: EntryDraft? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shortcutPending = TimerSurfaces.restoredShortcut(savedInstanceState,intent)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,48,24,24) }
        setContentView(ScrollView(this).apply { addView(body) })
        status = TextView(this); body.addView(status)
        name = input("Имя новой карточки").apply { id = R.id.form_name }
        details = input("Уточнение: например listening").apply { id = R.id.form_details }
        note = input("Заметка").apply { id = R.id.form_note }
        from = input("Начало / момент переключения: ISO-8601").apply { id = R.id.form_from }
        until = input("Конец: ISO-8601").apply { id = R.id.form_until }
        zone = input("Зона отчётов: например Europe/Moscow").apply { id = R.id.form_zone;setText(ZoneId.systemDefault().id) }
        pendingExportId = savedInstanceState?.getString("exportOperation")
        formReference = savedInstanceState?.getString("formReference")
        if(formReference != null) {
            try {
                val form = formFiles.read(formReference!!)
                selectedCardId = form.selectedCardId;name.setText(form.name);details.setText(form.details);note.setText(form.note)
                from.setText(form.start);until.setText(form.end);zone.setText(form.zone)
                form.source?.let { inputDraft = EntryDraft(Classification(),form.note.takeIf { it.isNotEmpty() },
                    source = DraftSource.valueOf(it),sourceReference = form.sourceReference) }
                pendingSelectedText = form.pendingText
            } catch(e: Exception) { status.text = "Не удалось прочитать сохранённую форму: ${e.javaClass.simpleName}" }
        } else if(savedInstanceState?.containsKey("selectedTextDraft") == true) {
            note.setText(savedInstanceState.getString("selectedTextDraft"))
            if(savedInstanceState.getBoolean("isSelectedTextDraft")) inputDraft = EntryDraft(Classification(),note.text.toString().takeIf { it.isNotEmpty() },source = DraftSource.SELECTED_TEXT)
        } else receiveSelectedText(intent)
        pendingSelectedText?.let(::reviewSelectedText)
        buildRecoveryControls()
        intent.action = Intent.ACTION_MAIN
        scope.launch {
            try { runtime = withContext(Dispatchers.IO) { TrackerRuntime.get(this@MainActivity) } }
            catch(e: Exception) { status.text = "Локальная база недоступна: ${e.message}\nИсходные файлы сохранены. Выберите копию в восстановлении.";return@launch }
            if(formReference == null) zone.setText(withContext(Dispatchers.IO) { runtime.tracker.data().settings.reportZone })
            buildActions()
            buildSurfaceControls()
            consumeShortcut()
            refreshSurfacePermissions()
            observeWhileVisible()
        }
    }
    private fun input(hint: String) = EditText(this).apply { this.hint = hint; contentDescription = hint;isSaveEnabled = false;body.addView(this) }
    private fun button(label: String,action: ()->Unit) { body.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
    private data class Form(val note: String,val start: String,val end: String,val draft: EntryDraft?)
    private fun command(label: String,needsSelection: Boolean = false,block: (Tracker,Token,Classification,Form)->Unit) = button(label) {
        val expected = shown?.token ?: return@button
        val chosenId = selectedCardId; val activity = details.text.toString().trim()
        val form = Form(note.text.toString(),from.text.toString(),until.text.toString(),inputDraft)
        runAction {
            val t = runtime.tracker
            val selected = if(needsSelection) CardInputs.selection(t.data(),chosenId) else Classification()
            block(t,expected,if (!needsSelection || activity.isEmpty()) selected else selected.copy(details = selected.details + ("activity" to activity)),form)
        }
    }
    private fun buildActions() {
        button("Сохранить новую карточку") {
            val expected = shown?.token ?: return@button; val value = name.text.toString(); val activity = details.text.toString()
            val card = Card(name = value,details = if(activity.isBlank()) emptyMap() else mapOf("activity" to activity))
            runAction { runtime.tracker.saveCard(expected,card);withContext(Dispatchers.Main) { selectedCardId = card.id;status.text = "Выбрана новая карточка · ${card.id}" } }
        }
        button("Выбрать карточку по ID…") { chooseCard() }
        button("Без карточки") { selectedCardId = null;status.text = "Следующий Start — без карточки" }
        button("Карточка из каталога…") { chooseType() }
        button("Обновить готовый каталог…") { runAction { val preview = runtime.prepareBundledCatalog();withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@MainActivity).setTitle("Каталог ${preview.version}")
                .setMessage("Новых: ${preview.report.added.size}; уточнений: ${preview.report.updated.size}; конфликтов: ${preview.report.conflicts.size}. Локальные имена, архивность и история сохраняются.")
                .setNegativeButton("Отмена",null).setPositiveButton("Применить") { _,_ -> runAction { runtime.catalogImports.apply(preview) } }.show()
        } } }
        buildCalendarControl()
        buildFocusControls()
        buildTransferControls()
        command("Start",true) { t,e,c,f ->
            if(f.draft == null) t.start(e,c,f.note.takeIf { it.isNotBlank() })
            else t.startDraft(e,f.draft.copy(selection = c,note = f.note.takeIf { it.isNotEmpty() }))
        }
        command("Назначить текущему занятию",true) { t,e,c,f -> t.labelCurrent(e,c,f.note) }
        command("Изменить уточнение с этого момента",true) { t,e,c,f -> t.changeDetails(e,c) }
        for(directive in listOf(TimerDirective.PAUSE,TimerDirective.RESUME,TimerDirective.STOP)) button(directive.name) {
            val target = shown?.timerTarget ?: return@button
            runAction { runtime.tracker.timerCommand(target,directive) }
        }
        command("Switch",true) { t,e,c,f -> t.switch(e,c) }
        command("Повторить последнее записанное (сохранённые, включая архивные параметры)") { t,e,_,f -> t.repeat(e,t.choices().latestRecorded()?.sourceSessionId ?: throw CoreError(ErrorCode.INVALID_INPUT,"Нет завершённой истории")) }
        command("Запомнить выбранное избранное",true) { t,e,c,f -> t.saveFavorite(e,Favorite(label = c.name ?: "Без уточнения",selection = c)) }
        button("Выбрать и запустить избранное…") { runAction {
            val choices = runtime.tracker.choices();showChoices(choices,ChoiceQuery(kinds = setOf(ChoiceKind.FAVORITE),includeArchived = true)) { hit ->
                val expected = choices.token
                AlertDialog.Builder(this@MainActivity).setTitle("Запустить сохранённый вариант?")
                    .setMessage("${hit.entry.title} · ${hit.entry.sourceId}\n${hit.entry.availability}. Избранное использует сохранённые параметры, в том числе архивные.")
                    .setNegativeButton("Отмена",null).setPositiveButton("Start сейчас") { _,_ -> runAction { runtime.tracker.startFavorite(expected,hit.entry.sourceId) } }.show()
            }
        } }
        command("Сохранить ручной интервал",true) { t,e,c,f ->
            if(f.draft == null) t.manual(e,time(f.start),time(f.end),c,f.note.takeIf { it.isNotBlank() })
            else t.recordDraft(e,f.draft.copy(selection = c,note = f.note.takeIf { it.isNotEmpty() },
                interval = DraftInterval(time(f.start),time(f.end))))
        }
        command("Переключился раньше: сохранить",true) { t,e,c,f -> t.switchedEarlier(e,time(f.start),c) }
        command("Исправить последний закрытый сегмент",true) { t,e,c,f -> t.correctSegment(e,t.choices().latestRecorded()?.sourceSegmentId ?: throw CoreError(ErrorCode.INVALID_INPUT,"Нет закрытого записанного сегмента"),time(f.start),time(f.end),c) }
        command("Отменить доступную правку") { t,e,_,f -> t.undo(e) }
        command("Подтвердить хвост указанным интервалом") { t,e,_,f -> t.resolveTail(e,time(f.start),time(f.end)) }
        command("Исключить неопределённый хвост") { t,e,_,f -> t.resolveTail(e,null,null) }
        button("Сохранить зону отчётов…") {
            val expected = shown?.token ?: return@button;val value = zone.text.toString().trim()
            try { ZoneId.of(value) } catch(_: Exception) { status.text = "Неизвестная IANA зона";return@button }
            AlertDialog.Builder(this).setTitle("Изменить зону отчётов на $value?")
                .setMessage("Изменятся границы дней и вид отчётов по существующей истории. Физические интервалы и отдельные сохранённые зоны целей сохраняются. Смена зоны телефона не изменяет эту настройку автоматически.")
                .setNegativeButton("Отмена",null).setPositiveButton("Сохранить") { _,_ -> runAction { runtime.tracker.settings(expected,runtime.tracker.data().settings.copy(reportZone = value)) } }.show()
        }
        button("Экспорт полного JSON") { beginExport("application/json","not-ai-backup.json") { runtime.backups.write(it) } }
        button("Повторить подготовленный экспорт…") { retryExport() }
        button("Отменить подготовленный экспорт") {
            val id = pendingExportId ?: exportOperations.latestPending()?.id
            if(id != null) runAction { exportOperations.cancel(id);withContext(Dispatchers.Main) { status.text = "Экспорт отменён; файлы сохранены. Можно подготовить новую копию.";pendingExportId = null } }
        }
        button("CSV отчёт за сегодня (не полная копия)") { runAction {
            val t = runtime.tracker; val d = t.data(); val now = t.clock.now()
            val report = t.view().day(Instant.ofEpochMilli(now.wall).atZone(ZoneId.of(d.settings.reportZone)).toLocalDate())
            val text = Csv.export(report)
            withContext(Dispatchers.Main) { beginExport("text/csv","not-ai-today.csv") { it.write(text.toByteArray(Charsets.UTF_8)) } }
        } }
    }
    private fun chooseCard() = runAction {
        val choices = runtime.tracker.choices();showChoices(choices,ChoiceQuery(kinds = setOf(ChoiceKind.CARD))) { hit ->
            selectedCardId = hit.entry.sourceId;status.text = "Выбрана: ${hit.entry.title} · ID ${hit.entry.sourceId}"
        }
    }
    private fun chooseType() {
        val query = EditText(this).apply { hint = "Поиск в полном каталоге" }
        AlertDialog.Builder(this).setTitle("Каталог").setView(query).setNegativeButton("Отмена",null)
            .setPositiveButton("Найти") { _,_ -> val value = query.text.toString();runAction {
                val choices = runtime.tracker.choices();showChoices(choices,ChoiceQuery(text = value,kinds = setOf(ChoiceKind.TYPE))) { hit -> chooseCatalogEntry(choices,hit) }
            } }.show()
    }
    private fun chooseCatalogEntry(view: ChoiceView,hit: ChoiceHit) {
        if(hit.entry.availability == ChoiceAvailability.GROUP) {
            runAction { showChoices(view,ChoiceQuery(kinds = setOf(ChoiceKind.TYPE),scope = CatalogScope.CHILDREN,parentId = hit.entry.sourceId)) { child -> chooseCatalogEntry(view,child) } }
            return
        }
        if(!hit.entry.canStart) { status.text = "Недоступно для нового занятия: ${hit.entry.availability}";return }
        runAction {
            val card = runtime.tracker.data().cardFromType(hit.entry.sourceId)
            runtime.tracker.saveCard(view.token,card)
            withContext(Dispatchers.Main) { selectedCardId = card.id;name.setText(card.name) }
        }
    }
    private suspend fun showChoices(view: ChoiceView,query: ChoiceQuery,cursor: ChoiceCursor? = null,choose: (ChoiceHit)->Unit) {
        val page = view.search(query,cursor,pageSize = 30)
        withContext(Dispatchers.Main) {
            val labels = page.items.map { "${it.entry.title} · ${it.entry.sourceId}\n${it.entry.path.joinToString(" / ") { p -> p.name }}" }
            val dialog = AlertDialog.Builder(this@MainActivity).setTitle("Выбор: ${page.total}")
                .setItems(labels.toTypedArray()) { _,index -> choose(page.items[index]) }.setNegativeButton("Отмена",null)
            if(page.next != null) dialog.setPositiveButton("Дальше") { _,_ -> runAction { showChoices(view,query,page.next,choose) } }
            dialog.show()
        }
    }
    private fun buildTransferControls() {
        button("Добавить историю CSV…") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*";addCategory(Intent.CATEGORY_OPENABLE) },3) }
        button("Количество для целой сессии/сегмента…") {
            val expected = shown?.token ?: return@button
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun field(label: String) = EditText(this).apply { hint = label;form.addView(this) }
            val metric = field("ID величины");val version = field("meaningVersion").apply { setText("1") }
            val unit = field("ID исходной единицы");val value = field("Неотрицательное Decimal, точка")
            val target = field("ID всей сессии или сегмента")
            val scopeInput = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,Scope.entries.map { it.name });form.addView(this) }
            val error = TextView(this).also { form.addView(it) }
            val dialog = AlertDialog.Builder(this).setTitle("Количество всего target; дневные окна не складываются")
                .setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val draft = runCatching { MeasurementDraft(metric.text.toString(),version.text.toString().toInt(),Decimal(value.text.toString()),
                        unit.text.toString(),Scope.valueOf(scopeInput.selectedItem.toString()),target.text.toString()) }
                    draft.onFailure { error.text = "Не сохранено: ${it.message}; исправьте форму или отмените" }
                    draft.onSuccess { captured -> runAction {
                        val prepared = runtime.tracker.data().prepareMeasurement(captured)
                        val measurement = prepared.measurement
                        if(measurement == null) withContext(Dispatchers.Main) { error.text = prepared.error }
                        else {
                            runtime.tracker.measure(expected,measurement)
                            withContext(Dispatchers.Main) { status.text = "Сохранено ${measurement.value} ${measurement.unit} для всего ${measurement.targetId}";dialog.dismiss() }
                        }
                    } }
                }
            }
            dialog.show()
        }
    }

    private fun inspectCsv(bytes: ByteArray) = runAction {
        val inspection = HistoryFiles.inspect(bytes)
        if(inspection.format != ImportFormat.DELIMITED_TEXT) throw CoreError(ErrorCode.UNSUPPORTED_IMPORT,inspection.problems.joinToString())
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@MainActivity).setTitle("Выберите разделитель")
                .setItems(inspection.candidates.map { "${if(it.delimiter == '\t') "TAB" else it.delimiter} · ${it.columns.joinToString()}" }.toTypedArray()) { _,index ->
                    csvMapping(inspection,inspection.candidates[index])
                }.setNegativeButton("Отмена",null).show()
        }
    }
    private fun csvMapping(inspection: ImportInspection,candidate: ColumnInspection) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(label: String,suggested: String = "") = EditText(this).apply { hint = label;setText(suggested);fields.addView(this) }
        fun suggestion(kind: ImportField) = candidate.suggestions[kind]?.firstOrNull().orEmpty()
        val namespace = field("Стабильный namespace источника","csv:local")
        val activity = field("Колонка занятия",suggestion(ImportField.ACTIVITY));val start = field("Колонка начала",suggestion(ImportField.START))
        val end = field("Колонка конца (или duration)",suggestion(ImportField.END));val duration = field("Колонка duration",suggestion(ImportField.DURATION))
        val startTime = field("Отдельная колонка времени начала",suggestion(ImportField.START_TIME));val endTime = field("Отдельная колонка времени конца",suggestion(ImportField.END_TIME))
        val category = field("Колонка категории",suggestion(ImportField.CATEGORY));val noteColumn = field("Колонка заметки",suggestion(ImportField.NOTE));val record = field("Колонка ID строки",suggestion(ImportField.ID))
        val pattern = field("Pattern локальной даты/времени; пусто = ISO с offset")
        val sourceZone = field("Зона времени источника; подтвердите явно",zone.text.toString())
        val format = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,DurationFormat.entries.map { it.name });fields.addView(this) }
        val useCard = CheckBox(this).apply { text = "Все строки → выбранная карточка; иначе исходные имена без типа";fields.addView(this) }
        AlertDialog.Builder(this).setTitle("Подтвердите колонки, время и единицы duration").setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton("Отмена",null).setPositiveButton("Preview") { _,_ ->
                fun optional(input: EditText) = input.text.toString().takeIf { it.isNotBlank() }
                val mapping = CsvMapping(namespace.text.toString(),activity.text.toString(),start.text.toString(),end = optional(end),duration = optional(duration),
                    startTime = optional(startTime),endTime = optional(endTime),category = optional(category),note = optional(noteColumn),id = optional(record),
                    localPattern = optional(pattern),zone = sourceZone.text.toString(),delimiter = candidate.delimiter,durationFormat = DurationFormat.valueOf(format.selectedItem.toString()))
                val card = if(useCard.isChecked) selectedCardId else null
                if(useCard.isChecked && card == null) { status.text = "Выберите карточку по ID перед CSV mapping";return@setPositiveButton }
                runAction {
                    val file = inspection.confirmCsv(candidate.delimiter,mapping,CsvTimeChoice(mapping.localPattern,mapping.zone),mapping.durationFormat)
                    val selection = card?.let { id -> runtime.tracker.data().cards.single { it.id == id }.classification() }
                    val preview = runtime.historyTransfer.preview(file,file.activities.associateWith { selection ?: Classification(name = it.activity.takeIf { text -> text.isNotBlank() }) })
                    withContext(Dispatchers.Main) { showCsvPreview(preview) }

                }
            }.show()
    }
    private fun showCsvPreview(preview: HistoryPreview,offset: Int = 0) {
        val rows = preview.intervals.drop(offset).take(30)
        val text = rows.joinToString("\n") { "${Instant.ofEpochMilli(it.start)} → ${Instant.ofEpochMilli(it.end!!)} · ${it.selection.name.orEmpty()} · ${it.selection.cardId ?: "без карточки"}" }
        val dialog = AlertDialog.Builder(this).setTitle("CSV append: ${offset+1}…${offset+rows.size} из ${preview.added}")
            .setMessage("Добавится: ${preview.added}; повторов: ${preview.duplicates}.\n$text\nОшибок: ${preview.errors.size}\n${preview.errors.take(30).joinToString("\n")}\n${preview.warnings.joinToString("\n")}\nДобавление истории; полный JSON restore заменяет всё хранилище.")
            .setNegativeButton("Отмена",null)
        if(preview.canApply) dialog.setPositiveButton("Добавить") { _,_ -> runAction { runtime.historyTransfer.apply(preview) } }
        if(offset+rows.size < preview.added) dialog.setNeutralButton("Следующие интервалы") { _,_ -> showCsvPreview(preview,offset+30) }
        dialog.show()
    }
    private fun buildFocusControls() {
        command("Начать Focus",true) { t,e,c,f -> t.startFocus(e,c) }
        command("Следующий этап Focus") { t,e,_,_ -> t.focusNext(e) }
        command("Пропустить этап Focus") { t,e,_,_ -> t.focusSkip(e) }
        command("Завершить Focus") { t,e,_,_ -> t.cancelFocus(e) }
        var loading = true
        val signal = CheckBox(this).apply { text = "Фоновый сигнал Focus (уведомление, ОС может задержать)";isEnabled = false }
        body.addView(signal)
        scope.launch { signal.isChecked = withContext(Dispatchers.IO) { runtime.tracker.data().settings.surfaces.focusSignal };loading = false;signal.isEnabled = true }
        signal.setOnCheckedChangeListener { _,enabled ->
            if(loading) return@setOnCheckedChangeListener
            if(enabled && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),20)
            runAction { val t = runtime.tracker;val d = t.data();t.settings(t.snapshot().token,d.settings.copy(surfaces = d.settings.surfaces.copy(focusSignal = enabled))) }
        }
    }
    private fun buildCalendarControl() {
        button("Выбрать occurrence из календаря…") {
            if(checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR),21)
            else discoverCalendar()
        }
    }
    private fun discoverCalendar() = runAction {
        val now = runtime.tracker.clock.now().wall
        withContext(Dispatchers.Main) { showCalendarPage(now,minOf(MAX_TIME,now+7*86_400_000L)) }
    }
    private fun showCalendarPage(fromTime: Long,untilTime: Long,cursor: CalendarPageCursor? = null): Unit = runAction {
        val page = runtime.calendarOccurrences.list(fromTime,untilTime,enabled = true,limit = 30,cursor = cursor)
        withContext(Dispatchers.Main) {
            if(page.issue != null) { status.text = "Календарь: ${page.issue}; история не изменена";return@withContext }
            val dialog = AlertDialog.Builder(this@MainActivity).setTitle("Календарь: текущие и ближайшие occurrences")
                .setItems(page.occurrences.map { "${it.title ?: "Без названия"} · ${if(it.allDay) "весь день" else Instant.ofEpochMilli(it.reference.begin).toString()}" }.toTypedArray()) { _,index ->
                    val ref = page.occurrences[index].reference
                    runAction {
                        val result = runtime.calendarOccurrences.readSelected(ref,enabled = true)
                        val occurrence = result.occurrence ?: throw CoreError(ErrorCode.INVALID_INPUT,"Occurrence недоступен: ${result.issue}")
                        val draft = occurrence.draft()
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@MainActivity).setTitle("Заполнить редактируемый черновик?")
                                .setMessage("Start использует текущее время. Ручная запись требует явных границ; all-day не задаёт рабочий интервал.")
                                .setNegativeButton("Отмена",null).setPositiveButton("Заполнить") { _,_ ->
                                    inputDraft = draft;note.setText(draft.note.orEmpty());selectedCardId = null
                                    from.setText(draft.interval?.start?.let { Instant.ofEpochMilli(it).toString() }.orEmpty())
                                    until.setText(draft.interval?.end?.let { Instant.ofEpochMilli(it).toString() }.orEmpty())
                                }.show()
                        }
                    }
                }.setNegativeButton("Отмена",null)
            if(page.next != null) dialog.setPositiveButton("Дальше") { _,_ -> showCalendarPage(fromTime,untilTime,page.next) }
            dialog.show()
        }
    }
    private suspend fun buildSurfaceControls() {
        val preferences = withContext(Dispatchers.IO) { runtime.tracker.data().settings.surfaces }
        surfaceStatus = TextView(this).also { body.addView(it) }
        fun toggle(source: Surface,label: String,initial: Boolean,change: (SurfaceSettings,Boolean)->SurfaceSettings,permission: ()->Unit = {}) {
            body.addView(CheckBox(this).apply {
                text = label; isChecked = initial; surfaceToggles[source] = this
                setOnCheckedChangeListener { _,enabled -> if(!renderingSurfaceSettings) runAction {
                    val t = runtime.tracker; val view = t.view()
                    t.settings(view.snapshot().token,view.data.settings.copy(surfaces = change(view.data.settings.surfaces,enabled)))
                    withContext(Dispatchers.Main) { if(enabled) permission(); refreshSurfacePermissions() }
                } }
            })
        }
        toggle(Surface.WIDGET,"Виджет (добавить через launcher)",preferences.widget,{ s,b -> s.copy(widget = b) })
        toggle(Surface.NOTIFICATION,"Управляющее уведомление",preferences.notification,{ s,b -> s.copy(notification = b) }) {
            if(android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),20)
        }
        toggle(Surface.OVERLAY,"Плавающая кнопка + обязательный индикатор сервиса",preferences.overlay,{ s,b -> s.copy(overlay = b) }) {
            if(!TimerSurfaces.overlayAllowed(this)) try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:$packageName")))
            } catch(_: android.content.ActivityNotFoundException) { surfaceStatus.text = "Настройки разрешения недоступны на этом устройстве" }
        }
        button("Проверить разрешения поверхностей") { refreshSurfacePermissions() }
    }
    private fun renderSurfaceSettings(settings: SurfaceSettings) {
        renderingSurfaceSettings = true
        try { surfaceToggles.forEach { (source,box) -> box.isChecked = TimerSurfaces.enabled(settings,source) } }
        finally { renderingSurfaceSettings = false }
    }
    private fun refreshSurfacePermissions() {
        if(!::runtime.isInitialized || !::surfaceStatus.isInitialized) return
        scope.launch {
            val view = withContext(Dispatchers.IO) { runtime.tracker.view().also { TimerSurfaces.refresh(this@MainActivity,it,forceWidget = true) } }
            val settings = view.data.settings.surfaces
            renderSurfaceSettings(settings)
            val overlay = if(resumed) FloatingTimerService.fromVisibleActivity(this@MainActivity,settings) else "Overlay запускается только из видимого приложения"
            surfaceStatus.text = "Уведомление: ${if(!settings.notification) "выключено" else if(TimerSurfaces.notificationsAllowed(this@MainActivity)) "разрешено" else "запрошено, но запрещено ОС/каналом"}\n$overlay"
        }
    }
    override fun onResume() { super.onResume(); resumed = true; refreshSurfacePermissions() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode == 20) refreshSurfacePermissions()
        if(requestCode == 20 && ::runtime.isInitialized) runAction { FocusSignals.refresh(this@MainActivity,runtime.tracker.view()) }
        if(requestCode == 21 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) discoverCalendar()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        TimerSurfaces.saveShortcut(this,outState,shortcutPending) // also covers recreation before runtime initialization
        try {
            formReference = formFiles.save(formReference,FormState(selectedCardId,name.text.toString(),details.text.toString(),note.text.toString(),
                from.text.toString(),until.text.toString(),zone.text.toString(),inputDraft?.source?.name,inputDraft?.sourceReference,pendingSelectedText))
            outState.putString("formReference",formReference)
        } catch(e: Exception) { status.text = "Форма не сохранена: ${e.javaClass.simpleName}: ${e.message}" }
        outState.putString("exportOperation",pendingExportId)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveSelectedText(intent)
        TimerSurfaces.parse(intent)?.takeIf { it.source == Surface.SHORTCUT }?.let { shortcutPending = it }
        intent.action = Intent.ACTION_MAIN; setIntent(intent); consumeShortcut()
    }
    private fun receiveSelectedText(incoming: Intent) {
        try {
            ProcessTextDrafts.read(incoming)?.note?.let(::reviewSelectedText)
        } catch(e: CoreError) {
            AlertDialog.Builder(this).setMessage("${e.code}: ${e.message}").setPositiveButton("OK",null).show()
        }
        if(incoming.action == Intent.ACTION_PROCESS_TEXT) {
            incoming.removeExtra(Intent.EXTRA_PROCESS_TEXT)
            incoming.action = Intent.ACTION_MAIN
        }
    }
    private fun reviewSelectedText(text: String) {
        val draft = EntryDrafts.selectedText(text)
        if(note.text.isEmpty() || note.text.toString() == text) {
            note.setText(text); inputDraft = draft; pendingSelectedText = null
        } else {
            pendingSelectedText = text
            AlertDialog.Builder(this).setTitle("Выделенный текст")
                .setMessage("Заменить текущую редактируемую заметку присланным текстом?")
                .setNegativeButton("Сохранить текущую") { _,_ -> pendingSelectedText = null }
                .setPositiveButton("Заменить") { _,_ -> note.setText(text); inputDraft = draft; pendingSelectedText = null }.show()
        }
    }
    private fun consumeShortcut() {
        if(!::runtime.isInitialized) return
        val action = shortcutPending ?: return
        val result = runtime.shortcuts.submit(action) // process-owned execution survives Activity cancellation
        shortcutPending = null
        runAction { result.await().getOrThrow() }
    }
    private fun time(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun runAction(block: suspend ()->Unit) { scope.launch {
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: Exception) { if (e is CancellationException) throw e; AlertDialog.Builder(this@MainActivity).setMessage("${(e as? CoreError)?.code ?: "Ошибка"}: ${e.message}").setPositiveButton("OK",null).show() }
    } }
    override fun onStart() { super.onStart(); visible = true; observeWhileVisible() }
    override fun onStop() { visible = false; observation?.cancel(); observation = null; super.onStop() }
    private fun observeWhileVisible() {
        if(!visible || !::runtime.isInitialized || observation != null) return
        observation = scope.launch {
            runtime.tracker.views.collectLatest { revision ->
                renderSurfaceSettings(revision.data.settings.surfaces)
                reportText = withContext(Dispatchers.IO) {
                    val d = revision.data; val now = runtime.tracker.clock.now()
                    val report = revision.day(Instant.ofEpochMilli(now.wall).atZone(ZoneId.of(d.settings.reportZone)).toLocalDate())
                    "Итоги на ${Instant.ofEpochMilli(report.asOf)} (${report.zone}): ${report.recordedMs} ms; неопределённых: ${report.unresolved.size}\nКарточки: ${d.cards.joinToString { it.name }}\nСессий: ${d.sessions.size}; сегментов: ${d.segments.size}\n${report.rows.joinToString("\n") { "${it.selection.name ?: "Без уточнения"}: ${it.durationMs} ms" }}"
                }
                while(isActive) {
                    val s = revision.snapshot(); shown = s
                    val focusText = s.focus?.let { "${it.safeLabel}: ${it.remainingMs} ms\n" }.orEmpty()
                    status.text = "Техническая оболочка · Room 3\n${s.safeLabel}\n${s.trustedDurationMs} ms · ревизия ${s.token.revision}\n$focusText${s.session?.selection ?: ""}\n$reportText"
                    runtime.focusDueSignals.consume(s.focus) { focus ->
                        if(revision.data.settings.surfaces.focusSignal && FocusSignals.allowed(this@MainActivity)) FocusSignals.deliver(this@MainActivity,focus)
                        else AlertDialog.Builder(this@MainActivity).setTitle("Focus").setMessage(focus.safeLabel)
                            .setPositiveButton("OK",null).show()
                    }
                    if(s.focus?.let { it.mode != FocusMode.RUNNING } ?: (s.mode != Mode.RUNNING)) break
                    delay(1000)
                }
            }
        }
    }
    private fun beginExport(mime: String,title: String,write: (java.io.OutputStream)->Unit) {
        val active = pendingExportId?.let { runCatching { exportOperations.read(it) }.getOrNull() }
        if(active?.state in setOf("PREPARING","READY")) { status.text = "Завершите или отмените текущий экспорт";return }
        val op = try { exportOperations.begin(mime,title) } catch(e: Exception) { status.text = "Подготовка экспорта не началась: ${e.message}";return }
        pendingExportId = op.id
        runAction { val prepared = exportOperations.prepare(op.id,write);withContext(Dispatchers.Main) { if(prepared.state == "READY") showExportPicker(op.id) } }
    }
    private fun showExportPicker(id: String) {
        val op = exportOperations.read(id)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = op.mime;addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,op.title) },1)
    }
    private fun retryExport() {
        val id = pendingExportId ?: exportOperations.latestPending()?.id
        if(id == null) { status.text = "Нет подготовленного экспорта";return }
        pendingExportId = id
        val op = exportOperations.read(id)
        if(op.state in setOf("READY","CANCELLED")) showExportPicker(id) else status.text = "Экспорт ${op.state}: повторите подготовку; частичный файл сохранён"
    }
    private fun restoreSource(file: java.io.File) = runAction {
        if(::runtime.isInitialized && !DatabaseRecovery.needsAttention(this@MainActivity)) {
            val preview = runtime.backups.prepare { file.inputStream() }
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity).setTitle("Подтвердить полную замену")
                    .setMessage("${preview.consequence}\nСессий: ${preview.sessions}; сегментов: ${preview.segments}; целей: ${preview.goals}. Активный хвост: ${preview.unfinishedTail}; Focus: ${preview.unfinishedFocus}")
                    .setNegativeButton("Отмена",null).setPositiveButton("Заменить") { _,_ -> runAction { val result = runtime.backups.apply(preview);withContext(Dispatchers.Main) {
                        status.text = "Восстановлено. Откатная копия: ${result.rollbackReference}"
                    } } }.show()
            }
        } else {
            val preview = DatabaseRecovery.prepare(file)
            withContext(Dispatchers.Main) { AlertDialog.Builder(this@MainActivity).setTitle("Восстановить недоступную базу")
                .setMessage("Сессий: ${preview.sessions}; сегментов: ${preview.segments}. Текущие DB/WAL/SHM будут сохранены с проверенными хешами до замены. Старые копии сохраняются.")
                .setNegativeButton("Отмена",null).setPositiveButton("Восстановить") { _,_ -> runAction { DatabaseRecovery.apply(this@MainActivity,preview);withContext(Dispatchers.Main) { recreate() } } }.show() }
        }
    }
    private fun buildRecoveryControls() {
        button("Восстановить полный JSON…") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json";addCategory(Intent.CATEGORY_OPENABLE) },2) }
        button("Локальные rollback копии…") { runAction {
            val files = PrivateRollbackStore(this@MainActivity).files()
            withContext(Dispatchers.Main) { if(files.isEmpty()) status.text = "Локальных копий пока нет" else AlertDialog.Builder(this@MainActivity)
                .setTitle("Выберите копию; текущие данные заменяются только после preview")
                .setItems(files.map { "${it.name} · ${it.length()} bytes" }.toTypedArray()) { _,index -> restoreSource(files[index]) }.show() }
        } }
        if(DatabaseRecovery.needsAttention(this)) button("Завершить незавершённое восстановление") {
            runAction { DatabaseRecovery.resume(this@MainActivity);withContext(Dispatchers.Main) { recreate() } }
        }
    }
    @Deprecated("Technical harness uses framework result API")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode != RESULT_OK || data?.data == null) {
            if(requestCode == 1) pendingExportId?.let { id -> runAction { exportOperations.cancel(id);withContext(Dispatchers.Main) { status.text = "Экспорт отменён; подготовленная копия доступна для повтора" } } }
            return
        }
        val uri = data.data!!
        if(requestCode == 1) {
            val id = pendingExportId ?: return
            runAction { val result = exportOperations.write(id,uri);withContext(Dispatchers.Main) {
                status.text = "Записано ${result.bytes} bytes. ${if(result.verified) "Копия проверена чтением." else "Provider не позволяет прочитать результат для проверки."}";pendingExportId = null
            } }
        } else if(requestCode == 3) runAction {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream();val buffer = ByteArray(8192)
                while(out.size() <= MAX_CSV_BYTES) { val n = input.read(buffer,0,minOf(buffer.size,MAX_CSV_BYTES+1-out.size()));if(n<0) break;if(n==0) { val b=input.read();if(b<0) break;out.write(b) } else out.write(buffer,0,n) }
                out.toByteArray()
            } ?: throw java.io.IOException("Источник CSV недоступен")
            withContext(Dispatchers.Main) { inspectCsv(bytes) }
        } else if(requestCode == 2) runAction {
            val file = contentResolver.openInputStream(uri)?.use { stageRestore(this@MainActivity,it) } ?: throw java.io.IOException("Источник недоступен")
            withContext(Dispatchers.Main) { restoreSource(file) }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
