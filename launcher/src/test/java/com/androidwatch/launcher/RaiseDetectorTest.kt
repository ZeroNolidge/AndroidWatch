package com.androidwatch.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Simulated wrist movements at 20 Hz (50 ms per sample). */
class RaiseDetectorTest {
    private val g = 9.81f
    private val hanging = Triple(-g, 0f, 0.5f)      // arm at your side: screen faces outward
    private val looking = Triple(0.5f, 3.5f, 9.1f)  // screen up, top edge tilted toward you
    private val flat = Triple(0f, 0f, g)            // lying on a table

    /** Runs [samples] through a fresh detector; returns how many times it woke the screen. */
    private fun wakes(samples: List<Triple<Float, Float, Float>>, s: Sensitivity = Sensitivity.NORMAL): Int {
        val d = RaiseDetector(s)
        return samples.withIndex().count { (i, s) -> d.onSample(s.first, s.second, s.third, 1_000L + i * 50L) }
    }

    private fun hold(p: Triple<Float, Float, Float>, ms: Int) = List(ms / 50) { p }

    /** Smoothly rotate from [a] to [b] over [ms]. */
    private fun move(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>, ms: Int) =
        List(ms / 50) { i ->
            val t = (i + 1f) / (ms / 50)
            Triple(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t, a.third + (b.third - a.third) * t)
        }

    @Test fun raiseWristWakesOnce() =
        assertEquals(1, wakes(hold(hanging, 2000) + move(hanging, looking, 500) + hold(looking, 3000)))

    @Test fun lyingOnTableNeverWakes() = assertEquals(0, wakes(hold(flat, 60_000)))

    @Test fun alreadyLookingDoesNotWake() = assertEquals(0, wakes(hold(looking, 10_000)))

    @Test fun slowRaiseDoesNotWake() =
        // Takes 4 s to come up (e.g. stretching): not a deliberate glance.
        assertEquals(0, wakes(hold(hanging, 2000) + move(hanging, looking, 4000) + hold(looking, 2000)))

    @Test fun brushingPastTheViewingPoseDoesNotWake() =
        // Arm passes through face-up for 100 ms and back down.
        assertEquals(0, wakes(hold(hanging, 2000) + move(hanging, looking, 300) + hold(looking, 100) + move(looking, hanging, 300) + hold(hanging, 2000)))

    @Test fun walkingArmSwingDoesNotWake() {
        // Arm hanging, swinging forward/back ±35° at ~1 Hz, plus step jolts.
        val samples = List(30 * 20) { i ->
            val t = i * 0.05
            val swing = 35 * PI / 180 * sin(2 * PI * t)
            val jolt = (2.5 * sin(2 * PI * 2 * t)).toFloat()
            Triple((-g * cos(swing)).toFloat() + jolt, (g * sin(swing)).toFloat(), 0.8f + jolt / 2)
        }
        assertEquals(0, wakes(samples))
    }

    @Test fun twoGlancesFarApartWakeTwice() =
        assertEquals(2, wakes(
            hold(hanging, 2000) + move(hanging, looking, 500) + hold(looking, 2000) +
                move(looking, hanging, 500) + hold(hanging, 5000) + move(hanging, looking, 500) + hold(looking, 2000),
        ))

    // ---- HIGH sensitivity

    private val tilted = Triple(-6f, -2f, 7f) // arm resting at an angle, screen half turned away

    @Test fun highWakesOnSmallerRaiseFromTiltedArm() {
        val samples = hold(tilted, 2000) + move(tilted, looking, 600) + hold(looking, 2000)
        assertEquals(0, wakes(samples, Sensitivity.NORMAL))
        assertEquals(1, wakes(samples, Sensitivity.HIGH))
    }

    @Test fun highWakesOnSlowerRaise() {
        val samples = hold(hanging, 2000) + move(hanging, looking, 4000) + hold(looking, 2000)
        assertEquals(0, wakes(samples, Sensitivity.NORMAL))
        assertEquals(1, wakes(samples, Sensitivity.HIGH))
    }

    @Test fun highStillIgnoresTable() = assertEquals(0, wakes(hold(flat, 60_000), Sensitivity.HIGH))

    @Test fun highStillIgnoresAlreadyLooking() = assertEquals(0, wakes(hold(looking, 10_000), Sensitivity.HIGH))

    @Test fun highStillIgnoresWalking() {
        val samples = List(30 * 20) { i ->
            val t = i * 0.05
            val swing = 35 * PI / 180 * sin(2 * PI * t)
            val jolt = (2.5 * sin(2 * PI * 2 * t)).toFloat()
            Triple((-g * cos(swing)).toFloat() + jolt, (g * sin(swing)).toFloat(), 0.8f + jolt / 2)
        }
        assertEquals(0, wakes(samples, Sensitivity.HIGH))
    }
}
