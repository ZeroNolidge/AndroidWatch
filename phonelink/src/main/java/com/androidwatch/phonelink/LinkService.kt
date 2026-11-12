package com.androidwatch.phonelink

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException

data class PhoneAction(val index: Int, val title: String, val reply: Boolean)

data class PhoneNotification(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val call: Boolean,
    val actions: List<PhoneAction>,
)

/** Everything the UI shows. Only modified on the main thread. */
object WatchState {
    val status = mutableStateOf("Not connected")
    val connected = mutableStateOf(false)
    val phoneName = mutableStateOf<String?>(null)
    val phoneBattery = mutableStateOf<Int?>(null)
    val notifications = mutableStateListOf<PhoneNotification>()
}

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("link", Context.MODE_PRIVATE)
    fun phoneAddress(c: Context): String? = prefs(c).getString("phone", null)
    fun setPhoneAddress(c: Context, address: String?) = prefs(c).edit().putString("phone", address).apply()
}

val QUICK_REPLIES = arrayOf("OK", "Yes", "No", "On my way", "Call you later", "👍")

/**
 * Foreground service that keeps connecting to the phone's Watch Link app,
 * mirrors its notifications here, and sends back taps and replies.
 */
class LinkService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    private var writer: BufferedWriter? = null
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Phone notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800, 400, 800)
            },
        )
        startForeground(STATUS_ID, statusNotification("Connecting…"))
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) stopLoop()
        if (!running) startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ connection loop

    private fun startLoop() {
        running = true
        thread = Thread(::loop, "link-client").apply { isDaemon = true; start() }
    }

    private fun stopLoop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var backoff = 5_000L
        while (running) {
            var wasConnected = false
            val address = Prefs.phoneAddress(this)
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            when {
                address == null -> setStatus("Pick your phone in Phone Link", false)
                adapter == null -> setStatus("No Bluetooth on this watch", false)
                !adapter.isEnabled -> setStatus("Bluetooth is off", false)
                else -> try {
                    val device = adapter.getRemoteDevice(address)
                    val name = runCatching { device.name }.getOrNull() ?: address
                    setStatus("Connecting to $name…", false)
                    val s = device.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
                    socket = s
                    runCatching { adapter.cancelDiscovery() }
                    s.connect()
                    synchronized(this) { writer = s.outputStream.bufferedWriter() }
                    backoff = 5_000L
                    wasConnected = true
                    main.post { WatchState.phoneName.value = name }
                    setStatus("Connected to $name", true)
                    send(JSONObject().put("t", "sync"))
                    s.inputStream.bufferedReader().forEachLine { line ->
                        runCatching { JSONObject(line) }.getOrNull()?.let { msg -> main.post { handle(msg) } }
                    }
                } catch (_: SecurityException) {
                    setStatus("Bluetooth permission missing", false)
                } catch (_: IOException) {
                    // Phone out of range, Bluetooth off, or Watch Link not running.
                } finally {
                    synchronized(this) { writer = null }
                    runCatching { socket?.close() }
                    socket = null
                }
            }
            if (!running) break
            if (wasConnected) setStatus("Phone disconnected, retrying…", false)
            else if (address != null && adapter?.isEnabled == true) {
                setStatus("Phone not reachable, retrying in ${backoff / 1000}s", false)
            }
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    /** Thread-safe. Returns false if the phone isn't connected. */
    fun send(msg: JSONObject): Boolean = synchronized(this) {
        val w = writer ?: return false
        try {
            w.write(msg.toString())
            w.write("\n")
            w.flush()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun setStatus(text: String, connected: Boolean) {
        main.post {
            WatchState.status.value = text
            WatchState.connected.value = connected
            if (!connected) WatchState.phoneBattery.value = null
            nm.notify(STATUS_ID, statusNotification(text))
        }
    }

    // ------------------------------------------------------------ messages from the phone (main thread)

    private fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello" -> {
                WatchState.phoneBattery.value = msg.optInt("battery", -1).takeIf { it >= 0 }
            }
            "clear" -> {
                WatchState.notifications.forEach { nm.cancel(notificationId(it.key)) }
                WatchState.notifications.clear()
            }
            "post" -> {
                val actionsJson = msg.optJSONArray("actions")
                val actions = (0 until (actionsJson?.length() ?: 0)).map {
                    val a = actionsJson!!.getJSONObject(it)
                    PhoneAction(a.optInt("i"), a.optString("title"), a.optBoolean("reply"))
                }
                val n = PhoneNotification(
                    key = msg.getString("key"),
                    app = msg.optString("app"),
                    title = msg.optString("title"),
                    text = msg.optString("text"),
                    time = msg.optLong("when", System.currentTimeMillis()),
                    call = msg.optBoolean("call"),
                    actions = actions,
                )
                val existing = WatchState.notifications.indexOfFirst { it.key == n.key }
                if (existing >= 0) WatchState.notifications.removeAt(existing)
                WatchState.notifications.add(0, n)
                // Only buzz for genuinely new notifications, not updates to ones already shown.
                showNotification(n, alert = existing < 0)
            }
            "remove" -> {
                val key = msg.getString("key")
                WatchState.notifications.removeAll { it.key == key }
                nm.cancel(notificationId(key))
            }
        }
    }

    private fun showNotification(n: PhoneNotification, alert: Boolean) {
        val id = notificationId(n.key)
        val open = PendingIntent.getActivity(
            this, id, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, if (n.call) CH_CALLS else CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(n.title.ifBlank { n.app })
            .setContentText(n.text)
            .setSubText(n.app)
            .setStyle(Notification.BigTextStyle().bigText(n.text))
            .setWhen(n.time)
            .setShowWhen(true)
            .setContentIntent(open)
            .setDeleteIntent(ActionReceiver.dismissIntent(this, n.key, id))
            .setOnlyAlertOnce(!alert)
            .setCategory(if (n.call) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
        if (n.call) builder.setOngoing(true)

        n.actions.take(3).forEach { a ->
            val pi = ActionReceiver.actionIntent(this, n.key, a.index, id)
            val action = Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), a.title, pi)
            if (a.reply) {
                action.addRemoteInput(
                    RemoteInput.Builder(ActionReceiver.REPLY_KEY).setLabel(a.title).setChoices(QUICK_REPLIES).build(),
                )
            }
            builder.addAction(action.build())
        }
        nm.notify(id, builder.build())
    }

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Phone Link")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CH_STATUS = "status"
        private const val CH_MESSAGES = "messages"
        private const val CH_CALLS = "calls"
        private const val STATUS_ID = 1
        private const val ACTION_RESTART = "restart"
        const val FLAG_IMMUTABLE = PendingIntent.FLAG_IMMUTABLE
        val FLAG_MUTABLE = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

        @Volatile var instance: LinkService? = null
            private set

        fun notificationId(key: String) = key.hashCode().let { if (it == STATUS_ID) it + 1 else it }

        fun start(context: Context, restart: Boolean = false) {
            val intent = Intent(context, LinkService::class.java)
            if (restart) intent.action = ACTION_RESTART
            context.startForegroundService(intent)
        }

        /** Sends to the phone; shows a toast if it isn't connected. */
        fun sendOrWarn(context: Context, msg: JSONObject): Boolean {
            val ok = instance?.send(msg) == true
            if (!ok) Toast.makeText(context, "Phone not connected", Toast.LENGTH_SHORT).show()
            return ok
        }

        fun dismiss(context: Context, key: String) {
            sendOrWarn(context, JSONObject().put("t", "dismiss").put("key", key))
            WatchState.notifications.removeAll { it.key == key }
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(key))
        }

        fun action(context: Context, key: String, index: Int) =
            sendOrWarn(context, JSONObject().put("t", "action").put("key", key).put("i", index))

        fun reply(context: Context, key: String, index: Int, text: String) =
            sendOrWarn(context, JSONObject().put("t", "reply").put("key", key).put("i", index).put("text", text))
    }
}

/** Handles taps on the mirrored notifications' buttons and swipe-to-dismiss. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        when (intent.action) {
            ACTION_DISMISS -> LinkService.dismiss(context, key)
            ACTION_TAP -> {
                val index = intent.getIntExtra(EXTRA_INDEX, -1)
                val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)
                if (reply != null) LinkService.reply(context, key, index, reply.toString())
                else LinkService.action(context, key, index)
                // Clear it here; the phone sends an update if the notification stays.
                context.getSystemService(NotificationManager::class.java).cancel(id)
            }
        }
    }

    companion object {
        const val REPLY_KEY = "reply"
        private const val ACTION_TAP = "com.androidwatch.phonelink.TAP"
        private const val ACTION_DISMISS = "com.androidwatch.phonelink.DISMISS"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_ID = "id"

        fun actionIntent(context: Context, key: String, index: Int, id: Int): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java)
                .setAction(ACTION_TAP)
                .putExtra(EXTRA_KEY, key).putExtra(EXTRA_INDEX, index).putExtra(EXTRA_ID, id)
            // Mutable so the system can attach the typed reply.
            return PendingIntent.getBroadcast(
                context, id * 8 + index, intent, PendingIntent.FLAG_UPDATE_CURRENT or LinkService.FLAG_MUTABLE,
            )
        }

        fun dismissIntent(context: Context, key: String, id: Int): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id)
            return PendingIntent.getBroadcast(
                context, id * 8 + 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or LinkService.FLAG_IMMUTABLE,
            )
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.phoneAddress(context) != null) {
            LinkService.start(context)
        }
    }
}
