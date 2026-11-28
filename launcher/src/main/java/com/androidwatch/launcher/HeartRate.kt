package com.androidwatch.launcher

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * Heart rate on the face. Measuring keeps the sensor's LEDs on, so it only
 * runs in short bursts: 30 s each time the face is shown, or when you tap ❤.
 * The last good reading is remembered and shown (dimmed) between bursts.
 */
class HeartRateTracker(
    private val context: Context,
    private val prefs: SharedPreferences,
    /** bpm (null = no reading yet / no skin contact), whether measuring now, time of that reading. */
    private val onUpdate: (bpm: Int?, measuring: Boolean, at: Long?) -> Unit,
) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val main = Handler(Looper.getMainLooper())
    private val stopLater = Runnable { stop() }
    private var measuring = false
    private var live: Int? = null

    val available get() = sensor != null

    val hasPermission get() = context.hasPermission(Manifest.permission.BODY_SENSORS)

    /** Shows the last saved reading (e.g. after the app restarts). */
    fun publishSaved() {
        val bpm = prefs.getInt("hr_last", 0).takeIf { it > 0 }
        val at = prefs.getLong("hr_at", 0).takeIf { it > 0 }
        onUpdate(bpm, false, at)
    }

    fun measure() {
        val s = sensor ?: return
        if (!hasPermission) return
        main.removeCallbacks(stopLater)
        if (!measuring) {
            live = null
            measuring = manager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        }
        main.postDelayed(stopLater, BURST_MS)
        onUpdate(live ?: lastSaved(), measuring, lastSavedAt())
    }

    fun stop() {
        main.removeCallbacks(stopLater)
        if (measuring) manager.unregisterListener(this)
        measuring = false
        onUpdate(lastSaved(), false, lastSavedAt())
    }

    override fun onSensorChanged(event: SensorEvent) {
        val bpm = event.values.firstOrNull()?.toInt() ?: 0
        // No skin contact, or the sensor is still settling: don't show a number.
        if (event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT || bpm !in 30..230) {
            live = null
            onUpdate(null, measuring, null)
            return
        }
        live = bpm
        val now = System.currentTimeMillis()
        prefs.edit().putInt("hr_last", bpm).putLong("hr_at", now).apply()
        onUpdate(bpm, measuring, now)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun lastSaved() = prefs.getInt("hr_last", 0).takeIf { it > 0 }
    private fun lastSavedAt() = prefs.getLong("hr_at", 0).takeIf { it > 0 }

    private companion object {
        const val BURST_MS = 30_000L
    }
}
