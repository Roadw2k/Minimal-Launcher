package com.roadw2k.minimallauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerSearchTest {
    private val apps = listOf(
        LauncherApp("calendar", "Calendar"),
        LauncherApp("camera", "Camera"),
        LauncherApp("messages", "Messages")
    )

    @Test
    fun emptyOrWhitespaceQueryShowsAllApps() {
        assertEquals(apps, filterDrawerApps(apps, ""))
        assertEquals(apps, filterDrawerApps(apps, "  "))
    }

    @Test
    fun searchMatchesPartialNamesIgnoringCaseAndSurroundingWhitespace() {
        assertEquals(apps.take(2), filterDrawerApps(apps, "  CA  "))
        assertEquals(listOf(apps.last()), filterDrawerApps(apps, "SAG"))
    }

    @Test
    fun unmatchedQueryAndEmptyAppListReturnNoResults() {
        assertEquals(emptyList<LauncherApp>(), filterDrawerApps(apps, "missing"))
        assertEquals(emptyList<LauncherApp>(), filterDrawerApps(emptyList(), "camera"))
    }
}
