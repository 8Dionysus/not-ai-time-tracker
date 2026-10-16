package dev.notai.tracker

import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Private accepted semantic-review input; native QA and rights are not forged. */
class PrivateCatalogPackIntegrationTest {
    private fun normalized(s: String) = Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim().replace(Regex("\\s+")," ")
    private fun find(view: ChoiceView,text: String,id: String): ChoiceHit {
        val query = ChoiceQuery(text,setOf(ChoiceKind.TYPE),includeArchived=true)
        var cursor: ChoiceCursor? = null
        do {
            val page = view.search(query,cursor,100)
            page.items.firstOrNull { it.entry.id==id }?.let { return it }
            cursor = page.next
        } while(cursor!=null)
        error("Missing private localized match for stable ID $id")
    }
    @Test fun allElevenReviewedPacksDriveExistingChoiceSearchWithoutChangingFactsOrOriginals() {
        val root = File(requireNotNull(System.getProperty("tracker.samples"))).parentFile
        val ready = ReadyCatalog.read { root.resolve("catalog/$it").inputStream() }
        val packs = CatalogContentPacks("tracker.catalog",ready);val originals = packs.sourceEntries()
        val expected = setOf("en","es","fr","pt-BR","ar","zh-Hans","de","it","ja","ko","hi")
        val tags = codec.parseToJsonElement(root.resolve("catalog/locales/index.json").readText()).jsonObject.getValue("locales").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected,tags.toSet());assertEquals(11,tags.size)
        val clock = FakeClock();val initial = Dataset(types=ready.vocabulary.types,axes=ready.vocabulary.axes,metrics=ready.vocabulary.metrics,units=ready.vocabulary.units)
        val tracker = Tracker(MemoryStore(initial),clock)
        val before = tracker.data();val token = tracker.snapshot().token
        val archive = Backups.decode(Backups(tracker,MemoryRollback()).export()).data
        val csv = Csv.export(tracker.view().report(0,clock.wall+1));val plain = tracker.view().choices()
        val originalTerms = before.types.flatMap { listOf(it.name)+it.aliases }.map(::normalized).toSet()
        for(tag in tags) {
            val status = packs.load(tag) { root.resolve("catalog/locales/$tag/$it").inputStream() }
            assertEquals(2385,status.entries);assertTrue(status.complete)
            assertEquals(TranslationQuality.REVIEWED,status.quality);assertEquals(PackAvailability.AVAILABLE_OFFLINE,status.availability)
            assertEquals(CatalogRightsStatus.SOURCE_RIGHTS_REVIEW_PENDING,status.manifest!!.rightsStatus);assertNull(status.manifest!!.license)
            val payload = codec.decodeFromString<CatalogContentPayload>(root.resolve("catalog/locales/$tag/labels.json").readText())
            assertEquals(originals.map { it.key },payload.entries.map { it.key })
            assertEquals(6793,payload.entries.sumOf { 1+it.text.aliases.size+(if(it.text.description.isNotBlank()) 1 else 0) })
            val displayed = packs.getLocalizedCatalogEntries("tracker.catalog",originals.map { it.key },tag)
            assertEquals(payload.entries.map { it.text },displayed.map { it.display })
            assertTrue(displayed.all { !it.missing && it.resolvedLocaleTag==tag })
            val labels = packs.choiceLabels(before,tag)
            assertEquals(1918,labels.translatedTypeCount);assertEquals(2490,labels.aliasCount)
            val types = payload.entries.filter { it.key.kind==CatalogContentKind.TYPE }
            assertEquals(types.associate { it.key.stableId to listOf(it.text.label)+it.text.aliases },labels.typeFields.mapValues { (_,fields)->fields.map { it.original } })
            val view = plain.withCatalogLabels(labels)
            val byLabel = types.first { it.text.label.length<=256 }
            assertEquals(byLabel.text.label,find(view,byLabel.text.label,"type:${byLabel.key.stableId}").entry.title)
            val (aliasId,alias) = types.flatMap { row -> row.text.aliases.map { row.key.stableId to it } }
                .filter { (_,text)->text.length<=256 && normalized(text) !in originalTerms }.maxBy { it.second.length }
            val hit = find(view,alias,"type:$aliasId")
            assertTrue(hit.matches.any { it.localeTag==tag })
            assertEquals(plain.reference(plain.resolve(hit.entry.id)!!),view.reference(hit.entry))
            val typeText=types.associate { it.key.stableId to it.text }
            val branch=before.types.first { child ->
                val parent=child.parentId?.let(typeText::get)
                val own=typeText[child.id]
                parent!=null && own!=null && parent.label.length+own.label.length+1<=256 &&
                    (listOf(own.label)+own.aliases+listOf(child.name)+child.aliases).none { normalized(it).contains(normalized(parent.label)) }
            }
            val parentText=typeText.getValue(branch.parentId!!).label
            val branchHit=find(view,parentText,"type:${branch.id}")
            assertTrue(branchHit.matches.any { it.field in setOf(MatchField.ANCESTOR_NAME,MatchField.ANCESTOR_ALIAS) && it.sourceTypeId in before.ancestorPath(branch.id).dropLast(1) && it.localeTag==tag })
            assertEquals(plain.reference(plain.resolve(branchHit.entry.id)!!),view.reference(branchHit.entry))
            find(view,typeText.getValue(branch.id).label+" "+parentText,"type:${branch.id}")
            val literal = CatalogPresentation("Personal unchanged",listOf("Personal alias"),"Personal definition")
            assertEquals(literal,packs.getLocalizedCatalogEntries("tracker.catalog",listOf(originals.first().key),tag,mapOf(originals.first().key to literal)).single().display)
        }
        assertEquals(expected,packs.installedReviewedLocales());assertEquals(originals,packs.sourceEntries())
        assertEquals(before,tracker.data());assertEquals(token,tracker.snapshot().token)
        assertEquals(archive,Backups.decode(Backups(tracker,MemoryRollback()).export()).data)
        assertEquals(csv,Csv.export(tracker.view().report(0,clock.wall+1)))
        assertTrue(packs.getLocalizedCatalogEntries("tracker.catalog",originals.map { it.key },"zh-Hant").all { it.resolvedLocaleTag=="ru" && it.provenance==null })
        val prior = packs.status("fr");val damaged = root.resolve("catalog/locales/fr/labels.json").readBytes().also { it[it.lastIndex]=(it.last()+1).toByte() }
        assertFailsWith<CoreError> { packs.load("fr") { name -> if(name=="manifest.json") codec.encodeToString(prior.manifest!!).byteInputStream() else damaged.inputStream() } }
        assertEquals(prior,packs.status("fr"))
    }
}
