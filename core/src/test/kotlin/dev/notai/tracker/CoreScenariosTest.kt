package dev.notai.tracker

import kotlinx.coroutines.flow.MutableStateFlow
class MemoryStore(initial: Dataset = Dataset()): StateStore {
    private var data = initial
    override val changes = MutableStateFlow(data)
    @Synchronized override fun read() = data
    @Synchronized override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
        val (next,result) = block(data); validate(next); data = next; changes.value = next; return result
    }
}
class CoreScenariosTest: ScenarioContract() {
    override fun createStore(): StateStore = MemoryStore()
    override fun reopen(store: StateStore): StateStore = MemoryStore(codec.decodeFromString<Dataset>(codec.encodeToString(store.read())))
}
