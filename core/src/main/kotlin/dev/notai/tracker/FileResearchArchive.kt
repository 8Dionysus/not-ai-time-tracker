package dev.notai.tracker

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ

/** Content-addressed local evidence. Use an app-private directory; no provider/network or credentials. */
class FileResearchArchive(private val directory: File): ResearchArchive {
    private fun file(hash: String): File {
        ensure(hash.matches(Regex("[0-9a-f]{64}")),"Invalid evidence hash")
        return File(directory,"$hash.json")
    }
    override fun put(sha256: String,bytes: ByteArray): Unit = storage {
        ensure(researchHash(bytes) == sha256,"Evidence bytes differ from key",ErrorCode.STORAGE)
        val target = file(sha256)
        if(target.exists()) {
            ensure(researchHash(target.readBytes()) == sha256,"Existing evidence is damaged",ErrorCode.STORAGE)
        } else {
            Files.createDirectories(directory.toPath())
            val temporary = File.createTempFile("evidence-",".tmp",directory)
            try {
                FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
                Files.move(temporary.toPath(),target.toPath(),ATOMIC_MOVE)
                FileChannel.open(directory.toPath(),READ).use { it.force(true) }
            } finally { temporary.delete() }
        }
    }
    override fun read(sha256: String): ByteArray = storage { file(sha256).readBytes() }
}
