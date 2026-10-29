package dev.notai.tracker

import org.junit.Test
import kotlin.test.*

/** The 0.9.2 editorial step renames most of the catalog and moves areas under new parents. A default name follows the
 * catalog (with its catalog aliases, so translations still match), a personal name stays, a move needs an explicit
 * reviewed step, and past time keeps its path at write while summaries follow where the area lives now. */
class CatalogEditorialMoveTest {
    @Test fun defaultNameRenameRetainsIndependentPersonalAliases() {
        val (tracker,_) = withHistory()
        val leaf = tracker.data().types.single { it.id == "leaf" }
        tracker.saveType(tracker.snapshot().token,leaf.copy(aliases = leaf.aliases+"мой рабочий псевдоним"))
        val before = tracker.data().segments
        val preview = install(tracker,files(v2(),"2",step(parentMove = true)))
        assertTrue(preview.canApply)
        val updated = tracker.data().types.single { it.id == "leaf" }
        assertEquals("Написание кода",updated.name)
        assertTrue("мой рабочий псевдоним" in updated.aliases,"Personal aliases are independent of the default name")
        assertEquals(before,tracker.data().segments)
    }
    private val definition = "Работать над кодом."
    private fun v1() = listOf(ActivityType("root.work","Работа",selectable = true),ActivityType("root.code","Разработка ПО",selectable = true),
        ActivityType("leaf","писать/дополнять исходный код",parentId = "root.code",aliases = listOf("coding","писать код."),definition = definition))
    private fun v2() = listOf(ActivityType("root.work","Работа",selectable = true),ActivityType("root.code","Разработка ПО",parentId = "root.work",selectable = true),
        ActivityType("leaf","Написание кода",parentId = "root.code",aliases = listOf("coding","писать код."),definition = definition))
    private fun files(types: List<ActivityType>,version: String,revisions: List<CatalogEditorialRevision>?): Map<String,ByteArray> {
        val definitions = codec.encodeToString(VocabularyData(types = types)).toByteArray(Charsets.UTF_8)
        val compatibility = revisions?.let { codec.encodeToString(it).toByteArray(Charsets.UTF_8) }
        val manifest = CatalogPackageManifest(1,version,"runtime-catalog.json",definitions.size,sha256(definitions),
            compatibility?.let { CatalogCompatibilityFile("compatibility.json",it.size,sha256(it)) })
        return buildMap {
            put("manifest.json",codec.encodeToString(manifest).toByteArray(Charsets.UTF_8));put("runtime-catalog.json",definitions)
            compatibility?.let { put("compatibility.json",it) }
        }
    }
    private fun install(tracker: Tracker,source: Map<String,ByteArray>): CatalogPreview {
        val imports = CatalogImports(tracker) { ReadyCatalog.read { source.getValue(it).inputStream() } }
        val preview = imports.prepare()
        if(preview.canApply) imports.apply(preview)
        return preview
    }
    private fun step(parentMove: Boolean): List<CatalogEditorialRevision> {
        val old = v1().associateBy { it.id };val new = v2().associateBy { it.id }
        return listOf(
            CatalogEditorialRevision("type","root.code",catalogMeaningFingerprint(old.getValue("root.code")),catalogMeaningFingerprint(new.getValue("root.code")),
                sha256("Разработка ПО".toByteArray()),parentMove = parentMove),
            CatalogEditorialRevision("type","leaf",catalogMeaningFingerprint(old.getValue("leaf")),catalogMeaningFingerprint(new.getValue("leaf")),
                sha256("писать/дополнять исходный код".toByteArray())))
    }
    private fun withHistory(): Pair<Tracker,FakeClock> {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock)
        install(tracker,files(v1(),"1",emptyList()))
        tracker.start(tracker.snapshot().token,Classification(activityTypeId = "leaf"))
        clock.advance(30*60_000);tracker.stop(tracker.snapshot().token)
        return tracker to clock
    }

    @Test fun reviewedMoveAndRenameUpdateTheTreeAndKeepHistoricalPaths() {
        val (tracker,clock) = withHistory()
        val preview = install(tracker,files(v2(),"2",step(parentMove = true)))
        assertTrue(preview.canApply);assertEquals(setOf("root.code","leaf"),preview.report.updated.map { it.id }.toSet())
        val d = tracker.data()
        assertEquals("root.work",d.types.single { it.id == "root.code" }.parentId)
        val leaf = d.types.single { it.id == "leaf" }
        assertEquals("Написание кода",leaf.name)
        // The default name takes the catalog presentation whole; the old wording is still in the catalog aliases.
        assertEquals(listOf("coding","писать код."),leaf.aliases)
        val report = tracker.view().report(0,clock.now().wall+1)
        assertEquals(listOf("root.code","leaf"),report.rows.single().selection.ancestorPathAtWrite)
        assertEquals(30*60_000L,report.byType()["root.code"]);assertNull(report.byType()["root.work"])
        assertEquals(mapOf<String?,Long>("root.work" to 30*60_000L),report.byCurrentRoot(d.types))
    }

    @Test fun personalNameSurvivesTheRenameWhileTheMoveStillApplies() {
        val (tracker,_) = withHistory()
        val own = tracker.data().types.single { it.id == "leaf" }.copy(name = "Мой код")
        tracker.saveType(tracker.snapshot().token,own)
        install(tracker,files(v2(),"2",step(parentMove = true)))
        val leaf = tracker.data().types.single { it.id == "leaf" }
        assertEquals("Мой код",leaf.name);assertEquals("root.code",leaf.parentId)
        assertEquals("root.work",tracker.data().types.single { it.id == "root.code" }.parentId)
    }

    @Test fun aMoveWithoutAnExplicitStepIsAConflict() {
        val (tracker,_) = withHistory()
        val before = tracker.data()
        val preview = install(tracker,files(v2(),"2",step(parentMove = false)))
        assertFalse(preview.canApply);assertEquals(listOf("root.code"),preview.report.conflicts.map { it.id })
        assertEquals(before,tracker.data())
    }

    @Test fun olderInstallsAreAcceptedThroughTheExtraFingerprints() {
        val ancient = v1().map { if(it.id == "leaf") it.copy(definition = "Писать код.",name = "кодить") else it }
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock)
        install(tracker,files(ancient,"0",emptyList()))
        val oldLeaf = ancient.single { it.id == "leaf" }
        val revisions = step(parentMove = true).map { r -> if(r.id != "leaf") r else r.copy(
            alsoFromMeaningSha256 = listOf(catalogMeaningFingerprint(oldLeaf)),previousDefaultNameSha256s = listOf(sha256("кодить".toByteArray()))) }
        assertTrue(install(tracker,files(v2(),"2",revisions)).canApply)
        val leaf = tracker.data().types.single { it.id == "leaf" }
        assertEquals("Написание кода",leaf.name);assertEquals(definition,leaf.definition)
    }

    /** 0.9.3 retires a few catalog entries. An unused one leaves the picker; one the user's own data names stays live. */
    private fun retired() = v1().map { if(it.id == "leaf") it.copy(archived = true) else it }
    private fun retire(): List<CatalogEditorialRevision> {
        val leaf = v1().single { it.id == "leaf" }
        return listOf(CatalogEditorialRevision("type","leaf",catalogMeaningFingerprint(leaf),catalogMeaningFingerprint(leaf),archive = true))
    }
    @Test fun anUnusedRetiredTypeLeavesThePicker() {
        val tracker = Tracker(MemoryStore(),FakeClock())
        install(tracker,files(v1(),"1",emptyList()))
        val preview = install(tracker,files(retired(),"2",retire()))
        assertTrue(preview.canApply);assertEquals(listOf("leaf"),preview.report.updated.map { it.id })
        assertTrue(tracker.data().types.single { it.id == "leaf" }.archived)
        assertTrue(tracker.data().children("root.code").none { it.id == "leaf" })
    }
    @Test fun aRetiredTypeTheUserTrackedStaysLive() {
        val (tracker,_) = withHistory()
        val preview = install(tracker,files(retired(),"2",retire()))
        assertTrue(preview.canApply);assertTrue(preview.report.conflicts.isEmpty())
        assertFalse(tracker.data().types.single { it.id == "leaf" }.archived)
        tracker.start(tracker.snapshot().token,Classification(activityTypeId = "leaf"))
    }
    @Test fun aRetiredTypeOnACardStaysLive() {
        val tracker = Tracker(MemoryStore(),FakeClock())
        install(tracker,files(v1(),"1",emptyList()))
        tracker.saveCard(tracker.snapshot().token,tracker.data().cardFromType("leaf"))
        install(tracker,files(retired(),"2",retire()))
        assertFalse(tracker.data().types.single { it.id == "leaf" }.archived)
    }
    @Test fun aRetiredTypeWithAPersonalChildStaysBrowsable() {
        val tracker = Tracker(MemoryStore(),FakeClock())
        install(tracker,files(v1(),"1",emptyList()))
        val child = ActivityType("personal.child","My activity",parentId = "leaf")
        tracker.saveType(tracker.snapshot().token,child)
        assertTrue(install(tracker,files(retired(),"2",retire())).canApply)
        assertFalse(tracker.data().types.single { it.id == "leaf" }.archived)
        assertEquals(listOf(child),tracker.data().children("leaf"))
        assertTrue(tracker.data().children("root.code").any { it.id == "leaf" })
    }
    @Test fun withoutTheReviewedStepAnInstalledTypeIsNotRetired() {
        val tracker = Tracker(MemoryStore(),FakeClock())
        install(tracker,files(v1(),"1",emptyList()))
        assertTrue(install(tracker,files(retired(),"2",emptyList())).canApply)
        assertFalse(tracker.data().types.single { it.id == "leaf" }.archived)
    }
    @Test fun onlyAnArchivedCatalogTypeMayBeRetired() {
        val source = files(v1(),"1",retire())
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { ReadyCatalog.read { source.getValue(it).inputStream() } }.code)
    }

    @Test fun onlyTypesMayMove() {
        val bad = listOf(CatalogEditorialRevision("axis","x","0".repeat(64),"0".repeat(64),parentMove = true))
        val source = files(v1(),"1",bad)
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { ReadyCatalog.read { source.getValue(it).inputStream() } }.code)
    }
}
