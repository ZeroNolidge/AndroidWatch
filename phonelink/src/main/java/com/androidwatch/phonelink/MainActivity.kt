package com.androidwatch.phonelink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Good = Color(0xFF66BB6A)
private val Accent = Color(0xFFCE93D8)
private val Muted = Color(0xFF9E9E9E)
private val CardBg = Color(0xFF1A1A1A)

class MainActivity : ComponentActivity() {

    private val refresh = mutableIntStateOf(0)
    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh.intValue++
            if (Prefs.phoneAddress(this) != null) LinkService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) askPermissions.launch(needed.toTypedArray())
        else if (Prefs.phoneAddress(this) != null) LinkService.start(this)

        setContent {
            refresh.intValue
            MaterialTheme(colorScheme = darkColorScheme()) { App() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }

    @Composable
    private fun App() {
        var picking by rememberSaveable { mutableStateOf(Prefs.phoneAddress(this) == null) }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 8.dp),
        ) {
            if (picking) {
                PhonePicker(onPicked = { device ->
                    Prefs.setPhoneAddress(this@MainActivity, device.address)
                    picking = false
                    LinkService.start(this@MainActivity, restart = true)
                })
            } else {
                StatusBar(onClick = { picking = true })
                NotificationList()
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    private fun PhonePicker(onPicked: (BluetoothDevice) -> Unit) {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val devices = remember(refresh.intValue) {
            runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
                .sortedBy { it.name ?: it.address }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("Pick your phone", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp))
                Text(
                    "Paired Bluetooth devices. Not listed? Pair the phone first, and install " +
                        "Watch Link on it.",
                    color = Muted, fontSize = 11.sp, lineHeight = 14.sp,
                )
            }
            items(devices, key = { it.address }) { d ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(CardBg, RoundedCornerShape(10.dp))
                        .clickable { onPicked(d) }
                        .padding(10.dp),
                ) {
                    Text(d.name ?: "Unnamed device", color = Color.White, fontSize = 14.sp)
                    Text(d.address, color = Muted, fontSize = 10.sp)
                }
            }
            item {
                Button(
                    onClick = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    modifier = Modifier.padding(vertical = 8.dp),
                ) { Text("Bluetooth settings", fontSize = 12.sp) }
            }
        }
    }

    @Composable
    private fun StatusBar(onClick: () -> Unit) {
        val connected = WatchState.connected.value
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(CardBg, RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (connected) "●" else "○", color = if (connected) Good else Muted, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                WatchState.status.value,
                color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            WatchState.phoneBattery.value?.let { Text("📱$it%", color = Muted, fontSize = 11.sp) }
        }
    }

    @Composable
    private fun NotificationList() {
        val items = WatchState.notifications
        if (items.isEmpty()) {
            Text(
                if (WatchState.connected.value) "No notifications on your phone." else
                    "Make sure Watch Link is set up on your phone and Bluetooth is on. Tap the bar above to change phone.",
                color = Muted, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(8.dp),
            )
            return
        }
        NotificationCards(items)
    }
}

/** List of mirrored notifications; also used by the screenshot test. */
@Composable
fun NotificationCards(items: List<PhoneNotification>, expandFirst: Boolean = false) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.key }) { NotificationCard(it, startExpanded = expandFirst && it == items.first()) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationCard(n: PhoneNotification, startExpanded: Boolean = false) {
    val context = LocalContext.current
    var expanded by rememberSaveable(n.key) { mutableStateOf(n.call || startExpanded) }
    var replyingTo by remember(n.key) { mutableStateOf<PhoneAction?>(null) }
    var draft by remember(n.key) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (n.call) Color(0xFF1B3A1F) else CardBg, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (n.call) "📞 " else "") + n.app,
                color = Accent, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1,
            )
            Text(
                DateUtils.getRelativeTimeSpanString(n.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                color = Muted, fontSize = 10.sp,
            )
        }
        if (n.title.isNotBlank()) {
            Text(n.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Text(
            n.text, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp,
            maxLines = if (expanded) 30 else 2, overflow = TextOverflow.Ellipsis,
        )

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                n.actions.forEach { a ->
                    SmallButton(a.title, highlight = a.reply) {
                        if (a.reply) replyingTo = a else LinkService.action(context, n.key, a.index)
                    }
                }
                if (!n.call) SmallButton("Dismiss", highlight = false) { LinkService.dismiss(context, n.key) }
            }

            replyingTo?.let { action ->
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    QUICK_REPLIES.forEach { r ->
                        SmallButton(r, highlight = false) {
                            if (LinkService.reply(context, n.key, action.index, r)) replyingTo = null
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Type…", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    SmallButton("Send", highlight = true) {
                        if (draft.isNotBlank() && LinkService.reply(context, n.key, action.index, draft.trim())) {
                            draft = ""
                            replyingTo = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, highlight: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlight) Color(0xFF7B1FA2) else Color(0xFF333333),
            contentColor = Color.White,
        ),
    ) { Text(label, fontSize = 12.sp, maxLines = 1) }
}
