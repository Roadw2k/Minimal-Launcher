package com.roadw2k.minimallauncher

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding


import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource

import androidx.compose.ui.semantics.Role

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import com.roadw2k.minimallauncher.ui.theme.MinimalLauncherTheme
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

internal data class LauncherApp(val id: String, val label: String)

internal fun contrastingTextColor(text: Color, background: Color?): Color = when {
    background == Color.Black && text == Color.Black -> Color.White
    background == Color.White && text == Color.White -> Color.Black
    else -> text
}

private val favoriteColors = listOf(
    "System default" to null,
    "White" to Color.White,
    "Black" to Color.Black,
    "Gray" to Color(0xFF9E9E9E),
    "Blue" to Color(0xFF64B5F6),
    "Green" to Color(0xFF81C784),
    "Yellow" to Color(0xFFFFD54F),
    "Pink" to Color(0xFFF48FB1)
)

class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    private val appChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Replacement emits remove/add/change events; query only the completed update.
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            refresh++
        }
    }

    private fun observeAppChanges() {
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val availability = IntentFilter().apply {
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
            addAction(Intent.ACTION_PACKAGES_SUSPENDED)
            addAction(Intent.ACTION_PACKAGES_UNSUSPENDED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        // Listen for the Activity lifetime, including while another app is in front,
        // so returning home can reuse the list without missing package changes.
        ContextCompat.registerReceiver(this, appChanges, packages, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, appChanges, availability, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(appChanges)
        super.onDestroy()
    }
    private var homeRequest by mutableIntStateOf(0)
    private var isDefaultHome by mutableStateOf(false)


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeRequest++
        }
    }
    private lateinit var widgets: LauncherWidgets
    private lateinit var launcherBackground: LauncherBackground

    override fun onStart() {
        super.onStart()
        widgets.startListening()
    }

    override fun onStop() {
        widgets.stopListening()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        isDefaultHome = updateLauncherSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgets = LauncherWidgets(this)
        launcherBackground = LauncherBackground(this)
        observeAppChanges()
        enableEdgeToEdge()
        isDefaultHome = updateLauncherSystemBars()
        val preferences = getSharedPreferences("favorites", MODE_PRIVATE)
        setContent {
            val solidBackground = if (launcherBackground.hasPhoto) null else launcherBackground.savedColor.color
            MinimalLauncherTheme(
                darkTheme = when (solidBackground) {
                    Color.Black -> true
                    Color.White -> false
                    else -> isSystemInDarkTheme()
                }
            ) {
                var savedIds by remember {
                    mutableStateOf(preferences.getStringSet("apps", emptySet())!!.toSet())
                }
                var configured by remember {
                    mutableStateOf(preferences.getBoolean("configured", false))
                }
                var editing by rememberSaveable { mutableStateOf(!configured) }
                var selectedIds by rememberSaveable { mutableStateOf(savedIds.toList()) }
                var settings by rememberSaveable { mutableStateOf(false) }
                var drawerOpen by rememberSaveable { mutableStateOf(false) }

                var settingsTab by rememberSaveable { mutableIntStateOf(0) }
                var editingWidgets by rememberSaveable { mutableStateOf(false) }
                var draftColor by rememberSaveable { mutableIntStateOf(preferences.getInt("text_color", 0)) }
                var textColor by remember { mutableIntStateOf(preferences.getInt("text_color", 0)) }
                var fontSize by remember {
                    mutableIntStateOf(preferences.getInt("favorite_font_size", 20).coerceIn(14, 36))
                }
                var draftFontSize by rememberSaveable { mutableIntStateOf(fontSize) }
                LaunchedEffect(homeRequest) {
                    if (homeRequest > 0) {
                        drawerOpen = false
                        settings = false
                        editingWidgets = false
                        settingsTab = 0
                        draftColor = textColor
                        draftFontSize = fontSize
                        if (configured) editing = false
                    }
                }
                LaunchedEffect(widgets.editRequested) {
                    if (widgets.editRequested) {
                        drawerOpen = false
                        editing = false
                        settings = true
                        settingsTab = 0
                        editingWidgets = true
                        widgets.consumeEditRequest()
                    }
                }
                val favoriteColor = contrastingTextColor(
                    favoriteColors.getOrNull(textColor)?.second ?: MaterialTheme.colorScheme.onBackground,
                    solidBackground
                )
                val apps by produceState<List<LauncherApp>?>(initialValue = null, refresh) {
                    value = withContext(Dispatchers.IO) {
                        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        packageManager.queryIntentActivities(intent, 0)
                            .filter { it.activityInfo.packageName != packageName }
                            .map {
                                LauncherApp(
                                    ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                                        .flattenToString(),
                                    it.loadLabel(packageManager).toString()
                                )
                            }
                            .distinctBy { it.id }
                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                    }
                }
                // At the default home screen, consume Back instead of finishing the launcher.
                BackHandler(enabled = isDefaultHome || (configured && (editing || settings || editingWidgets || drawerOpen))) {
                    when {
                        drawerOpen -> drawerOpen = false
                        editing && configured -> editing = false
                        editingWidgets -> editingWidgets = false
                        settingsTab != 0 -> settingsTab = 0
                        settings -> settings = false
                        else -> Unit
                    }
                }
                Box(Modifier.fillMaxSize()) {
                launcherBackground.Background()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) { padding ->
                    if (editing) {
                        FavoritePicker(
                            apps = apps,
                            selectedIds = selectedIds,
                            onToggle = { id ->
                                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                            },
                            onBack = if (configured) ({ editing = false }) else null,
                            onSave = {
                                savedIds = selectedIds.toSet()
                                preferences.edit().putStringSet("apps", savedIds)
                                    .putBoolean("configured", true).apply()
                                configured = true
                                editing = false
                            },
                            modifier = Modifier.padding(padding)
                        )
                    } else if (editingWidgets) {
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { editingWidgets = false }) { Text("Back to Settings") }
                                TextButton(onClick = { editingWidgets = false }) { Text("Done") }
                            }
                            Text("Drag Move to reposition a widget. Drag the bottom-right arrow to resize supported widgets.", Modifier.padding(horizontal = 24.dp))
                            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                                widgets.WidgetArea(Modifier.weight(0.6f).fillMaxSize(), editing = true)
                                Spacer(Modifier.width(12.dp))
                                Spacer(Modifier.weight(0.4f))
                            }
                        }
                    } else if (settings) {
                        Column(Modifier.fillMaxSize().padding(padding)) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Settings", style = MaterialTheme.typography.headlineMedium)
                            PrimaryTabRow(
                                selectedTabIndex = settingsTab,
                                containerColor = Color.Transparent
                            ) {
                                Tab(selected = settingsTab == 0, onClick = { settingsTab = 0 }, text = { Text("General") })
                                Tab(selected = settingsTab == 1, onClick = {
                                    if (settingsTab != 1) {
                                        draftColor = textColor
                                        draftFontSize = fontSize
                                    }
                                    settingsTab = 1
                                }, text = { Text(stringResource(R.string.appearance)) })
                            }
                            if (settingsTab == 0) {
                            TextButton(onClick = {
                                selectedIds = savedIds.toList()
                                editing = true
                            }) { Text(stringResource(R.string.edit_favorites)) }
                            TextButton(onClick = {
                                settings = false
                                widgets.addWidget()
                            }) { Text("Add widget") }
                            TextButton(onClick = { editingWidgets = true }) { Text("Edit widgets") }
                            Text("Add up to five widgets on the left side. Choose Edit widgets to move, resize, or remove them.")
                            TextButton(onClick = {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/roadw2k")))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(this@MainActivity, R.string.support_link_unavailable, Toast.LENGTH_SHORT).show()
                                } catch (_: SecurityException) {
                                    Toast.makeText(this@MainActivity, R.string.support_link_unavailable, Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(painterResource(R.drawable.ic_coffee), contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.support_roadw2k))
                            }
                              //  TextButton(onClick = { settings = false }) { Text("Back to home") }
                            } else {
                            TextButton(onClick = { settingsTab = 0 }) { Text("Back to Settings") }
                            Text("Preview", color = contrastingTextColor(
                                                            favoriteColors.getOrNull(draftColor)?.second ?: MaterialTheme.colorScheme.onBackground,
                                                            solidBackground
                                                        ),
                                style = MaterialTheme.typography.headlineSmall,
                                fontSize = draftFontSize.sp,
                                lineHeight = (draftFontSize * 4f / 3f).sp)
                            val fontSizeLabel = stringResource(R.string.favorite_font_size)
                            Text(stringResource(R.string.favorite_font_size_value, draftFontSize),
                                style = MaterialTheme.typography.titleMedium)
                            Slider(
                                value = draftFontSize.toFloat(),
                                onValueChange = { draftFontSize = it.roundToInt() },
                                valueRange = 14f..36f,
                                steps = 21,
                                modifier = Modifier.semantics { contentDescription = fontSizeLabel }
                            )
                            Text("App text color", style = MaterialTheme.typography.titleMedium)
                            favoriteColors.forEachIndexed { index, (name, color) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.RadioButton(
                                        selected = draftColor == index,
                                        onClick = {
                                            draftColor = index
                                        }
                                    )
                                    TextButton(onClick = {
                                        draftColor = index
                                    }) { Text(name, color = color ?: MaterialTheme.colorScheme.onBackground) }
                                }
                            }
                            Button(onClick = {
                                textColor = draftColor
                                fontSize = draftFontSize
                                preferences.edit().putInt("text_color", textColor)
                                    .putInt("favorite_font_size", fontSize).apply()
                                settingsTab = 0
                            }) { Text("Save text appearance") }
                            launcherBackground.Editor()
                            }
                        }

                        }
                    } else {
                        val pagerState = rememberPagerState(initialPage = if (drawerOpen) 1 else 0) { 2 }
                        LaunchedEffect(drawerOpen) {
                            pagerState.animateScrollToPage(if (drawerOpen) 1 else 0)
                        }
                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.settledPage }.drop(1).collect { page ->
                                drawerOpen = page == 1
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1
                        ) { page ->
                        if (page == 1) {
                        AppDrawer(
                            apps = apps,
                            textColor = favoriteColor,
                            fontSize = fontSize,
                            onLaunch = { app ->
                                drawerOpen = false
                                launchApp(app)
                            },

                            modifier = Modifier.padding(padding)
                        )
                    } else {
                        HoldSettingsSurface(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            onOpenSettings = {
                                settingsTab = 0
                                settings = true
                            }
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                                widgets.WidgetArea(Modifier.weight(0.6f).fillMaxSize())
                                Spacer(Modifier.width(12.dp))
                                Column(
                                    modifier = Modifier.weight(0.4f).fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                        apps.orEmpty().filter { it.id in savedIds }.forEach { app ->
                                            LauncherAppText(app, favoriteColor, fontSize, ::launchApp)
                                        }
                                        if (apps != null && apps.orEmpty().none { it.id in savedIds }) {
                                            Text(stringResource(R.string.no_favorites), color = favoriteColor)
                                        }
                                }
                            }
                            RightEdgeDrawerHandle(
                                onOpen = { drawerOpen = true },
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                        }
                        }
                    }
                }
                }
            }
        }
    }

    private fun launchApp(app: LauncherApp) {
        val component = ComponentName.unflattenFromString(app.id) ?: return
        try {
            // Keep launched apps out of the Home task, which Android excludes from Recents.
            val intent = Intent.makeMainActivity(component).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
            refresh++
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun FavoritePicker(
    apps: List<LauncherApp>?,
    selectedIds: List<String>,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("Back to Settings") }
        }
        Text(stringResource(R.string.choose_favorites), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.choose_favorites_description),
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (apps == null) {
                item { Text(stringResource(R.string.loading_apps)) }
            } else if (apps.isEmpty()) {
                item { Text(stringResource(R.string.no_apps)) }
            }
            items(apps.orEmpty(), key = { it.id }) { app ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Checkbox, onClick = { onToggle(app.id) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = app.id in selectedIds, onCheckedChange = null)
                    Text(app.label, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        Button(
            onClick = onSave,
            enabled = apps != null && apps.any { it.id in selectedIds },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text(stringResource(R.string.save_favorites)) }
    }
}
