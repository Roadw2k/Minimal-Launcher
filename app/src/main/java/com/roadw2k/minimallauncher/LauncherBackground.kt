package com.roadw2k.minimallauncher

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Construct once in Activity.onCreate, before STARTED (and before setContent).
 * All public actions are main-thread APIs. Changes are saved immediately, not on an Apply action.
 * A color selection switches to solid mode and removes the private photo. Null means theme background.
 * State is Compose-observable and read-only to callers. No storage permissions or URI grants needed.
 */
class LauncherBackground(private val activity: ComponentActivity) {
    enum class SolidColor(val label: String, val color: Color?) {
        SYSTEM("System default", null),
        BLACK("Black", Color.Black),
        WHITE("White", Color.White),
        GRAY("Gray", Color(0xFF666666)),
        WARM("Warm", Color(0xFFE8D8C3)),
        BLUE("Blue", Color(0xFF203B58)),
        GREEN("Green", Color(0xFF29483B))
    }

    var savedColor by mutableStateOf(SolidColor.SYSTEM)
        private set
    var customColor by mutableStateOf<Color?>(null)
        private set
    val solidColor: Color?
        get() = customColor ?: savedColor.color

    var hasPhoto by mutableStateOf(false)
        private set
    var busy by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var photoName by mutableStateOf<String?>(null)
    private var pickerOpen by mutableStateOf(false)
    private var bitmap by mutableStateOf<Bitmap?>(null)
    private val prefs by lazy { activity.getSharedPreferences("launcher_background", 0) }
    private val directory get() = File(activity.filesDir, "launcher_background")

    private val initialization = activity.lifecycleScope.launch {
        try {
            val saved = withContext(Dispatchers.IO) {
                val color = SolidColor.entries.firstOrNull { it.name == prefs.getString("color", null) }
                    ?: SolidColor.SYSTEM
                val custom = if (prefs.contains("custom_color")) {
                    Color(prefs.getInt("custom_color", 0))
                } else null
                val name = prefs.getString("photo", null)?.takeIf {
                    it.matches(Regex("photo-[a-f0-9-]+\\.img")) && File(directory, it).isFile
                }
                // Only our own directory is touched; recover leftovers from interrupted imports.
                directory.listFiles()?.filter { it.name != name }?.forEach { it.delete() }
                Triple(color, custom, name)
            }
            savedColor = saved.first
            customColor = saved.second
            photoName = saved.third
            hasPhoto = saved.third != null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "Could not load background settings: ${e.message}"
        } finally {
            busy = false
        }
    }

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerOpen = false
        if (uri != null) activity.lifecycleScope.launch {
            initialization.join()
            if (!busy) importPicture(uri)
        }
    }

    fun choosePicture() {
        if (busy || pickerOpen) return
        error = null
        try {
            pickerOpen = true
            picker.launch(arrayOf("image/*"))
        } catch (e: Exception) {
            pickerOpen = false
            error = "Could not open the picture picker: ${e.message}"
        }
    }

    fun setColor(color: SolidColor) = saveSolid(color, null)

    fun setCustomColor(color: Color) = saveSolid(savedColor, Color(color.toArgb()))

    /** Switches back to the saved solid color, deleting the private copy off the main thread. */
    fun removePicture() = saveSolid(savedColor, customColor)

    private fun saveSolid(color: SolidColor, custom: Color?) {
        if (busy || pickerOpen) return
        busy = true
        error = null
        activity.lifecycleScope.launch {
            try {
                val old = photoName
                // Finish persistence and state publication together even if the Activity is destroyed.
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        val editor = prefs.edit().putString("color", color.name).remove("photo")
                        if (custom != null) {
                            editor.putInt("custom_color", custom.toArgb())
                        } else {
                            editor.remove("custom_color")
                        }
                        check(editor.commit()) {
                            "Could not save background settings"
                        }
                        old?.let { File(directory, it).delete() }
                    }
                    savedColor = color
                    customColor = custom
                    photoName = null
                    hasPhoto = false
                    bitmap = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = "Could not change background: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun importPicture(uri: Uri) {
        busy = true
        error = null
        val old = photoName
        try {
            val name = withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "Private storage is unavailable" }
                val temp = File.createTempFile("import-", ".tmp", directory)
                var destination: File? = null
                var committed = false
                try {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_BYTES) throw IOException("Picture exceeds the 25 MB limit")
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    } ?: throw IOException("The provider could not open this picture")
                    val (width, height) = screenBounds()
                    val validation = decode(temp, width, height)
                    validation.recycle()
                    currentCoroutineContext().ensureActive()
                    // Unique filenames + same-directory rename leave the old picture intact on failure.
                    withContext(NonCancellable) {
                        val target = File(directory, "photo-${UUID.randomUUID()}.img")
                        destination = target
                        check(temp.renameTo(target)) { "Could not store the picture" }
                        check(prefs.edit().putString("photo", target.name).commit()) {
                            "Could not save background settings"
                        }
                        committed = true
                        old?.let { File(directory, it).delete() }
                        target.name
                    }
                } finally {
                    temp.delete()
                    if (!committed) destination?.delete()
                }
            }
            photoName = name
            hasPhoto = true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "Picture not changed: ${e.message ?: "Unreadable image"}"
        } finally {
            busy = false
        }
    }

    private fun screenBounds(): Pair<Int, Int> {
        val metrics = activity.resources.displayMetrics
        return metrics.widthPixels.coerceIn(1, 4096) to metrics.heightPixels.coerceIn(1, 4096)
    }

    private fun decode(file: File, width: Int, height: Int): Bitmap {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            // ImageDecoder reports oriented dimensions and applies EXIF rotation during decoding.
            val sourceWidth = info.size.width
            val sourceHeight = info.size.height
            if (sourceWidth <= 0 || sourceHeight <= 0) throw IOException("Unsupported or invalid image")
            var targetWidth = sourceWidth
            var targetHeight = sourceHeight
            while (targetWidth > width || targetHeight > height ||
                targetWidth.toLong() * targetHeight > 4_000_000
            ) {
                targetWidth = (targetWidth / 2).coerceAtLeast(1)
                targetHeight = (targetHeight / 2).coerceAtLeast(1)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(targetWidth, targetHeight)
            decoder.setOnPartialImageListener { false }
        }
    }

    /** Place behind the shared transparent scaffold so every launcher screen uses this background. */
    @Composable
    fun Background() {
        val configuration = LocalConfiguration.current
        val name = photoName
        LaunchedEffect(name, configuration.screenWidthDp, configuration.screenHeightDp, configuration.densityDpi) {
            bitmap = null
            if (name != null) {
                try {
                    bitmap = withContext(Dispatchers.IO) {
                        val (width, height) = screenBounds()
                        decode(File(directory, name), width, height)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = "Could not display picture. Remove it or choose another: ${e.message}"
                }
            }
        }
        Box(Modifier.fillMaxSize().background(solidColor ?: MaterialTheme.colorScheme.background)) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }

    /** Embed in a scrolling Settings surface if vertical space is limited. */
    @Composable
    fun Editor() {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Background", style = MaterialTheme.typography.titleMedium)
            Text("Selections save immediately; there is no Apply button. Choosing a color removes the picture. System default follows the theme.")
            Text("The background applies to every launcher screen, including Settings and the favorites picker.")
            SolidColor.entries.forEach { color ->
                TextButton(onClick = { setColor(color) }, enabled = !busy && !pickerOpen) {
                    Text(color.label + if (!hasPhoto && customColor == null && savedColor == color) " (selected)" else "")
                }
            }
            CustomColorButton(
                color = solidColor ?: MaterialTheme.colorScheme.background,
                onColorSelected = ::setCustomColor,
                enabled = !busy && !pickerOpen
            )
            if (!hasPhoto && customColor != null) Text("Custom color (selected)")
            Button(onClick = ::choosePicture, enabled = !busy && !pickerOpen) { Text(if (hasPhoto) "Replace picture (selected)" else "Choose picture") }
            TextButton(onClick = ::removePicture, enabled = hasPhoto && !busy && !pickerOpen) {
                Text("Remove picture")
            }
            Text("Files and Photos providers are supported. A private copy is kept (maximum 25 MB); no storage permission is needed.")
            if (busy) Text("Loading or saving background…")
            if (pickerOpen) Text("Waiting for picture selection…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    private companion object {
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
