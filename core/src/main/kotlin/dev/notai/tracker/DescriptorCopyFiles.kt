package dev.notai.tracker

import java.io.Closeable
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable data class FileIdentity(val device: Long,val inode: Long,val links: Long,val mode: Long,val bytes: Long,val modifiedNanos: Long) {
    val regular get() = mode and 61440L == 32768L
    val directory get() = mode and 61440L == 16384L
}
/** POSIX JNI boundary. No canonical-path fallback. Loading is explicit; unavailable platforms
 * cannot construct deletion storage. Android packaging/device validation is a separate gate. */
class DescriptorCopyFiles(root: String,library: String? = null): Closeable {
    private var descriptor: Long
    init { if(library == null) System.loadLibrary("tracker_copy_io") else System.load(library);descriptor = NativeCopyIo.root(root) }
    @Synchronized override fun close() { val fd = descriptor;descriptor = -1;if(fd >= 0) NativeCopyIo.close(fd) }
    @Synchronized internal fun stat(path: String): FileIdentity? = NativeCopyIo.stat(descriptor,path)?.identity()
    @Synchronized internal fun names(path: String): List<String> = NativeCopyIo.names(descriptor,path).sorted()
    @Synchronized internal fun mkdir(path: String) = NativeCopyIo.mkdir(descriptor,path)
    @Synchronized internal fun move(source: String,target: String) = NativeCopyIo.move(descriptor,source,target,false)
    @Synchronized internal fun remove(path: String,directory: Boolean = false,expected: FileIdentity? = stat(path),expectedLinks: Long = 1) {
        check(expected != null);NativeCopyIo.remove(descriptor,path,directory,expected.device,expected.inode,expectedLinks)
    }
    @Synchronized internal fun sync(path: String) = NativeCopyIo.sync(descriptor,path)
    @Synchronized internal fun read(path: String,maxBytes: Int = 16*1024*1024): ByteArray {
        val fd = NativeCopyIo.open(descriptor,path,1)
        return try {
            val out = java.io.ByteArrayOutputStream()
            while(true) { val b = NativeCopyIo.chunk(fd);if(b.isEmpty()) break;check(out.size().toLong()+b.size <= maxBytes);out.write(b) };out.toByteArray()
        } finally { NativeCopyIo.close(fd) }
    }
    @Synchronized internal fun member(path: String,id: String,expectedLinks: Long = 1): Pair<CopyMember,FileIdentity> {
        require(expectedLinks > 0)
        val fd = NativeCopyIo.open(descriptor,path,expectedLinks)
        return try {
            val before = NativeCopyIo.fstat(fd).identity();check(before.regular && before.links == expectedLinks) { "HARDLINK_GRAPH_INCOMPLETE" }
            val digest = MessageDigest.getInstance("SHA-256");var bytes = 0L
            while(true) { val b = NativeCopyIo.chunk(fd);if(b.isEmpty()) break;digest.update(b);bytes = Math.addExact(bytes,b.size.toLong()) }
            val after = NativeCopyIo.fstat(fd).identity()
            check(before == after && bytes == after.bytes && stat(path) == after) { "Copy changed during descriptor read" }
            CopyMember(id,bytes,digest.digest().joinToString("") { "%02x".format(it) }) to after
        } finally { NativeCopyIo.close(fd) }
    }
    @Synchronized internal fun writeAtomic(path: String,bytes: ByteArray) {
        val parent = path.substringBeforeLast('/',"");val temp = (if(parent.isEmpty()) "" else "$parent/")+".${newId()}.part"
        NativeCopyIo.put(descriptor,temp,bytes);NativeCopyIo.move(descriptor,temp,path,true);sync(parent)
    }
    fun metadata(path: String = "maintenance.json",maxBytes: Int = CopyMetadataBytes.MAX_BYTES): CopyMetadata = object: CopyMetadata {
        init { require(maxBytes in 1..CopyMetadataBytes.MAX_BYTES) }
        override fun read(): String? = if(stat(path) == null) null else CopyMetadataBytes.decode(read(path,maxBytes))
        override fun writeDurably(value: String) {
            writeAtomic(path,CopyMetadataBytes.encode(value,maxBytes))
        }
    }
}
private fun LongArray.identity() = FileIdentity(this[0],this[1],this[2],this[3],this[4],this[5])
internal object NativeCopyIo {
    external fun root(path: String): Long
    external fun close(fd: Long)
    external fun stat(root: Long,path: String): LongArray?
    external fun fstat(fd: Long): LongArray
    external fun names(root: Long,path: String): Array<String>
    external fun mkdir(root: Long,path: String)
    external fun open(root: Long,path: String,expectedLinks: Long): Long
    external fun chunk(fd: Long): ByteArray
    external fun put(root: Long,path: String,bytes: ByteArray)
    external fun move(root: Long,source: String,target: String,replace: Boolean)
    external fun remove(root: Long,path: String,directory: Boolean,device: Long,inode: Long,expectedLinks: Long)
    external fun sync(root: Long,path: String)
}
