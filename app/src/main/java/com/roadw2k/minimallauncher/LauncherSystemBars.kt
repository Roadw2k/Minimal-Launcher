package com.roadw2k.minimallauncher

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keeps Android's native navigation controls visible without changing status bar visibility.
 * Call after window setup and on resume to recheck the default HOME.
 * Android's navigation mode determines whether a gesture handle or buttons are shown.
 * Returns whether this app is currently the default home app.
 */
fun ComponentActivity.updateLauncherSystemBars(): Boolean {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val defaultHome = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
    val isDefaultHome = defaultHome?.activityInfo?.packageName == packageName
    val controller = WindowCompat.getInsetsController(window, window.decorView)

    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    controller.show(WindowInsetsCompat.Type.navigationBars())
    return isDefaultHome
}
