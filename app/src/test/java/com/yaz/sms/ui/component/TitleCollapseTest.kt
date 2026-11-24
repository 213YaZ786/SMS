package com.yaz.sms.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests canScroll, the decision handed to Material 3's exitUntilCollapsedScrollBehavior, as
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AppBar.kt
// does for its own canScroll.
class TitleCollapseTest {
    // A page that fits keeps its large title on a drag.
    @Test fun pageThatFitsKeepsHeight() = assertFalse(canScroll(false))

    // A page that scrolls folds it.
    @Test fun pageThatScrollsFolds() = assertTrue(canScroll(true))

    // No scroll state given: the title folds as before.
    @Test fun noScrollStateFoldsAsBefore() = assertTrue(canScroll(null))
}
