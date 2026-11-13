package com.androidwatch.phonelink

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * App-wide text size, on top of the system font size. Saved between launches.
 * (Copies of this file live in each app module; keep them in sync.)
 */
object TextZoom {
    private val steps = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)
    val scale = mutableFloatStateOf(1f)

    fun load(context: Context) {
        scale.floatValue = prefs(context).getFloat("text_zoom", 1f)
    }

    fun bigger(context: Context) = set(context, steps.firstOrNull { it > scale.floatValue + 0.01f } ?: steps.last())
    fun smaller(context: Context) = set(context, steps.lastOrNull { it < scale.floatValue - 0.01f } ?: steps.first())

    private fun set(context: Context, value: Float) {
        scale.floatValue = value
        prefs(context).edit().putFloat("text_zoom", value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("text_zoom", Context.MODE_PRIVATE)
}

/** Applies [TextZoom] to all text inside [content]. */
@Composable
fun ZoomedText(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(d.density, d.fontScale * TextZoom.scale.floatValue),
        content = content,
    )
}

/** "A−  125%  A+" control. Drawn at a fixed size so it stays usable at any zoom. */
@Composable
fun ZoomButtons(context: Context, modifier: Modifier = Modifier) {
    val d = LocalDensity.current
    // Undo our own zoom for these controls so they don't grow off-screen.
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale / TextZoom.scale.floatValue)) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ZoomKey("A−") { TextZoom.smaller(context) }
            Text(
                String.format(Locale.US, "%.0f%%", TextZoom.scale.floatValue * 100),
                color = Color(0xFF9E9E9E), fontSize = 12.sp,
            )
            ZoomKey("A+") { TextZoom.bigger(context) }
        }
    }
}

@Composable
private fun ZoomKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .background(Color(0xFF333333), RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}
