package dev.notai.tracker

import org.junit.Test
import kotlin.test.*

class LocaleContractTest {
    @Test fun englishEditorialCandidateLoadsWithoutClaimingRightsOrReplacingOriginals() =
        editorialCandidateLoads("en","en-US",TranslationQuality.REVIEWED)
    @Test fun spanishEditorialDraftLoadsWithoutReplacingOriginals() =
        editorialCandidateLoads("es","es-MX",TranslationQuality.DRAFT)
    private fun editorialCandidateLoads(locale: String,requested: String,quality: TranslationQuality) {
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile
        val catalog = ReadyCatalog.read { root.resolve("catalog/$it").inputStream() }
        val packs = CatalogContentPacks("tracker.catalog",catalog)
        val originals = packs.sourceEntries()
        val status = packs.load(locale) { root.resolve("catalog/editorial/$locale/$it").inputStream() }
        assertEquals(2385,status.entries);assertTrue(status.complete)
        assertEquals(quality,status.quality)
        assertEquals(CatalogRightsStatus.SOURCE_RIGHTS_REVIEW_PENDING,status.manifest!!.rightsStatus)
        assertNull(status.manifest!!.license);assertEquals(if(quality==TranslationQuality.REVIEWED) setOf(locale) else emptySet(),packs.installedReviewedLocales())
        val shown = packs.getLocalizedCatalogEntries("tracker.catalog",originals.map { it.key },requested)
        if(quality==TranslationQuality.REVIEWED) {
            assertTrue(shown.all { !it.missing && it.resolvedLocaleTag==locale && it.provenance==status.manifest })
            assertTrue(shown.any { it.display!=it.original })
        } else {
            assertEquals(originals.map { it.text },shown.map { it.display })
            assertTrue(shown.all { it.missing && it.resolvedLocaleTag=="ru" && it.provenance==status.manifest })
        }
        val custom = catalog.vocabulary.types.first().copy(name = "Personal original",aliases = listOf("Personal alias"),definition = "Personal definition")
        val data = Dataset(types = listOf(custom))
        val overridden = packs.getLocalizedCatalogEntries("tracker.catalog",listOf(originals.first().key),locale,packs.userOverrides(data)).single()
        assertTrue(overridden.userOverride);assertFalse(overridden.missing)
        assertEquals(CatalogPresentation(custom.name,custom.aliases,custom.definition),overridden.display)
        assertEquals(originals,packs.sourceEntries())
    }
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
    @Test fun sidecarCoverageParentIdentityAndBatchFallbackPreserveOriginals() {
        val vocabulary = VocabularyData(types = listOf(ActivityType("preset","Оригинал",aliases = listOf("Исходный alias","Другой alias"),definition = "Исходное описание")),
            axes = listOf(Axis("first-axis","Первая",valueKind = ValueKind.LOCAL_CHOICE,values = listOf(AxisValue("same","Один"))),
                Axis("second-axis","Вторая",valueKind = ValueKind.LOCAL_CHOICE,values = listOf(AxisValue("same","Другой")))),metrics = emptyList(),units = emptyList())
        val source = codec.encodeToString(vocabulary).toByteArray()
        val manifest = CatalogPackageManifest(1,"fixture","runtime-catalog.json",source.size,sha256(source))
        val catalog = ReadyCatalog.read { name -> if(name == "manifest.json") codec.encodeToString(manifest).byteInputStream() else source.inputStream() }
        val packs = CatalogContentPacks("fixture",catalog);val original = packs.sourceEntries()
        val values = original.filter { it.key.kind == CatalogContentKind.VALUE }
        assertEquals(2,values.size);assertEquals(1,values.map { it.key.stableId }.distinct().size)
        assertEquals(2,values.map { it.key.parentId }.distinct().size)
        val translated = original.mapIndexed { i,row -> row.copy(text = CatalogPresentation("Fixture $i",row.text.aliases.mapIndexed { j,_ -> "Fixture alias $j" },if(row.text.description.isEmpty()) "" else "Fixture description")) }
        fun install(rows: List<CatalogContentRow>,quality: TranslationQuality,complete: Boolean = true,tag: String = "es",rights: CatalogRightsStatus = CatalogRightsStatus.CLEARED,license: String? = "Apache-2.0") {
            val payload = codec.encodeToString(CatalogContentPayload(entries = rows)).toByteArray()
            val m = CatalogContentManifest(catalogId = "fixture",catalogRevision = "fixture",catalogSha256 = manifest.sha256,
                localeTag = tag,sourceLocaleTag = "ru",release = "synthetic fixture only",quality = quality,complete = complete,
                fallbackTags = localeFallbacks(tag).drop(1),authority = "fixture",license = license,translator = "fixture",editor = "fixture",rightsStatus = rights,
                payload = CatalogContentFile("labels.json",payload.size,sha256(payload)))
            packs.load { name -> if(name == "manifest.json") codec.encodeToString(m).byteInputStream() else payload.inputStream() }
        }
        install(translated,TranslationQuality.DRAFT)
        assertTrue(packs.installedReviewedLocales().isEmpty())
        assertTrue(packs.getLocalizedCatalogEntries("fixture",original.map { it.key },"es-MX").all { it.missing && it.quality == TranslationQuality.DRAFT && it.availability == PackAvailability.AVAILABLE_OFFLINE })
        assertFailsWith<CoreError> { install(translated.dropLast(1),TranslationQuality.REVIEWED) }
        install(translated,TranslationQuality.REVIEWED,rights = CatalogRightsStatus.SOURCE_RIGHTS_REVIEW_PENDING,license = null)
        assertEquals(setOf("es"),packs.installedReviewedLocales())
        val rightsPending = packs.getLocalizedCatalogEntries("fixture",values.map { it.key },"es")
        assertTrue(rightsPending.all { !it.missing && it.provenance!!.license == null && it.quality == TranslationQuality.REVIEWED && it.availability == PackAvailability.AVAILABLE_OFFLINE })
        install(translated,TranslationQuality.REVIEWED)
        val completeBeforeRefusal = packs.getLocalizedCatalogEntries("fixture",original.map { it.key },"es")
        assertFailsWith<CoreError> { install(translated.map { row -> if(row.key.kind == CatalogContentKind.TYPE) row.copy(text = row.text.copy(aliases = row.text.aliases.take(1))) else row },TranslationQuality.REVIEWED) }
        assertEquals(completeBeforeRefusal,packs.getLocalizedCatalogEntries("fixture",original.map { it.key },"es"))
        val found = packs.getLocalizedCatalogEntries("fixture",values.map { it.key },"es-MX")
        assertEquals(2,found.map { it.display.label }.distinct().size);assertTrue(found.all { it.resolvedLocaleTag == "es" && !it.missing })
        val own = CatalogPresentation("Personal original",listOf("Personal alias"),"Personal definition")
        val shown = packs.getLocalizedCatalogEntries("fixture",listOf(original.first().key),"es",mapOf(original.first().key to own)).single()
        assertTrue(shown.userOverride);assertEquals(own,shown.display);assertEquals(original.first().text,shown.original)
        assertTrue(packs.getLocalizedCatalogEntries("fixture",values.map { it.key },"zh-Hant").all { it.missing && it.resolvedLocaleTag == "ru" })
        assertEquals(original,packs.sourceEntries())
        assertFailsWith<CoreError> { packs.getLocalizedCatalogEntries("fixture",listOf(values.first().key.copy(parentId = null)),"es") }
        assertEquals(setOf("es"),packs.installedReviewedLocales())
    }
    @Test fun qualityAvailabilityScriptsAndOverridesStayIndependent() {
        val key = CatalogLabelKey("catalog","activity",1,"es")
        val reviewed = CatalogTranslation(key,"Actividad",quality = TranslationQuality.REVIEWED,availability = PackAvailability.AVAILABLE_OFFLINE,
            sourceLocale = "ru",release = "fixture",sha256 = "a".repeat(64),license = "Apache-2.0",authority = "test fixture")
        val aliases = mutableListOf("fixture alias")
        val labels = CatalogLabels(listOf(reviewed.copy(aliases = aliases),reviewed.copy(key = key.copy(localeTag = "zh-Hans"),label = "fixture only")))
        aliases[0] = "changed outside adapter"
        assertEquals(listOf("fixture alias"),labels.label("catalog","activity",1,"Original","es").translation!!.aliases)
        assertEquals("Actividad",labels.label("catalog","activity",1,"Original","es-MX").displayLabel)
        assertTrue(labels.label("catalog","activity",2,"Changed meaning","es").missing)
        assertTrue(labels.label("catalog","activity",1,"Original","zh-Hant").missing)
        assertEquals(listOf("zh-Hans-CN","zh-Hans"),localeFallbacks("zh-Hans-CN"))
        assertEquals("Personal original",labels.label("catalog","activity",1,"Original","es","Personal original").displayLabel)
        for(row in listOf(reviewed.copy(quality = TranslationQuality.DRAFT),reviewed.copy(availability = PackAvailability.NOT_INSTALLED)))
            assertTrue(CatalogLabels(listOf(row)).label("catalog","activity",1,"Original","es").missing)
    }
}
