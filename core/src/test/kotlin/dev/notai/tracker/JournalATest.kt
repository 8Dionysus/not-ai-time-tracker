package dev.notai.tracker

class JournalATest: JournalAScenarioContract() {
    override fun createJournalStore(): StateStore = MemoryStore()
}
