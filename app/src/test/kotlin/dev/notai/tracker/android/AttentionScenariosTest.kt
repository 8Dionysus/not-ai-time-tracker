package dev.notai.tracker.android

import dev.notai.tracker.*
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttentionScenariosTest: AttentionScenarioContract() {
    private val databases = mutableListOf<TrackerDatabase>()
    override fun createAttentionStore(): StateStore {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java)
            .allowMainThreadQueries().build()
        databases += db
        return RoomStore(db)
    }
    @After fun close() { databases.forEach { it.close() } }
}
