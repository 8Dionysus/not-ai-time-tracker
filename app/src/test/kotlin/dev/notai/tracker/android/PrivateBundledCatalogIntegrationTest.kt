package dev.notai.tracker.android

import dev.notai.tracker.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android asset loading under Robolectric only; no UI/resource/linguistic acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivateBundledCatalogIntegrationTest {
    @Test fun actualAssetIndexLoadsAllElevenLazilyWithoutAdvertisingOrEditingPersonalText() {
        val context = RuntimeEnvironment.getApplication()
        val ready = ReadyCatalog.read { context.assets.open("catalog/$it") }
        val keys = CatalogContentPacks("tracker.catalog",ready).sourceEntries().map { it.key }
        val index = context.assets.open("catalog/locales/index.json").bufferedReader().use { codec.parseToJsonElement(it.readText()).jsonObject }
        val expected = setOf("en","es","fr","pt-BR","ar","zh-Hans","de","it","ja","ko","hi")
        assertEquals(expected,index.getValue("locales").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val adapter = CatalogContentAdapter(context,ready)
        assertEquals(expected,adapter.health().unverifiedBundledPacks)
        assertTrue(adapter.health().installedReviewedPacks.isEmpty());assertTrue(adapter.health().refusedPacks.isEmpty())
        val data = Dataset(types = ready.vocabulary.types,axes = ready.vocabulary.axes,metrics = ready.vocabulary.metrics,units = ready.vocabulary.units)
        val before = codec.encodeToString(data)
        for((i,tag) in expected.withIndex()) {
            val status = adapter.status(tag)
            assertEquals(2385,status.entries);assertTrue(status.complete)
            assertEquals(TranslationQuality.DRAFT,status.quality);assertEquals(PackAvailability.AVAILABLE_OFFLINE,status.availability)
            assertEquals(CatalogRightsStatus.SOURCE_RIGHTS_REVIEW_PENDING,status.manifest!!.rightsStatus)
            assertNull(status.manifest!!.license)
            assertEquals(expected.size-i-1,adapter.health().unverifiedBundledPacks.size)
            val shown = adapter.getLocalizedCatalogEntries(keys,tag,data)
            assertEquals(2385,shown.size);assertTrue(shown.all { it.original==it.display && it.missing && !it.userOverride && it.provenance==status.manifest })
        }
        assertTrue(adapter.health().unverifiedBundledPacks.isEmpty());assertTrue(adapter.health().refusedPacks.isEmpty())
        assertTrue(adapter.health().installedReviewedPacks.isEmpty());assertEquals(before,codec.encodeToString(data))
        val original = data.types.first()
        val personal = original.copy(name = "Personal literal",aliases = listOf("Own alias"),definition = "Own definition")
        val custom = data.copy(types = data.types.map { if(it.id==personal.id) personal else it })
        val shown = adapter.getLocalizedCatalogEntries(listOf(keys.first()),"fr",custom).single()
        assertTrue(shown.userOverride);assertEquals(personal.name,shown.display.label)
        assertEquals(personal.aliases,shown.display.aliases);assertEquals(personal.definition,shown.display.description)
        assertTrue(adapter.getLocalizedCatalogEntries(keys,"zh-Hant",data).all { it.resolvedLocaleTag=="ru" && it.provenance==null })
    }
}
