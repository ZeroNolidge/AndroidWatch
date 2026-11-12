package com.androidwatch.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Good = Color(0xFF66BB6A)
private val Bad = Color(0xFFEF5350)

class MainActivity : ComponentActivity() {

    /** Bumped on resume so the checklist re-reads permission state. */
    private val refresh = mutableIntStateOf(0)

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh.intValue++ }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            refresh.intValue // re-compose on resume
            MaterialTheme(colorScheme = darkColorScheme()) { Screen() }
        }
    }

    @Composable
    private fun Screen() {
        val btOk = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        val listenerOk = hasNotificationAccess()
        val notifOk = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Watch Link", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Sends this phone's notifications to your watch's Phone Link app over Bluetooth.",
                color = Color.LightGray, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            Card {
                Text("Status", color = Color.Gray, fontSize = 12.sp)
                val connected = LinkState.watchName.value != null
                Text(LinkState.status.value, color = if (connected) Good else Color.White, fontSize = 16.sp)
            }

            Step(
                n = 1, done = btOk, title = "Allow Bluetooth",
                body = "Needed to talk to the watch.",
                button = "Allow",
            ) { askPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }

            Step(
                n = 2, done = listenerOk, title = "Allow notification access",
                body = "Turn on Watch Link in the list that opens.\n\n" +
                    "If it's greyed out or says \"Restricted setting\": open this phone's " +
                    "Settings → Apps → Watch Link, tap ⋮ (top right) → \"Allow restricted settings\", then try again.",
                button = "Open settings",
            ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

            Step(
                n = 3, done = LinkState.watchName.value != null, title = "Pair the watch",
                body = "Pair the watch with this phone in Bluetooth settings, then open Phone Link on the " +
                    "watch and pick this phone. The status above turns green when they're connected.",
                button = "Bluetooth settings",
            ) { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }

            Spacer(Modifier.height(16.dp))
            Card {
                Text("Test it", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Sends a notification with a Reply button. Reply from the watch and this " +
                        "notification updates with your answer.",
                    color = Color.LightGray, fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    if (notifOk) postTestNotification(this@MainActivity, null)
                    else askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(if (notifOk) "Send test notification" else "Allow notifications first") }
            }
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) { content() }
    }

    @Composable
    private fun Step(n: Int, done: Boolean, title: String, body: String, button: String, onClick: () -> Unit) {
        Card {
            Text(
                (if (done) "✓ " else "$n. ") + title,
                color = if (done) Good else Bad,
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            if (!done) {
                Text(body, color = Color.LightGray, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onClick) { Text(button) }
            }
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val me = ComponentName(this, LinkListenerService::class.java)
        return if (Build.VERSION.SDK_INT >= 27) nm.isNotificationListenerAccessGranted(me)
        else Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(me.flattenToString()) == true
    }
}

private const val TEST_ID = 4242
private const val TEST_CHANNEL = "test"
private const val REPLY_KEY = "reply"

/** Posts (or updates) the test notification. [reply] is the watch's answer, once there is one. */
fun postTestNotification(context: Context, reply: CharSequence?) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_HIGH))

    val builder = Notification.Builder(context, TEST_CHANNEL)
        .setSmallIcon(R.drawable.ic_notif)
        .setContentTitle("Watch Link test")
    if (reply == null) {
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, TestReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build()
        builder.setContentText("Hello from your phone! Reply from the watch.")
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notif), "Reply", pi)
                    .addRemoteInput(input).build(),
            )
    } else {
        builder.setContentText("Watch replied: $reply").setAutoCancel(true)
    }
    nm.notify(TEST_ID, builder.build())
}

class TestReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY) ?: return
        postTestNotification(context, reply)
    }
}
