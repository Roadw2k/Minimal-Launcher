package com.roadw2k.minimallauncher

import androidx.compose.foundation.clickable
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

@Composable
internal fun RightEdgeDrawerHandle(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val callback by rememberUpdatedState(onOpen)
    val label = stringResource(R.string.open_all_apps)
    // Android limits Back-gesture exclusions to 200 dp per edge. Leave bottom system
    // navigation untouched and reserve only the middle-right home-screen margin.
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
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.all_apps),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(24.dp)
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (apps == null || apps.isEmpty()) {
                item {
                    Text(
                        stringResource(if (apps == null) R.string.loading_apps else R.string.no_apps),
                        color = textColor
                    )
                }
            }
            items(apps.orEmpty(), key = { it.id }, contentType = { "app_text" }) { app ->
                LauncherAppText(app, textColor, fontSize, onLaunch)
            }
        }
    }
}

@Composable
internal fun LauncherAppText(
    app: LauncherApp,
    textColor: Color,
    fontSize: Int,
    onLaunch: (LauncherApp) -> Unit
) {
    Text(
        text = app.label,
        color = textColor,
        style = MaterialTheme.typography.headlineSmall,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 4f / 3f).sp,
        textAlign = TextAlign.End,
        modifier = Modifier.sizeIn(minHeight = 48.dp)
            .clickable(role = Role.Button) { onLaunch(app) }
            .padding(vertical = 8.dp)
    )
}
