package com.androidwatch.inspector

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Watch makers often strip menus out of Settings, but the screens themselves
 * are usually still there. These buttons open them directly.
 */
private val SHORTCUTS = listOf(
    "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    "Display" to Settings.ACTION_DISPLAY_SETTINGS,
    "Home app" to Settings.ACTION_HOME_SETTINGS,
    "Keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS,
    "Developer options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
    "Apps" to Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
    "Battery use" to Intent.ACTION_POWER_USAGE_SUMMARY,
    "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
    "Mobile network" to Settings.ACTION_DATA_ROAMING_SETTINGS,
    "Date & time" to Settings.ACTION_DATE_SETTINGS,
    "Language" to Settings.ACTION_LOCALE_SETTINGS,
    "Security" to Settings.ACTION_SECURITY_SETTINGS,
    "About" to Settings.ACTION_DEVICE_INFO_SETTINGS,
)

private fun open(context: Context, action: String, label: String) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, "This watch has no \"$label\" screen", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsShortcutsCard() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Text("Settings shortcuts", color = Color(0xFF90CAF9), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(
            "Opens settings screens that may be hidden from this watch's Settings menu.",
            color = Color(0xFF9E9E9E), fontSize = 10.sp, lineHeight = 13.sp,
        )
        FlowRow(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SHORTCUTS.forEach { (label, action) ->
                Button(
                    onClick = { open(context, action, label) },
                    modifier = Modifier.heightIn(min = 34.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333), contentColor = Color.White),
                ) { Text(label, fontSize = 11.sp, maxLines = 1) }
            }
        }
    }
}
