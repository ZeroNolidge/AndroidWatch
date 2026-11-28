package com.androidwatch.companion

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery optimisation can stop this app's Bluetooth link in the background
 * (watch makers' versions of Android are especially aggressive). These ask
 * Android to let it keep running. (Copy in phonelink and companion; keep in sync.)
 */
object Background {
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife") // Keeping a Bluetooth link alive is exactly what this exemption is for.
    fun requestUnrestricted(context: Context) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { context.startActivity(ask) }.onFailure { runCatching { context.startActivity(fallback) } }
    }
}
