package dev.notai.tracker.android

import androidx.room.Room
import dev.notai.tracker.*
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomChoiceLocalizationTest: ChoiceLocalizationScenarioContract() {
    private val databases = mutableListOf<TrackerDatabase>()
    override fun createChoiceStore(): StateStore {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java)
            .allowMainThreadQueries().build()
        databases += db;return RoomStore(db)
    }
    @After fun closeFixtures() { databases.forEach { it.close() } }
}
