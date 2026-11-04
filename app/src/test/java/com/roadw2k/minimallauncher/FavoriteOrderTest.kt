package com.roadw2k.minimallauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteOrderTest {
    private val apps = listOf(
        LauncherApp("c", "Charlie"),
        LauncherApp("a", "alpha"),
        LauncherApp("b", "Bravo")
    )

    @Test
    fun legacySetUsesAlphabeticalOrderNotSetIterationOrder() {
        assertEquals(listOf("a", "b", "c"), normalizeFavoriteOrder(
            linkedSetOf("c", "b", "a"), emptyList(), apps
        ))
    }

    @Test
    fun migratedOrderSurvivesAddingAndReopening() {
        val migrated = normalizeFavoriteOrder(setOf("b", "a"), emptyList(), apps)
        val added = migrated + "c"
        assertEquals(listOf("a", "b", "c"), normalizeFavoriteOrder(added.toSet(), added, apps))
        val appended = listOf("b", "c") + "a"
        assertEquals(appended, normalizeFavoriteOrder(appended.toSet(), appended, apps))
    }

    @Test
    fun preferredOrderIsDeduplicatedAndUnselectedIdsAreRemoved() {
        assertEquals(listOf("c", "a", "b"), normalizeFavoriteOrder(
            listOf("a", "b", "c", "a"), listOf("c", "removed", "c", "a"), apps
        ))
    }

    @Test
    fun unavailableSelectionsArePreservedAtEndDeterministically() {
        assertEquals(listOf("b", "a", "missing-z", "missing-a"), normalizeFavoriteOrder(
            setOf("missing-a", "a", "missing-z", "b"), listOf("missing-z", "b"), apps
        ))
        assertEquals(listOf("a", "missing-a", "missing-z"), normalizeFavoriteOrder(
            setOf("missing-z", "a", "missing-a"), emptyList(), apps
        ))
    }

    @Test
    fun unavailableSelectionsSurviveEmptyAppListAndReturn() {
        val order = listOf("b", "a")
        val unavailable = normalizeFavoriteOrder(order.toSet(), order, emptyList())
        assertEquals(order, unavailable)
        assertEquals(order, normalizeFavoriteOrder(order.toSet(), unavailable, apps))
    }

    @Test
    fun removingFavoriteDoesNotReorderRemainingFavorites() {
        assertEquals(listOf("c", "a"), normalizeFavoriteOrder(
            setOf("a", "c"), listOf("c", "b", "a"), apps
        ))
    }

    @Test
    fun movesPreserveOtherItemsAndDoNotMutateInput() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), moveFavorite(ids, "b", -1))
        assertEquals(listOf("a", "c", "b"), moveFavorite(ids, "b", 1))
        assertEquals(listOf("b", "c", "a"), moveFavorite(ids, "a", 2))
        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun invalidMovesAreSafeIncludingOverflow() {
        val ids = listOf("a", "b", "c")
        assertEquals(ids, moveFavorite(ids, "a", -1))
        assertEquals(ids, moveFavorite(ids, "c", 1))
        assertEquals(ids, moveFavorite(ids, "b", 0))
        assertEquals(ids, moveFavorite(ids, "unknown", 1))
        assertEquals(ids, moveFavorite(ids, "b", Int.MAX_VALUE))
        assertEquals(ids, moveFavorite(ids, "b", Int.MIN_VALUE))
        assertEquals(emptyList<String>(), moveFavorite(emptyList(), "a", 1))
    }
}
