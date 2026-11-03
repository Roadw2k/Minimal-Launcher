package com.roadw2k.minimallauncher

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class TextContrastTest {
    @Test
    fun matchingBlackAndWhiteAreInverted() {
        assertEquals(Color.White, contrastingTextColor(Color.Black, Color.Black))
        assertEquals(Color.Black, contrastingTextColor(Color.White, Color.White))
    }

    @Test
    fun otherSelectionsArePreserved() {
        assertEquals(Color.White, contrastingTextColor(Color.White, Color.Black))
        assertEquals(Color.Black, contrastingTextColor(Color.Black, Color.White))
        assertEquals(Color.Red, contrastingTextColor(Color.Red, Color.Black))
        assertEquals(Color.White, contrastingTextColor(Color.White, Color.Gray))
        assertEquals(Color.Black, contrastingTextColor(Color.Black, null))
    }
}
