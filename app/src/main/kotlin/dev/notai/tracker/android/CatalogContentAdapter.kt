package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import kotlinx.serialization.Serializable

@Serializable private data class CatalogPackIndex(val schemaVersion: Int = 1,val locales: List<String> = emptyList())
data class CatalogContentHealth(val installedReviewedPacks: Set<String>,val refusedPacks: Set<String>,val unverifiedBundledPacks: Set<String>)
/** Bundled offline data only; no network/download/background work and no Dataset mutation.
 * A bad locale refuses independently; it cannot suppress other valid installed packs. */
class CatalogContentAdapter(context: Context,catalog: ReadyCatalog) {
    private val assets = context.applicationContext.assets
    private val packs = CatalogContentPacks("tracker.catalog",catalog)
    private val refused = mutableSetOf<String>()
    private val attempted = mutableSetOf<String>()
    private var bundled = emptySet<String>()
    init {
        val folders = assets.list("catalog/locales").orEmpty().toSet()
        if("index.json" in folders) {
            val index = try {
                val bytes = assets.open("catalog/locales/index.json").use { input ->
                    val output = java.io.ByteArrayOutputStream();val chunk = ByteArray(8192)
                    while(output.size() <= 64*1024) { val n = input.read(chunk,0,minOf(chunk.size,64*1024+1-output.size()));if(n < 0) break
                        if(n == 0) { val b = input.read();if(b < 0) break;output.write(b) } else output.write(chunk,0,n) }
                    output.toByteArray()
                }
                ensure(bytes.size <= 64*1024,"Catalog locale index too large",ErrorCode.STORAGE)
                codec.decodeFromString<CatalogPackIndex>(bytes.toString(Charsets.UTF_8)).also {
                    ensure(it.schemaVersion == 1 && it.locales.size <= 128 && it.locales.distinct().size == it.locales.size &&
                        it.locales.all { tag -> tag == localeTag(tag) && tag.matches(Regex("[A-Za-z0-9-]+")) },"Invalid catalog locale index",ErrorCode.STORAGE)
                }
            } catch(_: Exception) { refused += "index.json";null }
            bundled = index?.locales?.toSet().orEmpty()
        }
    }
    @Synchronized private fun ensureLocale(tag: String) {
        if(tag !in bundled || !attempted.add(tag)) return
        try { packs.load(tag) { assets.open("catalog/locales/$tag/$it") } }
        catch(_: Exception) { refused += tag }
    }
    /** Headers/index do not prove payload integrity or review. Never eagerly read eleven payloads. */
    @Synchronized fun health() = CatalogContentHealth(packs.installedReviewedLocales(),refused.toSet(),bundled-attempted)
    fun status(locale: String): CatalogPackStatus { val tag = localeTag(locale);ensureLocale(tag);return packs.status(tag) }
    fun getLocalizedCatalogEntries(keys: List<CatalogContentKey>,displayLocaleTag: String,data: Dataset): List<CatalogDisplayEntry> {
        for(tag in localeFallbacks(displayLocaleTag)) { ensureLocale(tag);if(tag in packs.installedReviewedLocales()) break }
        return packs.getLocalizedCatalogEntries("tracker.catalog",keys,displayLocaleTag,packs.userOverrides(data))
    }
}
