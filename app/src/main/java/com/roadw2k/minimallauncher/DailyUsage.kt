package com.roadw2k.minimallauncher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class UsageTransition { RESUME, PAUSE, STOP, END_ALL }
internal data class DailyUsageEvent(
    val time: Long,
    val packageName: String = "",
    val activityId: String = "",
    val transition: UsageTransition
)
internal data class DailyUsageApp(val packageName: String, val label: String, val durationMillis: Long)

internal fun usageDayBounds(now: Long, zone: ZoneId): Pair<Long, Long> {
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return date.atStartOfDay(zone).toInstant().toEpochMilli() to
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

internal fun usageRefreshDelay(now: Long, zone: ZoneId): Long =
    (usageDayBounds(now, zone).second - now).coerceIn(1L, 15_000L)

/** Foreground usage, with app switches closing stale sessions even if a pause is missing. */
internal fun aggregateDailyUsage(events: List<DailyUsageEvent>, start: Long, now: Long): Map<String, Long> {
    if (now <= start) return emptyMap()
    val active = mutableMapOf<String, MutableSet<String>>()
    val opened = mutableMapOf<String, Long>()
    val totals = mutableMapOf<String, Long>()
    fun close(pkg: String, time: Long) {
        val since = opened.remove(pkg) ?: return
        val duration = time.coerceAtMost(now) - since.coerceAtLeast(start)
        if (duration > 0) totals[pkg] = totals.getOrDefault(pkg, 0L) + duration
    }
    for (event in events.sortedBy { it.time }) {
        if (event.time > now) break
        if (event.transition == UsageTransition.END_ALL) {
            opened.keys.toList().forEach { close(it, event.time) }
            active.clear()
            continue
        }
        if (event.packageName.isEmpty()) continue
        // Public UsageEvents exposes class names, not activity instance IDs. A set
        // avoids double-counting duplicate resumes and overlapping activity classes.
        val activities = active.getOrPut(event.packageName) { mutableSetOf() }
        when (event.transition) {
            UsageTransition.RESUME -> {
                // UsageEvents may omit a pause. Never let an older app keep accruing
                // time behind the current foreground app (including the launcher).
                opened.keys.filter { it != event.packageName }.toList().forEach { pkg ->
                    close(pkg, event.time)
                    active[pkg]?.clear()
                }
                if (activities.isEmpty()) opened[event.packageName] = event.time
                activities.add(event.activityId)
            }
            UsageTransition.PAUSE, UsageTransition.STOP -> {
                activities.remove(event.activityId)
                if (activities.isEmpty()) close(event.packageName, event.time)
            }
            UsageTransition.END_ALL -> Unit
        }
    }
    opened.keys.toList().forEach { close(it, now) }
    return totals
}

internal fun sortDailyUsage(apps: List<DailyUsageApp>): List<DailyUsageApp> = apps.sortedWith(
    compareByDescending<DailyUsageApp> { it.durationMillis }
        .thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.label }.thenBy { it.packageName }
)

internal fun formatUsageDuration(millis: Long, includeHours: Boolean = false): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    return when {
        seconds < 60 -> "<1m"
        minutes < 60 && !includeHours -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

private fun hasUsageAccess(context: Context): Boolean =
    context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
    ) == AppOpsManager.MODE_ALLOWED

internal data class UsageSnapshot(
    val allowed: Boolean = false,
    val apps: List<DailyUsageApp> = emptyList(),
    val dayStart: Long = 0,
    val error: String? = null
)

private fun readDailyUsage(context: Context): UsageSnapshot {
    val now = System.currentTimeMillis()
    val start = usageDayBounds(now, ZoneId.systemDefault()).first
    if (!hasUsageAccess(context)) return UsageSnapshot(dayStart = start)
    return try {
        // Android usually retains only a few days of events. Read a week to seed midnight
        // without assuming that the first event today is a resume.
        val stream = context.getSystemService(UsageStatsManager::class.java)
            .queryEvents(start - 7L * 24 * 60 * 60 * 1000, now)
            ?: return UsageSnapshot(true, dayStart = start, error = "Usage data is temporarily unavailable.")
        val widgetConfigurationActivities = android.appwidget.AppWidgetManager.getInstance(context)
            .installedProviders.mapNotNull { it.configure }.toSet()
        val events = mutableListOf<DailyUsageEvent>()
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            val isWidgetConfiguration = event.packageName != null && event.className != null &&
                android.content.ComponentName(event.packageName, event.className) in widgetConfigurationActivities
            if (isWidgetConfiguration) {
                // Configuring a widget is not opening its provider app.
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    events.add(DailyUsageEvent(event.timeStamp, transition = UsageTransition.END_ALL))
                }
                continue
            }
            val transition = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageTransition.RESUME
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageTransition.PAUSE
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageTransition.STOP
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN,
                UsageEvents.Event.DEVICE_STARTUP -> UsageTransition.END_ALL
                else -> continue
            }
            events.add(DailyUsageEvent(event.timeStamp, event.packageName.orEmpty(),
                event.className.orEmpty(), transition))
        }
        val pm = context.packageManager
        // Keep user-facing apps, including preinstalled apps, but not service-only
        // packages or this launcher. Foreground activity events supply the time.
        val launchablePackages = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        launchablePackages.remove(context.packageName)
        val apps = aggregateDailyUsage(events, start, now)
            .filterKeys { it in launchablePackages }
            .map { (pkg, duration) ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                pkg
            } catch (_: SecurityException) {
                pkg
            }
            DailyUsageApp(pkg, label, duration)
        }
        UsageSnapshot(true, sortDailyUsage(apps), start)
    } catch (_: SecurityException) {
        UsageSnapshot(dayStart = start)
    } catch (_: IllegalStateException) {
        UsageSnapshot(true, dayStart = start, error = "Usage data is temporarily unavailable.")
    }
}

internal class DailyUsageCache {
    var snapshot by mutableStateOf<UsageSnapshot?>(null)
}

@Composable
internal fun rememberDailyUsageCache(): DailyUsageCache {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val cache = remember { DailyUsageCache() }
    LaunchedEffect(owner, context) {
        if (owner == null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = System.currentTimeMillis()
                val today = usageDayBounds(now, ZoneId.systemDefault()).first
                if (cache.snapshot?.dayStart != today) cache.snapshot = null
                val result = withContext(Dispatchers.IO) { readDailyUsage(context) }
                val completedAt = System.currentTimeMillis()
                if (result.dayStart != usageDayBounds(completedAt, ZoneId.systemDefault()).first) {
                    cache.snapshot = null
                    continue // The query crossed midnight; do not publish yesterday's totals.
                }
                cache.snapshot = result
                delay(usageRefreshDelay(completedAt, ZoneId.systemDefault()))
            }
        }
    }
    return cache
}

@Composable
internal fun DailyUsagePage(textColor: Color, fontSize: Int, cache: DailyUsageCache) {
    val context = LocalContext.current
    var settingsError by remember { mutableStateOf<String?>(null) }
    var showUsagePrompt by remember { mutableStateOf(false) }
    if (showUsagePrompt) {
        AlertDialog(
            onDismissRequest = { showUsagePrompt = false },
            title = { Text("Allow usage access", color = textColor, fontSize = (fontSize + 4).sp) },
            text = {
                Column(
                    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Minimal Launcher needs usage access to calculate how long you use each app today. " +
                            "Usage data is processed on your device and isn't uploaded. " +
                            "The rest of the launcher works without this access.",
                        color = textColor, fontSize = fontSize.sp
                    )
                    Text(
                        "Tap Continue, select Minimal Launcher, and enable usage access.\n\n" +
                            "If Android blocks access with a restricted-settings warning, open " +
                            "Settings → Apps → Minimal Launcher → three-dot menu → Allow restricted settings. " +
                            "Then return to Usage access and try again. Menu names vary by device.",
                        color = textColor, fontSize = fontSize.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUsagePrompt = false
                    try {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        settingsError = null
                    } catch (_: android.content.ActivityNotFoundException) {
                        settingsError = "Usage access settings are unavailable on this device."
                    } catch (_: SecurityException) {
                        settingsError = "Unable to open usage access settings."
                    }
                }) { Text("Continue", color = textColor, fontSize = fontSize.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showUsagePrompt = false }) {
                    Text("Not now", color = textColor, fontSize = fontSize.sp)
                }
            }
        )
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val current = cache.snapshot
        val heading = if (current != null && current.allowed && current.error == null) {
            val total = current.apps.sumOf { it.durationMillis }
            "Daily usage - ${formatUsageDuration(total, includeHours = true)}"
        } else {
            "Daily usage"
        }
        Text(heading, color = textColor, fontSize = (fontSize + 4).sp)
        when {
            current == null -> Text("Loading usage…", color = textColor, fontSize = fontSize.sp)
            !current.allowed -> {
                Text("Allow usage access to see today's app usage.", color = textColor, fontSize = fontSize.sp)
                TextButton(onClick = { showUsagePrompt = true }) {
                    Text("Open usage access settings", color = textColor, fontSize = fontSize.sp)
                }
                settingsError?.let { Text(it, color = textColor, fontSize = fontSize.sp) }
            }
            current.error != null -> Text(current.error, color = textColor, fontSize = fontSize.sp)
            current.apps.isEmpty() -> Text("No usage recorded today.", color = textColor, fontSize = fontSize.sp)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(current.apps, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(app.label, modifier = Modifier.weight(1f), color = textColor, fontSize = fontSize.sp)
                        Text(formatUsageDuration(app.durationMillis), color = textColor, fontSize = fontSize.sp)
                    }
                }
            }
        }
    }
}
