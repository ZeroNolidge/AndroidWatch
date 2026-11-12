package com.androidwatch.companion

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Forwards the phone's notifications to the watch, and performs the watch's
 * taps (action buttons, replies, dismissals) on the real notifications.
 */
class LinkListenerService : NotificationListenerService() {

    private var server: RfcommServer? = null

    override fun onListenerConnected() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            LinkState.status.value = "This phone has no Bluetooth"
            return
        }
        server = RfcommServer(adapter, ::handle, ::sendHello).also { it.start() }
    }

    override fun onListenerDisconnected() {
        server?.stop()
        server = null
        LinkState.status.value = "Not running: grant notification access"
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldForward(sbn)) server?.send(post(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        server?.send(JSONObject().put("t", "remove").put("key", sbn.key))
    }

    // ------------------------------------------------------------ incoming from the watch

    private fun handle(msg: JSONObject) {
        val key = msg.optString("key")
        when (msg.optString("t")) {
            "sync" -> syncAll()
            "dismiss" -> runCatching { cancelNotification(key) }
            "action" -> findAction(key, msg.optInt("i", -1))?.let { send(it.actionIntent, null) }
            "reply" -> {
                val action = findAction(key, msg.optInt("i", -1)) ?: return
                val inputs = action.remoteInputs ?: return
                val results = Bundle()
                inputs.filter { it.allowFreeFormInput }.forEach {
                    results.putCharSequence(it.resultKey, msg.optString("text"))
                }
                val fill = Intent()
                RemoteInput.addResultsToIntent(inputs, fill, results)
                send(action.actionIntent, fill)
            }
        }
    }

    private fun findAction(key: String, index: Int): Notification.Action? =
        runCatching { activeNotifications }.getOrNull()
            ?.firstOrNull { it.key == key }
            ?.notification?.actions?.getOrNull(index)

    private fun send(pi: PendingIntent?, fill: Intent?) {
        pi ?: return
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Lets actions like "Answer" open the phone's call screen.
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
                pi.send(this, 0, fill, null, null, null, options)
            } else {
                pi.send(this, 0, fill)
            }
        } catch (_: PendingIntent.CanceledException) {
            // The app withdrew the action.
        }
    }

    // ------------------------------------------------------------ outgoing to the watch

    private fun sendHello() {
        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        server?.send(
            JSONObject().put("t", "hello").put("device", Build.MODEL).put("battery", battery),
        )
    }

    private fun syncAll() {
        val srv = server ?: return
        srv.send(JSONObject().put("t", "clear"))
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.filter(::shouldForward)
            .sortedBy { it.postTime }
            .forEach { srv.send(post(it)) }
    }

    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Skip ongoing things like music players and downloads, but keep calls.
        if (sbn.isOngoing && n.category != Notification.CATEGORY_CALL) return false
        val extras = n.extras
        return !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
            !extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    private fun post(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        val actions = JSONArray()
        n.actions?.forEachIndexed { i, a ->
            actions.put(
                JSONObject()
                    .put("i", i)
                    .put("title", a.title?.toString().orEmpty())
                    .put("reply", a.remoteInputs?.any { it.allowFreeFormInput } == true),
            )
        }
        return JSONObject()
            .put("t", "post")
            .put("key", sbn.key)
            .put("app", app)
            .put("title", title)
            .put("text", text)
            .put("when", sbn.postTime)
            .put("call", n.category == Notification.CATEGORY_CALL)
            .put("actions", actions)
    }
}
