package com.androidwatch.inspector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Latest live cellular readout, appended to the shared/saved report. */
object CellularLive {
    @Volatile var summary: String = ""
}

private val Good = Color(0xFF66BB6A)
private val Bad = Color(0xFFEF5350)
private val Muted = Color(0xFF9E9E9E)

/** US T-Mobile LTE bands (SpeedTalk and most US prepaid SIMs run on T-Mobile). */
private val TMOBILE_US_BANDS = setOf(2, 4, 12, 66, 71)

/** LTE downlink EARFCN → band number. */
fun lteBand(earfcn: Int): Int? = when (earfcn) {
    in 0..599 -> 1
    in 600..1199 -> 2
    in 1200..1949 -> 3
    in 1950..2399 -> 4
    in 2400..2649 -> 5
    in 2750..3449 -> 7
    in 3450..3799 -> 8
    in 5010..5179 -> 12
    in 5180..5279 -> 13
    in 5280..5379 -> 14
    in 5730..5849 -> 17
    in 6150..6449 -> 20
    in 8040..8689 -> 25
    in 8690..9039 -> 26
    in 9210..9659 -> 28
    in 9660..9769 -> 29
    in 9770..9869 -> 30
    in 37750..38249 -> 38
    in 38250..38649 -> 39
    in 38650..39649 -> 40
    in 39650..41589 -> 41
    in 66436..67335 -> 66
    in 68586..68935 -> 71
    else -> null
}

private fun networkTypeName(type: Int) = when (type) {
    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
    20 -> "5G NR"
    TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
    TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
    TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
    TelephonyManager.NETWORK_TYPE_GSM -> "2G"
    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "none"
    else -> "type $type"
}

private fun serviceName(state: Int) = when (state) {
    ServiceState.STATE_IN_SERVICE -> "In service"
    ServiceState.STATE_OUT_OF_SERVICE -> "No service"
    ServiceState.STATE_EMERGENCY_ONLY -> "Emergency calls only"
    ServiceState.STATE_POWER_OFF -> "Radio off (airplane mode?)"
    else -> "Unknown ($state)"
}

private fun dataName(state: Int) = when (state) {
    TelephonyManager.DATA_CONNECTED -> "connected"
    TelephonyManager.DATA_CONNECTING -> "connecting…"
    TelephonyManager.DATA_SUSPENDED -> "suspended"
    else -> "not connected"
}

@RequiresApi(24)
private fun describeCell(c: CellInfo): String? {
    val reg = if (c.isRegistered) " ← connected" else ""
    return when (c) {
        is CellInfoLte -> {
            val earfcn = c.cellIdentity.earfcn
            val band = lteBand(earfcn)?.let { "band $it" } ?: "EARFCN $earfcn"
            "4G $band · ${c.cellSignalStrength.dbm} dBm$reg"
        }
        is CellInfoWcdma -> "3G UARFCN ${c.cellIdentity.uarfcn} · ${c.cellSignalStrength.dbm} dBm$reg"
        is CellInfoGsm -> "2G ARFCN ${c.cellIdentity.arfcn} · ${c.cellSignalStrength.dbm} dBm$reg"
        else -> c.javaClass.simpleName.removePrefix("CellInfo") + reg
    }
}

@RequiresApi(24) // band numbers (EARFCN) need Android 7+
@SuppressLint("MissingPermission") // allCellInfo is only read when location permission is granted.
@Composable
fun CellularCard() {
    val context = LocalContext.current
    val tm = remember { context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager }

    var service by remember { mutableStateOf("checking…") }
    var inService by remember { mutableStateOf(false) }
    var signal by remember { mutableStateOf("–") }
    var network by remember { mutableStateOf("–") }
    var data by remember { mutableStateOf("–") }
    var operators by remember { mutableStateOf("–") }
    var cells by remember { mutableStateOf<List<CellInfo>?>(null) }
    var permissionTick by remember { mutableIntStateOf(0) }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    val canScan = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    DisposableEffect(Unit) {
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            override fun onServiceStateChanged(s: ServiceState) {
                service = serviceName(s.state) + if (s.roaming) " (roaming)" else ""
                inService = s.state == ServiceState.STATE_IN_SERVICE
            }

            override fun onSignalStrengthsChanged(s: SignalStrength) {
                signal = "${s.level}/4 bars"
            }
        }
        @Suppress("DEPRECATION")
        tm.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        @Suppress("DEPRECATION")
        onDispose { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    LaunchedEffect(permissionTick) {
        while (true) {
            @Suppress("DEPRECATION")
            network = runCatching { networkTypeName(tm.networkType) }.getOrDefault("?")
            data = dataName(tm.dataState)
            val sim = "${tm.simOperatorName.ifBlank { "?" }} (${tm.simOperator.ifBlank { "?" }})"
            val net = tm.networkOperatorName.ifBlank { "none" } +
                tm.networkOperator.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            operators = "SIM: $sim\nNetwork: $net"
            if (canScan) cells = runCatching { tm.allCellInfo }.getOrNull().orEmpty()
            delay(3000)
        }
    }

    // Plain-language verdict about the US T-Mobile bands.
    val lteBands = cells.orEmpty().filterIsInstance<CellInfoLte>().mapNotNull { lteBand(it.cellIdentity.earfcn) }.toSet()
    val verdict = when {
        inService -> null
        cells == null -> null
        cells!!.isEmpty() -> "The watch can't see any cell towers. On a US T-Mobile SIM (like SpeedTalk) that " +
            "usually means it doesn't support the US 4G bands (2, 4, 12, 66, 71). Make sure location " +
            "is on, then wait a minute: if this list stays empty, the watch won't work on this network."
        lteBands.isNotEmpty() && lteBands.none { it in TMOBILE_US_BANDS } ->
            "It only sees band(s) ${lteBands.sorted().joinToString()}, none of T-Mobile's main US bands."
        else -> "Towers are visible but it isn't registering. Likely causes: the watch's IMEI isn't " +
            "accepted on the network (check it on SpeedTalk's compatibility page), or it needs VoLTE."
    }

    CellularLive.summary = buildString {
        appendLine("## Cellular (live)")
        appendLine("Service: $service")
        appendLine("Network type: $network · Signal: $signal · Data: $data")
        appendLine(operators)
        cells?.let { list ->
            appendLine("Cell towers seen: ${list.size}")
            list.mapNotNull(::describeCell).forEach { appendLine("  $it") }
        }
        verdict?.let { appendLine("Verdict: $it") }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Text("Cellular (live)", color = Color(0xFF90CAF9), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 3.dp)) {
            Text(service, color = if (inService) Good else Bad, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("$network · $signal · data $data", color = Color.White, fontSize = 11.sp)
        Text(operators, color = Color.White, fontSize = 11.sp, lineHeight = 14.sp)

        Spacer(Modifier.height(4.dp))
        if (!canScan) {
            Text("Seeing nearby cell towers (and their bands) needs location permission.", color = Muted, fontSize = 11.sp)
            SmallAction("Scan cell towers") { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
        } else {
            val list = cells.orEmpty()
            Text("Cell towers seen: ${list.size}", color = Muted, fontSize = 11.sp)
            list.mapNotNull(::describeCell).take(12).forEach {
                Text(it, color = Color.White, fontSize = 11.sp)
            }
        }
        verdict?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Color(0xFFFFB74D), fontSize = 11.sp, lineHeight = 14.sp)
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction("Mobile network") {
                runCatching { context.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
            }
            SmallAction("APN settings") {
                runCatching { context.startActivity(Intent(Settings.ACTION_APN_SETTINGS)) }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 34.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
    ) { Text(label, fontSize = 11.sp, maxLines = 1) }
}
