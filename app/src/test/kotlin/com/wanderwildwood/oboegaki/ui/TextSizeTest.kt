package com.wanderwildwood.oboegaki.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSizeTest {
    @Test fun standardIsUntouched() = assertEquals(1f, TextSize.factorFor(1), 0f)
    @Test fun smallerShrinks() = assertEquals(0.75f, TextSize.factorFor(0), 0f)
    @Test fun largerGrows() = assertEquals(1.2f, TextSize.factorFor(2), 0f)
    @Test fun anUnknownLevelIsStandard() {
        assertEquals(1f, TextSize.factorFor(-1), 0f)
        assertEquals(1f, TextSize.factorFor(7), 0f)
    }
    @Test fun theLevelsRunSmallToLarge() = assertTrue(TextSize.factorFor(0) < TextSize.factorFor(1) && TextSize.factorFor(1) < TextSize.factorFor(2))
}

class SideWaysTest {
    @Test fun theKompaktUprightIsNotShort() = assertEquals(false, isShortWindow(600))
    @Test fun theKompaktOnItsSideIsShort() = assertEquals(true, isShortWindow(360))
    @Test fun aDialogListKeepsItsHeightWhenThereIsRoom() = assertEquals(440, dialogListHeightDp(440, 600))
    @Test fun aDialogListShrinksOnItsSide() = assertEquals(240, dialogListHeightDp(440, 360))
    @Test fun aDialogListIsNeverTinier() = assertEquals(120, dialogListHeightDp(440, 200))
}
