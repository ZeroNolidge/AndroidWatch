package com.androidwatch.inspector

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Good = Color(0xFF66BB6A)
private val Bad = Color(0xFFEF5350)
private val Muted = Color(0xFF9E9E9E)
private val CardBg = Color(0xFF1A1A1A)

@Composable
fun InspectorScreen(
    report: Report,
    sensors: List<Sensor>,
    sensorManager: SensorManager,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item { VerdictCard(report.verdict) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    SmallButton("Save report", onSave, Modifier.weight(1f))
                    SmallButton("Share", onShare, Modifier.weight(1f))
                }
            }
            items(report.sections) { SectionCard(it) }
            item { StorageTestCard() }
            item { SectionTitle("Sensors (${sensors.size}) — tap for live data") }
            items(sensors) { SensorRow(it, sensorManager) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun VerdictCard(v: Verdict) {
    val color = if (v.spoofed) Bad else Good
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Real Android version", color = Muted, fontSize = 11.sp)
        Text(
            "Android ${v.realVersion}",
            color = color,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("API ${v.apiLevel} · claims \"${v.claimed}\"", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(v.summary, color = Color.White, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
    ) { Text(label, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = Color(0xFF90CAF9),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
    )
}

@Composable
private fun SectionCard(section: InfoSection) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Text(section.title, color = Color(0xFF90CAF9), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        section.rows.forEach { row ->
            Spacer(Modifier.height(3.dp))
            // Stacked label/value: side-by-side columns don't fit a watch screen.
            Text(row.label, color = Muted, fontSize = 10.sp)
            Text(
                row.value,
                color = if (row.warn) Bad else Color.White,
                fontSize = 12.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun SensorRow(sensor: Sensor, manager: SensorManager) {
    var expanded by rememberSaveable(sensor.name, sensor.type) { mutableStateOf(false) }
    var reading by remember { mutableStateOf("waiting for data… (move the watch / wear it)") }

    if (expanded) {
        DisposableEffect(sensor) {
            val reader = SensorReader(manager, sensor) { values, accuracy ->
                reading = values.joinToString("  ") { DeviceInfo.fmt(it) } + "   (acc $accuracy)"
            }
            if (!reader.start()) reading = "could not start (permission or vendor restriction)"
            onDispose { reader.stop() }
        }
    }

    val isPrivate = Sensors.isVendorPrivate(sensor)
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(8.dp),
    ) {
        Text(
            Sensors.typeName(sensor),
            color = if (isPrivate) Color(0xFFFFB74D) else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("${sensor.name} · ${sensor.vendor}", color = Muted, fontSize = 10.sp, lineHeight = 12.sp)
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(reading, color = Good, fontSize = 12.sp, lineHeight = 14.sp)
        }
    }
}
