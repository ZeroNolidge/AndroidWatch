package com.androidwatch.phonelink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** Renders the notification list at the M99 Mini's screen: 412×502 px at 200 dpi. */
class ListScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 412, screenHeight = 502, xdpi = 200, ydpi = 200,
            density = Density.create(200), orientation = ScreenOrientation.PORTRAIT,
        ),
    )

    private val now = System.currentTimeMillis()
    private val sample = listOf(
        PhoneNotification("1", "Messages", "Alex", "Are we still on for lunch tomorrow? I can do 12:30 near the office.",
            now - 120_000, false, listOf(PhoneAction(0, "Reply", true), PhoneAction(1, "Mark as read", false))),
        PhoneNotification("2", "WhatsApp", "Family group", "Mum: Don't forget to call grandma 😊", now - 900_000, false,
            listOf(PhoneAction(0, "Reply", true))),
        PhoneNotification("3", "Gmail", "Your order has shipped", "Tracking number 1Z999AA10123456784", now - 3_600_000,
            false, emptyList()),
    )

    @Test fun list() = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 8.dp)) {
                NotificationCards(sample, expandFirst = true)
            }
        }
    }

    @Test fun listZoomed() {
        TextZoom.scale.floatValue = 2f
        paparazzi.snapshot {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ZoomedText {
                    Box(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 8.dp)) {
                        NotificationCards(sample, expandFirst = true)
                    }
                }
            }
        }
        TextZoom.scale.floatValue = 1f
    }
}
