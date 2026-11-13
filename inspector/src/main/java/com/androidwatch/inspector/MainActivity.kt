package com.androidwatch.inspector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

class MainActivity : ComponentActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = DeviceInfo.collect(this)
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .sortedWith(compareBy({ !Sensors.isVendorPrivate(it) }, { it.type }))

        maybeAskForBodySensors(sensors)
        // Also log the report so it can be read with: adb logcat -s WatchInspector
        reportText(report, sensors).lines().forEach { Log.i(TAG, it) }

        TextZoom.load(this)
        setContent {
            InspectorScreen(
                report = report,
                sensors = sensors,
                sensorManager = sensorManager,
                onSave = { saveReport(report, sensors) },
                onShare = { shareReport(report, sensors) },
            )
        }
    }

    private fun maybeAskForBodySensors(sensors: List<Sensor>) {
        if (Build.VERSION.SDK_INT < 23) return
        val hasHeartRate = sensors.any { it.type == Sensor.TYPE_HEART_RATE || Sensors.isVendorPrivate(it) }
        val granted = checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        if (hasHeartRate && !granted) requestPermission.launch(Manifest.permission.BODY_SENSORS)
    }

    private fun saveReport(report: Report, sensors: List<Sensor>) {
        try {
            // Saved under /sdcard/Android/data/<package>/files — no storage permission needed.
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "watch-inspector-report.txt")
            file.writeText(reportText(report, sensors))
            Toast.makeText(this, "Saved:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareReport(report: Report, sensors: List<Sensor>) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Watch Inspector: ${Build.MODEL}")
            .putExtra(Intent.EXTRA_TEXT, reportText(report, sensors))
        try {
            startActivity(Intent.createChooser(send, "Share report"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app on this watch can share text. Use Save instead.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val TAG = "WatchInspector"

        fun reportText(report: Report, sensors: List<Sensor>): String = buildString {
            appendLine("=== Watch Inspector report ===")
            appendLine(report.verdict.summary)
            if (CellularLive.summary.isNotBlank()) {
                appendLine()
                append(CellularLive.summary)
            }
            for (section in report.sections) {
                appendLine()
                appendLine("## ${section.title}")
                section.rows.forEach { appendLine("${it.label}: ${it.value}") }
            }
            appendLine()
            appendLine("## Sensors (${sensors.size})")
            sensors.forEach { appendLine(Sensors.describe(it)) }
        }
    }
}

/** Live sensor readings, registered only while the row is expanded. */
class SensorReader(
    private val manager: SensorManager,
    private val sensor: Sensor,
    private val onValues: (FloatArray, Int) -> Unit,
) : SensorEventListener {
    fun start() = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    fun stop() = manager.unregisterListener(this)
    override fun onSensorChanged(event: SensorEvent) = onValues(event.values.copyOf(), event.accuracy)
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
