package dev.notai.tracker

import org.junit.Test
import kotlin.test.*

class LocaleContractTest {
    private class Preferences: AppLocaleStore {
        var value = AppLocalePreference()
        override fun read() = value
        override fun update(expectedRevision: Long,edit: (AppLocalePreference)->AppLocalePreference): AppLocalePreference {
            if(value.revision != expectedRevision) throw CoreError(ErrorCode.STALE,"Settings stale")
            return edit(value).also { value = it }
        }
    }
    @Test fun onePreferenceFollowsSystemOrLastConfirmedExplicitChange() {
        val store = Preferences();val locales = AppLocales(store,setOf("ru","en","es","es-419","es-MX","zh-Hans","zh-Hant"),"ru")
        val tracker = Tracker(MemoryStore(),FakeClock());tracker.start(tracker.snapshot().token)
        val before = tracker.data();val token = tracker.snapshot().token;val archive = Backups(tracker,MemoryRollback()).export()
        assertEquals("es-419",locales.state(listOf("es-419","en")).resolvedLocaleTag)
        assertEquals("en",locales.state(listOf("en")).resolvedLocaleTag)
        locales.set("es-MX",0,LocaleChangeSource.IN_APP)
        assertEquals("es-MX",locales.state(listOf("en")).resolvedLocaleTag)
        locales.set("en",1,LocaleChangeSource.ANDROID_SETTINGS)
        assertEquals("en",locales.state(listOf("ru")).resolvedLocaleTag)
        assertFailsWith<CoreError> { locales.set("ru",1,LocaleChangeSource.IN_APP) }
        assertFailsWith<CoreError> { locales.set("ru",2,LocaleChangeSource.SYSTEM_CHANGE) }
        val error = assertFailsWith<CoreError> { locales.set("ja-JP",2,LocaleChangeSource.IN_APP) }
        assertEquals(ErrorReason.LOCALE_UNAVAILABLE,error.localized.reason)
        assertEquals(ErrorArgument.Text("ja-JP"),error.localized.arguments["localeTag"])
        locales.set(null,2,LocaleChangeSource.ANDROID_SETTINGS)
        assertEquals(AppLocaleMode.SYSTEM,locales.state(listOf("ru")).preference.mode)
        assertEquals(before,tracker.data());assertEquals(token,tracker.snapshot().token)
        assertEquals(Backups.decode(archive).data,Backups.decode(Backups(tracker,MemoryRollback()).export()).data)
    }
    @Test fun qualityAvailabilityScriptsAndOverridesStayIndependent() {
        val key = CatalogLabelKey("catalog","activity",1,"es")
        val reviewed = CatalogTranslation(key,"Actividad",quality = TranslationQuality.REVIEWED,availability = PackAvailability.AVAILABLE_OFFLINE,
            sourceLocale = "ru",release = "fixture",sha256 = "a".repeat(64),license = "Apache-2.0",authority = "test fixture")
        val labels = CatalogLabels(listOf(reviewed,reviewed.copy(key = key.copy(localeTag = "zh-Hans"),label = "fixture only")))
        assertEquals("Actividad",labels.label("catalog","activity",1,"Original","es-MX").displayLabel)
        assertTrue(labels.label("catalog","activity",2,"Changed meaning","es").missing)
        assertTrue(labels.label("catalog","activity",1,"Original","zh-Hant").missing)
        assertEquals(listOf("zh-Hans-CN","zh-Hans"),localeFallbacks("zh-Hans-CN"))
        assertEquals("Personal original",labels.label("catalog","activity",1,"Original","es","Personal original").displayLabel)
        for(row in listOf(reviewed.copy(quality = TranslationQuality.DRAFT),reviewed.copy(availability = PackAvailability.NOT_INSTALLED)))
            assertTrue(CatalogLabels(listOf(row)).label("catalog","activity",1,"Original","es").missing)
    }
}
