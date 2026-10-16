package dev.notai.tracker

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** VALUE identity is qualified by its axis AND both meaning versions. Raw value ID is never a key. */
@Serializable enum class CatalogContentKind { TYPE, AXIS, VALUE, METRIC, UNIT }
@Serializable data class CatalogContentKey(val kind: CatalogContentKind,val stableId: String,val meaningVersion: Int,
    val parentId: String? = null,val parentMeaningVersion: Int? = null)
@Serializable data class CatalogPresentation(val label: String,val aliases: List<String> = emptyList(),val description: String = "")
@Serializable data class CatalogContentRow(val key: CatalogContentKey,val text: CatalogPresentation)
@Serializable data class CatalogContentPayload(val schemaVersion: Int = 1,val entries: List<CatalogContentRow>)
@Serializable data class CatalogContentFile(val file: String,val bytes: Int,val sha256: String)
@Serializable enum class CatalogRightsStatus {
    @SerialName("source_rights_review_pending") SOURCE_RIGHTS_REVIEW_PENDING,
    @SerialName("cleared") CLEARED
}
@Serializable data class CatalogContentManifest(val schemaVersion: Int = 1,val catalogId: String,val catalogRevision: String,
    val catalogSha256: String,val localeTag: String,val sourceLocaleTag: String,val release: String,
    val quality: TranslationQuality,val complete: Boolean,val fallbackTags: List<String>,
    val authority: String,val license: String?,val translator: String,val editor: String?,val payload: CatalogContentFile,
    val rightsStatus: CatalogRightsStatus = CatalogRightsStatus.SOURCE_RIGHTS_REVIEW_PENDING)
data class CatalogPackStatus(val localeTag: String,val quality: TranslationQuality,val availability: PackAvailability,
    val complete: Boolean,val entries: Int,val manifest: CatalogContentManifest?)
data class CatalogDisplayEntry(val catalogId: String,val key: CatalogContentKey,val requestedLocaleTag: String,
    val resolvedLocaleTag: String?,val sourceLocaleTag: String?,val original: CatalogPresentation,val display: CatalogPresentation,
    val missing: Boolean,val userOverride: Boolean,val quality: TranslationQuality,val availability: PackAvailability,
    val provenance: CatalogContentManifest?)

/** Immutable sidecar. Reads bytes, validates identity/hash/coverage; never writes Dataset or executes code.
 * A hash proves integrity against this manifest, not authorship/review. Trusting editorial REVIEWED
 * remains the installer/release owner's decision. Draft packs can be inspected but never selected. */
class CatalogContentPacks(private val catalogId: String,private val catalog: ReadyCatalog,sourceLocale: String = "ru") {
    private val sourceLocale = localeTag(sourceLocale)
    private val originals: Map<CatalogContentKey,CatalogPresentation> = buildMap {
        catalog.vocabulary.types.forEach { put(CatalogContentKey(CatalogContentKind.TYPE,it.id,it.meaningVersion),CatalogPresentation(it.name,it.aliases,it.definition)) }
        catalog.vocabulary.axes.forEach { a ->
            put(CatalogContentKey(CatalogContentKind.AXIS,a.id,a.meaningVersion),CatalogPresentation(a.name))
            a.values.forEach { put(CatalogContentKey(CatalogContentKind.VALUE,it.id,it.meaningVersion,a.id,a.meaningVersion),CatalogPresentation(it.label)) }
        }
        catalog.vocabulary.metrics.forEach { put(CatalogContentKey(CatalogContentKind.METRIC,it.id,it.meaningVersion),CatalogPresentation(it.name)) }
        catalog.vocabulary.units.forEach { put(CatalogContentKey(CatalogContentKind.UNIT,it.id,1),CatalogPresentation(it.name)) }
    }.mapValues { (_,v) -> v.copy(aliases = frozenList(v.aliases)) }
    private data class Loaded(val manifest: CatalogContentManifest,val entries: Map<CatalogContentKey,CatalogPresentation>)
    @Volatile private var packs: Map<String,Loaded> = emptyMap()
    fun sourceEntries(): List<CatalogContentRow> = frozenList(originals.map { CatalogContentRow(it.key,it.value) })
    @Synchronized fun load(expectedLocaleTag: String? = null,open: (String) -> InputStream): CatalogPackStatus {
        val m = decode<CatalogContentManifest>(read(open("manifest.json"),64*1024))
        ensure((expectedLocaleTag == null || m.localeTag == expectedLocaleTag) && m.schemaVersion == 1 && m.catalogId == catalogId && m.catalogRevision == catalog.manifest.catalogVersion &&
            m.catalogSha256 == catalog.manifest.sha256 && m.sourceLocaleTag == sourceLocale && m.localeTag == localeTag(m.localeTag) &&
            m.fallbackTags == localeFallbacks(m.localeTag).drop(1) && m.release.isNotBlank() && m.authority.isNotBlank() &&
            (m.license == null || m.license.isNotBlank()) && (m.rightsStatus != CatalogRightsStatus.CLEARED || m.license != null) && m.translator.isNotBlank() && (m.quality != TranslationQuality.REVIEWED || !m.editor.isNullOrBlank()),
            "Incompatible catalog translation manifest",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(m.payload.file == "labels.json" && m.payload.bytes in 1..16*1024*1024 && m.payload.sha256.matches(Regex("[0-9a-f]{64}")),
            "Unsupported catalog translation payload",ErrorCode.UNSUPPORTED_IMPORT)
        val bytes = read(open(m.payload.file),m.payload.bytes)
        ensure(bytes.size == m.payload.bytes && sha256(bytes) == m.payload.sha256,"Catalog translation integrity mismatch",ErrorCode.UNSUPPORTED_IMPORT)
        val payload = decode<CatalogContentPayload>(bytes)
        ensure(payload.schemaVersion == 1 && payload.entries.map { it.key }.distinct().size == payload.entries.size,
            "Duplicate or unsupported catalog translation identity",ErrorCode.UNSUPPORTED_IMPORT)
        ensure(payload.entries.all { row -> originals[row.key]?.let { original -> row.text.label.isNotBlank() && row.text.aliases.all(String::isNotBlank) && (!m.complete || row.text.aliases.size >= original.aliases.size && (original.description.isBlank() || row.text.description.isNotBlank())) } == true },
            "Unknown or invalid catalog translation entry",ErrorCode.UNSUPPORTED_IMPORT)
        val all = payload.entries.map { it.key }.toSet() == originals.keys
        ensure(!m.complete || all,"Catalog translation coverage is incomplete",ErrorCode.UNSUPPORTED_IMPORT)
        val frozenManifest = m.copy(fallbackTags = frozenList(m.fallbackTags))
        val loaded = Loaded(frozenManifest,payload.entries.associate { it.key to it.text.copy(aliases = frozenList(it.text.aliases)) })
        packs = packs + (m.localeTag to loaded)
        return status(m.localeTag)
    }
    fun status(tag: String): CatalogPackStatus {
        val canonical = localeTag(tag);val p = packs[canonical]
        return if(p == null) CatalogPackStatus(canonical,TranslationQuality.SOURCE_ONLY,PackAvailability.NOT_INSTALLED,false,0,null)
        else CatalogPackStatus(canonical,p.manifest.quality,PackAvailability.AVAILABLE_OFFLINE,p.manifest.complete,p.entries.size,p.manifest)
    }
    // Editorial readiness is distinct from the preserved rights metadata. The
    // accepted current contract removes a general NOTICE-pending runtime veto;
    // this never marks rights cleared or authorizes publication of the payload.
    private fun ready(m: CatalogContentManifest) = m.complete && m.quality == TranslationQuality.REVIEWED
    fun installedReviewedLocales(): Set<String> = packs.filterValues { ready(it.manifest) }.keys.toSet()
    /** One captured Dataset and display locale for the existing ChoiceView index.
     * Foreign/custom identities remain literal; no translated strings enter Dataset. */
    fun choiceLabels(data: Dataset,displayLocaleTag: String): ChoiceCatalogLabels {
        val source = data.frozen()
        val keys = source.types.mapNotNull { type ->
            CatalogContentKey(CatalogContentKind.TYPE,type.id,type.meaningVersion).takeIf { it in originals }
        }
        return ChoiceCatalogLabels(token(source),source.controlBarrier,payloadHash(source.types),
            localeTag(displayLocaleTag),getLocalizedCatalogEntries(catalogId,keys,displayLocaleTag,userOverrides(source)))
    }
    /** Stable order/cardinality, one requested locale and one immutable installed-pack snapshot.
     * Overrides are literal presentation, including aliases/description; user names never enter a translator. */
    fun getLocalizedCatalogEntries(catalogId: String,stableIds: List<CatalogContentKey>,displayLocaleTag: String,
        overrides: Map<CatalogContentKey,CatalogPresentation> = emptyMap()): List<CatalogDisplayEntry> {
        ensure(catalogId == this.catalogId,"Unknown catalog",ErrorCode.INVALID_INPUT)
        val requested = localeTag(displayLocaleTag);val snapshot = packs
        val chain = localeFallbacks(requested)
        val pack = chain.firstNotNullOfOrNull { snapshot[it]?.takeIf { p -> ready(p.manifest) } }
        return frozenList(stableIds.map { key ->
            val original = originals[key] ?: fail(ErrorCode.INVALID_INPUT,"Unknown parent-qualified catalog identity")
            val override = overrides[key]?.also { ensure(it.label.isNotBlank(),"Blank user catalog override") }
            val found = if(override == null) pack?.entries?.get(key) else null
            val selected = if(found == null) null else pack
            val unavailable = chain.firstNotNullOfOrNull { snapshot[it] }
            CatalogDisplayEntry(catalogId,key,requested,if(override != null) null else selected?.manifest?.localeTag ?: sourceLocale,
                sourceLocale,original,(override ?: found ?: original).let { it.copy(aliases = frozenList(it.aliases)) },
                override == null && found == null && sourceLocale !in chain,override != null,
                selected?.manifest?.quality ?: unavailable?.manifest?.quality ?: TranslationQuality.SOURCE_ONLY,
                if(selected != null || sourceLocale in chain) PackAvailability.AVAILABLE_OFFLINE else if(unavailable != null) PackAvailability.AVAILABLE_OFFLINE else PackAvailability.NOT_INSTALLED,
                selected?.manifest ?: unavailable?.manifest)
        })
    }
    /** Infer literal user edits only against this exact source revision. Personal Card/Favorite/free
     * values are outside this preset API and must be displayed verbatim by callers. */
    fun userOverrides(data: Dataset): Map<CatalogContentKey,CatalogPresentation> = buildMap {
        fun add(key: CatalogContentKey,text: CatalogPresentation) { if(originals[key]?.let { it != text } == true) put(key,text) }
        data.types.forEach { add(CatalogContentKey(CatalogContentKind.TYPE,it.id,it.meaningVersion),CatalogPresentation(it.name,it.aliases,it.definition)) }
        data.axes.forEach { a -> add(CatalogContentKey(CatalogContentKind.AXIS,a.id,a.meaningVersion),CatalogPresentation(a.name))
            a.values.forEach { add(CatalogContentKey(CatalogContentKind.VALUE,it.id,it.meaningVersion,a.id,a.meaningVersion),CatalogPresentation(it.label)) } }
        data.metrics.forEach { add(CatalogContentKey(CatalogContentKind.METRIC,it.id,it.meaningVersion),CatalogPresentation(it.name)) }
        data.units.forEach { add(CatalogContentKey(CatalogContentKind.UNIT,it.id,1),CatalogPresentation(it.name)) }
    }
    companion object {
        private fun read(stream: InputStream,limit: Int): ByteArray = stream.use {
            val out = java.io.ByteArrayOutputStream();val buffer = ByteArray(8192)
            while(true) { val n = it.read(buffer,0,minOf(buffer.size,limit+1-out.size()));if(n < 0) break
                if(n == 0) { val b = it.read();if(b < 0) break;out.write(b) } else out.write(buffer,0,n)
                ensure(out.size() <= limit,"Catalog sidecar too large",ErrorCode.UNSUPPORTED_IMPORT) }
            out.toByteArray()
        }
        private inline fun <reified T> decode(bytes: ByteArray): T = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            codec.decodeFromString<T>(text)
        } catch(_: Exception) { fail(ErrorCode.UNSUPPORTED_IMPORT,"Malformed catalog sidecar") }
    }
}
