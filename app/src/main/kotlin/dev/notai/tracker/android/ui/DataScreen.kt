package dev.notai.tracker.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.notai.tracker.CatalogPreview
import dev.notai.tracker.RestorePreview
import dev.notai.tracker.TrackerView
import dev.notai.tracker.android.TimerSurfaces
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun DataScreen(vm: TrackerViewModel, view: TrackerView?, theme: ThemeChoice, openTechnical: () -> Unit, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = view?.data?.settings
    val zone = settings?.reportZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val last by vm.lastBackup.collectAsStateWithLifecycle()
    val exportPreparing by vm.exportPreparing.collectAsStateWithLifecycle()
    var restore by remember { mutableStateOf<RestorePreview?>(null) }
    var catalog by remember { mutableStateOf<CatalogPreview?>(null) }
    var zoneConfirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var permissionTick by remember { mutableIntStateOf(0) }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { busy = true; restore = vm.prepareRestore(uri); busy = false }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Данные", style = NotAi.type.title, color = c.ink)
        }

        Section("Резервная копия") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(last?.let { "Последняя копия ${Format.dayTitle(Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate(), java.time.LocalDate.now(zone)).lowercase()} в ${Format.clock(it.at, zone)}" }
                    ?: "Копий с этого телефона ещё не было", style = NotAi.type.body, color = c.ink)
                view?.data?.let { d ->
                    Text(Format.plural(d.sessions.size, "запись", "записи", "записей") + ", " + Format.plural(d.cards.size, "своё занятие", "своих занятия", "своих занятий") +
                        ", " + Format.plural(d.goals.size, "цель", "цели", "целей") +
                        (last?.let { if (it.verified) ". Файл проверен чтением." else "." } ?: "."), style = NotAi.type.bodySmall, color = c.ink2)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Сохранить копию", { vm.requestExport() }, Modifier.weight(1f), ButtonKind.PRIMARY, height = 50.dp, enabled = !busy && !exportPreparing)
                PillButton("Восстановить", { open.launch(arrayOf("application/json", "*/*")) }, Modifier.weight(1f), ButtonKind.TONAL, height = 50.dp, enabled = !busy && !exportPreparing)
            }
            Text("Восстановление заменяет все данные на телефоне. Перед заменой покажем, что изменится, и сохраним текущие данные отдельно.",
                style = NotAi.type.caption, color = c.ink2)
        }

        Section("Таблицы", gap = 0.dp) {
            ListRow("Выгрузить CSV", subtitle = "Вся история для таблиц и своих подсчётов. Это не резервная копия.", onClick = { vm.requestExport(csv = true) },
                trailing = { M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink2) }, minHeight = 60.dp)
            ListRow("Добавить историю из CSV", subtitle = "Из Toggl, Clockify или своей таблицы. Пока в технической оболочке.", onClick = openTechnical,
                trailing = { M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink2) }, minHeight = 60.dp)
        }

        Section("Каталог занятий") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, c.rule, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val types = view?.data?.types?.size ?: 0
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("%,d".format(types).replace(',', ' '), style = NotAi.type.big.copy(fontSize = 30.sp), color = c.ink)
                    Text("занятий и групп", style = NotAi.type.bodySmall, color = c.ink2, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text("Версия каталога: ${view?.data?.catalogVersion ?: "базовая"}. Обновление добавит новые занятия и уточнения; твои записи, карточки и названия не изменятся.",
                    style = NotAi.type.bodySmall, color = c.ink2)
                PillButton("Проверить обновление", { scope.launch { busy = true; catalog = vm.prepareCatalog(); busy = false } }, Modifier.fillMaxWidth(),
                    ButtonKind.TONAL, height = 46.dp, enabled = !busy)
            }
        }

        val surfaces = settings?.surfaces
        key(permissionTick) {
            Section("Быстрый доступ", gap = 0.dp) {
                SwitchRow("Виджет на главном экране", "Добавляется через рабочий стол", surfaces?.widget == true) { on -> vm.setSurfaces { it.copy(widget = on) } }
                val notifAllowed = TimerSurfaces.notificationsAllowed(context)
                SwitchRow("Уведомление с кнопками", if (surfaces?.notification == true && !notifAllowed) "Запрещено в настройках телефона" else "Пауза и стоп из шторки",
                    surfaces?.notification == true, warn = surfaces?.notification == true && !notifAllowed) { on ->
                    vm.setSurfaces { it.copy(notification = on) }
                    if (on && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val overlayAllowed = TimerSurfaces.overlayAllowed(context)
                SwitchRow("Плавающая кнопка", if (overlayAllowed) "Таймер поверх других приложений" else "Нужно разрешение «Поверх других приложений»",
                    surfaces?.overlay == true, warn = !overlayAllowed) { on ->
                    vm.setSurfaces { it.copy(overlay = on) }
                    if (on && !overlayAllowed) runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")))
                    }
                }
                SwitchRow("Сигнал конца отрезка в фокусе", "Уведомление; телефон может задержать его", surfaces?.focusSignal == true) { on ->
                    vm.setSurfaces { it.copy(focusSignal = on) }
                    if (on && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        Section("Вид и отчёты", gap = 0.dp) {
            val device = ZoneId.systemDefault().id
            ListRow("Часовой пояс отчётов", subtitle = if (settings != null && settings.reportZone != device) "На телефоне сейчас $device" else null,
                onClick = { if (settings != null && settings.reportZone != device) zoneConfirm = device },
                trailing = { Text(settings?.reportZone ?: "", style = NotAi.type.bodySmall, color = c.ink2) })
            Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Оформление", style = NotAi.type.body, color = c.ink)
                Segmented(listOf(ThemeChoice.SYSTEM to "Как в системе", ThemeChoice.LIGHT to "Светлое", ThemeChoice.DARK to "Тёмное"), theme, vm::setTheme)
            }
            ListRow("Техническая оболочка", subtitle = "Все функции ядра без оформления: цели, измерения, календарь, восстановление базы",
                onClick = openTechnical, trailing = { M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink2) }, divider = false)
        }
    }

    restore?.let { p ->
        ConfirmDialog("Заменить все данные?",
            "В файле ${p.sessions} записей, ${p.cards} своих занятий, ${p.goals} целей." +
                (if (p.unfinishedTail || p.unfinishedFocus) " Незакрытая запись придёт как «время неизвестно», её нужно будет уточнить." else "") +
                " Текущие данные будут сохранены отдельно перед заменой. Записи, сделанные после этой копии, исчезнут.",
            "Заменить", { vm.applyRestore(p); restore = null }, { restore = null })
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

@Composable
private fun Section(title: String, gap: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        Text(title, Modifier.padding(bottom = if (gap == 0.dp) 4.dp else 0.dp), style = NotAi.type.section, color = NotAi.colors.ink)
        content()
    }
}

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, warn: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = NotAi.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = NotAi.type.body, color = c.ink)
                Text(note, style = NotAi.type.caption, color = if (warn) c.amberInk else c.ink2)
            }
            NotAiSwitch(checked, onChange, title)
        }
        Hairline()
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    AlertDialog(onDismissRequest = onDismiss, containerColor = c.raised, titleContentColor = c.ink, textContentColor = c.ink2,
        title = { Text(title, style = NotAi.type.sheetTitle) },
        text = { Text(text, style = NotAi.type.bodySmall) },
        confirmButton = { if (confirm != null) PillButton(confirm, onConfirm, kind = ButtonKind.TEXT, height = 44.dp) },
        dismissButton = { PillButton(if (confirm != null) "Отмена" else "Понятно", onDismiss, kind = ButtonKind.TEXT, height = 44.dp) })
}
