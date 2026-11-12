package com.androidwatch.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
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
    }

    @Test fun face() = paparazzi.snapshot { LauncherApp(state(), true, {}, {}) }

    @Test fun faceNoWeather() = paparazzi.snapshot {
        LauncherApp(state().apply { weather.value = null; weatherStatus.value = "Finding location…" }, true, {}, {})
    }
}
