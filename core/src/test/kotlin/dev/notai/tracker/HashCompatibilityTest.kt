package dev.notai.tracker

import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals

class HashCompatibilityTest {
    @Test fun standardVectorsAndEveryInputByteRetainExactLowercaseHashesAcrossLocales() {
        val vectors = listOf(
            byteArrayOf() to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc".toByteArray(Charsets.US_ASCII) to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ByteArray(256) { it.toByte() } to "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880")
        val old = Locale.getDefault()
        try { for(tag in listOf("en-US","ru-RU","tr-TR","ar","hi")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            vectors.forEach { (input,expected) -> assertEquals(expected,sha256(input),tag) }
        } } finally { Locale.setDefault(old) }
    }
}
