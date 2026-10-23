package dev.notai.tracker.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.annotation.StringRes

/** Stable platform choice, outside the portable dataset/backup. MainActivity is never disabled. */
class LauncherIconController(private val context: Context) {
    data class Option(val key: String,val alias: String,@StringRes val labelRes: Int) {
        fun label(context: Context): String = context.getString(labelRes)
    }
    data class State(val selected: String?,val enabled: Set<String>)
    data class Selection(val state: State,val shortcutsRebound: Boolean)
    data class AvailableOption(val option: Option,val iconResource: Int,val compatibleKeys: Set<String>)

    /** Keep legacy keys switchable, but offer one row for each actual packaged icon resource. */
    fun availableOptions(): List<AvailableOption> = options.groupBy { option ->
        context.packageManager.getActivityInfo(component(option),PackageManager.MATCH_DISABLED_COMPONENTS).icon
            .takeIf { it != 0 } ?: context.applicationInfo.icon
    }.map { (icon,entries) -> AvailableOption(entries.first(),icon,entries.map { it.key }.toSet()) }

    fun read(): State {
        val enabled = options.filter { option ->
            when(context.packageManager.getComponentEnabledSetting(component(option))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> option.key == "default"
                else -> false
            }
        }.map { it.key }.toSet()
        return State(enabled.singleOrNull(),enabled)
    }
    fun component(): ComponentName {
        val state = read()
        return component(options.first { it.key == (state.selected ?: state.enabled.firstOrNull() ?: "default") })
    }
    private fun component(option: Option) = ComponentName(context, "dev.notai.tracker.android.${option.alias}")

    /** Call off the UI thread. On failure, attempts rollback and keeps an entry enabled if rollback fails.
     * Old APIs enable the destination before disabling other aliases, preserving an entry point.
     * A killed process between calls can leave duplicates; read() reports that state honestly.
     */
    fun select(key: String): Selection = synchronized(lock) {
        val chosen = options.singleOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown launcher icon")
        val manager = context.packageManager
        val previous = options.associateWith { manager.getComponentEnabledSetting(component(it)) }
        try {
            if(Build.VERSION.SDK_INT >= 33) {
                manager.setComponentEnabledSettings(options.map { option ->
                    PackageManager.ComponentEnabledSetting(component(option),
                        if(option == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP)
                })
            } else {
                manager.setComponentEnabledSetting(component(chosen),PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
                options.filter { it != chosen }.forEach { manager.setComponentEnabledSetting(component(it),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP) }
            }
            check(read().selected == key) { "Launcher selection unavailable" }
        } catch(failure: RuntimeException) {
            // Restore previously enabled entries first; never remove the sole working alias first.
            val restoreOrder = options.sortedBy { if(previous[it] == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (previous[it] == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it.key == "default")) 0 else 1 }
            if(Build.VERSION.SDK_INT >= 33) {
                runCatching { manager.setComponentEnabledSettings(restoreOrder.map { PackageManager.ComponentEnabledSetting(component(it),previous.getValue(it),PackageManager.DONT_KILL_APP) }) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
            } else {
                val formerlyEnabled = restoreOrder.filter { previous[it] == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (previous[it] == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it.key == "default") }
                fun restore(option: Option) {
                    runCatching { manager.setComponentEnabledSetting(component(option),previous.getValue(option),PackageManager.DONT_KILL_APP) }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
                formerlyEnabled.forEach(::restore)
                // If restoring every old entry failed, retain the newly enabled alias rather than
                // disabling the last working entry while unwinding the original error.
                val restored = runCatching { read().enabled.any { key -> formerlyEnabled.any { it.key == key } } }
                    .onFailure(failure::addSuppressed).getOrDefault(false)
                if(restored) restoreOrder.filter { it !in formerlyEnabled }.forEach(::restore)
            }
            throw failure
        }
        // Preserve each shortcut's ID, immutable route/payload, ranks and labels. No new token.
        val rebound = runCatching {
            val shortcuts = context.getSystemService(ShortcutManager::class.java)
            val existing = (shortcuts.dynamicShortcuts + shortcuts.pinnedShortcuts).distinctBy { it.id }
                .filter { it.id in shortcutIds && !it.isImmutable }
            existing.isEmpty() || shortcuts.updateShortcuts(existing.map {
                boundShortcut(context,it,component(chosen))
            })
        }.getOrDefault(false)
        Selection(read(),rebound)
    }

    companion object {
        private val lock = Any()
        /** These four app-owned entries all use the same existing icon. Copy public payload
         * fields explicitly so the rebind cannot replace an entry with an intent-less shell. */
        internal fun boundShortcut(context: Context,entry: ShortcutInfo,activity: ComponentName): ShortcutInfo =
            ShortcutInfo.Builder(context,entry.id).setActivity(activity)
                .setShortLabel(requireNotNull(entry.shortLabel)).setIntents(requireNotNull(entry.intents)).setRank(entry.rank)
                .setIcon(android.graphics.drawable.Icon.createWithResource(context,R.drawable.ic_shortcut)).apply {
                    entry.longLabel?.let(::setLongLabel)
                    entry.disabledMessage?.let(::setDisabledMessage)
                    entry.categories?.let(::setCategories)
                    entry.extras?.let(::setExtras)
                }.build()
        internal fun <T> withBinding(context: Context,block: (ComponentName) -> T): T = synchronized(lock) {
            block(LauncherIconController(context).component())
        }
        // Stable platform identities and localized labels. availableOptions reads the actual
        // packaged resources; UI themes never supply or rename this catalogue.
        val options = listOf(
            Option("default","LauncherDefault",R.string.launcher_icon_option_default),
            Option("amber","LauncherAmber",R.string.launcher_icon_option_placeholder_1),
            Option("mint","LauncherMint",R.string.launcher_icon_option_placeholder_2),
            Option("violet","LauncherViolet",R.string.launcher_icon_option_placeholder_3),
        )
        private val shortcutIds = setOf("timer_action","timer_choose","timer_favorite_0","timer_favorite_1")
    }
}
