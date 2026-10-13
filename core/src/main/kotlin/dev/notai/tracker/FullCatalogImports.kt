package dev.notai.tracker

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import kotlinx.serialization.Serializable

@Serializable data class CatalogSource(val path: String,val bytes: Int,val sha256: String)
@Serializable private data class CatalogSourceManifest(val schema: Int,val files: List<CatalogSource>)

class FullCatalogPreview internal constructor(internal val owner: FullCatalogImports,
    internal val plan: FullResearchPlan,val token: Token,val report: CatalogImportReport) {
    val types get() = plan.vocabulary.types.size
    val axes get() = plan.vocabulary.axes.size
    val canApply get() = report.conflicts.isEmpty()
    val sourceHashes get() = FullCatalogImports.sources.associate { it.path to it.sha256 }
}

/** Explicit import of the reviewed sources. Expected hashes come from the app's committed resource,
 * never from user-supplied bytes. The caller opens selected files; this class performs no network IO.
 */
class FullCatalogImports(private val tracker: Tracker,private val archive: ResearchArchive) {
    fun prepare(open: (String)->InputStream): FullCatalogPreview {
        val files = sources.associate { source ->
            val bytes = try { open(source.path).use { input ->
                val out = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while(out.size() <= source.bytes) {
                    val count = input.read(chunk,0,minOf(chunk.size,source.bytes+1-out.size()))
                    if(count < 0) break
                    if(count == 0) {
                        val byte = input.read(); if(byte < 0) break; out.write(byte)
                    } else out.write(chunk,0,count)
                }
                out.toByteArray()
            } } catch(e: Exception) {
                throw CoreError(ErrorCode.UNSUPPORTED_IMPORT,"Catalog source unavailable: ${source.path}").also { it.initCause(e) }
            }
            ensure(bytes.size == source.bytes && researchHash(bytes) == source.sha256,
                "Catalog source size/hash mismatch: ${source.path}",ErrorCode.UNSUPPORTED_IMPORT)
            source.path to bytes
        }
        val plan = verifiedPlan(files)
        val view = tracker.view()
        val report = view.data.mergeCatalog(plan.vocabulary,allowReviewedSourceRevision = true).second
        return FullCatalogPreview(this,plan,view.snapshot().token,report.copy(entries = frozenList(report.entries)))
    }
    fun apply(preview: FullCatalogPreview): Tracker.Change {
        ensure(preview.owner === this,"Catalog preview belongs to another importer")
        checkToken(tracker.data(),preview.token,tracker.clock.now())
        ensure(preview.canApply,"Review conflicting definitions before installing the full catalog")
        return preview.plan.applyReviewed(tracker,preview.token,archive)
    }
    companion object {
        private var cachedPlan: WeakReference<FullResearchPlan>? = null
        /** One immutable, hash-pinned projection can be reused by live previews. A weak reference
         * releases the large source graph when callers discard their previews; no user data is cached.
         */
        @Synchronized private fun verifiedPlan(files: Map<String,ByteArray>): FullResearchPlan {
            cachedPlan?.get()?.let { return it }
            val baseSource = sources.single { it.path == "catalog.research.json" }
            val supporting = files.filterKeys { it.startsWith("supporting/") }.mapKeys { it.key.removePrefix("supporting/") }
            val base = ResearchCatalog.read(files.getValue(baseSource.path),baseSource.sha256,supporting)
            val isco = sources.single { it.path == "projections/${FullResearchCatalog.ISCO}" }
            val sports = sources.single { it.path == "projections/${FullResearchCatalog.SPORTS}" }
            return FullResearchCatalog.read(base,files.getValue(isco.path),isco.sha256,files.getValue(sports.path),sports.sha256)
                .also { cachedPlan = WeakReference(it) }
        }
        val sources: List<CatalogSource> by lazy {
            val bytes = requireNotNull(FullCatalogImports::class.java.getResourceAsStream("/full-catalog-manifest.json")).use { it.readBytes() }
            val manifest = codec.decodeFromString<CatalogSourceManifest>(bytes.toString(Charsets.UTF_8))
            ensure(manifest.schema == 1 && manifest.files.size == 8,"Invalid trusted catalog manifest")
            ensure(manifest.files.map { it.path }.distinct().size == 8 && manifest.files.all {
                it.bytes in 1..32*1024*1024 && it.sha256.matches(Regex("[0-9a-f]{64}")) &&
                    !it.path.startsWith("/") && ".." !in it.path.split('/')
            },"Invalid trusted catalog source")
            frozenList(manifest.files)
        }
    }
}
