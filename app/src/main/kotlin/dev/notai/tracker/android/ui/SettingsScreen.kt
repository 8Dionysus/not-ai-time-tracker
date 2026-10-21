package dev.notai.tracker.android.ui

import dev.notai.tracker.android.RestoreReviewOwner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.togetherWith
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.notai.tracker.CatalogPreview
import dev.notai.tracker.RestorePreview
import dev.notai.tracker.TrackerView
import dev.notai.tracker.android.FloatingTimerService
import dev.notai.tracker.android.R
import dev.notai.tracker.android.Surface
import dev.notai.tracker.android.SurfaceCapability
import dev.notai.tracker.android.SurfaceProblem
import dev.notai.tracker.android.SurfaceSettingsAction
import dev.notai.tracker.android.SurfaceSettingsBackend
import dev.notai.tracker.android.WidgetPlacement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Settings pages. The first one is short: appearance up front, then each section with its live state on one line. */
enum class SettingsPage(val title: String) {
    ROOT("Настройки"), APPEARANCE("Вид"), SURFACES("Таймер вне приложения"), BACKUP("Резервная копия"),
    PLAN("Занятия и план"), FOCUS("Фокус и напоминания"), MORE("Ещё")
}

/**
 * Settings in sections rather than one long page: the root shows what matters most and the state of every section,
 * each section opens as its own page. Every state line comes from SettingsCapabilities (actual OS observations);
 * system prompts open only from a tap.
 */
@Composable
fun SettingsScreen(vm: TrackerViewModel, view: TrackerView?, theme: ThemeChoice, openTechnical: () -> Unit, modifier: Modifier = Modifier,
                   openPlan: (PlanSeriesUi?) -> Unit = {}, initialPage: SettingsPage = SettingsPage.ROOT) {
    val c = NotAi.colors
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val scope = rememberCoroutineScope()
    val settings = view?.data?.settings
    val surfaces = settings?.surfaces
    val zone = settings?.reportZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val caps by vm.capabilities.collectAsStateWithLifecycle()
    val widgetRequest by vm.widgetRequest.collectAsStateWithLifecycle()
    val last by vm.lastBackup.collectAsStateWithLifecycle()
    val auto by vm.autoBackup.collectAsStateWithLifecycle()
    val exportPreparing by vm.exportPreparing.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var restore by remember { mutableStateOf<RestorePreview?>(null) }
    val restoreOwner = remember(vm) { RestoreReviewOwner<RestorePreview>(vm::releaseRestorePreviewOnIo) }
    DisposableEffect(restoreOwner) { onDispose { restoreOwner.close() } }
    val heldRestore = restore
    DisposableEffect(heldRestore) { onDispose { heldRestore?.let(restoreOwner::discard) } }
    var catalog by remember { mutableStateOf<CatalogPreview?>(null) }
    var zoneConfirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notificationsDenied by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshCapabilities(); vm.refreshLongRecord(); vm.refreshAutoBackup() }
    LaunchedEffect(settings?.longRecordReminder) { vm.refreshLongRecord() }
    // While a copy is on its way, the line follows it; otherwise one read on open is enough.
    LaunchedEffect(page, auto?.state?.pending != null || auto?.state?.requested == true) {
        while (page == SettingsPage.BACKUP || auto?.state?.pending != null || auto?.state?.requested == true) { delay(2_000); vm.refreshAutoBackup()
            if (page != SettingsPage.BACKUP && auto?.state?.pending == null && auto?.state?.requested != true) break }
    }
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    val longRecord by vm.longRecord.collectAsStateWithLifecycle()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true;restore = null
            try { restore = vm.prepareRestore(uri,restoreOwner) } finally { busy = false }
        }
    }
    val csvFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.openCsv(uri) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) vm.chooseBackupFolder(uri, result.data?.flags ?: 0)
    }
    fun chooseFolder() { vm.backupFolderIntent()?.let { runCatching { folder.launch(it) } } }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) notificationsDenied = true
        vm.refreshCapabilities()
    }
    fun system(action: SurfaceSettingsAction) {
        SurfaceSettingsBackend.settingsIntent(context, action)?.let { runCatching { context.startActivity(it) } }
    }
    /** Runtime prompt once; after a refusal the system settings page is the only honest route. */
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsDenied) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else system(SurfaceSettingsAction.OPEN_NOTIFICATION_SETTINGS)
    }
    fun addWidget() { activity?.let { vm.requestWidgetPlacement(it) } }

    // The floating button may only start from a visible Activity: start it right after it is switched on here.
    LaunchedEffect(surfaces?.overlay, caps?.overlay?.permitted) {
        val s = settings ?: return@LaunchedEffect
        if (s.surfaces.overlay && caps?.overlay?.permitted == true) activity?.let { FloatingTimerService.fromVisibleActivity(it, s.surfaces) }
    }
    val today = Instant.ofEpochMilli(vm.now()).atZone(zone).toLocalDate()
    fun whenText(at: Long) = "${Format.dayTitle(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), today).lowercase()} в ${Format.clock(at, zone)}"

    androidx.compose.animation.AnimatedContent(page, modifier.fillMaxSize(), label = "settings-page",
        transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) }) { shown ->
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = if (shown == SettingsPage.ROOT) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shown != SettingsPage.ROOT) IconAction(Icon.ChevronLeft, "Назад к настройкам", { page = SettingsPage.ROOT }, tint = c.ink)
            Text(shown.title, style = if (shown == SettingsPage.ROOT) NotAi.type.title else NotAi.type.title.copy(fontSize = 23.sp, lineHeight = 30.sp), color = c.ink, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        when (shown) {
        SettingsPage.ROOT -> {
            AppearanceGroup(vm, theme) { page = SettingsPage.APPEARANCE }
            Group("Разделы") {
                val on = listOfNotNull("уведомление".takeIf { surfaces?.notification == true }, "плавающая кнопка".takeIf { surfaces?.overlay == true },
                    "виджет".takeIf { surfaces?.widget == true }, "ярлык на значке".takeIf { surfaces?.shortcut == true })
                val problem = listOf(caps?.notification, caps?.overlay, caps?.focusSignal).any { it.problemShown() } ||
                    (caps?.overlay?.enabled == true && caps?.overlay?.permitted == false)
                SectionRow(rememberVectorPainter(Icon.Bell), SettingsPage.SURFACES.title,
                    if (problem) "Чему-то не хватает разрешения телефона" else if (on.isEmpty()) "Всё выключено: учёт только в приложении"
                        else on.joinToString(", ").replaceFirstChar { it.uppercase() }, problem) { page = SettingsPage.SURFACES }
                Divider()
                val a = auto?.state
                val (backupLine, backupWarn) = when {
                    a?.enabled == true && a.error != null -> Format.backupProblem(a.error!!) to true
                    a?.enabled == true && a.pending != null -> "Автокопия сохраняется" to false
                    a?.enabled == true && a.lastSuccess != null -> "Автокопия ${whenText(a.lastSuccess!!.verifiedAt)}" to false
                    a?.enabled == true -> "Автокопия включена, первая скоро появится" to false
                    last != null -> "Последняя ${whenText(last!!.at)}. Автокопия выключена" to (vm.now() - last!!.at > 7 * 86_400_000L)
                    else -> "Копий ещё не было. Включи автокопию в папку" to true
                }
                SectionRow(rememberVectorPainter(Icon.Shield), SettingsPage.BACKUP.title, backupLine, backupWarn) { page = SettingsPage.BACKUP }
                Divider()
                val plans = vm.planSeries(view).size; val favs = view?.data?.favorites?.size ?: 0
                SectionRow(rememberVectorPainter(Icon.Star), SettingsPage.PLAN.title,
                    listOf(if (favs > 0) Format.plural(favs, "избранное", "избранных", "избранных") else "Избранного пока нет",
                        if (plans > 0) "в плане ${Format.plural(plans, "занятие", "занятия", "занятий")}" else "плана нет").joinToString(", "), false) { page = SettingsPage.PLAN }
                Divider()
                val plan = settings?.focusPlan
                SectionRow(painterResource(R.drawable.act_focus), SettingsPage.FOCUS.title,
                    (plan?.let { "Фокус ${it.workMs / 60_000L} + ${it.breakMs / 60_000L} мин" } ?: "Фокус") +
                        (if (settings?.longRecordReminder?.enabled == true) ", спросит о долгой записи" else ""),
                    caps?.focusSignal.problemShown() || longRecord?.confirmationRequired == true) { page = SettingsPage.FOCUS }
                Divider()
                val bundled by produceState<String?>(null) { value = vm.bundledCatalogVersion() }
                val installed = view?.data?.catalogVersion
                val update = bundled != null && installed != null && bundled != installed
                SectionRow(rememberVectorPainter(Icon.More), SettingsPage.MORE.title,
                    if (update) "Есть обновление каталога занятий" else "Часовой пояс, начало дня, каталог, CSV", update) { page = SettingsPage.MORE }
            }
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
            Text("(not)AI Time Tracker ${version.orEmpty()}. Все записи хранятся на этом телефоне".replace("  ", " "),
                Modifier.padding(start = 4.dp, top = 18.dp), style = NotAi.type.caption, color = c.ink3)
        }

        SettingsPage.APPEARANCE -> AppearancePage(vm)

        SettingsPage.SURFACES -> Group("На экранах телефона") {
            val n = caps?.notification
            SettingRow(rememberVectorPainter(Icon.Bell), "Уведомление",
                status = surfaceStatus(n, "Старт и стоп из шторки, быстрый выбор занятия"),
                warn = n.problemShown(),
                action = when (n?.takeIf { it.enabled }?.problem) {
                    SurfaceProblem.PERMISSION_REQUIRED -> "Разрешить" to ::askNotifications
                    SurfaceProblem.CHANNEL_BLOCKED -> "Открыть настройки" to { system(SurfaceSettingsAction.OPEN_NOTIFICATION_SETTINGS) }
                    else -> null
                },
                checked = surfaces?.notification == true) { on ->
                vm.setSurfaceEnabled(Surface.NOTIFICATION, on)
                if (on && n?.problem == SurfaceProblem.PERMISSION_REQUIRED) askNotifications()
            }
            if (surfaces?.notification == true) NotificationDetails(surfaces, caps?.timerNotification, { p -> vm.setSurfaces { it.copy(notificationPrivacy = p) } }) {
                system(SurfaceSettingsAction.OPEN_TIMER_CHANNEL_SETTINGS)
            }
            Divider()
            val o = caps?.overlay
            SettingRow(rememberVectorPainter(Icon.Floating), "Плавающая кнопка",
                status = surfaceStatus(o, "Полупрозрачный старт и стоп поверх приложений. После стопа можно сразу выбрать занятие"),
                warn = o.problemShown(),
                action = if (o?.enabled == true && !o.permitted) "Разрешить" to { system(SurfaceSettingsAction.OPEN_OVERLAY_PERMISSION) } else null,
                checked = surfaces?.overlay == true) { on ->
                vm.setSurfaceEnabled(Surface.OVERLAY, on)
                if (on && o?.permitted == false) system(SurfaceSettingsAction.OPEN_OVERLAY_PERMISSION)
            }
            if (surfaces?.overlay == true) AlphaRow("Фон окна выбора после стопа", surfaces.overlayChooserBackgroundAlphaPercent) { v ->
                vm.setSurfaces { it.copy(overlayChooserBackgroundAlphaPercent = v) }
            }
            Divider()
            val w = caps?.widgetPlacement
            val placement = widgetRequest?.takeIf { it.requestId == w?.requestId && it.problem != null }?.let { it.placement } ?: w?.placement
            val canAdd = surfaces?.widget == true && placement in setOf(WidgetPlacement.AVAILABLE, WidgetPlacement.UNCONFIRMED, WidgetPlacement.FAILED)
            SettingRow(rememberVectorPainter(Icon.Widget), "Виджет",
                status = when {
                    surfaces?.widget != true && placement == WidgetPlacement.PINNED -> "На главном экране, кнопки выключены"
                    surfaces?.widget != true -> "Выключен"
                    placement == WidgetPlacement.PINNED -> if ((w?.ids?.size ?: 0) > 1) "На главном экране: ${w?.ids?.size}" else "На главном экране"
                    placement == WidgetPlacement.PENDING -> "Подтверди добавление на рабочем столе"
                    placement == WidgetPlacement.UNCONFIRMED -> "Не появился. Попробуй ещё раз или добавь вручную: долгое нажатие на рабочем столе → Виджеты → (not)AI Time"
                    placement == WidgetPlacement.UNSUPPORTED -> "Рабочий стол не умеет добавлять виджеты из приложений. Добавь вручную: долгое нажатие на рабочем столе → Виджеты → (not)AI Time"
                    placement == WidgetPlacement.FAILED -> "Телефон не принял запрос. Попробуй ещё раз"
                    else -> "Ещё не на главном экране"
                },
                warn = surfaces?.widget == true && placement in setOf(WidgetPlacement.UNCONFIRMED, WidgetPlacement.FAILED, WidgetPlacement.UNSUPPORTED),
                action = if (canAdd) (if (placement == WidgetPlacement.AVAILABLE) "Добавить на экран" else "Попробовать ещё раз") to ::addWidget else null,
                checked = surfaces?.widget == true) { on ->
                vm.setSurfaceEnabled(Surface.WIDGET, on)
                if (on && placement == WidgetPlacement.AVAILABLE) addWidget()
            }
            if (surfaces?.widget == true) AlphaRow("Фон виджета", surfaces.widgetBackgroundAlphaPercent) { v ->
                vm.setSurfaces { it.copy(widgetBackgroundAlphaPercent = v) }
            }
            Divider()
            SettingRow(rememberVectorPainter(Icon.TabTimer), "Ярлык на значке", status = "Долгое нажатие на значок: старт и стоп, выбор занятия и два первых избранных",
                warn = false, action = null, checked = surfaces?.shortcut == true) { on -> vm.setSurfaceEnabled(Surface.SHORTCUT, on) }
        }

        SettingsPage.BACKUP -> BackupPage(vm, auto, last, view, zone, today, busy || exportPreparing, ::chooseFolder,
            restoreFile = { open.launch(arrayOf("application/json", "*/*")) },
            restoreVersion = { id -> scope.launch { busy = true; restore = null; try { restore = vm.prepareRestoreVersion(id, restoreOwner) } finally { busy = false } } })

        SettingsPage.PLAN -> PlanGroup(vm.planSeries(view), openPlan, favorites = view?.data?.favorites?.size ?: 0) { vm.openFavorites(true) }

        SettingsPage.FOCUS -> {
            settings?.focusPlan?.let { plan -> FocusGroup(vm, (plan.workMs / 60_000L).toInt(), (plan.breakMs / 60_000L).toInt()) }
            val fx by vm.focusEffects.collectAsStateWithLifecycle()
            val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.chooseFocusAudio(uri) }
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycle) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshFocusEffects() }
                lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
            }
            Group("Во время отрезка работы") {
                val needAccess = fx.choice.dnd && !fx.dndAccess
                SettingRow(rememberVectorPainter(Icon.Moon), "Не беспокоить",
                    status = when {
                        !fx.dndSupported -> "Нужен Android 10 или новее"
                        needAccess -> "Включено, но телефон ещё не разрешил приложению режим «Не беспокоить»"
                        else -> "Телефон затихнет на отрезок работы и вернётся сам, даже если приложение закрыто. Исключения — по твоим правилам «Не беспокоить»"
                    }, warn = needAccess,
                    action = if (needAccess) "Разрешить" to { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } } else null,
                    checked = fx.choice.dnd && fx.dndSupported) { on ->
                    if (!fx.dndSupported) return@SettingRow
                    vm.setFocusEffects { it.copy(dnd = on) }
                    if (on && !fx.dndAccess) runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                }
                Divider()
                SettingRow(rememberVectorPainter(Icon.Note), "Свой звук",
                    status = fx.choice.musicName?.let { "«$it». Играет по кругу, пока приложение открыто" }
                        ?: "Шум, дождь или музыка из твоего файла. Играет, пока приложение открыто",
                    warn = false, action = (if (fx.choice.musicId == null) "Выбрать файл" else "Другой файл") to { audio.launch(arrayOf("audio/*")) },
                    checked = fx.choice.music && fx.choice.musicId != null) { on ->
                    if (on && fx.choice.musicId == null) audio.launch(arrayOf("audio/*")) else vm.setFocusEffects { it.copy(music = on) }
                }
            }
            Group("Сигналы") {
                val f = caps?.focusSignal
                SettingRow(painterResource(R.drawable.act_focus), "Сигнал Фокуса",
                    status = surfaceStatus(f, "Уведомить, когда отрезок закончился. Телефон может немного задержать сигнал"),
                    warn = f.problemShown(),
                    action = when (f?.takeIf { it.enabled }?.problem) {
                        SurfaceProblem.PERMISSION_REQUIRED -> "Разрешить" to ::askNotifications
                        SurfaceProblem.CHANNEL_BLOCKED -> "Открыть настройки" to { system(SurfaceSettingsAction.OPEN_NOTIFICATION_SETTINGS) }
                        else -> null
                    },
                    checked = surfaces?.focusSignal == true) { on ->
                    vm.setSurfaces { it.copy(focusSignal = on) }
                    if (on && f?.problem == SurfaceProblem.PERMISSION_REQUIRED) askNotifications()
                }
                Divider()
                settings?.longRecordReminder?.let { pref -> LongRecordRow(vm, pref, longRecord, ::askNotifications) { system(SurfaceSettingsAction.OPEN_NOTIFICATION_SETTINGS) } }
            }
        }

        SettingsPage.MORE -> {
            Group("Дни в истории") {
                val device = ZoneId.systemDefault().id
                val differs = settings != null && settings.reportZone != device
                NavRow(rememberVectorPainter(Icon.Globe), "Часовой пояс",
                    note = settings?.reportZone.orEmpty().replace('_', ' ') +
                        if (differs) ". На телефоне сейчас ${device.replace('_', ' ')}, нажми, чтобы перейти" else ". По нему делятся дни в истории и обзоре",
                    onClick = if (differs) ({ zoneConfirm = device }) else null)
                Divider()
                settings?.reportDayStartMinute?.let { DayStartRow(vm, it) }
            }
            Group("Каталог и таблицы") {
                val types = view?.data?.types?.size ?: 0
                val bundled by produceState<String?>(null) { value = vm.bundledCatalogVersion() }
                val installed = view?.data?.catalogVersion
                val update = bundled != null && installed != null && bundled != installed
                NavRow(rememberVectorPainter(Icon.Layers), if (update) "Каталог занятий: есть обновление" else "Каталог занятий",
                    note = if (update) "Версия $bundled: понятные названия и разделы по местам. Твои названия и история останутся. Нажми, чтобы посмотреть"
                        else "${Format.plural(types, "занятие", "занятия", "занятий").replace(Regex("^(\\d)(\\d{3})"), "$1 $2")}, версия ${installed ?: "базовая"}. Нажми, чтобы проверить обновление",
                    onClick = { scope.launch { busy = true; catalog = vm.prepareCatalog(); busy = false } })
                Divider()
                NavRow(rememberVectorPainter(Icon.Table), "Выгрузить в CSV", note = "Вся история для таблиц. Это не резервная копия", onClick = { vm.requestExport(csv = true) })
                Divider()
                NavRow(rememberVectorPainter(Icon.Upload), "Добавить историю из CSV", note = "Из Toggl, Clockify или своей таблицы. Сначала покажу, что добавится",
                    onClick = { csvFile.launch(arrayOf("text/*", "application/csv", "text/csv", "*/*")) })
            }
            Group("О приложении") {
                NavRow(painterResource(R.drawable.act_tools), "Техническая оболочка", note = "Измерения, календарь, восстановление базы",
                    onClick = openTechnical)
                Divider()
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                NavRow(rememberVectorPainter(Icon.Info), "(not)AI Time Tracker ${version.orEmpty()}".trim(),
                    note = "Записи хранятся на этом телефоне; копии — там, куда ты их сохранишь. Шрифт Onest, SIL Open Font License")
            }
        }
        }
    }
    }

    restore?.let { p ->
        ConfirmDialog("Заменить все данные?",
            "В копии ${Format.plural(p.sessions, "запись", "записи", "записей")}, ${Format.plural(p.cards, "своё занятие", "своих занятия", "своих занятий")}, " +
                "${Format.plural(p.goals, "цель", "цели", "целей")}." +
                (if (p.unfinishedTail || p.unfinishedFocus) " Незакрытая запись придёт как «время неизвестно», её нужно будет уточнить." else "") +
                " Текущие данные будут сохранены отдельно перед заменой. Записи, сделанные после этой копии, исчезнут.",
            "Заменить", { vm.applyRestore(p,restoreOwner); restore = null }, { restoreOwner.discard(p);restore = null }, destructive = true)
    }
    catalog?.let { p ->
        val r = p.report
        ConfirmDialog(if (r.added.isEmpty() && r.updated.isEmpty()) "Каталог уже актуален" else "Обновить каталог?",
            "Версия ${p.version}. Новых занятий: ${r.added.size}, уточнений: ${r.updated.size}" +
                (if (r.conflicts.isNotEmpty()) ", расхождений со своими изменениями: ${r.conflicts.size}. Они требуют разбора в технической оболочке." else ".") +
                (if (r.updated.isNotEmpty()) " Названия, которые ты менял сам, останутся твоими; прошлые записи не меняются." else ""),
            if (p.canApply && (r.added.isNotEmpty() || r.updated.isNotEmpty())) "Обновить" else null, { vm.applyCatalog(p); catalog = null }, { catalog = null })
    }
    zoneConfirm?.let { z ->
        ConfirmDialog("Сменить часовой пояс отчётов на $z?",
            "Изменятся границы дней в истории и обзоре. Сами записи и зоны целей не меняются.", "Сменить",
            { vm.setReportZone(z); zoneConfirm = null }, { zoneConfirm = null })
    }
}

/** A section on the settings root: what it is and, in one line, how it stands now. */
@Composable
private fun SectionRow(icon: Painter, title: String, state: String, warn: Boolean, onClick: () -> Unit) {
    val c = NotAi.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).pressable(onClick, label = title).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        M3Icon(icon, null, Modifier.size(22.dp), tint = if (warn) c.amberInk else c.ink2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NotAi.type.body, color = c.ink)
            Text(state, style = NotAi.type.caption, color = if (warn) c.amberInk else c.ink2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink3)
    }
}

private fun Context.activity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) { if (current is Activity) return current; current = current.baseContext }
    return null
}

private fun SurfaceCapability?.problemShown() = this != null && enabled && problem != null

private fun surfaceStatus(cap: SurfaceCapability?, describe: String): String = when {
    cap == null || !cap.enabled -> describe
    cap.problem == SurfaceProblem.PERMISSION_REQUIRED -> "Включено, но нет разрешения телефона"
    cap.problem == SurfaceProblem.CHANNEL_BLOCKED -> "Включено, но уведомления выключены в настройках телефона"
    cap.problem == SurfaceProblem.PLATFORM_FAILURE -> "Телефон не дал включить. Попробуй ещё раз"
    else -> describe
}

/** Theme swatches first: that is what most people come here for. Accent and size follow; the rest is one tap deeper. */
@Composable
private fun AppearanceGroup(vm: TrackerViewModel, theme: ThemeChoice, more: () -> Unit) {
    val c = NotAi.colors
    val a by vm.appearance.collectAsStateWithLifecycle()
    Group("Вид") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ThemeChoice.entries.forEach { t -> ThemeSwatch(t, t == theme, a.accent) { vm.setTheme(t) } }
            }
            if (theme != ThemeChoice.NEON) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Акцент", Modifier.weight(1f), style = NotAi.type.body, color = c.ink)
                Accent.entries.forEach { ac ->
                    val set = accentSet(ac, c.isDark); val on = ac == a.accent
                    Box(Modifier.size(36.dp).clip(CircleShape).border(if (on) 2.dp else 1.dp, if (on) c.ink else c.rule, CircleShape).padding(5.dp)
                        .clip(CircleShape).background(set.accent).semantics { selected = on }
                        .pressable({ vm.setAppearance { it.copy(accent = ac) } }, role = androidx.compose.ui.semantics.Role.RadioButton, label = ac.title))
                }
            } else Text("Неон — экспериментальная тема со своими цветами и мягким свечением.", style = NotAi.type.caption, color = c.ink2)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Размер текста", style = NotAi.type.body, color = c.ink)
                Segmented(Appearance.textScales, a.textScale, { s -> vm.setAppearance { it.copy(textScale = s) } }, Modifier.fillMaxWidth())
            }
        }
        Divider()
        NavRow(rememberVectorPainter(Icon.Palette), "Шрифт, цифры и главный экран", note = "${a.font.title}, цифры ${a.digits.title.lowercase()}, " +
            (if (a.compact) "компактно" else "просторно"), onClick = more)
    }
}

@Composable
private fun ThemeSwatch(t: ThemeChoice, on: Boolean, accent: Accent, onClick: () -> Unit) {
    val c = NotAi.colors
    val sys = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when (t) { ThemeChoice.SYSTEM -> if (sys) DarkColors else LightColors; ThemeChoice.LIGHT -> LightColors
        ThemeChoice.DARK -> DarkColors; ThemeChoice.AMOLED -> AmoledColors; ThemeChoice.NEON -> NeonColors }
    val dot = if (t == ThemeChoice.NEON) colors.amber else accentSet(accent, colors.isDark).accent
    Column(Modifier.widthIn(min = 52.dp).semantics(mergeDescendants = true) { selected = on }
        .pressable(onClick, role = androidx.compose.ui.semantics.Role.RadioButton, label = "Тема: ${t.title}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).border(if (on) 2.dp else 1.dp, if (on) c.ink else c.rule, RoundedCornerShape(14.dp))
            .padding(3.dp).clip(RoundedCornerShape(11.dp)).background(
                if (t == ThemeChoice.SYSTEM) androidx.compose.ui.graphics.Brush.linearGradient(listOf(LightColors.paper, LightColors.paper, DarkColors.paper, DarkColors.paper))
                else androidx.compose.ui.graphics.SolidColor(colors.paper)), contentAlignment = Alignment.Center) {
            // The mark itself, in the theme's own ink and running colour.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Bracket(true, dot, width = 7.dp, height = 22.dp, stroke = 1.6.dp, glow = colors.glow)
                Box(Modifier.size(5.dp).clip(CircleShape).background(colors.ink2))
                Bracket(false, dot, width = 7.dp, height = 22.dp, stroke = 1.6.dp, glow = colors.glow)
            }
        }
        Text(t.title, style = NotAi.type.micro.copy(fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) c.ink else c.ink2)
    }
}

/** The deeper half of appearance, with a small live sample so a change is seen before leaving. */
@Composable
private fun AppearancePage(vm: TrackerViewModel) {
    val c = NotAi.colors
    val a by vm.appearance.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.sunk).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IntervalFrame(IntervalState.RUNNING, bracketWidth = 14.dp, bracketHeight = 60.dp, stroke = 1.8.dp, gap = 10.dp, opening = false) {
                    Text("1:24", style = NotAi.type.timer.copy(fontSize = 44.sp, lineHeight = 48.sp), color = c.ink)
                }
                Text("Английский · с 14:05", style = NotAi.type.caption, color = c.ink2)
            }
        }
        Labeled("Шрифт") { Segmented(FontChoice.entries.map { it to it.title }, a.font, { f -> vm.setAppearance { it.copy(font = f) } }, Modifier.fillMaxWidth()) }
        Labeled("Цифры таймера") { Segmented(TimerDigits.entries.map { it to it.title }, a.digits, { d -> vm.setAppearance { it.copy(digits = d) } }, Modifier.fillMaxWidth()) }
        Labeled("Плотность списков") { Segmented(listOf(false to "Просторно", true to "Компактно"), a.compact, { v -> vm.setAppearance { it.copy(compact = v) } }, Modifier.fillMaxWidth()) }
        Labeled("Главный экран") {
            Toggle("Приглашение на «Начать»", "Скобки медленно дышат, пока учёт не идёт", a.pulse) { v -> vm.setAppearance { it.copy(pulse = v) } }
            Toggle("Кнопка Фокуса", "Под быстрым стартом. Долгое нажатие — длительности", a.showFocus) { v -> vm.setAppearance { it.copy(showFocus = v) } }
            Toggle("Лента дня", "Полоска из отрезков над записями каждого дня", a.showRibbon) { v -> vm.setAppearance { it.copy(showRibbon = v) } }
        }
        PillButton("Вернуть как было", { vm.setAppearance { Appearance() } }, Modifier.fillMaxWidth(), ButtonKind.TONAL, height = 44.dp)
    }
}

@Composable
private fun Labeled(title: String, content: @Composable ColumnScope.() -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = NotAi.colors.ink2); content()
}

@Composable
private fun Toggle(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = NotAi.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable({ onChange(!checked) }, label = title), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NotAi.type.body, color = c.ink); Text(note, style = NotAi.type.caption, color = c.ink2)
        }
        NotAiSwitch(checked, onChange, title)
    }
}

/** Lock screen and status bar for the timer card. Android and the phone maker have the last word; this asks, it cannot force. */
@Composable
private fun NotificationDetails(surfaces: dev.notai.tracker.SurfaceSettings, timer: dev.notai.tracker.android.TimerNotificationCapability?,
                                setPrivacy: (dev.notai.tracker.NotificationPrivacy) -> Unit, openChannel: () -> Unit) {
    val c = NotAi.colors
    Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("На экране блокировки", style = NotAi.type.caption, color = c.ink2)
        val p = surfaces.notificationPrivacy ?: dev.notai.tracker.NotificationPrivacy.PRIVATE
        Segmented(listOf(dev.notai.tracker.NotificationPrivacy.FULL to "Полностью", dev.notai.tracker.NotificationPrivacy.PRIVATE to "Кратко",
            dev.notai.tracker.NotificationPrivacy.HIDDEN to "Скрыть"), p, setPrivacy, Modifier.fillMaxWidth(), height = 34.dp)
        Text(when (p) { dev.notai.tracker.NotificationPrivacy.FULL -> "Занятие, время и кнопки видны без разблокировки"
            dev.notai.tracker.NotificationPrivacy.PRIVATE -> "Без разблокировки видно только, что учёт идёт"
            dev.notai.tracker.NotificationPrivacy.HIDDEN -> "На заблокированном экране карточки нет" }, style = NotAi.type.caption, color = c.ink2)
        val icon = timer?.statusBarIconRequested
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).pressable(openChannel, label = "Значок в строке состояния"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (icon == false) "Значок в строке состояния скрыт" else "Значок в строке состояния", style = NotAi.type.bodySmall, color = c.ink)
                Text("Чтобы скрыть его и оставить карточку в шторке, включи «Свернуть» или «Без значка» в настройках этого уведомления",
                    style = NotAi.type.caption, color = c.ink2)
            }
            Text("Открыть", style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Background opacity in tens; text and buttons stay solid. Written after a short pause, like other steppers. */
@Composable
private fun AlphaRow(title: String, percent: Int, onChange: (Int) -> Unit) {
    val c = NotAi.colors
    var v by remember(percent) { mutableIntStateOf(percent) }
    LaunchedEffect(v) { if (v == percent) return@LaunchedEffect; delay(500); onChange(v) }
    Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = NotAi.type.caption, color = c.ink2)
            Text(if (v == 100) "непрозрачный" else "$v %", style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.ink)
        }
        androidx.compose.material3.Slider(v.toFloat(), { v = (Math.round(it / 10f) * 10) }, valueRange = 0f..100f, steps = 9,
            modifier = Modifier.semantics { contentDescription = "$title: $v процентов" },
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink2, inactiveTrackColor = c.sunk,
                activeTickColor = c.ink3, inactiveTickColor = c.rule))
    }
}

/** Night owls: records before this hour belong to the previous day. Applied after a short pause, like Focus steps. */
@Composable
private fun DayStartRow(vm: TrackerViewModel, minute: Int) {
    val c = NotAi.colors
    var hour by remember(minute) { mutableIntStateOf(minute / 60) }
    LaunchedEffect(hour) { if (hour * 60 == minute) return@LaunchedEffect; delay(700); vm.setDayStart(hour * 60) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            M3Icon(Icon.Moon, null, Modifier.size(22.dp), tint = c.ink2)
            Spacer(Modifier.width(14.dp))
            Text("День начинается", Modifier.weight(1f), style = NotAi.type.body, color = c.ink)
            IconAction(Icon.Minus, "Начало дня раньше", { hour = (hour - 1).coerceAtLeast(0) }, size = 16.dp, tint = c.ink)
            Text("$hour:00", Modifier.widthIn(min = 56.dp).semantics { contentDescription = "День начинается в $hour:00" },
                style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconAction(Icon.Plus, "Начало дня позже", { hour = (hour + 1).coerceAtMost(12) }, size = 16.dp, tint = c.ink)
        }
        Text(if (hour == 0) "В полночь, как в календаре" else "Записи до $hour:00 относятся к предыдущему дню. Сами записи не меняются",
            Modifier.padding(start = 36.dp, end = 8.dp), style = NotAi.type.caption, color = c.ink2)
    }
}

/** Off by default. The preference travels in copies; consent on this phone is separate and stated once. */
@Composable
private fun LongRecordRow(vm: TrackerViewModel, pref: dev.notai.tracker.LongRecordPreference, state: dev.notai.tracker.LongRecordState?,
                          askNotifications: () -> Unit, openChannel: () -> Unit) {
    val c = NotAi.colors
    val hours = (pref.afterMs / 3_600_000L).coerceAtLeast(1).toInt()
    var h by remember(hours) { mutableIntStateOf(hours) }
    LaunchedEffect(h) { if (h == hours) return@LaunchedEffect; delay(700); vm.configureLongRecord(pref.copy(afterMs = h * 3_600_000L)) }
    val blockers = state?.availability?.blockers.orEmpty()
    val confirm = state?.confirmationRequired == true
    val (status, warn, action) = when {
        !pref.enabled -> Triple("Если запись идёт дольше $hours ч, тихо спросить в шторке, не забыт ли таймер", false, null)
        confirm -> Triple("Включено в восстановленной копии. На этом телефоне нужно подтвердить", true, "Подтвердить" to vm::confirmLongRecord)
        dev.notai.tracker.ScheduleDeliveryBlocker.PERMISSION in blockers -> Triple("Включено, но нет разрешения на уведомления", true, "Разрешить" to askNotifications)
        dev.notai.tracker.ScheduleDeliveryBlocker.CHANNEL in blockers -> Triple("Включено, но уведомления выключены в настройках телефона", true, "Открыть настройки" to openChannel)
        else -> Triple("Через $hours ч записи спросит в шторке: продолжить, отложить или остановить", false, null)
    }
    SettingRow(rememberVectorPainter(Icon.Bell), "Долгая запись", status, warn, action, pref.enabled) { on ->
        vm.configureLongRecord(pref.copy(enabled = on))
        if (on && dev.notai.tracker.ScheduleDeliveryBlocker.PERMISSION in blockers) askNotifications()
    }
    if (pref.enabled) StepRow(rememberVectorPainter(Icon.TabTimer), "Спросить через", h, { h = (h - 1).coerceAtLeast(1) }, { h = (h + 1).coerceAtMost(12) }, unit = "ч")
}

/** Quick buttons and the optional plan for days. Each plan entry reads as one line; tapping opens it. */
@Composable
private fun PlanGroup(series: List<PlanSeriesUi>, open: (PlanSeriesUi?) -> Unit, favorites: Int, openFavorites: () -> Unit) {
    val c = NotAi.colors
    Group("Быстрые кнопки") {
        NavRow(rememberVectorPainter(Icon.Star), "Избранное", value = if (favorites > 0) "$favorites" else null,
            note = "Порядок, названия на кнопках, удаление повторов. Тот же порядок в шторке, виджете и на кнопке", onClick = openFavorites)
    }
    Group("План по дням") {
        series.forEach { p ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable({ open(p) }, label = p.label.title).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActivityGlyph(p.label, size = 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.label.title, style = NotAi.type.body, color = c.ink, maxLines = 1)
                    Text(p.summary, style = NotAi.type.caption, color = c.ink2)
                }
                M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink3)
            }
            Divider()
        }
        NavRow(rememberVectorPainter(Icon.Plus), if (series.isEmpty()) "Добавить занятие в план" else "Добавить ещё",
            note = if (series.isEmpty()) "Например, английский по понедельникам и средам. В эти дни он будет ждать на главном экране" else null,
            onClick = { open(null) })
    }
}

/** Work and break minutes. Steps apply after a short pause, so tapping through values writes once. */
@Composable
private fun FocusGroup(vm: TrackerViewModel, work: Int, rest: Int) {
    var w by remember(work) { mutableIntStateOf(work) }
    var b by remember(rest) { mutableIntStateOf(rest) }
    LaunchedEffect(w, b) {
        if (w == work && b == rest) return@LaunchedEffect
        delay(700)
        vm.configureFocus(w, b)
    }
    Group("Длительности Фокуса") {
        StepRow(painterResource(R.drawable.act_focus), "Отрезок работы", w, { w = (w - 5).coerceAtLeast(5) }, { w = (w + 5).coerceAtMost(180) })
        Divider()
        StepRow(painterResource(R.drawable.act_cup), "Перерыв", b, { b = if (b > 5) b - 5 else (b - 1).coerceAtLeast(1) }, { b = if (b >= 5) (b + 5).coerceAtMost(60) else b + 1 })
    }
}

@Composable
private fun StepRow(icon: Painter, title: String, minutes: Int, minus: () -> Unit, plus: () -> Unit, unit: String = "мин") {
    val c = NotAi.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        M3Icon(icon, null, Modifier.size(22.dp), tint = c.ink2)
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), style = NotAi.type.body, color = c.ink)
        IconAction(Icon.Minus, "$title: меньше", minus, size = 16.dp, tint = c.ink)
        Text("$minutes $unit".trim(), Modifier.widthIn(min = 64.dp).semantics { contentDescription = "$title: $minutes $unit".trim() }, style = NotAi.type.body.copy(fontWeight = FontWeight.Medium),
            color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconAction(Icon.Plus, "$title: больше", plus, size = 16.dp, tint = c.ink)
    }
}

/** A titled card: hairline border, inset dividers, nothing else. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = NotAi.colors
    Text(title, Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp), style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.ink2)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).border(1.dp, c.rule, RoundedCornerShape(20.dp)), content = content)
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(NotAi.colors.rule))

@Composable
private fun SettingRow(icon: Painter, title: String, status: String, warn: Boolean, action: Pair<String, () -> Unit>?, checked: Boolean,
                       onChange: (Boolean) -> Unit) {
    val c = NotAi.colors
    Row(Modifier.fillMaxWidth().pressable({ onChange(!checked) }, label = title).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        M3Icon(icon, null, Modifier.padding(top = 1.dp).size(22.dp), tint = if (checked) c.ink else c.ink2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = NotAi.type.body, color = c.ink)
            Text(status, style = NotAi.type.caption, color = if (warn) c.amberInk else c.ink2)
            action?.let { (label, act) -> PillButton(label, act, Modifier.padding(top = 6.dp), ButtonKind.TONAL, height = 38.dp) }
        }
        NotAiSwitch(checked, onChange, title)
    }
}

@Composable
private fun NavRow(icon: Painter, title: String, value: String? = null, note: String? = null, onClick: (() -> Unit)? = null) {
    val c = NotAi.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.pressable(onClick, label = title) else Modifier)
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        M3Icon(icon, null, Modifier.size(22.dp), tint = c.ink2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NotAi.type.body, color = c.ink)
            note?.let { Text(it, style = NotAi.type.caption, color = c.ink2) }
        }
        value?.takeIf { it.isNotEmpty() }?.let { Text(it, style = NotAi.type.bodySmall, color = c.ink2, maxLines = 1) }
        if (onClick != null) M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink3)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String?, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    val c = NotAi.colors
    AlertDialog(onDismissRequest = onDismiss, containerColor = c.raised, titleContentColor = c.ink, textContentColor = c.ink2,
        title = { Text(title, style = NotAi.type.sheetTitle) },
        text = { Text(text, style = NotAi.type.bodySmall) },
        confirmButton = {
            if (confirm != null) Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).pressable(onConfirm, label = confirm).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text(confirm, style = NotAi.type.button, color = if (destructive) c.danger else c.amberInk)
            }
        },
        dismissButton = { PillButton(if (confirm != null) "Отмена" else "Понятно", onDismiss, kind = ButtonKind.TEXT, height = 44.dp) })
}

/**
 * Copies that outlive the app. The automatic one goes to a folder the person picked (on the phone or a cloud app's folder),
 * after changes settle; the manual one is a file saved once. Restoring always goes through preview and confirmation.
 */
@Composable
private fun BackupPage(vm: TrackerViewModel, auto: AutoBackupUi?, last: BackupInfo?, view: TrackerView?, zone: ZoneId, today: java.time.LocalDate,
                       busy: Boolean, chooseFolder: () -> Unit, restoreFile: () -> Unit, restoreVersion: (String) -> Unit) {
    val c = NotAi.colors
    fun whenText(at: Long) = "${Format.dayTitle(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), today).lowercase()} в ${Format.clock(at, zone)}"
    val s = auto?.state
    Group("Автокопия в папку") {
        if (auto == null || s == null) {
            NavRow(rememberVectorPainter(Icon.Folder), "Автокопия", note = "Недоступна в этой сборке")
            return@Group
        }
        val err = s.error.takeIf { s.enabled }
        val suspended = s.enabled && s.consecutiveFailures >= 5
        val status = when {
            !s.enabled && s.destination == null -> "Сама сохраняет полную копию в выбранную папку: на телефоне или в облаке, если приложение облака даёт папку. Такая копия переживёт удаление приложения"
            !s.enabled -> "Выключена. Новые копии не сохраняются, прежние остаются в папке"
            suspended -> "Приостановлена после пяти неудач подряд. ${err?.let { Format.backupProblem(it) } ?: ""}".trim()
            err != null -> Format.backupProblem(err)
            s.pending != null -> "Сохраняю копию"
            s.lastSuccess != null -> "Последняя ${whenText(s.lastSuccess!!.verifiedAt)}, файл проверен"
            else -> "Первая копия появится через пару минут"
        }
        val action: Pair<String, () -> Unit>? = when {
            !s.enabled -> null
            err in setOf(dev.notai.tracker.android.AutomaticBackupError.GRANT_REVOKED, dev.notai.tracker.android.AutomaticBackupError.REQUIRES_SELECTION,
                dev.notai.tracker.android.AutomaticBackupError.UNSUPPORTED) -> "Выбрать папку" to chooseFolder
            err != null || suspended -> "Повторить" to { vm.retryAutoBackup() }
            else -> null
        }
        SettingRow(rememberVectorPainter(Icon.Folder), "Автокопия", status, warn = err != null || suspended, action = action, checked = s.enabled) { on ->
            if (on && s.destination == null) chooseFolder() else vm.setAutoBackup(on)
        }
        if (s.destination != null) {
            Divider()
            NavRow(rememberVectorPainter(Icon.Folder), "Папка", value = "Сменить", note = auto.folder ?: "Выбранная папка", onClick = chooseFolder)
        }
        if (s.enabled) {
            Divider()
            var keep by remember(s.policy.retain) { mutableIntStateOf(s.policy.retain) }
            LaunchedEffect(keep) { if (keep == s.policy.retain) return@LaunchedEffect; delay(700); vm.setBackupRetention(keep) }
            StepRow(rememberVectorPainter(Icon.Layers), "Хранить копий", keep, { keep = (keep - 1).coerceAtLeast(1) }, { keep = (keep + 1).coerceAtMost(30) }, unit = "")
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("После изменений копия сохраняется через пару минут, когда телефон разрешит фоновую работу. Раз в сутки — даже без изменений. " +
                    "Старые копии убираются, только когда новая проверена.", style = NotAi.type.caption, color = c.ink2)
                ButtonPair(spacing = 8.dp) { m ->
                    PillButton("Сохранить сейчас", { vm.backupNow() }, m, ButtonKind.TONAL, height = 42.dp, enabled = s.pending == null)
                    val uncertain = s.pending?.takeIf { err == dev.notai.tracker.android.AutomaticBackupError.CREATION_UNCERTAIN }
                    if (uncertain != null) PillButton("Пропустить эту", { vm.skipUncertainBackup(uncertain.id) }, m, ButtonKind.TEXT, height = 42.dp)
                }
            }
        }
    }
    val versions = s?.versions.orEmpty().filter { it.destination == s?.destination }.sortedByDescending { it.verifiedAt }
    if (versions.isNotEmpty()) Group("Копии в папке") {
        Text("Нажми на копию: сначала покажу, что в ней, заменю данные только после подтверждения",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = NotAi.type.caption, color = c.ink2)
        versions.forEachIndexed { i, v ->
            if (i > 0) Divider()
            NavRow(rememberVectorPainter(Icon.Download), whenText(v.snapshot.asOf).replaceFirstChar { it.uppercase() },
                value = sizeText(v.snapshot.bytes), onClick = if (busy) null else ({ restoreVersion(v.operationId) }))
        }
    }
    Group("Вручную") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                M3Icon(Icon.Download, null, Modifier.size(22.dp), tint = c.ink2)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Файл копии", style = NotAi.type.body, color = c.ink)
                    Text(last?.let { "Последний ${whenText(it.at)}" + if (it.verified) ", файл проверен" else "" } ?: "С этого телефона файлов ещё не сохраняли",
                        style = NotAi.type.caption, color = c.ink2)
                    view?.data?.let { d ->
                        Text(Format.plural(d.sessions.size, "запись", "записи", "записей") + ", " + Format.plural(d.cards.size, "своё занятие", "своих занятия", "своих занятий") +
                            ", " + Format.plural(d.goals.size, "цель", "цели", "целей"), style = NotAi.type.caption, color = c.ink2)
                    }
                }
            }
            ButtonPair(spacing = 8.dp) { m ->
                PillButton("Сохранить", { vm.requestExport() }, m, ButtonKind.PRIMARY, height = 44.dp, enabled = !busy)
                PillButton("Восстановить", restoreFile, m, ButtonKind.TONAL, height = 44.dp, enabled = !busy)
            }
            Text("После переустановки выбери здесь любой файл из папки автокопий. Копия — не синхронизация между телефонами.",
                style = NotAi.type.caption, color = c.ink2)
        }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.forLanguageTag("ru"), "%.1f МБ", bytes / 1_048_576.0)
    else -> "${(bytes + 1023) / 1024} КБ"
}
