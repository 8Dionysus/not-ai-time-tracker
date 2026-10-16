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
    /** Read-only platform binding: compare the opened descriptor with the trusted Context root. */
    @Synchronized fun rootIdentity(): FileIdentity = NativeCopyIo.fstat(descriptor).identity()
    @Synchronized fun inspect(relative: String): FileIdentity? = stat(relative)
    @Synchronized fun children(relative: String): List<String> = names(relative)
    @Synchronized fun inspectMember(relative: String,id: String): Pair<CopyMember,FileIdentity> {
        val identity = stat(relative) ?: error("Member missing")
        return member(relative,id,identity.links)
    }
    /** Descriptor-relative streaming reader; never follows a symlink or opens a special file.
     * The caller owns/ closes the stream. No deletion or path-derived cleanup authority. */
    @Synchronized fun input(relative: String,expectedLinks: Long = 1): java.io.InputStream {
        val fd = NativeCopyIo.open(descriptor,relative,expectedLinks)
        return object: java.io.InputStream() {
            private var open = true;private var chunk = byteArrayOf();private var offset = 0
            override fun read(): Int { val b = ByteArray(1);return if(read(b,0,1) < 0) -1 else b[0].toInt() and 255 }
            override fun read(b: ByteArray,off: Int,len: Int): Int {
                check(open);if(off < 0 || len < 0 || off > b.size-len) throw IndexOutOfBoundsException()
                if(len == 0) return 0
                if(offset == chunk.size) { chunk = NativeCopyIo.chunk(fd);offset = 0;if(chunk.isEmpty()) return -1 }
                val n = minOf(len,chunk.size-offset);chunk.copyInto(b,off,offset,offset+n);offset += n;return n
            }
            override fun close() { if(open) { open = false;NativeCopyIo.close(fd) } }
        }
    }
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
/** Native ABI constant only: API26 lacks the public Java O_CLOEXEC field. Passing
 * this actual platform value to Os.open retains one atomic close-on-exec/no-follow
 * open. No file, descriptor, maintenance authority or new IO engine is created here. */
object DescriptorOpenFlags {
    private var closeOnExec: Int? = null
    @Synchronized fun closeOnExec(library: String? = null): Int = closeOnExec ?: run {
        if(library == null) System.loadLibrary("tracker_copy_io") else System.load(library)
        NativeCopyIo.closeOnExecFlag().also { check(it > 0);closeOnExec = it }
    }
}
internal object NativeCopyIo {
    external fun closeOnExecFlag(): Int
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
