package dev.notai.tracker

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Same durable-state capacity on Android and descriptor ports; never truncate protection records. */
object CopyMetadataBytes {
    const val MAX_BYTES = 16*1024*1024
    fun check(value: String,maxBytes: Int = MAX_BYTES) {
        require(maxBytes in 1..MAX_BYTES)
        var bytes = 0;var i = 0
        while(i < value.length) {
            val c = value[i++]
            bytes += when {
                c.code < 128 -> 1
                c.code < 2048 -> 2
                c.isHighSurrogate() -> { check(i < value.length && value[i++].isLowSurrogate()) { "Malformed metadata UTF-16" };4 }
                c.isLowSurrogate() -> error("Malformed metadata UTF-16")
                else -> 3
            }
            check(bytes <= maxBytes) { "Maintenance metadata exceeds reader capacity" }
        }
    }
    fun encode(value: String,maxBytes: Int = MAX_BYTES): ByteArray { check(value,maxBytes);return value.toByteArray(Charsets.UTF_8) }
    fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun read(input: InputStream,maxBytes: Int = MAX_BYTES): String {
        require(maxBytes in 1..MAX_BYTES)
        val out = ByteArrayOutputStream();val buffer = ByteArray(minOf(65536,maxBytes+1))
        while(true) {
            val n = input.read(buffer,0,minOf(buffer.size,maxBytes-out.size()+1))
            if(n < 0) break
            if(n == 0) { val one = input.read();if(one < 0) break;out.write(one) } else out.write(buffer,0,n)
            check(out.size() <= maxBytes) { "Maintenance metadata exceeds reader capacity" }
        }
        return decode(out.toByteArray())
    }
}
