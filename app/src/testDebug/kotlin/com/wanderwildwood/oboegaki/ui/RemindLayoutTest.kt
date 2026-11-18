package com.wanderwildwood.oboegaki.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Remind dialog at the Kompakt's two window sizes: 800x480 pixels at 4/3 density is
 * 600x360 dp sideways and 360x600 dp upright, less the 24 dp status bar. The dialog's panel
 * is drawn as EInkDialog draws it: 20 dp in from each side, a 2 dp rim, 14 dp and 18 dp inside.
 * Every case is the fullest the dialog gets on the phone: a reminder already set (so Take the
 * reminder off shows), the calendar link, the alarms warning and its button, and "That time has
 * gone by".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RemindLayoutTest {

    @get:Rule val rule = createComposeRule()

    private val statusBar = 24.dp

    /** How far the lowest of the dialog's controls runs past the window's foot, in dp; 0 or less fits. */
    private fun overrun(width: Dp, height: Dp, sideways: Boolean, textScale: Float = 1f): Float {
        val window = height - statusBar
        rule.setContent {
            val density = LocalDensity.current
            // The app's text size scales the text alone, as MainActivity does.
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
                ThemeMMD(colorScheme = monochrome, typography = houseTypography) {
                    Box(Modifier.size(width, window)) {
                        Surface(
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 18.dp)) {
                                RemindLayout(
                                    heading = "Water the tomatoes",
                                    day = "Tomorrow",
                                    time = "18:30",
                                    note = "It rings on this phone, not on any other the note is on.",
                                    exact = false,
                                    fullScreen = true,
                                    past = true,
                                    setLabel = "Set",
                                    sideways = sideways,
                                    onPickDay = {}, onPickTime = {}, onAllowExact = {}, onAllowFullScreen = {},
                                    onCancel = {}, onSet = {}, onRemove = {}, onCalendar = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        // The panel's own foot (18 dp padding and the 2 dp rim) must fit too.
        val foot = listOf(REMIND_SET, REMIND_CANCEL, REMIND_REMOVE, REMIND_CALENDAR)
            .maxOf { rule.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot().bottom.value } + 20f
        return foot - window.value
    }

    @Test fun uprightTheOneColumnFits() {
        val over = overrun(360.dp, 600.dp, sideways = false)
        assertTrue("upright, the dialog runs $over dp past the window", over <= 0f)
    }

    @Test fun sidewaysTheOneColumnRunsPastTheFoot() {
        // What 0.3.10 drew: the reason the dialog has a sideways shape.
        val over = overrun(600.dp, 360.dp, sideways = false)
        assertTrue("sideways, the one column was expected not to fit; it is $over dp", over > 0f)
    }

    @Test fun sidewaysTheTwoColumnsFit() {
        val over = overrun(600.dp, 360.dp, sideways = true)
        assertTrue("sideways, the dialog runs $over dp past the window", over <= 0f)
    }

    @Test fun sidewaysTheTwoColumnsFitAtLarger() {
        val over = overrun(600.dp, 360.dp, sideways = true, textScale = TextSize.LARGER)
        assertTrue("sideways at Larger, the dialog runs $over dp past the window", over <= 0f)
    }

    @Test fun theShortWindowIsTheSidewaysOne() {
        assertTrue(isShortWindow(360))
        assertTrue(!isShortWindow(600))
    }
}
