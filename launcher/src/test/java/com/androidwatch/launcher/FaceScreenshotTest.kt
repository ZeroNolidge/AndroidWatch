package com.androidwatch.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import androidx.compose.foundation.background
import org.junit.Rule
import org.junit.Test

/** Renders the launcher at the M99 Mini's real screen: 412×502 px at 200 dpi. */
class FaceScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 412, screenHeight = 502, xdpi = 200, ydpi = 200,
            density = Density.create(200), orientation = ScreenOrientation.PORTRAIT,
        ),
    )

    private fun icon(color: Int): Bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawCircle(30f, 30f, 30f, Paint().apply { this.color = color })
    }

    private fun state() = FaceState().apply {
        battery.intValue = 72
        steps.value = 4321
        weather.value = Weather(18, "°C", "Partly cloudy", "⛅")
        val colors = listOf(AColor.RED, AColor.GREEN, AColor.BLUE, AColor.YELLOW, AColor.CYAN, AColor.MAGENTA, AColor.GRAY, AColor.WHITE, AColor.LTGRAY)
        apps.value = listOf("Phone", "Messages", "Camera", "Maps", "Settings", "Play Store", "Chrome", "Watch Inspector", "Clock")
            .mapIndexed { i, n -> AppEntry(n, "pkg.$i", "a", icon(colors[i])) }
        favorites.value = listOf("pkg.0", "pkg.1", "pkg.3")
        claudeLast.value = "✅ Fixed the weather fallback and pushed to main"
        claudeUnread.intValue = 2
    }

    @Test fun face() = paparazzi.snapshot { LauncherApp(state(), true, {}, {}) }

    @Test fun faceNoWeather() = paparazzi.snapshot {
        LauncherApp(state().apply { weather.value = null; weatherStatus.value = "Finding location…" }, true, {}, {})
    }

}

/** The M99: 480×480 px, rendered at the corrected density (232 dpi ≈ 330 dp wide). M99 has no step counter. */
class M99FaceScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 480, screenHeight = 480, xdpi = 232, ydpi = 232,
            density = Density.create(232), orientation = ScreenOrientation.PORTRAIT,
        ),
    )

    @Test fun face() = paparazzi.snapshot {
        val s = FaceState().apply {
            battery.intValue = 88
            weather.value = Weather(14, "°F", "Cloudy", "☁️")
            claudeLast.value = "🕐 Working on: add a dark theme"
        }
        LauncherApp(s, stepsAvailable = false, onLaunch = {}, onToggleFavorite = {})
    }
}

/** The round 466×466 watch, at two plausible densities, round layout forced on. */
class RoundFaceScreenshotTest {
    private fun cfg(dpi: Int) = DeviceConfig(
        screenWidth = 466, screenHeight = 466, xdpi = dpi, ydpi = dpi,
        density = Density.create(dpi), orientation = ScreenOrientation.PORTRAIT,
    )

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = cfg(320))

    private fun state() = FaceState().apply {
        battery.intValue = 64
        steps.value = 5210
        weather.value = Weather(11, "°C", "Rain", "🌧️")
        val colors = listOf(AColor.RED, AColor.GREEN, AColor.BLUE, AColor.YELLOW)
        apps.value = listOf("Phone", "Messages", "Camera", "Maps").mapIndexed { i, n ->
            AppEntry(n, "pkg.$i", "a", Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawCircle(30f, 30f, 30f, Paint().apply { color = colors[i] })
            })
        }
        favorites.value = listOf("pkg.0", "pkg.1", "pkg.2", "pkg.3")
        claudeLast.value = "✅ Added round-screen layouts and pushed"
        claudeUnread.intValue = 1
        forecast.value = sampleForecast()
        heartRate.value = 104
        heartMeasuring.value = true
        heartStats.value = HeartStats(average = 96, min = 71, max = 118, checks = 14)
    }

    /** Dry now, rain from ~2 h out, turning to snow after ~6 h. */
    private fun sampleForecast(): Forecast {
        val hour = 3_600_000L
        val start = System.currentTimeMillis() / hour * hour
        val chances = listOf(0, 5, 30, 70, 90, 80, 60, 70, 85, 50, 20, 0, 0)
        val hours = chances.mapIndexed { i, c ->
            HourForecast(start + i * hour, 11 - i / 2, c, if (c >= 50) 1.0 else 0.0, if (i >= 6 && c >= 50) 0.8 else 0.0, if (c >= 50) 61 else 3)
        }
        return Forecast(hours, emptyList(), 12, 3, "°C", System.currentTimeMillis())
    }

    @Test fun face320dpi() {
        RoundScreen.mode.value = RoundScreen.Mode.ON
        paparazzi.snapshot { LauncherApp(state(), true, {}, {}) }
    }

    @Test fun weatherPage() {
        RoundScreen.mode.value = RoundScreen.Mode.ON
        paparazzi.unsafeUpdateConfig(deviceConfig = cfg(240))
        paparazzi.snapshot {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color.Black),
                ) { WeatherPage(state(), {}) }
            }
        }
    }

    @Test fun face240dpi() {
        RoundScreen.mode.value = RoundScreen.Mode.ON
        paparazzi.unsafeUpdateConfig(deviceConfig = cfg(240))
        paparazzi.snapshot { LauncherApp(state(), true, {}, {}, heartAvailable = true) }
    }
}
