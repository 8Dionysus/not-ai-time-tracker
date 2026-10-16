package dev.notai.tracker

class ChoiceLocalizationTest: ChoiceLocalizationScenarioContract() {
    override fun createChoiceStore(): StateStore = MemoryStore()
}
