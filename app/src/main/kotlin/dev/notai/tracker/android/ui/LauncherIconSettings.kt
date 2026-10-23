package dev.notai.tracker.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Uses the same list rows as the rest of settings; supplied resources are the real launcher mark variants. */
@Composable
internal fun LauncherIconSettings(host: LauncherIconsUiHost) {
    val state by host.launcherIcons.collectAsStateWithLifecycle()
    val c = NotAi.colors
    if (state.options.isEmpty()) return
    Group("Значок приложения") {
        state.options.forEach { option ->
            val on = state.selectedKey in option.compatibleKeys
            ListRow(option.title, Modifier.padding(horizontal = 16.dp).semantics { selected = on },
                leading = { AndroidView(factory = { android.widget.ImageView(it) },
                    modifier = Modifier.size(40.dp),update = { it.setImageResource(option.previewDrawable) }) },
                trailing = { if (on) Text("Выбран", style = NotAi.type.caption, color = c.amberInk) },
                onClick = if (state.changing || on) null else ({ host.chooseLauncherIcon(option.key) }))
        }
        state.problem?.let { Text(it, Modifier.padding(16.dp), style = NotAi.type.caption, color = c.danger) }
    }
}
