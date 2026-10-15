package dev.notai.tracker

import org.junit.Test
import kotlin.test.*

class CatalogPackageEncodingTest {
    private fun vocabulary(name: String = "Original fixture") = codec.encodeToString(
        VocabularyData(types = listOf(ActivityType("encoding-fixture",name)))).toByteArray(Charsets.UTF_8)
    private fun files(definitions: ByteArray = vocabulary(),version: String = "encoding-fixture",compatibility: ByteArray? = null): Map<String,ByteArray> {
        val manifest = CatalogPackageManifest(1,version,"runtime-catalog.json",definitions.size,sha256(definitions),
            compatibility?.let { CatalogCompatibilityFile("compatibility.json",it.size,sha256(it)) })
        return buildMap {
            put("manifest.json",codec.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            put("runtime-catalog.json",definitions)
            compatibility?.let { put("compatibility.json",it) }
        }
    }
    private fun brokenUtf8(bytes: ByteArray): ByteArray = bytes.copyOf().also {
        val at = it.indexOf('@'.code.toByte());require(at >= 0 && it[at+1] == '@'.code.toByte())
        it[at] = 0xc3.toByte();it[at+1] = '('.code.toByte()
    }
    private fun rejectedBeforePreview(source: Map<String,ByteArray>) {
        val originalBytes = source.mapValues { it.value.copyOf() }
        val tracker = Tracker(MemoryStore(),FakeClock());val before = tracker.data()
        val imports = CatalogImports(tracker) { ReadyCatalog.read { source.getValue(it).inputStream() } }
        val error = assertFailsWith<CoreError> { imports.prepare() }
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,error.code)
        assertEquals(before,tracker.data())
        for((name,bytes) in originalBytes) assertContentEquals(bytes,source.getValue(name))
    }
    @Test fun malformedManifestUtf8RefusesBeforePreview() {
        val source = files(version = "@@").toMutableMap()
        source["manifest.json"] = brokenUtf8(source.getValue("manifest.json"))
        rejectedBeforePreview(source)
    }
    @Test fun correctlyHashedMalformedVocabularyUtf8RefusesBeforePreview() {
        val bytes = brokenUtf8(vocabulary("@@"))
        // Size/hash are valid for the supplied bytes; integrity alone must not admit a changed name.
        rejectedBeforePreview(files(definitions = bytes))
    }
    @Test fun correctlyHashedMalformedCompatibilityReturnsTypedImportError() {
        for(bytes in listOf("[".toByteArray(),byteArrayOf('['.code.toByte(),0xc3.toByte(),'('.code.toByte(),']'.code.toByte())))
            rejectedBeforePreview(files(compatibility = bytes))
    }
    @Test fun validUtf8IncludingLiteralReplacementCharacterRemainsExactOnApply() {
        val original = "Literal \uFFFD / e\u0301 / é / العربية"
        val source = files(definitions = vocabulary(original),compatibility = "[]".toByteArray())
        val tracker = Tracker(MemoryStore(),FakeClock())
        val imports = CatalogImports(tracker) { ReadyCatalog.read { source.getValue(it).inputStream() } }
        val before = tracker.data();val preview = imports.prepare()
        assertTrue(preview.canApply);assertEquals(before,tracker.data())
        imports.apply(preview)
        assertEquals(original,tracker.data().types.single { it.id == "encoding-fixture" }.name)
    }
}
