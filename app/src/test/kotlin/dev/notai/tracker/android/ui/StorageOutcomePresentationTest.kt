package dev.notai.tracker.android.ui

import dev.notai.tracker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36], qualifiers = "ru-rRU")
class StorageOutcomePresentationTest {
    @Test fun storageMessageDoesNotDenyAReadBackConfirmedCommit() {
        val store = object: StateStore {
            private var value = Dataset();override val changes = MutableStateFlow(value)
            override fun read() = value
            @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
                val (next,_) = block(value);validate(next);value = next;changes.value = next
                throw java.io.IOException("Synthetic disconnect after durable commit")
            }
        }
        val clock = FakeClock();val t = Tracker(store,clock)
        val error = try {
            t.manual(t.snapshot().token,clock.wall-1000,clock.wall,Classification())
            throw AssertionError("Expected ambiguous post-commit storage result")
        } catch(e: CoreError) { e }
        assertEquals(ErrorCode.STORAGE,error.code)
        // Readback establishes that the requested fact exists despite the returned STORAGE.
        assertEquals(1,t.data().sessions.size);assertEquals(1000L,t.view().report(0,clock.wall+1).recordedMs)
        val displayed = Format.error(error)
        assertFalse(displayed,displayed.contains("Данные не изменены"))
        assertTrue(displayed,displayed.lowercase().contains("проверь"))
    }
}
