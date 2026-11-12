package com.androidwatch.inspector

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

data class InfoRow(val label: String, val value: String, val warn: Boolean = false)

data class InfoSection(val title: String, val rows: List<InfoRow>)

/** The headline answer: what Android is this watch *really* running? */
data class Verdict(
    val claimed: String,
    val apiLevel: Int,
    val realVersion: String,
    val probedApiLevel: Int,
    val spoofed: Boolean,
    val summary: String,
)

data class Report(val verdict: Verdict, val sections: List<InfoSection>)

object DeviceInfo {

    private val versionBySdk = mapOf(
        21 to "5.0", 22 to "5.1", 23 to "6.0", 24 to "7.0", 25 to "7.1",
        26 to "8.0", 27 to "8.1", 28 to "9", 29 to "10", 30 to "11",
        31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16",
    )

    fun androidVersionFor(sdk: Int): String =
        versionBySdk[sdk] ?: if (sdk > 36) "newer than 16" else "older than 5.0"

    /**
     * Framework classes that first appeared at a given API level. A vendor can
     * edit the version *string* freely, but cannot make these classes exist, so
     * the highest level whose class loads is independent evidence of the real OS.
     */
    private val apiProbes = listOf(
        22 to "android.telephony.SubscriptionManager",
        23 to "android.hardware.fingerprint.FingerprintManager",
        24 to "android.service.quicksettings.TileService",
        25 to "android.content.pm.ShortcutManager",
        26 to "android.app.NotificationChannel",
        27 to "android.app.WallpaperColors",
        28 to "android.graphics.ImageDecoder",
        29 to "android.app.RecoverableSecurityException",
        30 to "android.app.ApplicationExitInfo",
        31 to "android.app.ForegroundServiceStartNotAllowedException",
        33 to "android.window.OnBackInvokedDispatcher",
        34 to "android.credentials.CredentialManager",
    )

    private fun probeApiLevel(): Int {
        var highest = 21
        for ((level, className) in apiProbes) {
            val exists = try {
                Class.forName(className, false, DeviceInfo::class.java.classLoader)
                true
            } catch (_: Throwable) {
                false
            }
            if (exists) highest = level
        }
        return highest
    }

    /** Reads every system property in one shell call. Works without root. */
    private fun readSystemProperties(): Map<String, String> = try {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val regex = Regex("""^\[(.+?)]: \[(.*)]$""")
        process.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { regex.find(it)?.destructured }
                .associate { (k, v) -> k to v }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun majorOf(version: String): Int? =
        version.trim().takeWhile { it.isDigit() }.toIntOrNull()

    private fun buildVerdict(): Verdict {
        val claimed = Build.VERSION.RELEASE ?: "?"
        val sdk = Build.VERSION.SDK_INT
        val real = androidVersionFor(sdk)
        val probed = probeApiLevel()

        val claimedMajor = majorOf(claimed)
        val realMajor = majorOf(real)
        val versionMismatch = claimedMajor != null && realMajor != null && claimedMajor != realMajor
        // Probes stop at 34 and skip 32, so only flag when the API level is
        // clearly higher than what the framework actually contains.
        val sdkInflated = sdk <= 34 && probed < sdk && sdk != 32

        val summary = when {
            sdkInflated ->
                "Faked: reports API $sdk, but the framework only has API $probed " +
                    "features (≈ Android ${androidVersionFor(probed)})."
            versionMismatch ->
                "Faked: claims Android $claimed, but API level $sdk is really Android $real."
            else ->
                "Genuine: Android $real (API $sdk). Note: a Settings screen can still " +
                    "show a different number; trust this one."
        }
        return Verdict(claimed, sdk, real, probed, versionMismatch || sdkInflated, summary)
    }

    fun collect(context: Context): Report {
        val props = readSystemProperties()
        fun prop(key: String) = props[key]?.takeIf { it.isNotBlank() }

        val verdict = buildVerdict()
        val sections = listOf(
            osSection(verdict, ::prop),
            hardwareSection(::prop),
            displaySection(context),
            memorySection(context),
            batterySection(context),
            googleSection(context),
            featuresSection(context),
            telephonySection(context),
        )
        return Report(verdict, sections)
    }

    private fun osSection(v: Verdict, prop: (String) -> String?): InfoSection {
        val rows = mutableListOf(
            InfoRow("Claimed version", v.claimed, warn = v.spoofed),
            InfoRow("API level (SDK_INT)", "${v.apiLevel} → Android ${v.realVersion}"),
            InfoRow("Framework probe", "API ${v.probedApiLevel}+ classes present"),
        )
        prop("ro.product.first_api_level")?.let {
            rows += InfoRow("First API level", "$it (OS it shipped with)")
        }
        prop("ro.vndk.version")?.let { rows += InfoRow("Vendor (VNDK) version", it) }
        prop("ro.treble.enabled")?.let { rows += InfoRow("Project Treble", it) }
        if (Build.VERSION.SDK_INT >= 23) {
            rows += InfoRow("Security patch", Build.VERSION.SECURITY_PATCH)
        }
        rows += InfoRow("Build ID", Build.DISPLAY)
        rows += InfoRow("Fingerprint", Build.FINGERPRINT)
        rows += InfoRow("Kernel", System.getProperty("os.version") ?: "?")
        return InfoSection("Operating system", rows)
    }

    private fun hardwareSection(prop: (String) -> String?): InfoSection {
        val rows = mutableListOf(
            InfoRow("Manufacturer", Build.MANUFACTURER),
            InfoRow("Brand", Build.BRAND),
            InfoRow("Model", Build.MODEL),
            InfoRow("Device", Build.DEVICE),
            InfoRow("Board", Build.BOARD),
            InfoRow("Hardware", Build.HARDWARE),
        )
        val platform = prop("ro.vendor.mediatek.platform")
            ?: prop("ro.mediatek.platform")
            ?: prop("ro.board.platform")
        platform?.let { rows += InfoRow("Chip platform", it) }
        if (Build.VERSION.SDK_INT >= 31) {
            rows += InfoRow("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        cpuInfoHardware()?.let { rows += InfoRow("CPU (cpuinfo)", it) }
        rows += InfoRow("CPU cores", Runtime.getRuntime().availableProcessors().toString())
        maxCpuFreqMhz()?.let { rows += InfoRow("Max CPU clock", "$it MHz") }
        rows += InfoRow("ABIs", Build.SUPPORTED_ABIS.joinToString())
        return InfoSection("Hardware", rows)
    }

    private fun cpuInfoHardware(): String? = try {
        File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Hardware") }
            ?.substringAfter(":")?.trim()
    } catch (_: Exception) {
        null
    }

    private fun maxCpuFreqMhz(): Long? = try {
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf(File::canRead)?.readText()?.trim()?.toLongOrNull() }
            ?.maxOrNull()?.div(1000)
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun displaySection(context: Context): InfoSection {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        val inches = sqrt(
            (m.widthPixels / m.xdpi).let { it * it } + (m.heightPixels / m.ydpi).let { it * it }
        )
        val config = context.resources.configuration
        val rows = mutableListOf(
            InfoRow("Resolution", "${m.widthPixels} × ${m.heightPixels} px"),
            InfoRow("Density", "${m.densityDpi} dpi (×${fmt(m.density)})"),
            InfoRow("Physical size", "≈ ${fmt(inches)}\" diagonal"),
            InfoRow("Usable size", "${config.screenWidthDp} × ${config.screenHeightDp} dp"),
            InfoRow("Smallest width", "${config.smallestScreenWidthDp} dp"),
            InfoRow("Refresh rate", "${fmt(display.refreshRate)} Hz"),
        )
        if (Build.VERSION.SDK_INT >= 23) {
            rows += InfoRow("Round screen", if (config.isScreenRound) "yes" else "no")
        }
        val orientation = if (config.orientation == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
        rows += InfoRow("Orientation", orientation)
        return InfoSection("Display", rows)
    }

    private fun memorySection(context: Context): InfoSection {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        return InfoSection(
            "Memory & storage",
            listOf(
                InfoRow("RAM total", bytes(mi.totalMem)),
                InfoRow("RAM free", bytes(mi.availMem)),
                InfoRow("Per-app heap", "${am.memoryClass} MB (large: ${am.largeMemoryClass} MB)"),
                InfoRow("Low-RAM device", if (am.isLowRamDevice) "yes" else "no", warn = am.isLowRamDevice),
                InfoRow("Storage total", bytes(stat.totalBytes)),
                InfoRow("Storage free", bytes(stat.availableBytes)),
            ),
        )
    }

    private fun batterySection(context: Context): InfoSection {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rows = mutableListOf<InfoRow>()
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val volts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            rows += InfoRow("Level", "${level * 100 / scale.coerceAtLeast(1)}%")
            rows += InfoRow("Charging", if (plugged) "yes" else "no")
            rows += InfoRow("Voltage", "${fmt(volts / 1000f)} V")
            rows += InfoRow("Temperature", "${fmt(temp / 10f)} °C")
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val chargeUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (chargeUah > 0) rows += InfoRow("Charge remaining", "${chargeUah / 1000} mAh")
        return InfoSection("Battery", rows)
    }

    private fun googleSection(context: Context): InfoSection {
        fun version(pkg: String): String? = try {
            context.packageManager.getPackageInfo(pkg, 0).versionName ?: "installed"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val gms = version("com.google.android.gms")
        val store = version("com.android.vending")
        val gsf = version("com.google.android.gsf")
        return InfoSection(
            "Google services",
            listOf(
                InfoRow("Play Services", gms ?: "not installed", warn = gms == null),
                InfoRow("Play Store", store ?: "not installed", warn = store == null),
                InfoRow("Services Framework", gsf ?: "not installed"),
            ),
        )
    }

    private fun featuresSection(context: Context): InfoSection {
        val pm = context.packageManager
        val features = listOf(
            "Cellular (SIM)" to PackageManager.FEATURE_TELEPHONY,
            "GPS" to PackageManager.FEATURE_LOCATION_GPS,
            "Wi-Fi" to PackageManager.FEATURE_WIFI,
            "Bluetooth" to PackageManager.FEATURE_BLUETOOTH,
            "Bluetooth LE" to PackageManager.FEATURE_BLUETOOTH_LE,
            "NFC" to PackageManager.FEATURE_NFC,
            "Camera (back)" to PackageManager.FEATURE_CAMERA,
            "Camera (front)" to PackageManager.FEATURE_CAMERA_FRONT,
            "Microphone" to PackageManager.FEATURE_MICROPHONE,
            "Speaker" to "android.hardware.audio.output",
            "Heart-rate sensor" to PackageManager.FEATURE_SENSOR_HEART_RATE,
            "Step counter" to PackageManager.FEATURE_SENSOR_STEP_COUNTER,
            "Fingerprint" to "android.hardware.fingerprint",
            "USB host" to PackageManager.FEATURE_USB_HOST,
            "Declares itself a watch" to PackageManager.FEATURE_WATCH,
        )
        return InfoSection(
            "Hardware features",
            features.map { (label, feature) ->
                InfoRow(label, if (pm.hasSystemFeature(feature)) "yes" else "no")
            },
        )
    }

    private fun telephonySection(context: Context): InfoSection {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return InfoSection("Cellular", listOf(InfoRow("Modem", "none")))
        val sim = when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "no SIM"
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
            TelephonyManager.SIM_STATE_UNKNOWN -> "unknown"
            else -> "state ${tm.simState}"
        }
        val phoneType = when (tm.phoneType) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM/LTE"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_NONE -> "none"
            else -> "other"
        }
        val rows = mutableListOf(InfoRow("Radio", phoneType), InfoRow("SIM", sim))
        tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { rows += InfoRow("Network", it) }
        return InfoSection("Cellular", rows)
    }

    fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    private fun bytes(b: Long): String {
        val gb = b / (1024.0 * 1024 * 1024)
        return if (gb >= 1) String.format(Locale.US, "%.2f GB", gb)
        else String.format(Locale.US, "%.0f MB", b / (1024.0 * 1024))
    }
}
