package com.androidwatch.inspector

import android.hardware.Sensor

/** Readable description of a sensor, used both on screen and in the saved report. */
object Sensors {

    /** Types at or above this value are vendor-private (often HR / SpO2 / blood pressure). */
    private const val TYPE_DEVICE_PRIVATE_BASE = 0x10000

    fun isVendorPrivate(sensor: Sensor) = sensor.type >= TYPE_DEVICE_PRIVATE_BASE

    fun typeName(sensor: Sensor): String {
        val known = when (sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
            Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope"
            Sensor.TYPE_LIGHT -> "Light"
            Sensor.TYPE_PRESSURE -> "Barometer"
            Sensor.TYPE_PROXIMITY -> "Proximity"
            Sensor.TYPE_GRAVITY -> "Gravity"
            Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration"
            Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
            Sensor.TYPE_RELATIVE_HUMIDITY -> "Humidity"
            Sensor.TYPE_AMBIENT_TEMPERATURE -> "Temperature"
            Sensor.TYPE_STEP_DETECTOR -> "Step detector"
            Sensor.TYPE_STEP_COUNTER -> "Step counter"
            Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"
            Sensor.TYPE_HEART_RATE -> "Heart rate"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic rotation"
            else -> null
        }
        val stringType: String? = sensor.stringType
        return when {
            known != null -> known
            isVendorPrivate(sensor) -> "Vendor-private (${stringType ?: sensor.type})"
            stringType != null -> stringType.substringAfterLast('.')
            else -> "Type ${sensor.type}"
        }
    }

    fun describe(sensor: Sensor): String =
        "${typeName(sensor)} — ${sensor.name} (${sensor.vendor}), type ${sensor.type}, " +
            "range ${DeviceInfo.fmt(sensor.maximumRange)}, ${DeviceInfo.fmt(sensor.power)} mA"
}
