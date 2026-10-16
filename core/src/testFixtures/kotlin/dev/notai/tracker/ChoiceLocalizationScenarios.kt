package dev.notai.tracker

import org.junit.Test
import kotlin.test.*

abstract class ChoiceLocalizationScenarioContract {
    protected abstract fun createChoiceStore(): StateStore
    private val one = ActivityType("locale-one","Исходное первое",aliases = listOf("исходный синоним"))
    private val two = ActivityType("locale-two","Исходное второе",parentId = one.id,aliases = listOf("другой исходный"))
    private fun packs(): CatalogContentPacks {
        val vocabulary = VocabularyData(types = listOf(one,two));val source = codec.encodeToString(vocabulary).toByteArray()
        val manifest = CatalogPackageManifest(1,"locale-fixture","runtime-catalog.json",source.size,sha256(source))
        val ready = ReadyCatalog.read { name -> if(name=="manifest.json") codec.encodeToString(manifest).byteInputStream() else source.inputStream() }
        val packs = CatalogContentPacks("tracker.catalog",ready)
        for(locale in listOf("en","es")) {
            val rows = packs.sourceEntries().mapIndexed { i,row -> row.copy(text = CatalogPresentation("$locale Reviewed $i",listOf("$locale translated-only-$i"))) }
            val raw = codec.encodeToString(CatalogContentPayload(entries = rows)).toByteArray()
            val content = CatalogContentManifest(catalogId="tracker.catalog",catalogRevision="locale-fixture",catalogSha256=manifest.sha256,
                localeTag=locale,sourceLocaleTag="ru",release="synthetic reviewed fixture",quality=TranslationQuality.REVIEWED,complete=true,
                fallbackTags=emptyList(),authority="fixture",license=null,translator="fixture",editor="independent fixture review",
                rightsStatus=CatalogRightsStatus.SOURCE_RIGHTS_REVIEW_PENDING,payload=CatalogContentFile("labels.json",raw.size,sha256(raw)))
            packs.load(locale) { name -> if(name=="manifest.json") codec.encodeToString(content).byteInputStream() else raw.inputStream() }
        }
        return packs
    }
    @Test fun existingSearchUsesTranslatedAliasesWithoutChangingLiteralHistoryOrStableCommandReferences() {
        val packs = packs();val clock = FakeClock();val t = Tracker(createChoiceStore(),clock)
        t.saveType(t.snapshot().token,one);t.saveType(t.snapshot().token,two)
        val card = Card("locale-card","Personal literal",activityTypeId=two.id)
        t.saveCard(t.snapshot().token,card)
        t.saveFavorite(t.snapshot().token,Favorite("locale-favorite","Favorite literal",card.classification()))
        t.manual(t.snapshot().token,clock.wall-2000,clock.wall-1000,card.classification(),"Literal note")
        val before = t.data();val token = t.snapshot().token;val archive = Backups.decode(Backups(t,MemoryRollback()).export()).data
        val plain = t.view().choices();val labels = packs.choiceLabels(before,"en-US")
        assertEquals(2,labels.translatedTypeCount);assertEquals(2,labels.aliasCount)
        val localized = plain.withCatalogLabels(labels)
        val query = ChoiceQuery("en translated-only-1",setOf(ChoiceKind.TYPE,ChoiceKind.CARD,ChoiceKind.FAVORITE,ChoiceKind.HISTORY))
        val hits = localized.search(query,pageSize=100)
        assertEquals(setOf(ChoiceKind.TYPE,ChoiceKind.CARD,ChoiceKind.FAVORITE,ChoiceKind.HISTORY),hits.items.map { it.entry.kind }.toSet())
        assertTrue(hits.items.all { hit -> hit.matches.any { it.localeTag=="en" } })
        assertEquals("en Reviewed 1",hits.items.single { it.entry.kind==ChoiceKind.TYPE }.entry.title)
        assertEquals("Personal literal",hits.items.single { it.entry.kind==ChoiceKind.CARD }.entry.title)
        assertEquals("Favorite literal",hits.items.single { it.entry.kind==ChoiceKind.FAVORITE }.entry.title)
        assertEquals("Personal literal",hits.items.single { it.entry.kind==ChoiceKind.HISTORY }.entry.title)
        assertTrue(localized.search(ChoiceQuery("другой исходный")).items.any { it.entry.id=="type:${two.id}" })
        for(hit in hits.items) assertEquals(plain.reference(plain.resolve(hit.entry.id)!!),localized.reference(hit.entry))
        val page = localized.search(ChoiceQuery(),pageSize=1);assertNotNull(page.next)
        val spanish = plain.withCatalogLabels(packs.choiceLabels(before,"es"))
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { spanish.search(ChoiceQuery(),page.next,1) }.code)
        assertEquals(before,t.data());assertEquals(token,t.snapshot().token)
        assertEquals(archive,Backups.decode(Backups(t,MemoryRollback()).export()).data)
        val choice = hits.items.single { it.entry.kind==ChoiceKind.TYPE }.entry
        t.startChoice(t.snapshot().timerTarget!!,localized.reference(choice))
        assertEquals(two.name,t.snapshot().session!!.selection.name)
        assertEquals(two.id,t.snapshot().session!!.selection.activityTypeId)
    }
    @Test fun personalOverridesAndExactSnapshotBindingPreventCrossLanguageStalePresentation() {
        val packs = packs();val t = Tracker(createChoiceStore(),FakeClock())
        t.saveType(t.snapshot().token,one);t.saveType(t.snapshot().token,two.copy(name="User original",aliases=listOf("Own synonym")))
        val data = t.data();val plain = t.view().choices()
        val english = plain.withCatalogLabels(packs.choiceLabels(data,"en"))
        val entry = english.resolve("type:${two.id}")!!
        assertEquals("User original",entry.title)
        assertTrue(english.search(ChoiceQuery("Own synonym")).items.any { it.entry.id==entry.id })
        assertFalse(english.search(ChoiceQuery("en translated-only-1")).items.any { it.entry.id==entry.id })
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { plain.withCatalogLabels(packs.choiceLabels(data.copy(controlBarrier="other-barrier"),"en")) }.code)
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { plain.withCatalogLabels(packs.choiceLabels(data.copy(types=data.types.map { if(it.id==two.id) it.copy(name="Other presentation") else it }),"en")) }.code)
        t.saveCard(t.snapshot().token,Card("later","Later"))
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { t.view().choices().withCatalogLabels(packs.choiceLabels(data,"en")) }.code)
    }
}
