package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Locale

/** Diagnostics remain for logs. UI maps code/resourceKey and typed arguments, never parses message. */
@Serializable sealed class ErrorArgument {
    @Serializable data class Text(val value: String): ErrorArgument()
    @Serializable data class Count(val value: Long): ErrorArgument()
    @Serializable data class Identifier(val value: String): ErrorArgument()
    @Serializable data class Date(val iso: String): ErrorArgument()
}
@Serializable data class LocalizedError(val code: ErrorCode, val arguments: Map<String,ErrorArgument> = emptyMap(), val fieldPath: String? = null) {
    val resourceKey get() = "tracker.error.${code.name.lowercase(Locale.ROOT)}"
}
@Serializable enum class TranslationQuality { SOURCE_ONLY, DRAFT, REVIEWED }
@Serializable enum class PackAvailability { AVAILABLE_OFFLINE, NOT_INSTALLED, UNAVAILABLE }
@Serializable data class CatalogLabelKey(val catalogId: String,val stableId: String,val meaningVersion: Int,val localeTag: String)
@Serializable data class CatalogTranslation(val key: CatalogLabelKey,val label: String,val description: String = "",val aliases: List<String> = emptyList(),
    val quality: TranslationQuality,val availability: PackAvailability,val sourceLocale: String,val release: String,val sha256: String,val license: String,val authority: String)
data class LocalizedCatalogEntry(val key: CatalogLabelKey,val originalLabel: String,val displayLabel: String,val resolvedLocale: String?,
    val missing: Boolean,val userOverride: Boolean,val translation: CatalogTranslation?)
fun localeTag(raw: String): String = try {
    Locale.Builder().setLanguageTag(raw).build().toLanguageTag().also { ensure(it != "und","Undefined locale") }
} catch(e: CoreError) { throw e } catch(_: Exception) { fail(ErrorCode.INVALID_INPUT,"Invalid BCP-47 locale") }
/** Explicit chains; Chinese scripts never cross. No claim of installed/complete translations. */
fun localeFallbacks(raw: String): List<String> {
    val tag = localeTag(raw); val locale = Locale.forLanguageTag(tag)
    if(locale.language == "zh") return (listOf(tag)+if(locale.script in setOf("Hans","Hant")) listOf("zh-${locale.script}") else emptyList()).distinct() // script-specific resources only
    return (listOf(tag) + when(tag) { "es-MX" -> listOf("es-419","es"); else -> listOf(locale.language) }).distinct()
}
class CatalogLabels(translations: List<CatalogTranslation>) {
    private val entries = translations.onEach {
        ensure(it.key.localeTag == localeTag(it.key.localeTag) && it.key.meaningVersion > 0 && it.label.isNotBlank(),"Invalid translation")
        ensure(it.sha256.matches(Regex("[0-9a-f]{64}")) && it.release.isNotBlank() && it.license.isNotBlank() && it.authority.isNotBlank(),"Missing translation provenance")
    }.associateBy { it.key }.also { ensure(it.size == translations.size,"Duplicate translation key") }
    fun label(catalogId: String,id: String,meaningVersion: Int,original: String,requestedLocale: String,override: String? = null): LocalizedCatalogEntry {
        val requested = localeTag(requestedLocale); val key = CatalogLabelKey(catalogId,id,meaningVersion,requested)
        if(override != null) return LocalizedCatalogEntry(key,original,override,null,false,true,null)
        val found = localeFallbacks(requested).firstNotNullOfOrNull { entries[key.copy(localeTag = it)]?.takeIf {
            row -> row.quality == TranslationQuality.REVIEWED && row.availability == PackAvailability.AVAILABLE_OFFLINE
        } }
        return LocalizedCatalogEntry(key,original,found?.label ?: original,found?.key?.localeTag,found == null,false,found)
    }
}
@Serializable enum class AppLocaleMode { SYSTEM, EXPLICIT }
@Serializable enum class LocaleChangeSource { IN_APP, ANDROID_SETTINGS, SYSTEM_CHANGE, MIGRATION }
@Serializable data class AppLocalePreference(val revision: Long = 0,val explicitTag: String? = null,val source: LocaleChangeSource = LocaleChangeSource.MIGRATION) {
    val mode get() = if(explicitTag == null) AppLocaleMode.SYSTEM else AppLocaleMode.EXPLICIT
}
data class AppLocaleState(val preference: AppLocalePreference,val requestedLocaleTag: String?,val resolvedLocaleTag: String,val fallback: Boolean)
/** Adapter stores this separately from Dataset; Android Settings and in-app use the same transaction. */
interface AppLocaleStore { fun read(): AppLocalePreference; fun update(expectedRevision: Long,edit: (AppLocalePreference)->AppLocalePreference): AppLocalePreference }
class AppLocales(private val store: AppLocaleStore,supportedTags: Set<String>,private val fallback: String) {
    private val supported = supportedTags.map(::localeTag).toSet().also { ensure(fallback in it,"Missing complete fallback UI locale") }
    fun state(systemLanguages: List<String>): AppLocaleState {
        val p = store.read();val requested = p.explicitTag ?: systemLanguages.firstOrNull()
        val resolved = (p.explicitTag?.let(::listOf) ?: systemLanguages).flatMap(::localeFallbacks).firstOrNull { it in supported } ?: fallback
        return AppLocaleState(p,requested,resolved,requested != resolved)
    }
    fun set(tag: String?,expectedRevision: Long,source: LocaleChangeSource): AppLocalePreference {
        ensure(source != LocaleChangeSource.SYSTEM_CHANGE,"System-language changes resolve presentation without changing explicit preference")
        val canonical = tag?.let(::localeTag)
        ensure(canonical == null || canonical in supported,"UI locale unavailable",ErrorCode.INVALID_INPUT)
        return store.update(expectedRevision) { it.copy(revision = Math.addExact(it.revision,1),explicitTag = canonical,source = source) }
    }
}
/** Stable object-key ordering for command receipts. Array ordering stays meaningful. */
internal fun canonicalJson(value: JsonElement): JsonElement = when(value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { if(it.key in setOf("tags","weekdays","extraDates","suggestedAxisIds","suggestedMetricIds","scopes") && it.value is JsonArray)
        JsonArray((it.value as JsonArray).map(::canonicalJson).sortedBy { v -> v.toString() }) else canonicalJson(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalJson))
    else -> value
}
internal inline fun <reified T> payloadHash(value: T): String = sha256(canonicalJson(codec.encodeToJsonElement(value)).toString().toByteArray(Charsets.UTF_8))
