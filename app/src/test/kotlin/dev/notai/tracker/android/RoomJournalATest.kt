package dev.notai.tracker.android

import androidx.room.Room
import dev.notai.tracker.*
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class RoomJournalATest: JournalAScenarioContract() {
    private val stores=mutableMapOf<StateStore,Pair<String,TrackerDatabase>>()
    private fun open(name: String): StateStore {
        val db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java,name)
            .addMigrations(TrackerDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
        return RoomStore(db).also { stores[it]=name to db }
    }
    override fun createJournalStore(): StateStore = open("journal-a-${newId()}.db")
    override fun reopenJournalStore(store: StateStore): StateStore {
        val (name,db)=stores.remove(store)!!;db.close();return open(name)
    }
    @After fun close() { stores.values.forEach { it.second.close() } }
}
