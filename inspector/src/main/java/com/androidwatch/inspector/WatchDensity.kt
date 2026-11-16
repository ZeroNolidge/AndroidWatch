package com.androidwatch.inspector

import android.content.Context
import android.content.res.Configuration
import kotlin.math.min

/**
 * Some cheap watches (e.g. the M99) ship configured like a tablet: a 480 px,
 * ~2" screen reports itself as 640 dp wide, so every app draws text and
 * buttons at about half size. If the screen claims to be tablet-sized, we
 * re-scale this app to a sensible watch width.
 *
 * (Copies of this file live in each watch app module; keep them in sync.)
 */
object WatchDensity {
    /** Width (in dp) a ~2" watch screen should have; the M99 Mini is 329 dp. */
    const val TARGET_SMALLEST_DP = 330

    /** Above this smallest width, the configuration is clearly not a watch's. */
    private const val TABLET_SW_DP = 480

    /** Corrected density (dpi) for the screen, or null if no fix is needed. */
    fun correctedDpi(widthPx: Int, heightPx: Int, smallestWidthDp: Int): Int? {
        if (smallestWidthDp < TABLET_SW_DP) return null
        val px = min(widthPx, heightPx)
        if (px <= 0) return null
        return (px * 160 / TARGET_SMALLEST_DP).coerceIn(120, 640)
    }

    /** Use in Activity.attachBaseContext. */
    fun wrap(base: Context): Context {
        val res = base.resources
        val cfg = res.configuration
        val dm = res.displayMetrics
        val dpi = correctedDpi(dm.widthPixels, dm.heightPixels, cfg.smallestScreenWidthDp) ?: return base
        val fixed = Configuration(cfg).apply {
            densityDpi = dpi
            screenWidthDp = dm.widthPixels * 160 / dpi
            screenHeightDp = dm.heightPixels * 160 / dpi
            smallestScreenWidthDp = min(screenWidthDp, screenHeightDp)
        }
        return base.createConfigurationContext(fixed)
    }
}
