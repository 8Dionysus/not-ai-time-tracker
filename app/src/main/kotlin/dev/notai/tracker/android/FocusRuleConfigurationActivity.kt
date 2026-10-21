package dev.notai.tracker.android

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.notai.tracker.android.ui.*

/** System rule configuration opens the existing Focus settings, without starting a timer or an effect. */
class FocusRuleConfigurationActivity: ComponentActivity() {
    private val vm: TrackerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_RULE_ID)
        if(id != null) {
            if(android.os.Build.VERSION.SDK_INT < 29) { finish();return }
            val owned = runCatching { getSystemService(NotificationManager::class.java).automaticZenRules[id]
                ?.configurationActivity == ComponentName(this,FocusRuleConfigurationActivity::class.java) }.getOrDefault(false)
            if(!owned) { finish();return }
            setResult(RESULT_OK,Intent().putExtra(NotificationManager.EXTRA_AUTOMATIC_RULE_ID,id))
        }
        enableEdgeToEdge()
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val appearance by vm.appearance.collectAsStateWithLifecycle()
            val view by vm.view.collectAsStateWithLifecycle()
            NotAiTheme(theme,appearance = appearance) {
                SettingsScreen(vm,view,theme,{ startActivity(Intent(this,TechnicalActivity::class.java)) },
                    Modifier.fillMaxSize().background(NotAi.colors.paper).safeDrawingPadding(),initialPage = SettingsPage.FOCUS,closeSection = { finish() })
            }
        }
    }
}
