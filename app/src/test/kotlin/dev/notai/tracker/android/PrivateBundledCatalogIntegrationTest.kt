package dev.notai.tracker.android

import androidx.room.Room
import dev.notai.tracker.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivateBundledCatalogIntegrationTest {
    @Test fun actualElevenAssetsResolveReviewedDisplayAndChoiceSearchLazily() {
        val context = RuntimeEnvironment.getApplication();val ready = ReadyCatalog.read { context.assets.open("catalog/$it") }
        val keys = CatalogContentPacks("tracker.catalog",ready).sourceEntries().map { it.key }
        val index = context.assets.open("catalog/locales/index.json").bufferedReader().use { codec.parseToJsonElement(it.readText()).jsonObject }
        val tags = index.getValue("locales").jsonArray.map { it.jsonPrimitive.content }
        val expected = setOf("en","es","fr","pt-BR","ar","zh-Hans","de","it","ja","ko","hi")
        assertEquals(expected,tags.toSet());val adapter = CatalogContentAdapter(context,ready)
        assertEquals(expected,adapter.health().unverifiedBundledPacks);assertTrue(adapter.health().installedReviewedPacks.isEmpty())
        val data = Dataset(types=ready.vocabulary.types,axes=ready.vocabulary.axes,metrics=ready.vocabulary.metrics,units=ready.vocabulary.units)
        val db = Room.inMemoryDatabaseBuilder(context,TrackerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = RoomStore(db);store.transaction { data to Unit } // Isolated fixture bootstrap, never real user import.
            val tracker = Tracker(store,FakeClock());val before = tracker.data();val beforeToken = tracker.snapshot().token
            val captured = tracker.view();val plain = captured.choices()
            for((i,tag) in tags.withIndex()) {
                val status = adapter.status(tag)
                assertEquals(2386,status.entries);assertTrue(status.complete)
                assertEquals(TranslationQuality.REVIEWED,status.quality);assertEquals(PackAvailability.AVAILABLE_OFFLINE,status.availability)
                assertEquals(CatalogRightsStatus.SOURCE_RIGHTS_REVIEW_PENDING,status.manifest!!.rightsStatus);assertNull(status.manifest!!.license)
                assertEquals(expected.size-i-1,adapter.health().unverifiedBundledPacks.size)
                val payload = context.assets.open("catalog/locales/$tag/labels.json").bufferedReader().use { codec.decodeFromString<CatalogContentPayload>(it.readText()) }
                val shown = adapter.getLocalizedCatalogEntries(keys,tag,captured.data)
                assertEquals(payload.entries.map { it.text },shown.map { it.display });assertTrue(shown.all { !it.missing && !it.userOverride })
                val labels = adapter.choiceLabels(tag,captured.data);assertEquals(1919,labels.translatedTypeCount);assertEquals(2493,labels.aliasCount)
                val view = plain.withCatalogLabels(labels)
                val row = payload.entries.first { it.key.kind==CatalogContentKind.TYPE && it.text.label.length<=256 }
                val query = ChoiceQuery(row.text.label,setOf(ChoiceKind.TYPE),includeArchived=true)
                var cursor: ChoiceCursor? = null;var hit: ChoiceHit? = null
                do { val page = view.search(query,cursor,100);hit = page.items.firstOrNull { it.entry.id=="type:${row.key.stableId}" };cursor=page.next } while(hit==null && cursor!=null)
                assertNotNull(hit);assertEquals(row.text.label,hit!!.entry.title)
                assertEquals(plain.reference(plain.resolve(hit.entry.id)!!),view.reference(hit.entry))
                fun norm(value: String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT).trim().replace(Regex("\\s+")," ")
                val text=payload.entries.filter { it.key.kind==CatalogContentKind.TYPE }.associate { it.key.stableId to it.text }
                val branch=before.types.first { child ->
                    val parent=child.parentId?.let(text::get);val own=text[child.id]
                    parent!=null && own!=null && parent.label.length<=256 &&
                        (listOf(own.label)+own.aliases+listOf(child.name)+child.aliases).none { norm(it).contains(norm(parent.label)) }
                }
                val contextQuery=ChoiceQuery(text.getValue(branch.parentId!!).label,setOf(ChoiceKind.TYPE),includeArchived=true)
                var contextCursor: ChoiceCursor?=null;var contextHit: ChoiceHit?=null
                do { val page=view.search(contextQuery,contextCursor,100);contextHit=page.items.firstOrNull { it.entry.id=="type:${branch.id}" };contextCursor=page.next } while(contextHit==null && contextCursor!=null)
                assertNotNull(contextHit)
                assertTrue(contextHit!!.matches.any { it.field in setOf(MatchField.ANCESTOR_NAME,MatchField.ANCESTOR_ALIAS) && it.sourceTypeId in before.ancestorPath(branch.id).dropLast(1) && it.localeTag==tag })

            }
            assertEquals(expected,adapter.health().installedReviewedPacks);assertTrue(adapter.health().unverifiedBundledPacks.isEmpty());assertTrue(adapter.health().refusedPacks.isEmpty())
            assertEquals(before,tracker.data());assertEquals(beforeToken,tracker.snapshot().token)
            val original = data.types.first();val personal = original.copy(name="Personal literal",aliases=listOf("Own alias"),definition="Own definition")
            val custom = data.copy(types=data.types.map { if(it.id==personal.id) personal else it })
            val shown = adapter.getLocalizedCatalogEntries(listOf(keys.first()),"fr",custom).single()
            assertTrue(shown.userOverride);assertEquals(personal.name,shown.display.label);assertEquals(personal.aliases,shown.display.aliases)
            assertTrue(adapter.getLocalizedCatalogEntries(keys,"zh-Hant",data).all { it.resolvedLocaleTag=="ru" && it.provenance==null })
        } finally { db.close() }
    }
}
