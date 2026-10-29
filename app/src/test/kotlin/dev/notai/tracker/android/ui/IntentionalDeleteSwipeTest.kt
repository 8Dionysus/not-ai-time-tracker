package dev.notai.tracker.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class IntentionalDeleteSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leftSwipeRevealsClickableDeleteInLtr() = exerciseSwipe(LayoutDirection.Ltr)
    @Test fun leftSwipeRevealsClickableDeleteInRtl() = exerciseSwipe(LayoutDirection.Rtl)

    private fun exerciseSwipe(direction: LayoutDirection) {
        val deletes = AtomicInteger()
        val rowClicks = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                NotAiTheme(reducedMotion = true) {
                    IntentionalDeleteSwipe(
                        delete = { deletes.incrementAndGet(); true },
                        modifier = Modifier.width(300.dp).height(64.dp).testTag("row"),
                        actionTag = "delete"
                    ) {
                        Box(Modifier.fillMaxSize().clickable { rowClicks.incrementAndGet() })
                    }
                }
            }
        }

        fun row() = compose.onNodeWithTag("row")
        fun closed() {
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithTag("delete").fetchSemanticsNodes().isEmpty())
            assertEquals(0, deletes.get())
        }
        fun open() {
            row().performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertEquals(0, deletes.get())
            assertEquals(1, compose.onAllNodesWithTag("delete").fetchSemanticsNodes().size)
        }

        // A cancelled or short drag settles closed; an open row can be closed and opened again.
        row().performTouchInput {
            down(Offset(width * 0.8f, centerY))
            moveTo(Offset(width * 0.7f, centerY))
            cancel()
        }
        closed()
        row().performTouchInput { swipe(Offset(width * 0.8f, centerY), Offset(width * 0.72f, centerY), 300) }
        closed()
        open()
        row().performTouchInput { swipeRight() }
        closed()
        open()

        val rowBounds = row().fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithTag("delete").fetchSemanticsNode().boundsInRoot
        assertTrue("Delete must be on the physical right revealed by a left swipe", actionBounds.center.x > rowBounds.center.x)
        // Inject a real pointer at the displayed button, rather than invoking its semantics click.
        compose.onRoot().performTouchInput { click(actionBounds.center) }
        compose.waitForIdle()
        assertEquals(1, deletes.get())
        assertEquals(0, rowClicks.get())
    }
}
