package dev.notai.tracker.android.ui


import dev.notai.tracker.android.R
import dev.notai.tracker.android.L
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
private val licenseAssets get() = listOf(
    LicenseAsset(L.s(R.string.t_5fdf8988d5), "project-Apache-2.0.txt"),
    LicenseAsset(L.s(R.string.t_861bd8760b), "onest-OFL.txt"),
    LicenseAsset(L.s(R.string.t_832c48f416), "third-party-notices.txt"),
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
                .getOrElse { L.s(R.string.t_dd27b7de89) }
        }
    }
    NotAiSheet(onDismiss) {
        SheetHeader(asset?.title ?: L.s(R.string.t_488dd14a85), onDismiss, back = if (asset != null) ({ selected = null }) else null)
        if (asset == null) Column(Modifier.padding(horizontal = 20.dp)) {
            licenseAssets.forEach { item ->
                ListRow(item.title, onClick = { selected = item })
            }
        } else {
            val scroll = rememberScrollState()
            SelectionContainer(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(text ?: L.s(R.string.t_dc417ebce7), style = NotAi.type.bodySmall, color = c.ink)
            }
        }
    }
}
