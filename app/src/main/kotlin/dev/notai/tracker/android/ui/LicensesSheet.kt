package dev.notai.tracker.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LicenseAsset(val title: String, val file: String)
private val licenseAssets = listOf(
    LicenseAsset("Приложение · Apache 2.0", "project-Apache-2.0.txt"),
    LicenseAsset("Шрифт Onest · SIL Open Font License", "onest-OFL.txt"),
    LicenseAsset("Сторонние компоненты", "third-party-notices.txt"),
)

/** Displays the existing bundled notices verbatim; no remote document or permissions are needed. */
@Composable
internal fun LicensesSheet(onDismiss: () -> Unit) {
    val c = NotAi.colors
    val context = LocalContext.current
    var selected by remember { mutableStateOf<LicenseAsset?>(null) }
    val asset = selected
    val text by produceState<String?>(null, asset) {
        value = null
        if (asset != null) value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/${asset.file}").bufferedReader().use { it.readText() } }
                .getOrElse { "Не удалось открыть лицензию" }
        }
    }
    NotAiSheet(onDismiss) {
        SheetHeader(asset?.title ?: "Лицензии", onDismiss, back = if (asset != null) ({ selected = null }) else null)
        if (asset == null) Column(Modifier.padding(horizontal = 20.dp)) {
            licenseAssets.forEach { item ->
                ListRow(item.title, onClick = { selected = item })
            }
        } else {
            val scroll = rememberScrollState()
            SelectionContainer(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(text ?: "Загружаю…", style = NotAi.type.bodySmall, color = c.ink)
            }
        }
    }
}
