package dev.notai.tracker

import kotlin.test.*

private fun verifyContentDerivedCopy(target: Tracker,pack: SelectivePackage,reopen: (StateStore) -> StateStore,
    selection: (Dataset) -> TransferSelection,check: (Dataset) -> Unit) {
    val importer = SelectiveTransfer(target);importer.inspect(pack)
    val ids = pack.groups.map { it.id }.toSet();val policies = ids.associateWith { TransferConflictPolicy.COPY }
    val before = target.data();val plan = importer.plan(pack,ids,policies)
    assertTrue(plan.conflicts.isNotEmpty())
    val first = importer.apply(plan,"content-copy");assertTrue(first.complete);assertFalse(first.partial);check(target.data())
    val copied = target.data();assertEquals(first,importer.apply(plan,"content-copy"));assertEquals(copied,target.data())
    importer.undo("content-copy",target.snapshot().token)
    assertEquals(before.types,target.data().types);assertEquals(before.cards,target.data().cards)
    assertEquals(before.favorites,target.data().favorites);assertEquals(before.metrics,target.data().metrics);assertEquals(before.units,target.data().units)
    assertEquals(before.sessions,target.data().sessions)
    assertTrue(importer.apply(importer.plan(pack,ids,policies),"content-redo").complete);check(target.data())
    var opened = Tracker(reopen(target.store),target.clock);check(opened.data())
    fun exported() = SelectiveTransfer(opened).export(selection(opened.data())).also {
        SelectiveTransfer(opened).inspect(it);assertEquals(pack.groups,it.groups)
    }
    exported()
    val retention = opened.previewTransferRetention(TransferRetentionSelection(operationIds = setOf("content-redo"),originIds = opened.data().transfer.origins.map { it.originId }.toSet()))
    opened.applyTransferRetention(retention,"content-prune",retention.requiredAcknowledgements)
    opened = Tracker(reopen(opened.store),opened.clock);exported();check(opened.data())
    val again = SelectiveTransfer(opened)
    assertTrue(again.apply(again.plan(pack,ids,policies),"content-redo").payloadsPruned)
    val compacted = opened.data();assertFailsWith<CoreError> { again.undo("content-redo",opened.snapshot().token) };assertEquals(compacted,opened.data())
}

fun boundedTypeDependenciesCloseFromContent(create: () -> StateStore,reopen: (StateStore) -> StateStore,sharedMetric: Boolean) {
    val clock = FakeClock();val source = Tracker(create(),clock)
    val unit = UnitDefinition("bounded-u","Source unit","bounded-scale");source.saveUnit(source.snapshot().token,unit)
    val a = Metric("bounded-m1","Scale one",numberKind = NumberKind.DECIMAL,canonicalUnitId = unit.id,
        bounds = ScaleBounds(Decimal.ZERO,Decimal("100"),"low","high"))
    val b = a.copy(id = "bounded-m2",name = "Scale two")
    source.saveMetric(source.snapshot().token,a);if(!sharedMetric) source.saveMetric(source.snapshot().token,b)
    source.saveType(source.snapshot().token,ActivityType("bounded-a","Bounded A",suggestedMetricIds = setOf(a.id)))
    source.saveType(source.snapshot().token,ActivityType("bounded-b","Bounded B",suggestedMetricIds = setOf(if(sharedMetric) a.id else b.id)))
    source.saveType(source.snapshot().token,ActivityType("independent-c","Independent C"))
    source.manual(source.snapshot().token,clock.wall-1000,clock.wall-500,Classification(activityTypeId = "bounded-a"))
    val selected = TransferSelection(typeIds = setOf("bounded-a","bounded-b","independent-c"))
    val pack = SelectiveTransfer(source).export(selected)
    assertEquals(4,pack.formatVersion)
    // Inspect first: the previous exporter returned format4 that its own reader refused.
    SelectiveTransfer(source).inspect(pack)
    assertEquals(2,pack.groups.size);assertTrue(pack.groups.all { it.content.sessions.isEmpty() && it.content.catalogRoots.cardIds.isEmpty() })
    val joint = pack.groups.single { it.content.metrics.isNotEmpty() }
    assertEquals(setOf("bounded-a","bounded-b"),joint.content.catalogRoots.typeIds)
    assertEquals(if(sharedMetric) 1 else 2,joint.content.metrics.size);assertEquals(1,joint.content.units.size)
    val target = Tracker(create(),clock);target.saveUnit(target.snapshot().token,unit.copy(name = "Local conflicting unit"))
    target.saveType(target.snapshot().token,ActivityType("bounded-a","Local conflicting type"))
    verifyContentDerivedCopy(target,pack,reopen,{ d -> TransferSelection(typeIds = d.types.filter { it.name in setOf("Bounded A","Bounded B","Independent C") }.map { it.id }.toSet()) }) { d ->
        assertTrue(d.sessions.isEmpty());assertEquals("Local conflicting unit",d.units.single { it.id == unit.id }.name)
        val metrics = d.metrics.filter { it.name in setOf(a.name,b.name) }
        assertEquals(if(sharedMetric) 1 else 2,metrics.size)
        assertEquals(1,metrics.map { it.canonicalUnitId }.distinct().size);assertNotEquals(unit.id,metrics.first().canonicalUnitId)
        assertTrue(metrics.all { it.bounds == a.bounds })
        val roots = d.types.filter { it.name in setOf("Bounded A","Bounded B") }
        assertEquals(2,roots.size);assertTrue(roots.flatMap { it.suggestedMetricIds }.all { id -> metrics.any { it.id == id } })
        assertEquals("independent-c",d.types.single { it.name == "Independent C" }.id)
    }
    // Legacy output/read admission retains the former grouping when no actual field requires4.
    val legacy = Tracker(create(),clock);legacy.saveUnit(legacy.snapshot().token,unit)
    legacy.saveMetric(legacy.snapshot().token,a.copy(bounds = null))
    for(id in listOf("a","b")) legacy.saveType(legacy.snapshot().token,ActivityType(id,id,suggestedMetricIds = setOf(a.id)))
    val old = SelectiveTransfer(legacy).export(TransferSelection(typeIds = setOf("a","b")))
    assertEquals(2,old.formatVersion);assertEquals(2,old.groups.size);assertEquals(2,SelectiveTransfer(legacy).inspect(old).groups.size)
}

fun dependencyOnlyPersonalizationClosesFromContent(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock();val source = Tracker(create(),clock)
    val card = Card("dependent-card","Source card",personal = ChoicePersonalization(aliases = listOf("literal own alias")))
    source.saveCard(source.snapshot().token,card)
    for(id in listOf("favorite-a","favorite-b")) source.saveFavorite(source.snapshot().token,Favorite(id,id,card.classification()))
    val pack = SelectiveTransfer(source).export(TransferSelection(favoriteIds = setOf("favorite-a","favorite-b")))
    assertEquals(4,pack.formatVersion);SelectiveTransfer(source).inspect(pack)
    assertEquals(1,pack.groups.size);assertTrue(pack.groups.single().content.catalogRoots.cardIds.isEmpty())
    assertEquals(card.personal,pack.groups.single().content.cards.single().personal)
    val target = Tracker(create(),clock);target.saveCard(target.snapshot().token,card.copy(name = "Local conflicting card",personal = ChoicePersonalization()))
    verifyContentDerivedCopy(target,pack,reopen,{ d -> TransferSelection(favoriteIds = d.favorites.map { it.id }.toSet()) }) { d ->
        val copied = d.cards.single { it.name == card.name };assertNotEquals(card.id,copied.id)
        assertEquals(card.personal,copied.personal);assertEquals(2,d.favorites.size)
        assertTrue(d.favorites.all { it.selection.cardId == copied.id });assertTrue(d.sessions.isEmpty())
    }
}
