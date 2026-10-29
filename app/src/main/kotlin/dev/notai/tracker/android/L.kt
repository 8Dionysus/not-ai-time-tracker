package dev.notai.tracker.android

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Visible copy in the language the person chose, from anywhere: composables, the view model, notifications,
 * widgets. The tag is snapshot state, so every composition that read a string recomposes when the language
 * changes, without recreating the activity or losing what is on screen. User values are never passed through here.
 */
object L {
    @Volatile private var app: Context? = null
    private val tag = mutableStateOf<String?>(null)
    private val cache = ConcurrentHashMap<String, Resources>()

    fun init(context: Context) {
        // Every Application start (and every test application) rebinds: the choice lives in that app's preferences.
        app = context.applicationContext
        cache.clear()
        tag.value = runCatching { AppLanguage.state(context.applicationContext).resolvedLocaleTag }.getOrDefault("ru")
    }

    /** The resolved tag of the current choice; set by the language picker and at start. */
    fun use(resolvedTag: String) { if (tag.value != resolvedTag) tag.value = resolvedTag }

    val currentTag: String get() = tag.value ?: "ru"
    val locale: Locale get() = Locale.forLanguageTag(currentTag)

    /** Normally bound in TrackerApplication.onCreate. A component created earlier (or a bare test) finds the process application. */
    private fun context(): Context = app ?: (runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()?.also(::init) ?: error("No application context for copy"))
    fun res(): Resources { val t = currentTag; return cache.getOrPut(t) { AppLanguage.context(context(), t).resources } }

    fun s(id: Int): String = res().getString(id)
    fun s(id: Int, vararg args: Any?): String = res().getString(id, *args)
    /** Quantity strings carry the number as their first argument. */
    fun p(id: Int, n: Int, vararg args: Any?): String = res().getQuantityString(id, n, n, *args)
    fun p(id: Int, n: Long, vararg args: Any?): String = p(id, n.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(), *args)
}
