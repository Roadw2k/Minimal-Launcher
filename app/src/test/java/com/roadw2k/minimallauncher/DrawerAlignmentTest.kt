package com.roadw2k.minimallauncher

import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerAlignmentTest {
    @Test
    fun savedValuesRoundTrip() {
        DrawerAlignment.entries.forEach { alignment ->
            assertEquals(alignment, DrawerAlignment.fromPreference(alignment.preferenceValue))
        }
    }

    @Test
    fun missingOrUnknownValuesKeepDefaultRightAlignment() {
        assertEquals(DrawerAlignment.RIGHT, DrawerAlignment.fromPreference(null))
        assertEquals(DrawerAlignment.RIGHT, DrawerAlignment.fromPreference("unknown"))
    }

    @Test
    fun positionsUseMatchingPhysicalTextAndLayoutAlignment() {
        assertEquals(AbsoluteAlignment.Left, DrawerAlignment.LEFT.horizontalAlignment)
        assertEquals(Alignment.CenterHorizontally, DrawerAlignment.CENTER.horizontalAlignment)
        assertEquals(AbsoluteAlignment.Right, DrawerAlignment.RIGHT.horizontalAlignment)
        assertEquals(TextAlign.Left, DrawerAlignment.LEFT.textAlign)
        assertEquals(TextAlign.Center, DrawerAlignment.CENTER.textAlign)
        assertEquals(TextAlign.Right, DrawerAlignment.RIGHT.textAlign)
    }
}
