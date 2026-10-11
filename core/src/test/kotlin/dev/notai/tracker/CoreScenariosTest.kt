package dev.notai.tracker

import kotlinx.coroutines.flow.MutableStateFlow
class MemoryStore(initial: Dataset = Dataset()): StateStore {
    private var checked = CheckedState.validate(initial)
    private val data get() = checked.data
    override val changes = MutableStateFlow(data)
    override val checkedChanges = MutableStateFlow(checked)
    @Synchronized override fun readChecked() = checked
    @Synchronized override fun read() = data
    @Synchronized override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
        val (next,result) = block(data)
        return checkedTransaction { it.replace(next) { result } }
    }
    @Synchronized override fun <T> checkedTransaction(block: (CheckedState)->CheckedCommit<T>): T {
        val commit = block(checked); commit.requireBase(checked)
        checked = commit.state; checkedChanges.value = checked; changes.value = data; return commit.result
    }
}
class CoreScenariosTest: ScenarioContract() {
    override fun createStore(): StateStore = MemoryStore()
    override fun reopen(store: StateStore): StateStore = MemoryStore(codec.decodeFromString<Dataset>(codec.encodeToString(store.read())))
}
