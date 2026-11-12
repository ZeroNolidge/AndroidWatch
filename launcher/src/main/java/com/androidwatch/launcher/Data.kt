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
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
class StepTracker(
    context: Context,
    private val prefs: SharedPreferences,
    /** steps today, and when counting started if that was after midnight (first use). */
    private val onSteps: (steps: Int, since: Long?) -> Unit,
    /** Raw readings for the tap-to-show diagnostic line. */
    private val onDebug: (String) -> Unit = {},
) :
    SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val available get() = sensor != null

    private var events = 0

    fun start() {
        events = 0
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values[0].toLong()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val day = prefs.getString("steps_day", null)
        var baseline = prefs.getLong("steps_baseline", raw)
        var carried = prefs.getLong("steps_carried", 0)
        val lastRaw = prefs.getLong("steps_last_raw", raw)
        var since = prefs.getLong("steps_since", 0)

        if (day != today.toString()) {
            carried = 0
            if (day == today.minusDays(1).toString()) {
                // New day: count from the last reading we saw yesterday, so steps taken
                // this morning before the face was opened still count. (Steps between
                // that last reading and midnight are counted as today: a small overcount.)
                baseline = if (raw >= lastRaw) lastRaw else 0 // 0: rebooted overnight
                since = 0
            } else {
                // First use, or not used for days. The counter holds steps since the
                // watch last restarted: if that was today, they're all today's steps.
                val bootTime = now - SystemClock.elapsedRealtime()
                val bootDay = Instant.ofEpochMilli(bootTime).atZone(ZoneId.systemDefault()).toLocalDate()
                if (bootDay == today) {
                    baseline = 0
                    since = bootTime
                } else {
                    baseline = raw
                    since = now
                }
            }
        } else if (raw < lastRaw) {
            // Counter restarted (reboot): keep what we had and count from zero.
            carried += lastRaw - baseline
            baseline = 0
        }
        prefs.edit()
            .putString("steps_day", today.toString())
            .putLong("steps_since", since)
            .putLong("steps_baseline", baseline)
            .putLong("steps_carried", carried)
            .putLong("steps_last_raw", raw)
            .apply()
        onSteps((carried + raw - baseline).toInt(), since.takeIf { it > 0 })
        events++
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(now))
        onDebug("sensor total $raw · start $baseline · carried $carried\nlast reading $time · readings since opened: $events")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}

// ---------------------------------------------------------------- Weather

data class Weather(val temperature: Int, val unit: String, val description: String, val icon: String)

/** Current weather from Open-Meteo (free, no API key) for the watch's approximate location. */
object WeatherClient {

    private val usesFahrenheit get() = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")

    fun isLocationOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /**
     * Finds the watch's approximate location, calling [onResult] once (on the main
     * thread) with the location, or null after ~15 s. Tries Google's fused location
     * first (Wi-Fi/cell based, works indoors), then the plain Android providers.
     */
    @SuppressLint("MissingPermission") // Caller checks ACCESS_COARSE_LOCATION.
    fun findLocation(context: Context, onResult: (Location?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var done = false
        fun finish(loc: Location?) {
            if (done) return
            done = true
            onResult(loc)
        }
        main.postDelayed({ finish(null) }, 15_000)

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fun androidFallback() {
            val recent = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (recent != null) return finish(recent)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    finish(location)
                }
                @Deprecated("Required on Android 8-10")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
                .forEach { runCatching { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) } }
            main.postDelayed({ lm.removeUpdates(listener) }, 15_000)
        }

        try {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc -> if (loc != null) finish(loc) else androidFallback() }
                .addOnFailureListener { androidFallback() }
        } catch (_: Exception) {
            androidFallback() // Play Services missing (e.g. some other watch)
        }
    }

    /**
     * Rough (city-level) location from the internet connection's IP address.
     * Needs no permission and works indoors. Blocking: run off the main thread.
     */
    fun ipLocation(): Location? {
        val services = listOf(
            "https://ipwho.is/?fields=success,latitude,longitude",
            "https://ipapi.co/json/",
        )
        for (service in services) {
            val loc = runCatching {
                val conn = URL(service).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "AndroidWatch-WatchFace")
                try {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    if (!json.optBoolean("success", true) || !json.has("latitude")) null
                    else Location("ip").apply {
                        latitude = json.getDouble("latitude")
                        longitude = json.getDouble("longitude")
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (loc != null) return loc
        }
        return null
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
