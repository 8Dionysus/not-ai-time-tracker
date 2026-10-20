package dev.notai.tracker.android

import android.content.Intent
import dev.notai.tracker.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A plan chip still visible in an older shade or widget rendering must keep sending the token it was shown with. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanSurfacesIntentTest {
    private val choice = PlannedChoice(OccurrenceKey("series","2026-10-20"),3,null,"Английский",0,null,"19:00")
    @Test fun newerRenderingCannotRewriteAnOlderVisibleButton() {
        val c = RuntimeEnvironment.getApplication();val shown = Token("dataset",5,"running-a")
        val old = PlanSurfaces.pending(c,choice,shown,0)
        val again = PlanSurfaces.pending(c,choice,shown,0)
        val newer = PlanSurfaces.pending(c,choice,Token("dataset",9,"running-b"),0)
        val moved = PlanSurfaces.pending(c,choice.copy(overrideRevision = 1),shown,0)
        assertEquals(old,again);assertEquals(3,setOf(old,newer,moved).size)
        assertEquals(shown,PlanSurfaces.parse(shadowOf(old).savedIntent)!!.token)
        assertEquals(Token("dataset",9,"running-b"),PlanSurfaces.parse(shadowOf(newer).savedIntent)!!.token)
        assertEquals(1L,PlanSurfaces.parse(shadowOf(moved).savedIntent)!!.overrideRevision)
        assertTrue(old.isImmutable)
    }
    @Test fun extrasAloneAreNotATap() {
        val c = RuntimeEnvironment.getApplication()
        val forged = Intent(c,PlanStartReceiver::class.java).setAction("dev.notai.tracker.PLAN_START").putExtra("tap","{}").putExtra("planTap","{}")
        assertNull(PlanSurfaces.parse(forged))
        assertNull(PlanSurfaces.parse(Intent("other").setData(android.net.Uri.parse("notai://plan/0?tap=%7B%7D"))))
    }
}
