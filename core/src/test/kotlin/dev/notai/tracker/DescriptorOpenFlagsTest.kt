package dev.notai.tracker

import org.junit.Test
import kotlin.test.*

class DescriptorOpenFlagsTest {
    @Test fun nativeCloseOnExecMatchesTheActualHostAbiAndCachesWithoutOpeningAFile() {
        val library = requireNotNull(System.getProperty("tracker.cleanup.library"))
        val process = ProcessBuilder("python3","-c","import os; print(os.O_CLOEXEC)").start()
        val expected = process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
        assertEquals(0,process.waitFor())
        assertTrue(expected>0);assertEquals(expected,DescriptorOpenFlags.closeOnExec(library))
        assertEquals(expected,DescriptorOpenFlags.closeOnExec())
        // A later caller cannot select another library after the actual value was captured.
        assertEquals(expected,DescriptorOpenFlags.closeOnExec("/nonexistent/not-loaded.so"))
    }
}
