package dev.notai.tracker

class AttentionScenariosTest: AttentionScenarioContract() {
    override fun createAttentionStore(): StateStore = MemoryStore()
}
