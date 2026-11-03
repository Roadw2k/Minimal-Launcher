package com.roadw2k.minimallauncher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Coordinates are fractions of the available travel, not of the entire container. */
data class LauncherWidgetEntry(
    val id: Int,
    val x: Float = 0f,
    val y: Float = 0f,
    val widthFraction: Float = 0.92f,
    val heightFraction: Float = 0f
)

/** Construct once in onCreate, before STARTED; forward onStart/onStop to listening methods. */
class LauncherWidgets(private val activity: ComponentActivity) {
    private val preferences = activity.getSharedPreferences("launcher_widgets", Context.MODE_PRIVATE)
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity, HOST_ID)
    var entries: List<LauncherWidgetEntry> by mutableStateOf(emptyList())
        private set
    private var pendingId = preferences.getInt("pending", AppWidgetManager.INVALID_APPWIDGET_ID)
    private var listening = false
    var editRequested by mutableStateOf(false)
        private set

    fun consumeEditRequest() {
        editRequested = false
    }

    // Explicit keys remain stable even if other activity result registrations change order.
    private val picker = activity.activityResultRegistry.register(
        "launcher_widgets.pick", activity, ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (result.resultCode == Activity.RESULT_OK) bindOrConfigure(result.data) else cancelPending()
        }
    }
    private val binder = activity.activityResultRegistry.register(
        "launcher_widgets.bind", activity, ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (result.resultCode == Activity.RESULT_OK) configure() else cancelPending()
        }
    }
    private val configurator = activity.activityResultRegistry.register(
        "launcher_widgets.configure", activity, ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (result.resultCode == Activity.RESULT_OK) finishAdding() else cancelPending()
        }
    }

    init {
        entries = preferences.getStringSet("ids", emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }.sorted()
            .map { id ->
                LauncherWidgetEntry(
                    id, coordinate(id, "x"), coordinate(id, "y"),
                    savedSize(id, "width", 0.92f), savedSize(id, "height", 0f)
                )
            }
        if (entries.any { it.id == pendingId }) {
            pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
            preferences.edit().remove("pending").commit()
        }
        // Reclaim allocations left behind by interrupted writes, but preserve the in-flight picker.
        host.appWidgetIds.filter { id -> id != pendingId && entries.none { it.id == id } }
            .forEach { deleteId(it) }
    }

    fun addWidget() {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            message("Finish selecting the current widget first")
            return
        }
        if (entries.size >= MAX_WIDGETS) {
            message("Remove a widget before adding another (maximum five)")
            return
        }
        try {
            pendingId = host.allocateAppWidgetId()
            preferences.edit().putInt("pending", pendingId).commit()
            picker.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId))
        } catch (_: ActivityNotFoundException) {
            fail("No widget picker is available on this device")
        } catch (_: SecurityException) {
            fail("Widget selection is not permitted")
        }
    }

    fun removeWidget(id: Int) {
        if (id == pendingId) cancelPending()
        entries = entries.filterNot { it.id == id }
        persist()
        preferences.edit().remove("$id.x").remove("$id.y")
            .remove("$id.width").remove("$id.height").apply()
        deleteId(id)
    }

    fun startListening() {
        if (listening) return
        try {
            host.startListening()
            listening = true
        } catch (_: SecurityException) {
            message("Widget updates are not permitted")
        }
    }

    fun stopListening() {
        if (!listening) return
        try {
            host.stopListening()
        } catch (_: SecurityException) {
            // The host may have lost permission while the activity was visible.
        } finally {
            listening = false
        }
    }

    private fun bindOrConfigure(data: Intent?) {
        // Always use our allocated ID, never an ID supplied by the picker.
        if (providerAvailable(pendingId)) {
            configure()
            return
        }
        @Suppress("DEPRECATION")
        val provider = data?.getParcelableExtra<android.content.ComponentName>(
            AppWidgetManager.EXTRA_APPWIDGET_PROVIDER
        )
        if (provider == null) {
            fail("The selected widget provider is unavailable or could not be bound")
            return
        }
        try {
            if (manager.bindAppWidgetIdIfAllowed(pendingId, provider)) {
                configure()
            } else {
                binder.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider))
            }
        } catch (_: ActivityNotFoundException) {
            fail("Widget binding is unavailable")
        } catch (_: SecurityException) {
            fail("Widget binding is not permitted")
        }
    }

    private fun configure() {
        try {
            val info = manager.getAppWidgetInfo(pendingId)
            if (info == null) {
                fail("The selected widget is no longer available")
                return
            }
            if (info.configure != null) {
                configurator.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(info.configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId))
            } else {
                finishAdding()
            }
        } catch (_: ActivityNotFoundException) {
            fail("The widget configuration screen is unavailable")
        } catch (_: SecurityException) {
            fail("Widget binding or configuration is not permitted")
        }
    }

    private fun finishAdding() {
        if (!providerAvailable(pendingId)) {
            fail("The selected widget is no longer available")
            return
        }
        if (entries.size >= MAX_WIDGETS) {
            cancelPending()
            return
        }
        val positions = listOf(0f, 0.5f, 1f, 0.25f, 0.75f)
        val initialY = positions.maxBy { position ->
            entries.minOfOrNull { kotlin.math.abs(it.y - position) } ?: 1f
        }
        entries = entries + LauncherWidgetEntry(pendingId, y = initialY)
        // Save the accepted ID before clearing pending so process death cannot orphan it.
        persist()
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        preferences.edit().remove("pending").commit()
        editRequested = true
    }

    private fun providerAvailable(id: Int): Boolean = try {
        manager.getAppWidgetInfo(id) != null
    } catch (_: SecurityException) {
        false
    }

    private fun cancelPending() {
        if (entries.none { it.id == pendingId }) deleteId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        preferences.edit().remove("pending").commit()
    }

    private fun deleteId(id: Int) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        try {
            host.deleteAppWidgetId(id)
        } catch (_: SecurityException) {
            message("The system could not release this widget")
        }
    }

    private fun fail(text: String) { cancelPending(); message(text) }
    private fun message(text: String) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    private fun coordinate(id: Int, axis: String): Float =
        preferences.getFloat("$id.$axis", 0f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }

    private fun savedSize(id: Int, axis: String, default: Float): Float =
        preferences.getFloat("$id.$axis", default).let {
            if (it.isFinite() && it > 0f) it.coerceAtMost(1f) else default
        }

    private fun persist() {
        val editor = preferences.edit().putStringSet("ids", entries.map { it.id.toString() }.toSet())
        entries.forEach {
            editor.putFloat("${it.id}.x", it.x).putFloat("${it.id}.y", it.y)
                .putFloat("${it.id}.width", it.widthFraction)
                .putFloat("${it.id}.height", it.heightFraction)
        }
        editor.commit()
    }

    @Composable
    fun WidgetArea(modifier: Modifier = Modifier, editing: Boolean = false) {
        val widgetIds = entries.map { it.id }
        var providerInfos by remember(this, widgetIds) {
            mutableStateOf<Map<Int, AppWidgetProviderInfo?>>(emptyMap())
        }
        DisposableEffect(this, widgetIds) {
            fun refresh() {
                providerInfos = widgetIds.associateWith { id ->
                    try { manager.getAppWidgetInfo(id) } catch (_: SecurityException) { null }
                }
            }
            val lifecycle = activity.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refresh()
            }
            // Adding an observer to a resumed activity immediately delivers ON_RESUME.
            lifecycle.addObserver(observer)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refresh()
            onDispose { lifecycle.removeObserver(observer) }
        }
        BoxWithConstraints(modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val areaWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val areaHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val defaultHeight = with(density) { (maxHeight / 3 - 48.dp).toPx() }
                .coerceIn(1f, areaHeight)
            entries.forEach { entry ->
                key(entry.id) {
                    val info = providerInfos[entry.id]
                    val reportedSizes = remember { WeakHashMap<AppWidgetHostView, android.util.SizeF>() }
                    val horizontal = info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
                    val vertical = info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
                    // Provider dimensions are pixels; persist fractions so layouts adapt to screen changes.
                    val minWidth = (if (horizontal) info?.minResizeWidth else info?.minWidth)
                        ?.takeIf { it > 0 }?.toFloat()?.coerceIn(1f, areaWidth)
                        ?: with(density) { 64.dp.toPx() }.coerceAtMost(areaWidth)
                    val minHeight = (if (vertical) info?.minResizeHeight else info?.minHeight)
                        ?.takeIf { it > 0 }?.toFloat()?.coerceIn(1f, areaHeight)
                        ?: with(density) { 64.dp.toPx() }.coerceAtMost(areaHeight)
                    val maxWidgetWidth = info?.maxResizeWidth?.takeIf { horizontal && it > 0 }
                        ?.toFloat()?.coerceIn(minWidth, areaWidth) ?: areaWidth
                    val maxWidgetHeight = info?.maxResizeHeight?.takeIf { vertical && it > 0 }
                        ?.toFloat()?.coerceIn(minHeight, areaHeight) ?: areaHeight
                    val widthPx = (areaWidth * entry.widthFraction).coerceIn(minWidth, maxWidgetWidth)
                    val heightPx = (if (entry.heightFraction > 0f) areaHeight * entry.heightFraction else defaultHeight)
                        .coerceIn(minHeight, maxWidgetHeight)
                    val widgetWidth = with(density) { widthPx.toDp() }
                    val widgetHeight = with(density) { heightPx.toDp() }
                    val travelX = (areaWidth - widthPx).coerceAtLeast(0f)
                    val travelY = (areaHeight - heightPx).coerceAtLeast(0f)
                    Surface(
                        modifier = Modifier.offset {
                            IntOffset((entry.x * travelX).roundToInt(), (entry.y * travelY).roundToInt())
                        }.width(widgetWidth).height(widgetHeight),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    ) {
                        Box {
                            if (info != null) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { context -> host.createView(context, entry.id, info) },
                                    update = { view ->
                                        val size = android.util.SizeF(widgetWidth.value, widgetHeight.value)
                                        if (reportedSizes[view] != size) try {
                                            view.updateAppWidgetSize(android.os.Bundle(), listOf(size))
                                            reportedSizes[view] = size
                                        } catch (_: SecurityException) {
                                            // Editing controls remain available if provider access is lost.
                                        }
                                    }
                                )
                            } else {
                                Text("Widget unavailable — remove it and add it again")
                            }
                            if (editing) {
                                Row(
                                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(48.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                                ) {
                                    Box(
                                        Modifier.weight(1f).height(48.dp)
                                            .pointerInput(entry.id, travelX, travelY) {
                                                detectDragGestures(
                                                    onDragEnd = { persist() },
                                                    onDragCancel = { persist() }
                                                ) { change, amount ->
                                                    change.consume()
                                                    entries = entries.map {
                                                        if (it.id != entry.id) it else it.copy(
                                                            x = if (travelX > 0) (it.x + amount.x / travelX).coerceIn(0f, 1f) else 0f,
                                                            y = if (travelY > 0) (it.y + amount.y / travelY).coerceIn(0f, 1f) else 0f
                                                        )
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) { Text("↕ Move") }
                                    TextButton(onClick = { removeWidget(entry.id) }) { Text("Remove") }
                                }
                                if (horizontal || vertical) {
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).size(48.dp)
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                            .semantics { contentDescription = "Resize widget" }
                                            .pointerInput(entry.id, areaWidth, areaHeight, minWidth, minHeight, maxWidgetWidth, maxWidgetHeight, horizontal, vertical) {
                                                detectDragGestures(
                                                    onDragEnd = { persist() },
                                                    onDragCancel = { persist() }
                                                ) { change, amount ->
                                                    change.consume()
                                                    entries = entries.map {
                                                        if (it.id != entry.id) it else {
                                                            val oldWidth = (it.widthFraction * areaWidth).coerceIn(minWidth, maxWidgetWidth)
                                                            val oldHeight = (if (it.heightFraction > 0f) it.heightFraction * areaHeight else defaultHeight)
                                                                .coerceIn(minHeight, maxWidgetHeight)
                                                            val newWidth = (oldWidth + if (horizontal) amount.x else 0f).coerceIn(minWidth, maxWidgetWidth)
                                                            val newHeight = (oldHeight + if (vertical) amount.y else 0f).coerceIn(minHeight, maxWidgetHeight)
                                                            // Keep the top-left corner anchored, unless a screen edge requires clamping.
                                                            it.copy(
                                                                widthFraction = newWidth / areaWidth,
                                                                heightFraction = newHeight / areaHeight,
                                                                x = if (newWidth < areaWidth) (it.x * (areaWidth - oldWidth) / (areaWidth - newWidth)).coerceIn(0f, 1f) else 0f,
                                                                y = if (newHeight < areaHeight) (it.y * (areaHeight - oldHeight) / (areaHeight - newHeight)).coerceIn(0f, 1f) else 0f
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) { Text("↘", color = MaterialTheme.colorScheme.onPrimaryContainer) }
                                } else if (info != null) {
                                    Text(
                                        "Fixed-size widget",
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                            .background(MaterialTheme.colorScheme.surface).padding(4.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val HOST_ID = 1042
        const val MAX_WIDGETS = 5
    }
}
