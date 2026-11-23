package com.androidwatch.inspector

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub "latest" release for newer builds of the watch
 * apps and installs them. Android always shows its own confirmation before
 * installing; apps can't update silently.
 */
object Updater {
    private const val BASE = "https://github.com/ZeroNolidge/AndroidWatch/releases/download/latest/"

    /** The watch apps, as named in the release. (Watch Link runs on the phone, so it's not listed.) */
    val WATCH_APPS = listOf(
        "watch-inspector.apk" to "Watch Inspector",
        "watch-face.apk" to "Watch Face",
        "phone-link-watch.apk" to "Phone Link",
    )

    data class Latest(val packageName: String, val versionCode: Long, val versionName: String)

    /** Blocking. Asset name → latest version. */
    fun fetchLatest(): Map<String, Latest> {
        val json = JSONObject(httpGet(BASE + "versions.json").readText())
        return json.keys().asSequence().associateWith { asset ->
            val o = json.getJSONObject(asset)
            Latest(o.getString("package"), o.getLong("versionCode"), o.optString("versionName"))
        }
    }

    /** Installed version code, or null if the app isn't installed. */
    fun installedVersion(context: Context, packageName: String): Long? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Android 8+ needs the user to allow this app to install others, once. */
    fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Blocking: downloads the APK, reporting progress 0..1. */
    fun download(context: Context, asset: String, onProgress: (Float) -> Unit): File {
        val file = File(context.cacheDir, asset)
        val conn = openConnection(BASE + asset)
        try {
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** Hands the APK to Android's installer; [InstallResultReceiver] handles the outcome. */
    fun install(context: Context, apk: File, packageName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java), flags,
            )
            session.commit(callback.intentSender)
        }
        apk.delete()
    }

    private fun openConnection(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "AndroidWatch-Updater")
            when (conn.responseCode) {
                in 300..399 -> {
                    // GitHub redirects release downloads to its file servers.
                    current = URL(current, conn.getHeaderField("Location"))
                    conn.disconnect()
                }
                in 200..299 -> return conn
                else -> {
                    val code = conn.responseCode
                    conn.disconnect()
                    throw java.io.IOException("Download failed ($code)")
                }
            }
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun httpGet(url: String) = openConnection(url).inputStream.bufferedReader()
}

/** Receives the installer's result: asks the user to confirm, or reports success/failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "Updated ✓", Toast.LENGTH_SHORT).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun UpdatesCard() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<Map<String, Updater.Latest>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) } // asset being downloaded
    var progress by remember { mutableStateOf(0f) }

    fun check() {
        status = "Checking…"
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                result.onSuccess { latest = it; status = null }
                    .onFailure { status = "Couldn't check: ${it.message ?: "no connection"}" }
            }
        }.start()
    }

    fun update(asset: String, l: Updater.Latest) {
        if (!Updater.canInstall(context)) {
            Toast.makeText(context, "Allow \"Install unknown apps\" for Watch Inspector, then tap Update again", Toast.LENGTH_LONG).show()
            Updater.openInstallPermission(context)
            return
        }
        busy = asset
        progress = 0f
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val result = runCatching {
                val apk = Updater.download(context, asset) { p -> main.post { progress = p } }
                Updater.install(context, apk, l.packageName)
            }
            main.post {
                busy = null
                result.onFailure { status = "Update failed: ${it.message}" }
            }
        }.start()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Text("Updates", color = Color(0xFF90CAF9), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val l = latest
        if (l == null) {
            Text("Checks GitHub for newer versions of the watch apps.", color = Color(0xFF9E9E9E), fontSize = 10.sp)
        } else {
            Updater.WATCH_APPS.forEach { (asset, label) ->
                val info = l[asset]
                val installed = info?.let { Updater.installedVersion(context, it.packageName) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, color = Color.White, fontSize = 12.sp)
                        val line = when {
                            info == null -> "not in the release"
                            installed == null -> "not installed · latest ${info.versionName}"
                            installed >= info.versionCode -> "up to date ✓"
                            else -> "update available (build $installed → ${info.versionCode})"
                        }
                        Text(line, color = Color(0xFF9E9E9E), fontSize = 10.sp)
                    }
                    if (info != null && (installed == null || installed < info.versionCode)) {
                        Spacer(Modifier.width(4.dp))
                        if (busy == asset) {
                            Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                        } else {
                            UpdateButton(if (installed == null) "Install" else "Update", enabled = busy == null) {
                                update(asset, info)
                            }
                        }
                    }
                }
            }
        }
        status?.let { Text(it, color = Color(0xFFFFB74D), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.padding(top = 4.dp))
        UpdateButton(if (l == null) "Check for updates" else "Check again", enabled = busy == null) { check() }
    }
}

@Composable
private fun UpdateButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 34.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
    ) { Text(label, fontSize = 11.sp, maxLines = 1) }
}
