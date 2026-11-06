package com.roadw2k.minimallauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun filterDrawerApps(apps: List<LauncherApp>, query: String): List<LauncherApp> {
    val search = query.trim()
    return if (search.isEmpty()) apps else apps.filter { it.label.contains(search, ignoreCase = true) }
}

internal enum class DrawerAlignment(val preferenceValue: String) {
    LEFT("left"), CENTER("center"), RIGHT("right");

    companion object {
        fun fromPreference(value: String?): DrawerAlignment =
            entries.firstOrNull { it.preferenceValue == value } ?: RIGHT
    }
}

@Composable
internal fun RightEdgeDrawerHandle(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.open_all_apps)
) {
    val callback by rememberUpdatedState(onOpen)
    // Android limits Back-gesture exclusions to 200 dp per edge. Leave bottom system
    // navigation untouched and reserve only the middle home-screen edge margin.
    Box(
        modifier.width(24.dp).height(200.dp)
            .systemGestureExclusion()
            .semantics {
                contentDescription = label
                onClick(label) { callback(); true }
            }

    )
}

@Composable
internal fun AppDrawer(
    apps: List<LauncherApp>?,
    onLaunch: (LauncherApp) -> Unit,
    textColor: Color,
    fontSize: Int,
    alignment: DrawerAlignment = DrawerAlignment.RIGHT,
    modifier: Modifier = Modifier,
    isOpen: Boolean = true
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val filteredApps = remember(apps, query) { filterDrawerApps(apps.orEmpty(), query) }
    LaunchedEffect(isOpen) {
        if (!isOpen) {
            query = ""
            focusManager.clearFocus()
        }
    }
    LaunchedEffect(query) { listState.scrollToItem(0) }
    Column(modifier.fillMaxSize().imePadding()) {
        Text(
            stringResource(R.string.all_apps),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(24.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_apps)) },
            singleLine = true,
            trailingIcon = if (query.isNotEmpty()) ({
                TextButton(onClick = { query = "" }) { Text(stringResource(R.string.clear_search)) }
            }) else null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = alignment.horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (apps == null || filteredApps.isEmpty()) {
                item {
                    Text(
                        stringResource(when {
                                                    apps == null -> R.string.loading_apps
                                                    apps.isEmpty() -> R.string.no_apps
                                                    else -> R.string.no_matching_apps
                                                }),
                        color = textColor,
                        textAlign = alignment.textAlign
                    )
                }
            }
            items(filteredApps, key = { it.id }, contentType = { "app_text" }) { app ->
                LauncherAppText(app, textColor, fontSize, onLaunch, textAlign = alignment.textAlign)
            }
        }
    }
}

@Composable
internal fun LauncherAppText(
    app: LauncherApp,
    textColor: Color,
    fontSize: Int,
    onLaunch: (LauncherApp) -> Unit,
    textAlign: TextAlign = TextAlign.End
) {
    Text(
        text = app.label,
        color = textColor,
        style = MaterialTheme.typography.headlineSmall,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 4f / 3f).sp,
        textAlign = textAlign,
        modifier = Modifier.sizeIn(minHeight = 48.dp)
            .clickable(role = Role.Button) { onLaunch(app) }
            .padding(vertical = 8.dp)
    )
}
