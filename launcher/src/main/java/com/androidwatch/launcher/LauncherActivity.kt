package com.androidwatch.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.Executors

/** Shared, observable state for the watch face. Written from callbacks, read by Compose. */
class FaceState {
    val battery = mutableIntStateOf(-1)
    val charging = mutableStateOf(false)
    val steps = mutableStateOf<Int?>(null)
    val weather = mutableStateOf<Weather?>(null)
    /** Why there is no weather yet (shown instead of it), or null. */
    val weatherStatus = mutableStateOf<String?>("Weather loading…")
    /** When step counting started, if after midnight (first day of use). */
    val stepsSince = mutableStateOf<Long?>(null)
    val apps = mutableStateOf<List<AppEntry>>(emptyList())
    val favorites = mutableStateOf<List<String>>(emptyList())
    /** Bumped each time Home is pressed while we're already showing, to jump back to the face. */
    val homePresses = mutableIntStateOf(0)
}

class LauncherActivity : ComponentActivity() {

    private val state = FaceState()
    private val background = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val favorites by lazy { Favorites(prefs) }
    private lateinit var steps: StepTracker
    private var lastWeatherFetch = 0L
    private var weatherInFlight = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            steps.start()
            refreshWeather(force = true)
        }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            state.battery.intValue = level * 100 / scale
            state.charging.value = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
    }

    private val packagesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        steps = StepTracker(this, prefs) { count, since ->
            state.steps.value = count
            state.stepsSince.value = since
        }
        state.favorites.value = favorites.get()
        reloadApps()

        // Refresh the app list when apps are installed or removed.
        registerReceiver(
            packagesReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
        )

        val needed = buildList {
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29 && !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())

        setContent {
            LauncherApp(
                state = state,
                stepsAvailable = steps.available,
                onLaunch = { Apps.launch(this, it) },
                onToggleFavorite = ::toggleFavorite,
                onWeatherTapped = ::onWeatherTapped,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) state.homePresses.intValue++
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        steps.start()
        refreshWeather(force = false)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(batteryReceiver)
        steps.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packagesReceiver)
        background.shutdown()
    }

    private fun reloadApps() {
        background.execute {
            val apps = runCatching { Apps.load(this) }.getOrDefault(emptyList())
            runOnUiThread { state.apps.value = apps }
        }
    }

    private fun toggleFavorite(app: AppEntry) {
        val added = favorites.toggle(app.packageName)
        state.favorites.value = favorites.get()
        val msg = if (added) "${app.label} added to watch face" else "${app.label} removed from watch face"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** At most every 30 minutes, and only while the face is visible. */
    private fun refreshWeather(force: Boolean) {
        val now = System.currentTimeMillis()
        if (weatherInFlight) return
        if (!force && now - lastWeatherFetch < 30 * 60_000) return
        weatherInFlight = true
        lastWeatherFetch = now
        if (state.weather.value == null && state.weatherStatus.value == INITIAL_STATUS) {
            state.weatherStatus.value = "Finding location…"
        }

        fun fetchWeather(deviceLocation: Location?) {
            background.execute {
                // No device location (indoors, no permission, location off)? Use the IP address.
                val location = deviceLocation ?: WeatherClient.ipLocation()
                if (location != null) saveLocation(location)
                val weather = location?.let { runCatching { WeatherClient.fetch(it.latitude, it.longitude) }.getOrNull() }
                runOnUiThread {
                    weatherInFlight = false
                    when {
                        weather != null -> {
                            state.weather.value = weather
                            state.weatherStatus.value = null
                        }
                        location == null -> weatherProblem("No internet · tap to retry")
                        else -> weatherProblem("Weather service unreachable · tap to retry")
                    }
                }
            }
        }

        val cached = cachedLocation(maxAgeMs = 6 * 60 * 60_000L)
        when {
            cached != null -> fetchWeather(cached)
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) && WeatherClient.isLocationOn(this) ->
                WeatherClient.findLocation(this) { fetchWeather(it) }
            else -> fetchWeather(null)
        }
    }

    private fun weatherProblem(msg: String) {
        lastWeatherFetch = System.currentTimeMillis() - 28 * 60_000 // retry in ~2 minutes
        if (state.weather.value == null) state.weatherStatus.value = msg
    }

    private fun saveLocation(l: Location) {
        prefs.edit()
            .putString("loc_lat", l.latitude.toString())
            .putString("loc_lon", l.longitude.toString())
            .putLong("loc_time", System.currentTimeMillis())
            .apply()
    }

    private fun cachedLocation(maxAgeMs: Long): Location? {
        if (System.currentTimeMillis() - prefs.getLong("loc_time", 0) > maxAgeMs) return null
        val lat = prefs.getString("loc_lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("loc_lon", null)?.toDoubleOrNull() ?: return null
        return Location("cache").apply { latitude = lat; longitude = lon }
    }

    /** Tap on the weather: retry now, and ask for location permission (more accurate) if missing. */
    private fun onWeatherTapped() {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            requestPermissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            prefs.edit().remove("loc_time").apply() // look the location up again
            refreshWeather(force = true)
        }
    }

    private companion object {
        const val INITIAL_STATUS = "Weather loading…"
    }
}
