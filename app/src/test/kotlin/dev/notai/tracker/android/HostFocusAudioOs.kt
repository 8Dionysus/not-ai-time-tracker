package dev.notai.tracker.android

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.os.Build
import dev.notai.tracker.DescriptorOpenFlags
import java.io.FileDescriptor
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.util.ReflectionHelpers

/** Test-only host bridge: Robolectric4.17 ShadowLinux.fstat(fd) calls stat(null),
 * losing mode/size, and its open ignores flags. Use NIO's actual NOFOLLOW open and
 * /proc descriptor metadata for this file-fixture test. Required Android flags are
 * checked; Android's O_NONBLOCK/Os implementation still needs device verification. */
@Implements(Os::class)
class HostFocusAudioOs {
    companion object {
        private val channels = IdentityHashMap<FileDescriptor,FileChannel>()
        @JvmStatic @Implementation fun open(path: String,flags: Int,mode: Int): FileDescriptor {
            val cloexec = if(Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else DescriptorOpenFlags.closeOnExec()
            assertEquals(OsConstants.O_RDONLY or cloexec or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,flags)
            assertEquals(0,mode)
            val channel = FileChannel.open(Path.of(path),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
            return try {
                ReflectionHelpers.getField<FileDescriptor>(channel,"fd").also { channels[it] = channel }
            } catch(e: Exception) { channel.close();throw e }
        }
        @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat {
            check(channels.containsKey(fd))
            val access = Class.forName("jdk.internal.access.SharedSecrets").getMethod("getJavaIOFileDescriptorAccess").invoke(null)
            val number = Class.forName("jdk.internal.access.JavaIOFileDescriptorAccess").getMethod("get",FileDescriptor::class.java).invoke(access,fd) as Int
            val attributes = Files.readAttributes(Path.of("/proc/self/fd/$number"),"unix:mode,size")
            return StructStat(0,0,attributes.getValue("mode") as Int,1,0,0,0,attributes.getValue("size") as Long,0,0,0,0,0)
        }
        @JvmStatic @Resetter fun reset() {
            channels.values.forEach { it.close() };channels.clear()
        }
    }
}
