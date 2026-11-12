package com.androidwatch.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

// ---------------------------------------------------------------- Apps

data class AppEntry(val label: String, val packageName: String, val activity: String, val icon: Bitmap)

object Apps {
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val iconPx = (48 * context.resources.displayMetrics.density).toInt()
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    activity = it.activityInfo.name,
                    icon = it.loadIcon(pm).toBitmap(iconPx),
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    fun launch(context: Context, app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let(context::startActivity)
        }
    }

    private fun Drawable.toBitmap(size: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return Bitmap.createScaledBitmap(bitmap, size, size, true)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, size, size)
        draw(Canvas(bmp))
        return bmp
    }
}

/** Favourite apps shown on the watch face, in the order they were pinned. */
class Favorites(private val prefs: SharedPreferences) {
    fun get(): List<String> = prefs.getString(KEY, "")!!.split(',').filter { it.isNotBlank() }

    /** Returns true if the app is now a favourite. */
    fun toggle(packageName: String): Boolean {
        val list = get().toMutableList()
        val added = if (packageName in list) { list.remove(packageName); false }
        else { list.add(packageName); true }
        prefs.edit().putString(KEY, list.takeLast(MAX).joinToString(",")).apply()
        return added
    }

    companion object {
        const val MAX = 4
        private const val KEY = "favorites"
    }
}

// ---------------------------------------------------------------- Steps

/**
 * The hardware step counter reports steps since the last reboot. We store a
 * baseline at the start of each day (and carry steps across reboots) to get
 * "steps today".
 */
class StepTracker(context: Context, private val prefs: SharedPreferences, private val onSteps: (Int) -> Unit) :
    SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val available get() = sensor != null

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values[0].toLong()
        val today = LocalDate.now().toString()
        var baseline = prefs.getLong("steps_baseline", raw)
        var carried = prefs.getLong("steps_carried", 0)
        val lastRaw = prefs.getLong("steps_last_raw", raw)

        if (prefs.getString("steps_day", null) != today) {
            baseline = raw
            carried = 0
        } else if (raw < lastRaw) {
            // Counter restarted (reboot): keep what we had and count from zero.
            carried += lastRaw - baseline
            baseline = 0
        }
        prefs.edit()
            .putString("steps_day", today)
            .putLong("steps_baseline", baseline)
            .putLong("steps_carried", carried)
            .putLong("steps_last_raw", raw)
            .apply()
        onSteps((carried + raw - baseline).toInt())
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}

// ---------------------------------------------------------------- Weather

data class Weather(val temperature: Int, val unit: String, val description: String, val icon: String)

/** Current weather from Open-Meteo (free, no API key) for the watch's approximate location. */
object WeatherClient {

    private val usesFahrenheit get() = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")

    @SuppressLint("MissingPermission") // Caller checks ACCESS_COARSE_LOCATION.
    fun lastLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Blocking network call: run off the main thread. */
    fun fetch(lat: Double, lon: Double): Weather {
        val unit = if (usesFahrenheit) "fahrenheit" else "celsius"
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&current=temperature_2m,weather_code,is_day&temperature_unit=%s",
                lat, lon, unit,
            ),
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val body = conn.inputStream.bufferedReader().readText()
            val current = JSONObject(body).getJSONObject("current")
            val code = current.getInt("weather_code")
            val isDay = current.optInt("is_day", 1) == 1
            val (desc, icon) = describe(code, isDay)
            return Weather(
                temperature = Math.round(current.getDouble("temperature_2m")).toInt(),
                unit = if (usesFahrenheit) "°F" else "°C",
                description = desc,
                icon = icon,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** WMO weather codes → short label and emoji. */
    private fun describe(code: Int, isDay: Boolean): Pair<String, String> = when (code) {
        0 -> "Clear" to if (isDay) "☀️" else "🌙"
        1, 2 -> "Partly cloudy" to if (isDay) "⛅" else "☁️"
        3 -> "Cloudy" to "☁️"
        45, 48 -> "Fog" to "🌫️"
        in 51..57 -> "Drizzle" to "🌦️"
        in 61..67, in 80..82 -> "Rain" to "🌧️"
        in 71..77, 85, 86 -> "Snow" to "🌨️"
        in 95..99 -> "Storm" to "⛈️"
        else -> "—" to "🌡️"
    }
}

fun Context.hasPermission(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
