package com.androidwatch.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        steps = StepTracker(this, prefs) { state.steps.value = it }
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
        if (!force && now - lastWeatherFetch < 30 * 60_000) return
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        lastWeatherFetch = now
        background.execute {
            val location = WeatherClient.lastLocation(this) ?: return@execute
            val weather = runCatching { WeatherClient.fetch(location.latitude, location.longitude) }.getOrNull()
            if (weather != null) runOnUiThread { state.weather.value = weather }
            else lastWeatherFetch = 0 // retry next time the face is shown
        }
    }
}
