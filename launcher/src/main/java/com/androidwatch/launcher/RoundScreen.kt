package com.androidwatch.launcher

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Round-screen layout. AUTO trusts Android's "screen is round" flag, but cheap
 * watches running phone builds often don't set it, so the user can force it.
 * (Copies of this file live in each watch app module; keep them in sync.)
 */
object RoundScreen {
    enum class Mode(val symbol: String, val description: String) {
        AUTO("A", "Screen shape: automatic"),
        ON("◯", "Screen shape: round"),
        OFF("▢", "Screen shape: square"),
    }

    val mode = mutableStateOf(Mode.AUTO)

    fun load(context: Context) {
        mode.value = runCatching { Mode.valueOf(prefs(context).getString("round", "AUTO")!!) }.getOrDefault(Mode.AUTO)
    }

    fun cycle(context: Context) {
        mode.value = Mode.entries[(mode.value.ordinal + 1) % Mode.entries.size]
        prefs(context).edit().putString("round", mode.value.name).apply()
        android.widget.Toast.makeText(context, mode.value.description, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("round_screen", Context.MODE_PRIVATE)
}

/** True if the layout should keep clear of a round screen's edges. */
@Composable
fun isRoundScreen(): Boolean = when (RoundScreen.mode.value) {
    RoundScreen.Mode.ON -> true
    RoundScreen.Mode.OFF -> false
    RoundScreen.Mode.AUTO -> android.os.Build.VERSION.SDK_INT >= 23 && LocalConfiguration.current.isScreenRound
}

/**
 * Padding for scrolling lists: on a round screen, keeps the sides clear and
 * lets the first and last items scroll up to the middle, where the circle is widest.
 */
@Composable
fun roundListPadding(): PaddingValues {
    if (!isRoundScreen()) return PaddingValues(0.dp)
    val cfg = LocalConfiguration.current
    val side = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) * 0.09f).dp
    val ends = (cfg.screenHeightDp * 0.16f).dp
    return PaddingValues(start = side, end = side, top = ends, bottom = ends)
}

/**
 * Padding for fixed (non-scrolling) screens: keeps everything inside the
 * largest square that fits in the circle (about 15% in from each edge).
 */
@Composable
fun roundFixedPadding(): PaddingValues {
    if (!isRoundScreen()) return PaddingValues(0.dp)
    val cfg = LocalConfiguration.current
    val inset = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) * 0.146f).dp
    return PaddingValues(inset)
}
