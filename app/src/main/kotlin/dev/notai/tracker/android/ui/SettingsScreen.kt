package dev.notai.tracker.android.ui

import dev.notai.tracker.android.RestoreReviewOwner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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

/**
 * Settings: what is on the phone's screens first, then Focus, appearance, data and the technical shell.
 * Every state line comes from SettingsCapabilities (actual OS observations); system prompts open only from a tap.
 */
@Composable
fun SettingsScreen(vm: TrackerViewModel, view: TrackerView?, theme: ThemeChoice, openTechnical: () -> Unit, modifier: Modifier = Modifier,
                   openPlan: (PlanSeriesUi?) -> Unit = {}) {
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
    val exportPreparing by vm.exportPreparing.collectAsStateWithLifecycle()
    var restore by remember { mutableStateOf<RestorePreview?>(null) }
    val restoreOwner = remember(vm) { RestoreReviewOwner<RestorePreview>(vm::releaseRestorePreviewOnIo) }
    DisposableEffect(restoreOwner) { onDispose { restoreOwner.close() } }
    val heldRestore = restore
    DisposableEffect(heldRestore) { onDispose { heldRestore?.let(restoreOwner::discard) } }
    var catalog by remember { mutableStateOf<CatalogPreview?>(null) }
    var zoneConfirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notificationsDenied by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshCapabilities(); vm.refreshLongRecord() }
    LaunchedEffect(settings?.longRecordReminder) { vm.refreshLongRecord() }
    val longRecord by vm.longRecord.collectAsStateWithLifecycle()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true;restore = null
            try { restore = vm.prepareRestore(uri,restoreOwner) } finally { busy = false }
        }
    }
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

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Настройки", style = NotAi.type.title, color = c.ink)
        }

        Group("Таймер вне приложения") {
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
            Divider()
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
            SettingRow(rememberVectorPainter(Icon.TabTimer), "Ярлык на значке", status = "Долгое нажатие на значок приложения: старт и стоп",
                warn = false, action = null, checked = surfaces?.shortcut == true) { on -> vm.setSurfaceEnabled(Surface.SHORTCUT, on) }
            Divider()
            settings?.longRecordReminder?.let { pref -> LongRecordRow(vm, pref, longRecord, ::askNotifications) { system(SurfaceSettingsAction.OPEN_NOTIFICATION_SETTINGS) } }
        }

        PlanGroup(vm.planSeries(view), openPlan)

        settings?.focusPlan?.let { plan -> FocusGroup(vm, (plan.workMs / 60_000L).toInt(), (plan.breakMs / 60_000L).toInt()) }

        Group("Вид") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    M3Icon(if (c.isDark) Icon.Moon else Icon.Sun, null, Modifier.size(22.dp), tint = c.ink2)
                    Text("Тема", style = NotAi.type.body, color = c.ink)
                }
                Segmented(listOf(ThemeChoice.SYSTEM to "Системная", ThemeChoice.LIGHT to "Светлая", ThemeChoice.DARK to "Тёмная"), theme, vm::setTheme)
            }
        }

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

        Group("Данные") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    M3Icon(Icon.Download, null, Modifier.size(22.dp), tint = c.ink2)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Резервная копия", style = NotAi.type.body, color = c.ink)
                        Text(last?.let { "Последняя ${Format.dayTitle(Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate(), Instant.ofEpochMilli(vm.now()).atZone(zone).toLocalDate()).lowercase()} в ${Format.clock(it.at, zone)}" +
                            if (it.verified) ", файл проверен" else "" } ?: "С этого телефона копий ещё не было", style = NotAi.type.caption, color = c.ink2)
                        view?.data?.let { d ->
                            Text(Format.plural(d.sessions.size, "запись", "записи", "записей") + ", " + Format.plural(d.cards.size, "своё занятие", "своих занятия", "своих занятий") +
                                ", " + Format.plural(d.goals.size, "цель", "цели", "целей"), style = NotAi.type.caption, color = c.ink2)
                        }
                    }
                }
                ButtonPair(spacing = 8.dp) { m ->
                    PillButton("Сохранить", { vm.requestExport() }, m, ButtonKind.PRIMARY, height = 44.dp, enabled = !busy && !exportPreparing)
                    PillButton("Восстановить", { open.launch(arrayOf("application/json", "*/*")) }, m, ButtonKind.TONAL, height = 44.dp,
                        enabled = !busy && !exportPreparing)
                }
            }
            Divider()
            NavRow(rememberVectorPainter(Icon.Table), "Выгрузить в CSV", note = "Вся история для таблиц. Это не резервная копия", onClick = { vm.requestExport(csv = true) })
            Divider()
            NavRow(rememberVectorPainter(Icon.Upload), "Загрузить историю из CSV", note = "Из Toggl, Clockify или своей таблицы. Пока в технической оболочке",
                onClick = openTechnical)
            Divider()
            val types = view?.data?.types?.size ?: 0
            NavRow(rememberVectorPainter(Icon.Layers), "Каталог занятий",
                note = "${Format.plural(types, "занятие", "занятия", "занятий").replace(Regex("^(\\d)(\\d{3})"), "$1 $2")}, версия ${view?.data?.catalogVersion ?: "базовая"}. Нажми, чтобы проверить обновление",
                onClick = { scope.launch { busy = true; catalog = vm.prepareCatalog(); busy = false } })
        }

        Group("Ещё") {
            NavRow(painterResource(R.drawable.act_tools), "Техническая оболочка", note = "Измерения, календарь, сопоставление CSV, восстановление базы",
                onClick = openTechnical)
            Divider()
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
            NavRow(rememberVectorPainter(Icon.Info), "(not)AI Time Tracker ${version.orEmpty()}".trim(),
                note = "Все данные хранятся только на этом телефоне. Шрифт Onest, SIL Open Font License")
        }
    }

    restore?.let { p ->
        ConfirmDialog("Заменить все данные?",
            "В файле ${Format.plural(p.sessions, "запись", "записи", "записей")}, ${Format.plural(p.cards, "своё занятие", "своих занятия", "своих занятий")}, " +
                "${Format.plural(p.goals, "цель", "цели", "целей")}." +
                (if (p.unfinishedTail || p.unfinishedFocus) " Незакрытая запись придёт как «время неизвестно», её нужно будет уточнить." else "") +
                " Текущие данные будут сохранены отдельно перед заменой. Записи, сделанные после этой копии, исчезнут.",
            "Заменить", { vm.applyRestore(p,restoreOwner); restore = null }, { restoreOwner.discard(p);restore = null }, destructive = true)
    }
    catalog?.let { p ->
        val r = p.report
        ConfirmDialog(if (r.added.isEmpty() && r.updated.isEmpty()) "Каталог уже актуален" else "Обновить каталог?",
            "Версия ${p.version}. Новых занятий: ${r.added.size}, уточнений: ${r.updated.size}" +
                (if (r.conflicts.isNotEmpty()) ", расхождений со своими изменениями: ${r.conflicts.size}. Они требуют разбора в технической оболочке." else "."),
            if (p.canApply && (r.added.isNotEmpty() || r.updated.isNotEmpty())) "Обновить" else null, { vm.applyCatalog(p); catalog = null }, { catalog = null })
    }
    zoneConfirm?.let { z ->
        ConfirmDialog("Сменить часовой пояс отчётов на $z?",
            "Изменятся границы дней в истории и обзоре. Сами записи и зоны целей не меняются.", "Сменить",
            { vm.setReportZone(z); zoneConfirm = null }, { zoneConfirm = null })
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

/** The optional plan for days. Each entry reads as one line; tapping opens it. */
@Composable
private fun PlanGroup(series: List<PlanSeriesUi>, open: (PlanSeriesUi?) -> Unit) {
    val c = NotAi.colors
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
    Group("Фокус") {
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
        Text("$minutes $unit", Modifier.widthIn(min = 64.dp).semantics { contentDescription = "$title: $minutes $unit" }, style = NotAi.type.body.copy(fontWeight = FontWeight.Medium),
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
