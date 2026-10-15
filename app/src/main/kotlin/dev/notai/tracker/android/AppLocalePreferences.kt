package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*

/** Single durable UI preference, deliberately outside Room/Dataset and archives. The frontend
 * calls update only AFTER a confirmed platform/in-app change, and uses this same state on every
 * surface. It must not continuously push an old mirror back into Android LocaleManager.
 * Existing installations may explicitly migrate their previous Russian UI once; new installs
 * retain SYSTEM. Configuration recreation/draft restoration remains the frontend's boundary.
 */
class AppLocalePreferences(context: Context,existingRussianInstallation: Boolean = false): AppLocaleStore {
    private val preferences = context.applicationContext.getSharedPreferences("app-locale",Context.MODE_PRIVATE)
    init { synchronized(lock) {
        if(!preferences.contains("state")) {
            val initial = AppLocalePreference(explicitTag = if(existingRussianInstallation) "ru" else null)
            ensure(preferences.edit().putString("state",codec.encodeToString(initial)).commit(),"Locale migration failed",ErrorCode.STORAGE)
        }
    } }
    override fun read(): AppLocalePreference = synchronized(lock) {
        codec.decodeFromString(preferences.getString("state",null) ?: fail(ErrorCode.STORAGE,"Locale preference missing"))
    }
    override fun update(expectedRevision: Long,edit: (AppLocalePreference)->AppLocalePreference): AppLocalePreference = synchronized(lock) {
        val old = read();ensure(old.revision == expectedRevision,"Locale settings changed",ErrorCode.STALE)
        val next = edit(old);ensure(next.revision == Math.addExact(old.revision,1),"Invalid locale revision")
        next.explicitTag?.let { ensure(it == localeTag(it),"Noncanonical locale") }
        ensure(preferences.edit().putString("state",codec.encodeToString(next)).commit(),"Locale settings write failed",ErrorCode.STORAGE)
        next
    }
    companion object { private val lock = Any() }
}
