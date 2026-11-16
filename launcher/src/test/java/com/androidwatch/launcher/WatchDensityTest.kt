package com.androidwatch.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchDensityTest {
    @Test fun m99TabletConfigIsCorrected() {
        // M99: 480×480 px reported as 640 dp wide.
        val dpi = WatchDensity.correctedDpi(480, 480, 640)!!
        assertEquals(232, dpi)
        assertEquals(331, 480 * 160 / dpi) // ≈ 330 dp wide, like the Mini
    }

    @Test fun m99MiniIsLeftAlone() = assertNull(WatchDensity.correctedDpi(412, 502, 329))

    @Test fun normalPhoneIsLeftAlone() = assertNull(WatchDensity.correctedDpi(1080, 2400, 411))
}
