package dev.notai.tracker

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Versioned prepared vocabulary. No research pipeline, source corpus or occupation policy is loaded. */
@Serializable data class CatalogPackageManifest(val formatVersion: Int,val catalogVersion: String,
    val file: String,val bytes: Int,val sha256: String,val compatibility: CatalogCompatibilityFile? = null)
@Serializable data class CatalogCompatibilityFile(val file: String,val bytes: Int,val sha256: String)
/** One explicitly reviewed editorial step. [parentMove] allows the new parent to replace the installed one (the type moves
 * in the tree; recorded history keeps its path at write). [alsoFromMeaningSha256] and [previousDefaultNameSha256s] accept
 * older installed catalogs on the same step; a locally renamed entry keeps its personal name. [archive] retires a catalog
 * type that the catalog now ships archived: it leaves the picker only where nothing in the dataset points at it and its
 * presentation is not personalized, so referenced and personally edited entries remain available. */
@Serializable data class CatalogEditorialRevision(val kind: String,val id: String,val fromMeaningSha256: String,
    val toMeaningSha256: String,val previousDefaultNameSha256: String? = null,val parentMove: Boolean = false,
    val alsoFromMeaningSha256: List<String> = emptyList(),val previousDefaultNameSha256s: List<String> = emptyList(),
    val archive: Boolean = false)
class ReadyCatalog private constructor(val manifest: CatalogPackageManifest,val vocabulary: VocabularyData,
    internal val revisions: List<CatalogEditorialRevision>) {
    companion object {
        fun read(open: (String)->InputStream): ReadyCatalog {
            val metadata = open("manifest.json").use { it.boundedBytes(16*1024) }
            ensure(metadata.size <= 16*1024,"Catalog manifest too large",ErrorCode.UNSUPPORTED_IMPORT)
            val m = decodeCatalogJson<CatalogPackageManifest>(metadata,"Malformed catalog manifest")
            ensure(m.formatVersion == 1 && m.catalogVersion.isNotBlank() && m.file == "runtime-catalog.json" &&
                m.bytes in 1..16*1024*1024 && m.sha256.matches(Regex("[0-9a-f]{64}")),"Unsupported catalog package",ErrorCode.UNSUPPORTED_IMPORT)
            val bytes = open(m.file).use { it.boundedBytes(m.bytes) }
            ensure(bytes.size == m.bytes && sha256(bytes) == m.sha256,"Catalog package hash/size mismatch",ErrorCode.UNSUPPORTED_IMPORT)
            val v = decodeCatalogJson<VocabularyData>(bytes,"Malformed catalog definitions")
            val d = Dataset(types = v.types,axes = v.axes,metrics = v.metrics,units = v.units)
            validate(d)
            val frozen = d.frozen()
            val revisions = m.compatibility?.let { c ->
                ensure(c.file == "compatibility.json" && c.bytes in 1..2*1024*1024 && c.sha256.matches(Regex("[0-9a-f]{64}")),"Unsupported catalog compatibility",ErrorCode.UNSUPPORTED_IMPORT)
                val data = open(c.file).use { it.boundedBytes(c.bytes) }
                ensure(data.size == c.bytes && sha256(data) == c.sha256,"Compatibility hash/size mismatch",ErrorCode.UNSUPPORTED_IMPORT)
                decodeCatalogJson<List<CatalogEditorialRevision>>(data,"Malformed catalog compatibility")
            }.orEmpty()
            ensure(revisions.map { it.kind to it.id }.distinct().size == revisions.size,"Duplicate editorial revision",ErrorCode.UNSUPPORTED_IMPORT)
            for(r in revisions) {
                ensure((listOfNotNull(r.fromMeaningSha256,r.toMeaningSha256,r.previousDefaultNameSha256)+r.alsoFromMeaningSha256+r.previousDefaultNameSha256s)
                    .all { it.matches(Regex("[0-9a-f]{64}")) },"Invalid meaning fingerprint",ErrorCode.UNSUPPORTED_IMPORT)
                ensure(!r.parentMove || r.kind == "type","Only activity types can move",ErrorCode.UNSUPPORTED_IMPORT)
                ensure(!r.archive || (r.kind == "type" && v.types.singleOrNull { it.id == r.id }?.archived == true),
                    "Only a type the catalog ships archived can be retired",ErrorCode.UNSUPPORTED_IMPORT)
                val fingerprint = when(r.kind) {
                    "type" -> v.types.singleOrNull { it.id == r.id }?.let(::catalogMeaningFingerprint)
                    "axis" -> v.axes.singleOrNull { it.id == r.id }?.let(::catalogMeaningFingerprint)
                    else -> null
                }
                ensure(fingerprint == r.toMeaningSha256,"Editorial revision does not match current definition",ErrorCode.UNSUPPORTED_IMPORT)
            }
            return ReadyCatalog(m,VocabularyData(frozen.types,frozen.axes,frozen.metrics,frozen.units),frozenList(revisions))
        }
    }
}
/** Reject broken source bytes rather than installing replacement characters under their hash. */
private inline fun <reified T> decodeCatalogJson(bytes: ByteArray,diagnostic: String): T = try {
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    codec.decodeFromString<T>(text)
} catch(_: Exception) { fail(ErrorCode.UNSUPPORTED_IMPORT,diagnostic) }
/** A generic definition fingerprint excludes local presentation and technical source metadata. */
fun catalogMeaningFingerprint(type: ActivityType): String {
    val fields = sortedMapOf<String,JsonElement>("kind" to JsonPrimitive("type"),"id" to JsonPrimitive(type.id),
        "definition" to JsonPrimitive(type.definition),"parentId" to (type.parentId?.let(::JsonPrimitive) ?: JsonNull),
        "selectable" to JsonPrimitive(type.selectable),"meaningVersion" to JsonPrimitive(type.meaningVersion),
        "suggestedAxisIds" to JsonArray(type.suggestedAxisIds.sorted().map(::JsonPrimitive)),
        "suggestedMetricIds" to JsonArray(type.suggestedMetricIds.sorted().map(::JsonPrimitive)))
    return sha256(JsonObject(fields).toString().toByteArray(Charsets.UTF_8))
}
fun catalogMeaningFingerprint(axis: Axis): String {
    val values = axis.values.sortedWith(compareBy<AxisValue> { it.id }.thenBy { it.meaningVersion }).map {
        JsonObject(sortedMapOf("id" to JsonPrimitive(it.id),"meaningVersion" to JsonPrimitive(it.meaningVersion)))
    }
    val fields = sortedMapOf<String,JsonElement>("kind" to JsonPrimitive("axis"),"id" to JsonPrimitive(axis.id),
        "meaningVersion" to JsonPrimitive(axis.meaningVersion),"valueKind" to JsonPrimitive(axis.valueKind.name),
        "cardinality" to JsonPrimitive(axis.cardinality.name),"values" to JsonArray(values))
    return sha256(JsonObject(fields).toString().toByteArray(Charsets.UTF_8))
}
private fun InputStream.boundedBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream();val chunk = ByteArray(8192)
    while(output.size() <= limit) {
        val count = read(chunk,0,minOf(chunk.size,limit+1-output.size()))
        if(count < 0) break
        if(count == 0) { val byte = read();if(byte < 0) break;output.write(byte) } else output.write(chunk,0,count)
    }
    return output.toByteArray()
}
fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val digits = "0123456789abcdef";val hex = CharArray(digest.size*2)
    for(i in digest.indices) {
        val value = digest[i].toInt() and 255
        hex[i*2] = digits[value ushr 4];hex[i*2+1] = digits[value and 15]
    }
    return hex.concatToString()
}

class CatalogPreview internal constructor(internal val owner: CatalogImports,internal val catalog: ReadyCatalog,
    val token: Token,val report: CatalogImportReport) {
    val types get() = catalog.vocabulary.types.size
    val axes get() = catalog.vocabulary.axes.size
    val version get() = catalog.manifest.catalogVersion
    val canApply get() = report.conflicts.isEmpty()
}
class CatalogImports(private val tracker: Tracker,private val load: ()->ReadyCatalog) {
    fun prepare(): CatalogPreview {
        val catalog = load();val view = tracker.view()
        val report = view.data.mergeCatalog(catalog.vocabulary,CatalogInstallPolicy.PRESERVE_LOCAL_PRESENTATION,catalog.revisions).second
        return CatalogPreview(this,catalog,view.snapshot().token,report.copy(entries = frozenList(report.entries)))
    }
    fun apply(preview: CatalogPreview): Tracker.Change {
        ensure(preview.owner === this,"Catalog preview belongs to another importer")
        ensure(preview.canApply,"Review catalog meaning conflicts before applying")
        return tracker.addPreparedCatalog(preview.token,preview.catalog)
    }
}
