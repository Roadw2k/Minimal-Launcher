package com.roadw2k.minimallauncher

internal fun normalizeFavoriteOrder(
    selectedIds: Collection<String>,
    preferredOrder: List<String>,
    apps: List<LauncherApp>
): List<String> {
    val selected = selectedIds.toSet()
    val available = apps.mapTo(HashSet()) { it.id }
    val preferred = preferredOrder.filter { it in selected }.distinct()
    val ordered = preferred.filter { it in available }
    val remaining = selected - preferred.toSet()
    val alphabetical = apps.sortedWith(
        compareBy<LauncherApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.id }
    ).map { it.id }.filter { it in remaining }.distinct()
    // Missing apps remain selected, but never interrupt the visible favorites.
    val unavailable = preferred.filter { it !in available } +
        remaining.filter { it !in available }.sorted()
    return ordered + alphabetical + unavailable
}

internal fun moveFavorite(ids: List<String>, id: String, offset: Int): List<String> {
    val index = ids.indexOf(id)
    val target = index.toLong() + offset.toLong()
    if (index < 0 || target < 0 || target >= ids.size || offset == 0) return ids
    return ids.toMutableList().apply {
        removeAt(index)
        add(target.toInt(), id)
    }
}
