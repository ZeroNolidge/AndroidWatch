package com.androidwatch.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/**
 * Turns the screen on when you raise your wrist to look at the watch.
 *
 * While the screen is off we sample the accelerometer about 20 times a second
 * and track gravity. A "raise" is the screen going from not facing up (arm
 * hanging or swinging) to facing up toward your eyes within ~1.5 s, then
 * staying there for 0.2 s. A watch lying still on a table never triggers,
 * because nothing changes.
 *
 * Cost: the processor has to stay awake while the screen is off, so standby
 * battery life drops. That's why it's an opt-in switch.
 */
class RaiseToWakeService : Service(), SensorEventListener {

    private lateinit var sensors: SensorManager
    private lateinit var power: PowerManager
    private var accel: Sensor? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var watching = false

    private var detector = RaiseDetector()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> startWatching()
                Intent.ACTION_SCREEN_ON -> stopWatching()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        power = getSystemService(POWER_SERVICE) as PowerManager
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Raise to wake", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, LauncherActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Raise to wake is on")
                .setContentIntent(open)
                .setOngoing(true)
                .build(),
        )

        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        if (!power.isInteractive) startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        stopWatching()
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private fun startWatching() {
        val sensor = accel ?: return
        if (watching) return
        watching = true
        detector = RaiseDetector(sensitivity(this))
        // Keep the CPU running so sensor readings keep arriving with the screen off.
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchface:raise").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // safety cap; released when the screen turns on
        }
        sensors.registerListener(this, sensor, SAMPLE_PERIOD_US)
    }

    private fun stopWatching() {
        if (!watching) return
        watching = false
        sensors.unregisterListener(this)
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (detector.onSample(event.values[0], event.values[1], event.values[2], SystemClock.elapsedRealtime())) {
            wakeScreen()
        }
    }

    @Suppress("DEPRECATION") // Still the only way for a regular app to turn the screen on.
    private fun wakeScreen() {
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "watchface:wake",
        ).acquire(1_000)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    companion object {
        private const val CHANNEL = "raise_to_wake"
        private const val NOTIFICATION_ID = 7
        private const val SAMPLE_PERIOD_US = 50_000 // 20 Hz

        private const val PREF = "raise_to_wake"
        private const val PREF_SENSITIVITY = "raise_to_wake_sensitivity"

        private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

        fun isEnabled(context: Context) = prefs(context).getBoolean(PREF, false)

        fun sensitivity(context: Context): Sensitivity =
            runCatching { Sensitivity.valueOf(prefs(context).getString(PREF_SENSITIVITY, "NORMAL")!!) }
                .getOrDefault(Sensitivity.NORMAL)

        /** null = off. Takes effect the next time the screen turns off. */
        fun setMode(context: Context, mode: Sensitivity?) {
            prefs(context).edit()
                .putBoolean(PREF, mode != null)
                .putString(PREF_SENSITIVITY, (mode ?: sensitivity(context)).name)
                .apply()
            val intent = Intent(context, RaiseToWakeService::class.java)
            if (mode != null) context.startForegroundService(intent) else context.stopService(intent)
        }

        fun mode(context: Context): Sensitivity? = if (isEnabled(context)) sensitivity(context) else null

        /** Restart the service if it's enabled (e.g. after the system killed it). */
        fun ensureRunning(context: Context) {
            if (isEnabled(context)) context.startForegroundService(Intent(context, RaiseToWakeService::class.java))
        }
    }
}

/** How eager raise-to-wake is. */
enum class Sensitivity(
    val label: String,
    val smoothing: Float,      // how quickly the gravity estimate follows the arm
    val downZ: Float,          // m/s²: below this, the screen isn't facing up
    val viewZ: Float,          // m/s²: above this, it's facing up
    val maxTiltY: Float,       // how far the top edge may tilt toward you
    val maxRollX: Float,       // how far it may roll sideways
    val raiseWindowMs: Long,   // max time from "down" to "looking"
    val holdMs: Long,          // how long to hold the pose before waking
    /** Also start a raise from any not-looking pose, not just clearly down. */
    val fromAnyPose: Boolean,
) {
    NORMAL("NORMAL", 0.25f, 3.0f, 6.5f, 7.5f, 4.5f, 1_500, 200, false),
    HIGH("HIGH", 0.40f, 4.5f, 5.0f, 8.5f, 6.0f, 2_500, 80, true),
}

/**
 * The raise gesture, separated from Android so it can be unit-tested.
 * Feed it raw accelerometer samples (m/s², device axes); returns true when the
 * screen should turn on.
 */
class RaiseDetector(private val s: Sensitivity = Sensitivity.NORMAL) {
    private val gravity = FloatArray(3)
    private var haveGravity = false
    private var lastDownAt = 0L   // last time the screen was not in the viewing pose
    private var viewingSince = 0L // when the current "looking at it" pose started
    private var lastWakeAt = -COOLDOWN_MS

    fun reset() {
        haveGravity = false
        lastDownAt = 0
        viewingSince = 0
    }

    fun onSample(ax: Float, ay: Float, az: Float, nowMs: Long): Boolean {
        // Low-pass filter: keep gravity, drop arm-swing jolts.
        if (!haveGravity) {
            gravity[0] = ax; gravity[1] = ay; gravity[2] = az
            haveGravity = true
        } else {
            gravity[0] += s.smoothing * (ax - gravity[0])
            gravity[1] += s.smoothing * (ay - gravity[1])
            gravity[2] += s.smoothing * (az - gravity[2])
        }
        val x = gravity[0]
        val y = gravity[1]
        val z = gravity[2]

        // Screen clearly not facing up: arm hanging at your side, swinging, sideways.
        if (z < s.downZ) {
            lastDownAt = nowMs
            viewingSince = 0
            return false
        }
        // Facing up toward you: top edge level or tilted up, not rolled sideways.
        val viewing = z > s.viewZ && y > -2.5f && y < s.maxTiltY && kotlin.math.abs(x) < s.maxRollX
        if (!viewing) {
            if (s.fromAnyPose) lastDownAt = nowMs
            viewingSince = 0
            return false
        }
        if (viewingSince == 0L) viewingSince = nowMs
        val raisedQuickly = lastDownAt > 0 && viewingSince - lastDownAt < s.raiseWindowMs
        if (raisedQuickly && nowMs - viewingSince >= s.holdMs && nowMs - lastWakeAt > COOLDOWN_MS) {
            lastWakeAt = nowMs
            lastDownAt = 0
            return true
        }
        return false
    }

    private companion object {
        const val COOLDOWN_MS = 3_000L
    }
}
