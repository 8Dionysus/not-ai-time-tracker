package dev.notai.tracker.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.notai.tracker.android.AppLanguage
import dev.notai.tracker.android.R

@Composable internal fun LanguageSettingsRow(vm: TrackerViewModel) {
    val state by vm.language.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val title = stringResource(R.string.language_title)
    val system = stringResource(R.string.language_system)
    val current = state.preference.explicitTag?.let(AppLanguage::nativeName) ?: system
    SectionRow(painterResource(R.drawable.act_language),title,current,false) { open = true }
    if(open) NotAiSheet({ open = false }) {
        SheetHeader(title,{ open = false })
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(system,{ vm.setLanguage(null);open = false },Modifier.fillMaxWidth(),if(state.preference.explicitTag == null) ButtonKind.PRIMARY else ButtonKind.TONAL)
            AppLanguage.supported.forEach { tag ->
                PillButton(AppLanguage.nativeName(tag),{ vm.setLanguage(tag);open = false },Modifier.fillMaxWidth(),
                    if(state.preference.explicitTag == tag) ButtonKind.PRIMARY else ButtonKind.TONAL)
            }
            Text(stringResource(R.string.language_partial),Modifier.padding(12.dp),style = NotAi.type.caption,color = NotAi.colors.ink2)
        }
    }
}
