package com.roadw2k.minimallauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import java.util.Locale

@Composable
internal fun CustomColorButton(
    color: Color,
    onColorSelected: (Color) -> Unit,
    enabled: Boolean = true
) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) { Text("Choose custom color…") }
    if (open) {
        var hex by rememberSaveable { mutableStateOf(color.toArgb().let { String.format(Locale.ROOT, "%06X", it and 0xFFFFFF) }) }
        val rgb = hex.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toIntOrNull(16)
        val preview = rgb?.let { Color(it or 0xFF000000.toInt()) } ?: color
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Custom color") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().height(64.dp).background(preview))
                    Text("Preview", color = if (preview.red * 0.299f + preview.green * 0.587f + preview.blue * 0.114f > 0.5f) Color.Black else Color.White,
                        modifier = Modifier.fillMaxWidth().background(preview).padding(8.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { hex = it.removePrefix("#").uppercase(Locale.ROOT) },
                        label = { Text("Hex color (RRGGBB)") },
                        prefix = { Text("#") },
                        singleLine = true,
                        isError = rgb == null,
                        supportingText = { if (rgb == null) Text("Enter six hexadecimal digits (0–9, A–F).") }
                    )
                    listOf("Red", "Green", "Blue").forEachIndexed { index, label ->
                        val shift = (2 - index) * 8
                        val current = preview.toArgb() and 0xFFFFFF
                        val channel = (current shr shift) and 255
                        Text("$label: $channel")
                        Slider(
                            value = channel.toFloat(),
                            onValueChange = { value ->
                                val updated = (current and (255 shl shift).inv()) or (value.roundToInt() shl shift)
                                hex = String.format(Locale.ROOT, "%06X", updated)
                            },
                            valueRange = 0f..255f,
                            steps = 254,
                            modifier = Modifier.semantics { contentDescription = label }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = rgb != null && enabled, onClick = {
                    onColorSelected(preview)
                    open = false
                }) { Text("Use color") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        )
    }
}
