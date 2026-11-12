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

    private val detector = RaiseDetector()

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
        detector.reset()
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

        fun isEnabled(context: Context) =
            context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean(PREF, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean(PREF, enabled).apply()
            val intent = Intent(context, RaiseToWakeService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }

        /** Restart the service if it's enabled (e.g. after the system killed it). */
        fun ensureRunning(context: Context) {
            if (isEnabled(context)) context.startForegroundService(Intent(context, RaiseToWakeService::class.java))
        }
    }
}

/**
 * The raise gesture, separated from Android so it can be unit-tested.
 * Feed it raw accelerometer samples (m/s², device axes); returns true when the
 * screen should turn on.
 */
class RaiseDetector {
    private val gravity = FloatArray(3)
    private var haveGravity = false
    private var lastDownAt = 0L   // last time the screen was clearly not facing up
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
            gravity[0] += SMOOTHING * (ax - gravity[0])
            gravity[1] += SMOOTHING * (ay - gravity[1])
            gravity[2] += SMOOTHING * (az - gravity[2])
        }
        val x = gravity[0]
        val y = gravity[1]
        val z = gravity[2]

        // Screen clearly not facing up: arm hanging at your side, swinging, sideways.
        if (z < DOWN_Z) {
            lastDownAt = nowMs
            viewingSince = 0
            return false
        }
        // Facing up toward you: top edge level or tilted up, not rolled sideways.
        val viewing = z > VIEW_Z && y > -2f && y < 7.5f && kotlin.math.abs(x) < 4.5f
        if (!viewing) {
            viewingSince = 0
            return false
        }
        if (viewingSince == 0L) viewingSince = nowMs
        val raisedQuickly = lastDownAt > 0 && viewingSince - lastDownAt < RAISE_WINDOW_MS
        if (raisedQuickly && nowMs - viewingSince >= HOLD_MS && nowMs - lastWakeAt > COOLDOWN_MS) {
            lastWakeAt = nowMs
            lastDownAt = 0
            return true
        }
        return false
    }

    private companion object {
        const val SMOOTHING = 0.25f
        const val DOWN_Z = 3f      // m/s²: below this the screen isn't facing up
        const val VIEW_Z = 6.5f    // m/s²: above this it's facing up
        const val RAISE_WINDOW_MS = 1_500L
        const val HOLD_MS = 200L
        const val COOLDOWN_MS = 3_000L
    }
}
