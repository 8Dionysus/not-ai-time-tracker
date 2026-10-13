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
    private var exportText: String? = null
    private var pendingSelectedText: String? = null
    private var inputDraft: EntryDraft? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shortcutPending = TimerSurfaces.restoredShortcut(savedInstanceState,intent)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,48,24,24) }
        setContentView(ScrollView(this).apply { addView(body) })
        status = TextView(this); body.addView(status)
        name = input("Имя карточки (пусто = без уточнения)")
        details = input("Уточнение: например listening")
        note = input("Заметка")
        from = input("Начало / момент переключения: ISO-8601, например 2026-10-11T12:00:00Z")
        until = input("Конец: ISO-8601")
        zone = input("Зона отчётов: например Europe/Moscow").apply { setText("UTC") }
        if(savedInstanceState?.containsKey("selectedTextDraft") == true) {
            note.setText(savedInstanceState.getString("selectedTextDraft"))
            if(savedInstanceState.getBoolean("isSelectedTextDraft"))
                inputDraft = EntryDraft(Classification(),note.text.toString().takeIf { it.isNotEmpty() },source = DraftSource.SELECTED_TEXT)
            savedInstanceState.getString("pendingSelectedText")?.let(::reviewSelectedText)
        } else receiveSelectedText(intent)
        intent.action = Intent.ACTION_MAIN
        scope.launch {
            runtime = withContext(Dispatchers.IO) { TrackerRuntime.get(this@MainActivity) }
            buildActions()
            buildSurfaceControls()
            consumeShortcut()
            refreshSurfacePermissions()
            observeWhileVisible()
        }
    }
    private fun input(hint: String) = EditText(this).apply { this.hint = hint; contentDescription = hint; body.addView(this) }
    private fun button(label: String,action: ()->Unit) { body.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
    private data class Form(val note: String,val start: String,val end: String,val draft: EntryDraft?)
    private fun command(label: String,needsSelection: Boolean = false,block: (Tracker,Token,Classification,Form)->Unit) = button(label) {
        val expected = shown?.token ?: return@button
        val chosenName = name.text.toString().trim(); val activity = details.text.toString().trim()
        val form = Form(note.text.toString(),from.text.toString(),until.text.toString(),inputDraft)
        runAction {
            val t = runtime.tracker
            val selected = t.data().cards.firstOrNull { it.name == chosenName && !it.archived }?.classification()
                ?: if (!needsSelection || chosenName.isEmpty()) Classification() else throw CoreError(ErrorCode.INVALID_INPUT,"Сначала сохраните карточку")
            block(t,expected,if (activity.isEmpty()) selected else selected.copy(details = selected.details + ("activity" to activity)),form)
        }
    }
    private fun buildActions() {
        button("Сохранить новую карточку") {
            val expected = shown?.token ?: return@button; val value = name.text.toString(); val activity = details.text.toString()
            runAction { runtime.tracker.saveCard(expected,Card(name = value,details = if (activity.isBlank()) emptyMap() else mapOf("activity" to activity))) }
        }
        button("Добавить первый шаблон каталога") { val token = shown?.token ?: return@button; runAction { runtime.tracker.saveCard(token,Catalog.card(Catalog.types.first { it.selectable && !it.archived }.id)) } }
        command("Start",true) { t,e,c,f ->
            if(f.draft == null) t.start(e,c,f.note.takeIf { it.isNotBlank() })
            else t.startDraft(e,f.draft.copy(selection = c,note = f.note.takeIf { it.isNotEmpty() }))
        }
        command("Назначить текущему занятию",true) { t,e,c,f -> t.labelCurrent(e,c,f.note) }
        command("Изменить уточнение с этого момента",true) { t,e,c,f -> t.changeDetails(e,c) }
        command("Pause") { t,e,_,f -> t.pause(e) }
        command("Resume") { t,e,_,f -> t.resume(e) }
        command("Stop") { t,e,_,f -> t.stop(e) }
        command("Switch",true) { t,e,c,f -> t.switch(e,c) }
        command("Повторить последнее") { t,e,_,f -> t.repeat(e,t.data().sessions.last { it.status == SessionStatus.STOPPED }.id) }
        command("Запомнить выбранное избранное",true) { t,e,c,f -> t.saveFavorite(e,Favorite(label = c.name ?: "Без уточнения",selection = c)) }
        command("Запустить последнее избранное") { t,e,_,f -> t.startFavorite(e,t.data().favorites.last().id) }
        command("Сохранить ручной интервал",true) { t,e,c,f ->
            if(f.draft == null) t.manual(e,time(f.start),time(f.end),c,f.note.takeIf { it.isNotBlank() })
            else t.recordDraft(e,f.draft.copy(selection = c,note = f.note.takeIf { it.isNotEmpty() },
                interval = DraftInterval(time(f.start),time(f.end))))
        }
        command("Переключился раньше: сохранить",true) { t,e,c,f -> t.switchedEarlier(e,time(f.start),c) }
        command("Исправить последний закрытый сегмент",true) { t,e,c,f -> t.correctSegment(e,t.data().segments.last { it.end != null }.id,time(f.start),time(f.end),c) }
        command("Отменить доступную правку") { t,e,_,f -> t.undo(e) }
        command("Подтвердить хвост указанным интервалом") { t,e,_,f -> t.resolveTail(e,time(f.start),time(f.end)) }
        command("Исключить неопределённый хвост") { t,e,_,f -> t.resolveTail(e,null,null) }
        button("Сохранить зону") { val expected = shown?.token ?: return@button; val value = zone.text.toString(); runAction { runtime.tracker.settings(expected,runtime.tracker.data().settings.copy(reportZone = value)) } }
        button("Экспорт полного JSON") { runAction { val json = runtime.backups.export(); withContext(Dispatchers.Main) { createDocument(json,"application/json","not-ai-backup.json") } } }
        button("Экспорт CSV за сегодня") { runAction {
            val t = runtime.tracker; val d = t.data(); val now = t.clock.now()
            val report = Reports.day(d,now,Instant.ofEpochMilli(now.wall).atZone(ZoneId.of(d.settings.reportZone)).toLocalDate())
            withContext(Dispatchers.Main) { createDocument(Csv.export(report),"text/csv","not-ai-today.csv") }
        } }
        button("Восстановить JSON…") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) },2) }
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
    }
    override fun onSaveInstanceState(outState: Bundle) {
        TimerSurfaces.saveShortcut(this,outState,shortcutPending) // also covers recreation before runtime initialization
        outState.putString("selectedTextDraft",note.text.toString())
        outState.putString("pendingSelectedText",pendingSelectedText)
        outState.putBoolean("isSelectedTextDraft",inputDraft?.source == DraftSource.SELECTED_TEXT)
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
                    val report = Reports.day(d,now,Instant.ofEpochMilli(now.wall).atZone(ZoneId.of(d.settings.reportZone)).toLocalDate())
                    "Итоги на ${Instant.ofEpochMilli(report.asOf)} (${report.zone}): ${report.recordedMs} ms; неопределённых: ${report.unresolved.size}\nКарточки: ${d.cards.joinToString { it.name }}\nСессий: ${d.sessions.size}; сегментов: ${d.segments.size}\n${report.rows.joinToString("\n") { "${it.selection.name ?: "Без уточнения"}: ${it.durationMs} ms" }}"
                }
                while(isActive) {
                    val s = revision.snapshot(); shown = s
                    val focusText = s.focus?.let { "${it.safeLabel}: ${it.remainingMs} ms\n" }.orEmpty()
                    status.text = "Техническая оболочка · Room 3\n${s.safeLabel}\n${s.trustedDurationMs} ms · ревизия ${s.token.revision}\n$focusText${s.session?.selection ?: ""}\n$reportText"
                    runtime.focusDueSignals.consume(s.focus) { focus ->
                        AlertDialog.Builder(this@MainActivity).setTitle("Focus").setMessage(focus.safeLabel)
                            .setPositiveButton("OK",null).show()
                    }
                    if(s.focus?.let { it.mode != FocusMode.RUNNING } ?: (s.mode != Mode.RUNNING)) break
                    delay(1000)
                }
            }
        }
    }
    private fun createDocument(text: String,mime: String,filename: String) {
        exportText = text
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = mime; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE,filename) },1)
    }
    @Deprecated("Technical harness uses framework result API")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == 1) {
            val bytes = exportText ?: return
            runAction { contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(bytes) } }
        } else if (requestCode == 2) runAction {
            val preview = contentResolver.openInputStream(uri)!!.use { stream ->
                BackupDocuments.prepare(stream,runtime.backups)
            }
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity).setTitle("Подтвердить полную замену")
                    .setMessage("${preview.consequence}\nКарточек: ${preview.cards}; сессий: ${preview.sessions}; сегментов: ${preview.segments}; целей: ${preview.goals}.\nАктивный хвост: ${preview.unfinishedTail}; Focus: ${preview.unfinishedFocus}")
                    .setNegativeButton("Отмена",null).setPositiveButton("Заменить") { _,_ -> runAction { runtime.backups.apply(preview) } }.show()
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
