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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
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
import org.json.JSONArray

internal data class LauncherApp(val id: String, val label: String)

internal val DrawerAlignment.horizontalAlignment: Alignment.Horizontal
    get() = when (this) {
        DrawerAlignment.LEFT -> AbsoluteAlignment.Left
        DrawerAlignment.CENTER -> Alignment.CenterHorizontally
        DrawerAlignment.RIGHT -> AbsoluteAlignment.Right
    }

internal val DrawerAlignment.textAlign: TextAlign
    get() = when (this) {
        DrawerAlignment.LEFT -> TextAlign.Left
        DrawerAlignment.CENTER -> TextAlign.Center
        DrawerAlignment.RIGHT -> TextAlign.Right
    }

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
                var savedOrder by remember {
                    mutableStateOf(runCatching {
                        val array = JSONArray(preferences.getString("favorite_order", "[]"))
                        (0 until array.length()).mapNotNull { index ->
                            (array.opt(index) as? String)?.takeIf { it.isNotEmpty() }
                        }.distinct()
                    }.getOrDefault(emptyList()))
                }
                var configured by remember {
                    mutableStateOf(preferences.getBoolean("configured", false))
                }
                var editing by rememberSaveable { mutableStateOf(!configured) }
                var selectedIds by rememberSaveable { mutableStateOf(savedIds.toList()) }
                var draftOrderInitialized by rememberSaveable { mutableStateOf(false) }
                var settings by rememberSaveable { mutableStateOf(false) }
                var launcherPage by rememberSaveable { mutableIntStateOf(1) }
                val drawerOpen = launcherPage == 2
                val dailyUsageCache = rememberDailyUsageCache()

                var settingsTab by rememberSaveable { mutableIntStateOf(0) }
                var editingWidgets by rememberSaveable { mutableStateOf(false) }
                var draftColor by rememberSaveable { mutableIntStateOf(preferences.getInt("text_color", 0)) }
                var textColor by remember { mutableIntStateOf(preferences.getInt("text_color", 0)) }
                var fontSize by remember {
                    mutableIntStateOf(preferences.getInt("favorite_font_size", 20).coerceIn(14, 36))
                }
                var draftFontSize by rememberSaveable { mutableIntStateOf(fontSize) }
                var favoritesOnLeft by remember {
                    mutableStateOf(preferences.getBoolean("favorites_on_left", false))
                }
                var drawerAlignment by remember {
                    mutableStateOf(DrawerAlignment.fromPreference(
                        preferences.getString("app_drawer_alignment", "right")
                    ))
                }
                val contentLayoutDirection = LocalLayoutDirection.current
                LaunchedEffect(homeRequest) {
                    if (homeRequest > 0) {
                        launcherPage = 1
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
                        launcherPage = 1
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
                val draftIds = normalizeFavoriteOrder(
                    selectedIds, if (draftOrderInitialized) selectedIds else savedOrder, apps.orEmpty()
                )
                LaunchedEffect(apps, editing) {
                    if (editing && apps != null && !draftOrderInitialized) {
                        selectedIds = draftIds
                        draftOrderInitialized = true
                    }
                }
                val homeFavorites = remember(apps, savedIds, savedOrder) {
                    val appsById = apps.orEmpty().associateBy { it.id }
                    normalizeFavoriteOrder(savedIds, savedOrder, apps.orEmpty()).mapNotNull(appsById::get)
                }
                // At the default home screen, consume Back instead of finishing the launcher.
                BackHandler(enabled = isDefaultHome || (configured && (editing || settings || editingWidgets || launcherPage != 1))) {
                    when {
                        launcherPage != 1 -> launcherPage = 1
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
                            selectedIds = draftIds,
                            onToggle = { id ->
                                selectedIds = if (id in draftIds) draftIds - id else draftIds + id
                                draftOrderInitialized = true
                            },
                            onMove = { id, offset ->
                                selectedIds = moveFavorite(draftIds, id, offset)
                                draftOrderInitialized = true
                            },
                            onBack = if (configured) ({ editing = false }) else null,
                            onSave = {
                                savedOrder = draftIds
                                savedIds = draftIds.toSet()
                                preferences.edit().putStringSet("apps", savedIds)
                                    .putString("favorite_order", JSONArray(savedOrder).toString())
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
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                                    if (favoritesOnLeft) {
                                        Spacer(Modifier.weight(0.4f))
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    CompositionLocalProvider(LocalLayoutDirection provides contentLayoutDirection) {
                                        widgets.WidgetArea(Modifier.weight(0.6f).fillMaxSize(), editing = true)
                                    }
                                    if (!favoritesOnLeft) {
                                        Spacer(Modifier.width(12.dp))
                                        Spacer(Modifier.weight(0.4f))
                                    }
                                }
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
                                selectedIds = normalizeFavoriteOrder(savedIds, savedOrder, apps.orEmpty())
                                draftOrderInitialized = apps != null
                                editing = true
                            }) { Text(stringResource(R.string.edit_favorites)) }
                            TextButton(onClick = {
                                settings = false
                                widgets.addWidget()
                            }) { Text("Add widget") }
                            TextButton(onClick = { editingWidgets = true }) { Text("Edit widgets") }
                            Text(stringResource(R.string.widget_layout_help))
                            Text(stringResource(R.string.home_layout), style = MaterialTheme.typography.titleMedium)
                            Column(Modifier.selectableGroup()) {
                                listOf(
                                    true to R.string.favorites_left_widgets_right,
                                    false to R.string.favorites_right_widgets_left
                                ).forEach { (onLeft, label) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().selectable(
                                            selected = favoritesOnLeft == onLeft,
                                            role = Role.RadioButton,
                                            onClick = {
                                                favoritesOnLeft = onLeft
                                                preferences.edit().putBoolean("favorites_on_left", onLeft).apply()
                                            }
                                        ).padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = favoritesOnLeft == onLeft, onClick = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(label))
                                    }
                                }
                            }
                            Text(stringResource(R.string.app_drawer_alignment), style = MaterialTheme.typography.titleMedium)
                            Column(Modifier.selectableGroup()) {
                                listOf(
                                    DrawerAlignment.LEFT to R.string.position_left,
                                    DrawerAlignment.CENTER to R.string.position_center,
                                    DrawerAlignment.RIGHT to R.string.position_right
                                ).forEach { (alignment, label) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().selectable(
                                            selected = drawerAlignment == alignment,
                                            role = Role.RadioButton,
                                            onClick = {
                                                drawerAlignment = alignment
                                                preferences.edit().putString(
                                                    "app_drawer_alignment", alignment.preferenceValue
                                                ).apply()
                                            }
                                        ).padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = drawerAlignment == alignment, onClick = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(label))
                                    }
                                }
                            }
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
                        val versionName = remember {
                            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                        }
                        Text(
                            text = "Version $versionName",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(AbsoluteAlignment.Left)
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                        }
                    } else {
                        val pagerState = rememberPagerState(initialPage = launcherPage) { 3 }
                        LaunchedEffect(launcherPage) {
                            pagerState.animateScrollToPage(launcherPage)
                        }
                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.settledPage }.drop(1).collect { page ->
                                launcherPage = page
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1
                        ) { page ->
                        if (page == 0) {
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                DailyUsagePage(favoriteColor, fontSize, dailyUsageCache)
                            }
                        } else if (page == 2) {
                        AppDrawer(
                            apps = apps,
                            textColor = favoriteColor,
                            fontSize = fontSize,
                            alignment = drawerAlignment,
                            isOpen = drawerOpen,
                            onLaunch = { app ->
                                launcherPage = 1
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
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                                    if (!favoritesOnLeft) {
                                        CompositionLocalProvider(LocalLayoutDirection provides contentLayoutDirection) {
                                            widgets.WidgetArea(Modifier.weight(0.6f).fillMaxSize())
                                        }
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Column(
                                        modifier = Modifier.weight(0.4f).fillMaxSize()
                                            .verticalScroll(rememberScrollState()),
                                        horizontalAlignment = if (favoritesOnLeft) Alignment.Start else Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CompositionLocalProvider(LocalLayoutDirection provides contentLayoutDirection) {
                                            val favoriteTextAlign = if (favoritesOnLeft) TextAlign.Left else TextAlign.Right
                                            homeFavorites.forEach { app ->
                                                LauncherAppText(app, favoriteColor, fontSize, ::launchApp,
                                                    textAlign = favoriteTextAlign)
                                            }
                                            if (apps != null && homeFavorites.isEmpty()) {
                                                Text(stringResource(R.string.no_favorites), color = favoriteColor,
                                                    textAlign = favoriteTextAlign)
                                            }
                                        }
                                    }
                                    if (favoritesOnLeft) {
                                        Spacer(Modifier.width(12.dp))
                                        CompositionLocalProvider(LocalLayoutDirection provides contentLayoutDirection) {
                                            widgets.WidgetArea(Modifier.weight(0.6f).fillMaxSize())
                                        }
                                    }
                                }
                            }
                            RightEdgeDrawerHandle(
                                onOpen = { launcherPage = 0 },
                                modifier = Modifier.align(Alignment.CenterStart),
                                label = "Open daily usage"
                            )
                            RightEdgeDrawerHandle(
                                onOpen = { launcherPage = 2 },
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
    onMove: (String, Int) -> Unit,
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
        Text(stringResource(R.string.favorite_order_help))
        val appsById = remember(apps) { apps.orEmpty().associateBy { it.id } }
        val selected = selectedIds.toSet()
        val selectedApps = selectedIds.mapNotNull(appsById::get)
        val selectedPositions = selectedApps.mapIndexed { index, app -> app.id to index }.toMap()
        val pickerApps = selectedApps + apps.orEmpty().filter { it.id !in selected }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        val listState = rememberLazyListState()
        var draggingId by remember { mutableStateOf<String?>(null) }
        var pointerRootY by remember { mutableStateOf(0f) }
        var listRootY by remember { mutableStateOf(0f) }
        val currentSelectedApps by rememberUpdatedState(selectedApps)
        val currentSelectedIds by rememberUpdatedState(selectedIds)
        val currentOnMove by rememberUpdatedState(onMove)
        val edgeSize = with(LocalDensity.current) { 56.dp.toPx() }
        val maxScrollStep = with(LocalDensity.current) { 12.dp.toPx() }

        // One bounded scroll/reorder step per frame; cancellation follows the drag lifetime.
        LaunchedEffect(draggingId) {
            val id = draggingId ?: return@LaunchedEffect
            while (isActive) {
                if (currentSelectedApps.none { it.id == id }) {
                    draggingId = null
                    break
                }
                val layout = listState.layoutInfo
                val y = pointerRootY - listRootY
                val top = layout.viewportStartOffset.toFloat()
                val bottom = layout.viewportEndOffset.toFloat()
                val edge = minOf(edgeSize, (bottom - top).coerceAtLeast(0f) / 2f)
                val scroll = when {
                    edge <= 0f -> 0f
                    y < top + edge -> -maxScrollStep * ((top + edge - y) / edge).coerceIn(0f, 1f)
                    y > bottom - edge -> maxScrollStep * ((y - bottom + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (scroll != 0f) listState.scrollBy(scroll)
                val positions = currentSelectedApps.mapIndexed { index, app -> app.id to index }.toMap()
                val source = positions[id]
                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    item.key in positions && y >= item.offset && y < item.offset + item.size
                }
                val targetId = target?.key as? String
                val destination = positions[targetId]
                if (target != null && source != null && destination != null && source != destination &&
                    ((destination > source && y > target.offset + target.size / 2f) ||
                        (destination < source && y < target.offset + target.size / 2f))) {
                    // Persisted IDs may also contain unavailable apps; use their actual offsets.
                    val ids = currentSelectedIds
                    currentOnMove(id, ids.indexOf(targetId) - ids.indexOf(id))
                }
                delay(16)
            }
        }
        LazyColumn(
            state = listState,
            userScrollEnabled = draggingId == null,
            modifier = Modifier.weight(1f).onGloballyPositioned { listRootY = it.positionInRoot().y }
        ) {
            if (apps == null) {
                item { Text(stringResource(R.string.loading_apps)) }
            } else if (apps.isEmpty()) {
                item { Text(stringResource(R.string.no_apps)) }
            }
            items(pickerApps, key = { it.id }) { app ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            if (draggingId == app.id) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).clickable(
                            enabled = draggingId == null,
                            role = Role.Checkbox,
                            onClick = { onToggle(app.id) }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = app.id in selectedIds, onCheckedChange = null)
                        Text(app.label, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    }
                    val position = selectedPositions[app.id]
                    if (position != null) {
                        val upDescription = stringResource(R.string.move_favorite_up, app.label)
                        val downDescription = stringResource(R.string.move_favorite_down, app.label)
                        val dragDescription = stringResource(R.string.drag_favorite, app.label)
                        var handleRootY by remember(app.id) { mutableStateOf(0f) }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .onGloballyPositioned { handleRootY = it.positionInRoot().y }
                                .semantics {
                                    contentDescription = dragDescription
                                    customActions = buildList {
                                        if (position > 0) add(CustomAccessibilityAction(upDescription) {
                                            if (draggingId != null) false else {
                                                currentOnMove(app.id, -1)
                                                true
                                            }
                                        })
                                        if (position < selectedApps.lastIndex) add(CustomAccessibilityAction(downDescription) {
                                            if (draggingId != null) false else {
                                                currentOnMove(app.id, 1)
                                                true
                                            }
                                        })
                                    }
                                }
                                .pointerInput(app.id) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            pointerRootY = handleRootY + offset.y
                                            draggingId = app.id
                                        },
                                        onDragEnd = { draggingId = null },
                                        onDragCancel = { draggingId = null },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            pointerRootY += amount.y
                                        }
                                    )
                                }
                        ) { Text("☰", fontSize = 24.sp) }
                    }
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
