package dev.notai.tracker.android.ui

import dev.notai.tracker.ActivityType
import dev.notai.tracker.Classification
import dev.notai.tracker.Dataset
import org.junit.Assert.*
import org.junit.Test

class PresentationContextTest {
    @Test fun categoryHidesOnlyItsOwnFallbackAndNeverUserDetails() {
        val data = Dataset(types = listOf(ActivityType("area.sport", "Тренировка и спорт"),
            ActivityType("sport.strength", "Силовая тренировка", parentId = "area.sport")))
        val selection = Classification(activityTypeId = "sport.strength", ancestorPathAtWrite = listOf("area.sport", "sport.strength"))
        val fallback = data.label(selection)
        assertEquals("тренировка и спорт", fallback.detail)
        assertNull(fallback.detailInCategory("area.sport"))
        assertEquals(fallback.detail, fallback.detailInCategory(null))
        assertEquals(fallback.detail, fallback.detailInCategory("area.work"))
        val own = data.label(selection.copy(details = mapOf("kind" to "силовая тренировка")))
        assertEquals("силовая тренировка", own.detailInCategory("area.sport"))
        assertNull(own.fallbackRootId)
        assertEquals("Силовая тренировка", data.types.last().name)
    }

    @Test fun homonymousGlobalChoicesRetainDifferentCategoryContext() {
        val data = Dataset(types = listOf(ActivityType("area.work", "Работа"), ActivityType("area.study", "Учёба"),
            ActivityType("work.practice", "Практика", parentId = "area.work"), ActivityType("study.practice", "Практика", parentId = "area.study")))
        val work = data.label(Classification(activityTypeId = "work.practice"))
        val study = data.label(Classification(activityTypeId = "study.practice"))
        assertEquals(work.title, study.title)
        assertEquals("работа", work.detailInCategory(null))
        assertEquals("учёба", study.detailInCategory(null))
        assertNotEquals(work.detail, study.detail)
    }
}
