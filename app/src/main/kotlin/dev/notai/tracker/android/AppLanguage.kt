package dev.notai.tracker.android

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import dev.notai.tracker.*
import java.util.Locale

/** The existing locale model/store plus Android's resource resolver, never persisted user-text replacement. */
object AppLanguage {
    val supported = linkedSetOf("ru","en","es","fr","pt-BR","de","it","ar","zh-Hans","ja","ko","hi")
    fun systemTags(): List<String> = Resources.getSystem().configuration.locales.toLanguageTags().split(',').filter { it.isNotBlank() }
    private fun controller(context: Context) = AppLocales(AppLocalePreferences(context),supported,"ru")
    fun state(context: Context) = controller(context).state(systemTags())
    fun select(context: Context,tag: String?,revision: Long): AppLocaleState {
        controller(context).set(tag,revision,LocaleChangeSource.IN_APP)
        return state(context)
    }
    fun nativeName(tag: String) = Locale.forLanguageTag(tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }
    fun context(base: Context,tag: String = state(base).resolvedLocaleTag): Context {
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        val localized = base.createConfigurationContext(config)
        // Keep the Activity in the wrapper chain for existing permission/document launchers.
        return object: ContextWrapper(base) {
            override fun getResources() = localized.resources
            override fun getAssets() = localized.assets
        }
    }
}
