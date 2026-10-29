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

/** Technical harness: every core API without product design (CSV mapping, measurements, calendar, database recovery).
 * The product interface is MainActivity. No domain transitions, totals or duration arithmetic live here. */
class TechnicalActivity: Activity() {
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
    private var tracedReady = false
    private val restoreReviews = RestoreReviewOwner<AutoCloseable> { CopyLifetimes.get(this).releasePreview(it) }
    private var pendingRestoreDialog: AlertDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.mark(this,"activity-create")
        shortcutPending = TimerSurfaces.restoredShortcut(savedInstanceState,intent)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,48,24,24) }
        setContentView(ScrollView(this).apply { addView(body) })
        status = TextView(this); body.addView(status)
        name = input(L.s(R.string.t_9e8bfc9912)).apply { id = R.id.form_name }
        details = input(L.s(R.string.t_66c789d74f)).apply { id = R.id.form_details }
        note = input(L.s(R.string.t_917b58362c)).apply { id = R.id.form_note }
        from = input(L.s(R.string.t_4ebd720453)).apply { id = R.id.form_from }
        until = input(L.s(R.string.t_9711f6b5b7)).apply { id = R.id.form_until }
        zone = input(L.s(R.string.t_b67fbff66e)).apply { id = R.id.form_zone;setText(ZoneId.systemDefault().id) }
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
            } catch(e: Exception) { status.text = L.s(R.string.t_4ebf488399, e.javaClass.simpleName) }
        } else if(savedInstanceState?.containsKey("selectedTextDraft") == true) {
            note.setText(savedInstanceState.getString("selectedTextDraft"))
            if(savedInstanceState.getBoolean("isSelectedTextDraft")) inputDraft = EntryDraft(Classification(),note.text.toString().takeIf { it.isNotEmpty() },source = DraftSource.SELECTED_TEXT)
        } else receiveSelectedText(intent)
        pendingSelectedText?.let(::reviewSelectedText)
        buildRecoveryControls()
        intent.action = Intent.ACTION_MAIN
        scope.launch {
            try { runtime = withContext(Dispatchers.IO) { TrackerRuntime.get(this@TechnicalActivity) } }
            catch(e: Exception) { status.text = L.s(R.string.t_7da3c34069, e.message);return@launch }
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
        button(L.s(R.string.t_129dc42035)) {
            val expected = shown?.token ?: return@button; val value = name.text.toString(); val activity = details.text.toString()
            val card = Card(name = value,details = if(activity.isBlank()) emptyMap() else mapOf("activity" to activity))
            runAction { runtime.tracker.saveCard(expected,card);withContext(Dispatchers.Main) { selectedCardId = card.id;status.text = L.s(R.string.t_51f805f925, card.id) } }
        }
        button(L.s(R.string.t_434e93a022)) { chooseCard() }
        button(L.s(R.string.t_93f389d809)) { selectedCardId = null;status.text = L.s(R.string.t_e6aa58192b) }
        button(L.s(R.string.t_489dd4cb3d)) { chooseType() }
        button(L.s(R.string.t_eeee074ac3)) { runAction { val preview = runtime.prepareBundledCatalog();withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@TechnicalActivity).setTitle(L.s(R.string.t_ba9db63b9d, preview.version))
                .setMessage(L.s(R.string.t_5de1eb4784, preview.report.added.size, preview.report.updated.size, preview.report.conflicts.size))
                .setNegativeButton(L.s(R.string.t_0ec753be8d),null).setPositiveButton(L.s(R.string.t_768af67762)) { _,_ -> runAction { runtime.catalogImports.apply(preview) } }.show()
        } } }
        buildCalendarControl()
        buildFocusControls()
        buildTransferControls()
        command("Start",true) { t,e,c,f ->
            if(f.draft == null) t.start(e,c,f.note.takeIf { it.isNotBlank() })
            else t.startDraft(e,f.draft.copy(selection = c,note = f.note.takeIf { it.isNotEmpty() }))
        }
        command(L.s(R.string.t_0195b83c6c),true) { t,e,c,f -> t.labelCurrent(e,c,f.note) }
        command(L.s(R.string.t_14d31156f2),true) { t,e,c,f -> t.changeDetails(e,c) }
        for(directive in listOf(TimerDirective.PAUSE,TimerDirective.RESUME,TimerDirective.STOP)) button(directive.name) {
            val target = shown?.timerTarget ?: return@button
            runAction { runtime.tracker.timerCommand(target,directive) }
        }
        command("Switch",true) { t,e,c,f -> t.switch(e,c) }
        command(L.s(R.string.t_86fde46076)) { t,e,_,f -> t.repeat(e,t.choices().latestRecorded()?.sourceSessionId ?: throw CoreError(ErrorCode.INVALID_INPUT,L.s(R.string.t_1a54271b7a))) }
        command(L.s(R.string.t_f11be88b10),true) { t,e,c,f -> t.saveFavorite(e,Favorite(label = c.name ?: L.s(R.string.t_5a7fa46600),selection = c)) }
        button(L.s(R.string.t_4c70fed089)) { runAction {
            val choices = runtime.tracker.choices();showChoices(choices,ChoiceQuery(kinds = setOf(ChoiceKind.FAVORITE),includeArchived = true)) { hit ->
                val expected = choices.token
                AlertDialog.Builder(this@TechnicalActivity).setTitle(L.s(R.string.t_08f120e3b9))
                    .setMessage(L.s(R.string.t_b747495fc3, hit.entry.title, hit.entry.sourceId, hit.entry.availability))
                    .setNegativeButton(L.s(R.string.t_0ec753be8d),null).setPositiveButton(L.s(R.string.t_a1103b6e0c)) { _,_ -> runAction { runtime.tracker.startFavorite(expected,hit.entry.sourceId) } }.show()
            }
        } }
        command(L.s(R.string.t_0ad4a07529),true) { t,e,c,f ->
            if(f.draft == null) t.manual(e,time(f.start),time(f.end),c,f.note.takeIf { it.isNotBlank() })
            else t.recordDraft(e,f.draft.copy(selection = c,note = f.note.takeIf { it.isNotEmpty() },
                interval = DraftInterval(time(f.start),time(f.end))))
        }
        command(L.s(R.string.t_2583e76aee),true) { t,e,c,f -> t.switchedEarlier(e,time(f.start),c) }
        command(L.s(R.string.t_fdfa49e623),true) { t,e,c,f -> t.correctSegment(e,t.choices().latestRecorded()?.sourceSegmentId ?: throw CoreError(ErrorCode.INVALID_INPUT,L.s(R.string.t_7f76296ba9)),time(f.start),time(f.end),c) }
        command(L.s(R.string.t_9cae6690eb)) { t,e,_,f -> t.undo(e) }
        command(L.s(R.string.t_d398842570)) { t,e,_,f -> t.resolveTail(e,time(f.start),time(f.end)) }
        command(L.s(R.string.t_e98d66a21e)) { t,e,_,f -> t.resolveTail(e,null,null) }
        button(L.s(R.string.t_e5451eea1b)) {
            val expected = shown?.token ?: return@button;val value = zone.text.toString().trim()
            try { ZoneId.of(value) } catch(_: Exception) { status.text = L.s(R.string.t_5873f87916);return@button }
            AlertDialog.Builder(this).setTitle(L.s(R.string.t_96a64755de, value))
                .setMessage(L.s(R.string.t_b905452397))
                .setNegativeButton(L.s(R.string.t_0ec753be8d),null).setPositiveButton(L.s(R.string.t_4864057d62)) { _,_ -> runAction { runtime.reportZone.setFixed(expected,value) } }.show()
        }
        button(L.s(R.string.t_12fa39305e)) { beginExport("application/json","not-ai-backup.json") { runtime.backups.write(it) } }
        button(L.s(R.string.t_aa9fc365f5)) { retryExport() }
        button(L.s(R.string.t_253cf1644f)) { cancelPreparedExport() }
        button(L.s(R.string.t_cc6d018f5b)) { runAction {
            val t = runtime.tracker; val d = t.data(); val now = t.clock.now()
            val report = t.view().day(ReportingDayPolicy(d.settings.reportZone,d.settings.reportDayStartMinute).dateAt(now.wall))
            withContext(Dispatchers.Main) { beginExport("text/csv","not-ai-today.csv") { out ->
                val writer = out.bufferedWriter(Charsets.UTF_8);Csv.write(report,writer);writer.flush()
            } }
        } }
    }
    private fun chooseCard() = runAction {
        val choices = runtime.tracker.choices();showChoices(choices,ChoiceQuery(kinds = setOf(ChoiceKind.CARD))) { hit ->
            selectedCardId = hit.entry.sourceId;status.text = L.s(R.string.t_a12db4c166, hit.entry.title, hit.entry.sourceId)
        }
    }
    private fun chooseType() {
        val query = EditText(this).apply { hint = L.s(R.string.t_657703c887) }
        AlertDialog.Builder(this).setTitle(L.s(R.string.t_46e27e2f0a)).setView(query).setNegativeButton(L.s(R.string.t_0ec753be8d),null)
            .setPositiveButton(L.s(R.string.t_ed6e1d0308)) { _,_ -> val value = query.text.toString();runAction {
                val choices = runtime.tracker.choices();showChoices(choices,ChoiceQuery(text = value,kinds = setOf(ChoiceKind.TYPE))) { hit -> chooseCatalogEntry(choices,hit) }
            } }.show()
    }
    private fun chooseCatalogEntry(view: ChoiceView,hit: ChoiceHit) {
        if(hit.entry.availability == ChoiceAvailability.GROUP) {
            runAction { showChoices(view,ChoiceQuery(kinds = setOf(ChoiceKind.TYPE),scope = CatalogScope.CHILDREN,parentId = hit.entry.sourceId)) { child -> chooseCatalogEntry(view,child) } }
            return
        }
        if(!hit.entry.canStart) { status.text = L.s(R.string.t_d524af03ac, hit.entry.availability);return }
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
            val dialog = AlertDialog.Builder(this@TechnicalActivity).setTitle(L.s(R.string.t_7d4b8f8a7e, page.total))
                .setItems(labels.toTypedArray()) { _,index -> choose(page.items[index]) }.setNegativeButton(L.s(R.string.t_0ec753be8d),null)
            if(page.next != null) dialog.setPositiveButton(L.s(R.string.t_e3b9331301)) { _,_ -> runAction { showChoices(view,query,page.next,choose) } }
            dialog.show()
        }
    }
    private fun buildTransferControls() {
        button(L.s(R.string.t_6400526c15)) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*";addCategory(Intent.CATEGORY_OPENABLE) },3) }
        button(L.s(R.string.t_007f15b539)) {
            val expected = shown?.token ?: return@button
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun field(label: String) = EditText(this).apply { hint = label;form.addView(this) }
            val metric = field(L.s(R.string.t_ce12182bac));val version = field("meaningVersion").apply { setText("1") }
            val unit = field(L.s(R.string.t_b4efdc2013));val value = field(L.s(R.string.t_69e5b8a8c6))
            val target = field(L.s(R.string.t_9bc1f05bd8))
            val scopeInput = Spinner(this).apply { adapter = ArrayAdapter(this@TechnicalActivity,android.R.layout.simple_spinner_dropdown_item,Scope.entries.map { it.name });form.addView(this) }
            val error = TextView(this).also { form.addView(it) }
            val dialog = AlertDialog.Builder(this).setTitle(L.s(R.string.t_de5d4764e8))
                .setView(form).setNegativeButton(L.s(R.string.t_0ec753be8d),null).setPositiveButton(L.s(R.string.t_4864057d62),null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val draft = runCatching { MeasurementDraft(metric.text.toString(),version.text.toString().toInt(),Decimal(value.text.toString()),
                        unit.text.toString(),Scope.valueOf(scopeInput.selectedItem.toString()),target.text.toString()) }
                    draft.onFailure { error.text = L.s(R.string.t_8972ce6fc3, it.message) }
                    draft.onSuccess { captured -> runAction {
                        val prepared = runtime.tracker.data().prepareMeasurement(captured)
                        val measurement = prepared.measurement
                        if(measurement == null) withContext(Dispatchers.Main) { error.text = prepared.error }
                        else {
                            runtime.tracker.measure(expected,measurement)
                            withContext(Dispatchers.Main) { status.text = L.s(R.string.t_a18bb83bf1, measurement.value, measurement.unit, measurement.targetId);dialog.dismiss() }
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
            AlertDialog.Builder(this@TechnicalActivity).setTitle(L.s(R.string.t_affff57571))
                .setItems(inspection.candidates.map { "${if(it.delimiter == '\t') "TAB" else it.delimiter} · ${it.columns.joinToString()}" }.toTypedArray()) { _,index ->
                    csvMapping(inspection,inspection.candidates[index])
                }.setNegativeButton(L.s(R.string.t_0ec753be8d),null).show()
        }
    }
    private fun csvMapping(inspection: ImportInspection,candidate: ColumnInspection) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(label: String,suggested: String = "") = EditText(this).apply { hint = label;setText(suggested);fields.addView(this) }
        fun suggestion(kind: ImportField) = candidate.suggestions[kind]?.firstOrNull().orEmpty()
        val namespace = field(L.s(R.string.t_a4ac32f350),"csv:local")
        val activity = field(L.s(R.string.t_12b0be1a44),suggestion(ImportField.ACTIVITY));val start = field(L.s(R.string.t_5f0968991b),suggestion(ImportField.START))
        val end = field(L.s(R.string.t_cefdb75984),suggestion(ImportField.END));val duration = field(L.s(R.string.t_629e3bb846),suggestion(ImportField.DURATION))
        val startTime = field(L.s(R.string.t_c8317c85b2),suggestion(ImportField.START_TIME));val endTime = field(L.s(R.string.t_9eb9a9691b),suggestion(ImportField.END_TIME))
        val category = field(L.s(R.string.t_d223a16aed),suggestion(ImportField.CATEGORY));val noteColumn = field(L.s(R.string.t_7ec7c94cc1),suggestion(ImportField.NOTE));val record = field(L.s(R.string.t_7eb07227f1),suggestion(ImportField.ID))
        val pattern = field(L.s(R.string.t_e4c06f14a4))
        val sourceZone = field(L.s(R.string.t_c67ed0b774),zone.text.toString())
        val format = Spinner(this).apply { adapter = ArrayAdapter(this@TechnicalActivity,android.R.layout.simple_spinner_dropdown_item,DurationFormat.entries.map { it.name });fields.addView(this) }
        val useCard = CheckBox(this).apply { text = L.s(R.string.t_8125e2fba0);fields.addView(this) }
        AlertDialog.Builder(this).setTitle(L.s(R.string.t_d41ba2c06d)).setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton(L.s(R.string.t_0ec753be8d),null).setPositiveButton("Preview") { _,_ ->
                fun optional(input: EditText) = input.text.toString().takeIf { it.isNotBlank() }
                val mapping = CsvMapping(namespace.text.toString(),activity.text.toString(),start.text.toString(),end = optional(end),duration = optional(duration),
                    startTime = optional(startTime),endTime = optional(endTime),category = optional(category),note = optional(noteColumn),id = optional(record),
                    localPattern = optional(pattern),zone = sourceZone.text.toString(),delimiter = candidate.delimiter,durationFormat = DurationFormat.valueOf(format.selectedItem.toString()))
                val card = if(useCard.isChecked) selectedCardId else null
                if(useCard.isChecked && card == null) { status.text = L.s(R.string.t_067a6eef15);return@setPositiveButton }
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
        val text = rows.joinToString("\n") { "${Instant.ofEpochMilli(it.start)} → ${Instant.ofEpochMilli(it.end!!)} · ${it.selection.name.orEmpty()} · ${it.selection.cardId ?: L.s(R.string.t_7aeae28ae9)}" }
        val dialog = AlertDialog.Builder(this).setTitle(L.s(R.string.t_117ce12b52, offset+1, offset+rows.size, preview.added))
            .setMessage(L.s(R.string.t_1bcdac8735, preview.added, preview.duplicates, text, preview.errors.size, preview.errors.take(30).joinToString("\n"), preview.warnings.joinToString("\n")))
            .setNegativeButton(L.s(R.string.t_0ec753be8d),null)
        if(preview.canApply) dialog.setPositiveButton(L.s(R.string.t_559a87f7cc)) { _,_ -> runAction { runtime.historyTransfer.apply(preview) } }
        if(offset+rows.size < preview.added) dialog.setNeutralButton(L.s(R.string.t_322896daf3)) { _,_ -> showCsvPreview(preview,offset+30) }
        dialog.show()
    }
    private fun buildFocusControls() {
        command(L.s(R.string.t_f0caca47ee),true) { t,e,c,f -> t.startFocus(e,c) }
        command(L.s(R.string.t_38a279b292)) { t,e,_,_ -> t.focusNext(e) }
        command(L.s(R.string.t_d73bef6ef5)) { t,e,_,_ -> t.focusSkip(e) }
        command(L.s(R.string.t_15b056d9f4)) { t,e,_,_ -> t.cancelFocus(e) }
        var loading = true
        val signal = CheckBox(this).apply { text = L.s(R.string.t_6d95221406);isEnabled = false }
        body.addView(signal)
        scope.launch { signal.isChecked = withContext(Dispatchers.IO) { runtime.tracker.data().settings.surfaces.focusSignal };loading = false;signal.isEnabled = true }
        signal.setOnCheckedChangeListener { _,enabled ->
            if(loading) return@setOnCheckedChangeListener
            if(enabled && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),20)
            runAction { configureTechnicalFocusSignal(runtime.tracker,enabled) }
        }
    }
    internal fun configureTechnicalFocusSignal(t: Tracker,enabled: Boolean) {
        t.configureFocusSignal(t.snapshot().token,enabled)
    }
    internal fun deliverTechnicalFocus(t: Tracker,focus: FocusSnapshot): Job = scope.launch {
        try {
            val result = withContext(Dispatchers.IO) { FocusSignals.deliverResult(this@TechnicalActivity,t) }
            val current = result.focus
            if(result.status == FocusDeliveryStatus.BLOCKED_BEFORE_POST && current?.mode == FocusMode.DUE && current.signalKey == focus.signalKey)
                AlertDialog.Builder(this@TechnicalActivity).setTitle("Focus").setMessage(current.safeLabel).setPositiveButton("OK",null).show()
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            status.text = dev.notai.tracker.android.ui.Format.error(e)
        }
    }
    private fun buildCalendarControl() {
        button(L.s(R.string.t_20490f3632)) {
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
            if(page.issue != null) { status.text = L.s(R.string.t_3c244ffaa5, page.issue);return@withContext }
            val dialog = AlertDialog.Builder(this@TechnicalActivity).setTitle(L.s(R.string.t_eca2a38089))
                .setItems(page.occurrences.map { "${it.title ?: L.s(R.string.t_32b74a3c47)} · ${if(it.allDay) L.s(R.string.t_6ae7f2feef) else Instant.ofEpochMilli(it.reference.begin).toString()}" }.toTypedArray()) { _,index ->
                    val ref = page.occurrences[index].reference
                    runAction {
                        val result = runtime.calendarOccurrences.readSelected(ref,enabled = true)
                        val occurrence = result.occurrence ?: throw CoreError(ErrorCode.INVALID_INPUT,L.s(R.string.t_bf38c3fd75, result.issue))
                        val draft = occurrence.draft()
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@TechnicalActivity).setTitle(L.s(R.string.t_d129caabe1))
                                .setMessage(L.s(R.string.t_7b47d9e19e))
                                .setNegativeButton(L.s(R.string.t_0ec753be8d),null).setPositiveButton(L.s(R.string.t_d261564113)) { _,_ ->
                                    inputDraft = draft;note.setText(draft.note.orEmpty());selectedCardId = null
                                    from.setText(draft.interval?.start?.let { Instant.ofEpochMilli(it).toString() }.orEmpty())
                                    until.setText(draft.interval?.end?.let { Instant.ofEpochMilli(it).toString() }.orEmpty())
                                }.show()
                        }
                    }
                }.setNegativeButton(L.s(R.string.t_0ec753be8d),null)
            if(page.next != null) dialog.setPositiveButton(L.s(R.string.t_e3b9331301)) { _,_ -> showCalendarPage(fromTime,untilTime,page.next) }
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
        toggle(Surface.WIDGET,L.s(R.string.t_544a30f1ea),preferences.widget,{ s,b -> s.copy(widget = b) })
        toggle(Surface.NOTIFICATION,L.s(R.string.t_9bcc818ccb),preferences.notification,{ s,b -> s.copy(notification = b) }) {
            if(android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),20)
        }
        toggle(Surface.OVERLAY,L.s(R.string.t_494ef4deb5),preferences.overlay,{ s,b -> s.copy(overlay = b) }) {
            if(!TimerSurfaces.overlayAllowed(this)) try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:$packageName")))
            } catch(_: android.content.ActivityNotFoundException) { surfaceStatus.text = L.s(R.string.t_8213ed5bbc) }
        }
        button(L.s(R.string.t_e8255d5bdc)) { refreshSurfacePermissions() }
    }
    private fun renderSurfaceSettings(settings: SurfaceSettings) {
        renderingSurfaceSettings = true
        try { surfaceToggles.forEach { (source,box) -> box.isChecked = TimerSurfaces.enabled(settings,source) } }
        finally { renderingSurfaceSettings = false }
    }
    private fun refreshSurfacePermissions() {
        if(!::runtime.isInitialized || !::surfaceStatus.isInitialized) return
        scope.launch {
            val view = withContext(Dispatchers.IO) { runtime.tracker.view().also { TimerSurfaces.refresh(this@TechnicalActivity,runtime.tracker,it,forceWidget = true) } }
            val settings = view.data.settings.surfaces
            renderSurfaceSettings(settings)
            val overlay = if(resumed) FloatingTimerService.fromVisibleActivity(this@TechnicalActivity,settings) else L.s(R.string.t_781ebc1c5f)
            surfaceStatus.text = L.s(R.string.t_963e150816, if(!settings.notification) L.s(R.string.t_bedfedb4d0) else if(TimerSurfaces.notificationsAllowed(this@TechnicalActivity)) L.s(R.string.t_877c853fa7) else L.s(R.string.t_964fb00c4e), overlay)
        }
    }
    override fun onResume() { super.onResume(); resumed = true; refreshSurfacePermissions() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode == 20) refreshSurfacePermissions()
        if(requestCode == 20 && ::runtime.isInitialized) runAction { FocusSignals.refresh(this@TechnicalActivity,runtime.tracker.view()) }
        if(requestCode == 21 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) discoverCalendar()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        TimerSurfaces.saveShortcut(this,outState,shortcutPending) // also covers recreation before runtime initialization
        try {
            formReference = formFiles.save(formReference,FormState(selectedCardId,name.text.toString(),details.text.toString(),note.text.toString(),
                from.text.toString(),until.text.toString(),zone.text.toString(),inputDraft?.source?.name,inputDraft?.sourceReference,pendingSelectedText))
            outState.putString("formReference",formReference)
        } catch(e: Exception) { status.text = L.s(R.string.t_937cbcbd50, e.javaClass.simpleName, e.message) }
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
            AlertDialog.Builder(this).setTitle(L.s(R.string.t_c619870299))
                .setMessage(L.s(R.string.t_680a3249f0))
                .setNegativeButton(L.s(R.string.t_9e97224af0)) { _,_ -> pendingSelectedText = null }
                .setPositiveButton(L.s(R.string.t_d772cc53be)) { _,_ -> note.setText(text); inputDraft = draft; pendingSelectedText = null }.show()
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
    private fun runAction(block: suspend ()->Unit) { launchAction(block) }
    private fun launchAction(block: suspend ()->Unit): Job = scope.launch {
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: Exception) { if (e is CancellationException) throw e; AlertDialog.Builder(this@TechnicalActivity).setMessage("${(e as? CoreError)?.code ?: L.s(R.string.t_72aecd9ad8)}: ${e.message}").setPositiveButton("OK",null).show() }
    }
    override fun onStart() { super.onStart(); visible = true; observeWhileVisible() }
    override fun onStop() { visible = false; observation?.cancel(); observation = null; super.onStop() }
    private fun observeWhileVisible() {
        if(!visible || !::runtime.isInitialized || observation != null) return
        observation = scope.launch {
            runtime.tracker.views.collectLatest { revision ->
                renderSurfaceSettings(revision.data.settings.surfaces)
                reportText = withContext(Dispatchers.IO) {
                    val d = revision.data; val now = runtime.tracker.clock.now()
                    val report = revision.day(ReportingDayPolicy(d.settings.reportZone,d.settings.reportDayStartMinute).dateAt(now.wall))
                    L.s(R.string.t_cc5d5d0fd4, Instant.ofEpochMilli(report.asOf), report.zone, report.recordedMs, report.unresolved.size, d.cards.joinToString { it.name }, d.sessions.size, d.segments.size, report.rows.joinToString("\n") { "${it.selection.name ?: L.s(R.string.t_5a7fa46600)}: ${it.durationMs} ms" })
                }
                while(isActive) {
                    val s = revision.snapshot(); shown = s
                    if(!tracedReady) {
                        tracedReady = true
                        StartupTrace.mark(this@TechnicalActivity,"action-ready")
                        body.viewTreeObserver.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                body.viewTreeObserver.removeOnPreDrawListener(this)
                                StartupTrace.mark(this@TechnicalActivity,"action-first-frame")
                                return true
                            }
                        })
                    }
                    val focusText = s.focus?.let { "${it.safeLabel}: ${it.remainingMs} ms\n" }.orEmpty()
                    status.text = L.s(R.string.t_79fb04fd8b, TRACKER_DATABASE_VERSION, s.safeLabel, s.trustedDurationMs, s.token.revision, focusText, s.session?.selection ?: "", reportText)
                    runtime.focusDueSignals.consume(s.focus) { focus ->
                        deliverTechnicalFocus(runtime.tracker,focus)
                    }
                    if(s.focus?.let { it.mode != FocusMode.RUNNING } ?: (s.mode != Mode.RUNNING)) break
                    delay(1000)
                }
            }
        }
    }
    private fun beginExport(mime: String,title: String,write: (java.io.OutputStream)->Unit) {
        val active = pendingExportId?.let { runCatching { exportOperations.read(it) }.getOrNull() }
        if(active?.state in setOf("PREPARING","READY") || active?.resultReceived == false) { status.text = L.s(R.string.t_43c9451f7e);return }
        val op = try { exportOperations.begin(mime,title) } catch(e: Exception) { status.text = L.s(R.string.t_aa447181f4, e.message);return }
        pendingExportId = op.id
        runAction { val prepared = exportOperations.prepare(op.id,write);withContext(Dispatchers.Main) { if(prepared.state == "READY") showExportPicker(op.id) } }
    }
    private fun cancelPreparedExport() {
        val id = pendingExportId ?: exportOperations.latestPending()?.id
        if(id != null) runAction {
            val gate = PrivateFileMaintenance.forContext(this@TechnicalActivity)
            val awaiting = gate.locked {
                val held = gate.state().references.any { it.owner == "export-chooser:$id" }
                if(held) exportOperations.cancel(id) else exportOperations.receiveResult(id,true)
                held
            }
            withContext(Dispatchers.Main) {
                status.text = L.s(R.string.t_f713a587ea)
                if(!awaiting && pendingExportId == id) pendingExportId = null
            }
        }
    }
    private fun showExportPicker(id: String,retry: Boolean = false) {
        val gate = PrivateFileMaintenance.forContext(this)
        val op = gate.locked {
            val current = exportOperations.read(id)
            val held = gate.state().references.any { it.owner == "export-chooser:$id" }
            if(pendingExportId != id || held || (current.state != "READY" && !(retry && current.state == "CANCELLED"))) null
            else { exportOperations.awaitResult(id);current }
        } ?: return
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = op.mime;addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,op.title) },1)
        } catch(e: Exception) {
            exportOperations.receiveResult(id,true) // This chooser did not launch; release only its own lease.
            throw e
        }
    }
    private fun retryExport() {
        val id = pendingExportId ?: exportOperations.latestPending()?.id
        if(id == null) { status.text = L.s(R.string.t_4ac8d095fb);return }
        pendingExportId = id
        val op = exportOperations.read(id)
        if(op.state in setOf("READY","CANCELLED")) showExportPicker(id,true) else status.text = L.s(R.string.t_0077164ed0, op.state)
    }
    private fun restoreSource(file: java.io.File) = runAction {
        val request = restoreReviews.begin() ?: return@runAction
        try {
        if(::runtime.isInitialized && !DatabaseRecovery.needsAttention(this@TechnicalActivity)) {
            val preview = runtime.backups.prepare { file.inputStream() }
            if(restoreReviews.prepared(request,preview) == null) return@runAction
            withContext(Dispatchers.Main) {
                showRestoreReview(preview,L.s(R.string.t_0f605ae8ff),
                    L.s(R.string.t_ac72e3a247, preview.consequence, preview.sessions, preview.segments, preview.goals, preview.unfinishedTail, preview.unfinishedFocus),L.s(R.string.t_d772cc53be)) {
                    val result = runtime.backups.apply(preview);withContext(Dispatchers.Main) {
                        status.text = L.s(R.string.t_886ebf7a3e, result.rollbackReference)
                    }
                }
            }
        } else {
            val preview = DatabaseRecovery.prepare(file)
            if(restoreReviews.prepared(request,preview) == null) return@runAction
            withContext(Dispatchers.Main) { showRestoreReview(preview,L.s(R.string.t_d66df6cc7f),
                L.s(R.string.t_59ef713872, preview.sessions, preview.segments),L.s(R.string.t_29f3b29df0)) {
                    DatabaseRecovery.apply(this@TechnicalActivity,preview);withContext(Dispatchers.Main) { recreate() }
                }
            }
        }
        } catch(e: Exception) { restoreReviews.cancel(request);throw e }
    }
    private fun showRestoreReview(preview: AutoCloseable,title: String,message: String,confirm: String,apply: suspend () -> Unit) {
        pendingRestoreDialog?.dismiss()
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton(L.s(R.string.t_0ec753be8d),null).setPositiveButton(confirm) { _,_ ->
                if(restoreReviews.claim(preview)) launchAction(apply).invokeOnCompletion { CopyLifetimes.get(this).releasePreview(preview) }
            }.create()
        dialog.setOnDismissListener { restoreReviews.discard(preview);if(pendingRestoreDialog === dialog) pendingRestoreDialog = null }
        pendingRestoreDialog = dialog;dialog.show()
    }
    private fun buildRecoveryControls() {
        button(L.s(R.string.t_94405e99a7)) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json";addCategory(Intent.CATEGORY_OPENABLE) },2) }
        button(L.s(R.string.t_76393b3245)) { runAction {
            val files = PrivateRollbackStore(this@TechnicalActivity).files()
            withContext(Dispatchers.Main) { if(files.isEmpty()) status.text = L.s(R.string.t_91b707a9d1) else AlertDialog.Builder(this@TechnicalActivity)
                .setTitle(L.s(R.string.t_d64f83a24b))
                .setItems(files.map { "${it.name} · ${it.length()} bytes" }.toTypedArray()) { _,index -> restoreSource(files[index]) }.show() }
        } }
        if(DatabaseRecovery.needsAttention(this)) button(L.s(R.string.t_cca1d52e45)) {
            runAction { DatabaseRecovery.resume(this@TechnicalActivity);withContext(Dispatchers.Main) { recreate() } }
        }
    }
    @Deprecated("Technical harness uses framework result API")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode == 1) {
            val id = pendingExportId ?: return
            val uri = data?.data?.takeIf { resultCode == RESULT_OK }
            // Commit consumption before scheduling destination I/O: recreation must not
            // leave a completed/cancelled copy owned by a chooser that already returned.
            try { exportOperations.receiveResult(id,uri == null) }
            catch(e: Exception) { status.text = L.s(R.string.t_9ac9a4c129, e.message);return }
            if(uri == null) { status.text = L.s(R.string.t_737fe33491);return }
            runAction { val result = exportOperations.write(id,uri);withContext(Dispatchers.Main) {
                status.text = L.s(R.string.t_87a5f792b0, result.bytes, if(result.verified) L.s(R.string.t_c344c6222f) else L.s(R.string.t_0d0c228652));pendingExportId = null
            } }
            return
        }
        if(resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if(requestCode == 3) runAction {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream();val buffer = ByteArray(8192)
                while(out.size() <= MAX_CSV_BYTES) { val n = input.read(buffer,0,minOf(buffer.size,MAX_CSV_BYTES+1-out.size()));if(n<0) break;if(n==0) { val b=input.read();if(b<0) break;out.write(b) } else out.write(buffer,0,n) }
                out.toByteArray()
            } ?: throw java.io.IOException(L.s(R.string.t_92c584ce74))
            withContext(Dispatchers.Main) { inspectCsv(bytes) }
        } else if(requestCode == 2) runAction {
            val file = contentResolver.openInputStream(uri)?.use { stageRestore(this@TechnicalActivity,it) } ?: throw java.io.IOException(L.s(R.string.t_5779709c59))
            withContext(Dispatchers.Main) { restoreSource(file) }
        }
    }
    override fun onDestroy() { pendingRestoreDialog?.dismiss();pendingRestoreDialog = null;restoreReviews.close();scope.cancel();super.onDestroy() }
}
