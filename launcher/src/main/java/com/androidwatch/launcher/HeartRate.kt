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
import java.time.LocalDate

/** Today's heart-rate summary: one value per measurement (its median). */
data class HeartStats(val average: Int, val min: Int, val max: Int, val checks: Int)

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
    private val burst = mutableListOf<Int>()

    /** Called with today's summary whenever it changes. */
    var onStats: (HeartStats?) -> Unit = {}

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
        recordBurst()
        onUpdate(lastSaved(), false, lastSavedAt())
    }

    /** Adds this measurement (its median, so one odd reading doesn't skew it) to today's stats. */
    private fun recordBurst() {
        if (burst.isEmpty()) return
        val median = burst.sorted()[burst.size / 2]
        burst.clear()
        val today = LocalDate.now().toString()
        val sameDay = prefs.getString("hr_stats_day", null) == today
        val count = (if (sameDay) prefs.getInt("hr_stats_count", 0) else 0) + 1
        val sum = (if (sameDay) prefs.getLong("hr_stats_sum", 0) else 0) + median
        val min = if (sameDay) minOf(prefs.getInt("hr_stats_min", median), median) else median
        val max = if (sameDay) maxOf(prefs.getInt("hr_stats_max", median), median) else median
        prefs.edit()
            .putString("hr_stats_day", today)
            .putInt("hr_stats_count", count).putLong("hr_stats_sum", sum)
            .putInt("hr_stats_min", min).putInt("hr_stats_max", max)
            .apply()
        onStats(todayStats())
    }

    fun todayStats(): HeartStats? {
        if (prefs.getString("hr_stats_day", null) != LocalDate.now().toString()) return null
        val count = prefs.getInt("hr_stats_count", 0).takeIf { it > 0 } ?: return null
        return HeartStats(
            average = Math.round(prefs.getLong("hr_stats_sum", 0) / count.toDouble()).toInt(),
            min = prefs.getInt("hr_stats_min", 0),
            max = prefs.getInt("hr_stats_max", 0),
            checks = count,
        )
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
        burst += bpm
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
