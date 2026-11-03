package com.roadw2k.minimallauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp

@Composable
internal fun HoldSettingsSurface(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var touch by remember { mutableStateOf<Offset?>(null) }
    val progress = remember { Animatable(0f) }
    val slop = LocalViewConfiguration.current.touchSlop
    val color = MaterialTheme.colorScheme.primary
    LaunchedEffect(touch) {
        progress.snapTo(0f)
        if (touch != null) progress.animateTo(1f, tween(1_000, easing = LinearEasing))
    }
    Box(modifier.pointerInput(slop) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            touch = down.position
            try {
                val finishedEarly = withTimeoutOrNull(1_000L) {
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        val cancelled = event.changes.size != 1 || change == null ||
                            change.isConsumed || !change.pressed ||
                            (change.position - down.position).getDistance() > slop
                    } while (!cancelled)
                    true
                }
                if (finishedEarly == null) {
                    onOpenSettings()
                    // Do not let the release become a click after completing the hold.
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            } finally {
                touch = null
            }
        }
    }) {
        content()
        touch?.let { position ->
            Canvas(Modifier.fillMaxSize()) {
                val radius = 22.dp.toPx()
                val margin = radius + 4.dp.toPx()
                val center = Offset(
                    position.x.coerceIn(margin.coerceAtMost(size.width / 2), (size.width - margin).coerceAtLeast(size.width / 2)),
                    (position.y - 56.dp.toPx()).coerceIn(margin.coerceAtMost(size.height / 2), (size.height - margin).coerceAtLeast(size.height / 2))
                )
                drawCircle(color.copy(alpha = 0.15f), radius, center)
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = center - Offset(radius, radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}
