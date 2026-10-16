package dev.notai.tracker

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Explicit private input fixture. The eleven payloads are excluded from public delivery. */
class PrivateCatalogPackIntegrationTest {
    @Test fun allElevenSourceBoundPacksLoadWithoutChangingHistoryOrClaimingReleaseReadiness() {
        val root = File(requireNotNull(System.getProperty("tracker.samples"))).parentFile
        val ready = ReadyCatalog.read { root.resolve("catalog/$it").inputStream() }
        val packs = CatalogContentPacks("tracker.catalog",ready)
        val originals = packs.sourceEntries()
        val expected = setOf("en","es","fr","pt-BR","ar","zh-Hans","de","it","ja","ko","hi")
        val index = codec.parseToJsonElement(root.resolve("catalog/locales/index.json").readText()).jsonObject
        val tags = index.getValue("locales").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected,tags.toSet());assertEquals(11,tags.size)
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock)
        tracker.start(tracker.snapshot().token);clock.advance(1000)
        val before = tracker.data();val token = tracker.snapshot().token
        val archive = Backups.decode(Backups(tracker,MemoryRollback()).export()).data
        for(tag in tags) {
            val status = packs.load(tag) { root.resolve("catalog/locales/$tag/$it").inputStream() }
            assertEquals(2385,status.entries);assertTrue(status.complete)
            assertEquals(TranslationQuality.DRAFT,status.quality)
            assertEquals(PackAvailability.AVAILABLE_OFFLINE,status.availability)
            assertEquals(CatalogRightsStatus.SOURCE_RIGHTS_REVIEW_PENDING,status.manifest!!.rightsStatus)
            assertNull(status.manifest!!.license)
            val payload = codec.decodeFromString<CatalogContentPayload>(root.resolve("catalog/locales/$tag/labels.json").readText())
            assertEquals(originals.map { it.key },payload.entries.map { it.key })
            assertEquals(6793,payload.entries.sumOf { 1+it.text.aliases.size+(if(it.text.description.isNotBlank()) 1 else 0) })
            val displayed = packs.getLocalizedCatalogEntries("tracker.catalog",originals.map { it.key },tag)
            assertEquals(originals.map { it.text },displayed.map { it.display })
            assertTrue(displayed.all { it.missing && it.provenance==status.manifest && it.resolvedLocaleTag=="ru" })
            val key = originals.first().key
            val literal = CatalogPresentation("Personal unchanged",listOf("Personal alias"),"Personal definition")
            val overridden = packs.getLocalizedCatalogEntries("tracker.catalog",listOf(key),tag,mapOf(key to literal)).single()
            assertEquals(literal,overridden.display);assertTrue(overridden.userOverride);assertFalse(overridden.missing)
        }
        assertTrue(packs.installedReviewedLocales().isEmpty())
        assertEquals(originals,packs.sourceEntries());assertEquals(before,tracker.data());assertEquals(token,tracker.snapshot().token)
        assertEquals(archive,Backups.decode(Backups(tracker,MemoryRollback()).export()).data)
        assertTrue(packs.getLocalizedCatalogEntries("tracker.catalog",originals.map { it.key },"zh-Hant").all {
            it.resolvedLocaleTag=="ru" && it.provenance==null && it.quality==TranslationQuality.SOURCE_ONLY
        })
        val prior = packs.status("fr")
        val manifest = prior.manifest!!
        val damaged = root.resolve("catalog/locales/fr/labels.json").readBytes().also { it[it.lastIndex]=(it.last()+1).toByte() }
        assertFailsWith<CoreError> { packs.load("fr") { name ->
            if(name=="manifest.json") codec.encodeToString(manifest).byteInputStream() else damaged.inputStream()
        } }
        assertEquals(prior,packs.status("fr"))
    }
}
