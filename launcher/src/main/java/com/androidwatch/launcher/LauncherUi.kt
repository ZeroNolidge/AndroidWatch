package com.androidwatch.launcher

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val Accent = Color(0xFFFFA726)
private val Muted = Color(0xFF9E9E9E)
private val Chip = Color(0xFF1C1C1C)

@Composable
fun LauncherApp(
    state: FaceState,
    stepsAvailable: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit,
    onWeatherTapped: () -> Unit = {},
    onToggleRaiseToWake: () -> Unit = {},
    onOpenClaude: () -> Unit = {},
) {
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    // Home button / back gesture always return to the face.
    LaunchedEffect(state.homePresses.intValue) { pager.animateScrollToPage(0) }
    BackHandler(enabled = pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }

    MaterialTheme(colorScheme = darkColorScheme()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) { page ->
            when (page) {
                0 -> if (isRoundScreen()) RoundWatchFace(
                    state = state,
                    stepsAvailable = stepsAvailable,
                    onLaunch = onLaunch,
                    onOpenApps = { scope.launch { pager.animateScrollToPage(1) } },
                    onWeatherTapped = onWeatherTapped,
                    onOpenClaude = onOpenClaude,
                ) else WatchFace(
                    state = state,
                    stepsAvailable = stepsAvailable,
                    onLaunch = onLaunch,
                    onOpenApps = { scope.launch { pager.animateScrollToPage(1) } },
                    onWeatherTapped = onWeatherTapped,
                    onOpenClaude = onOpenClaude,
                )
                else -> AppDrawer(state, onLaunch, onToggleFavorite, onToggleRaiseToWake)
            }
        }
    }
}

// ---------------------------------------------------------------- Face

@Composable
private fun WatchFace(
    state: FaceState,
    stepsAvailable: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onOpenApps: () -> Unit,
    onWeatherTapped: () -> Unit,
    onOpenClaude: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val d = minOf(LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.screenHeightDp)
    // Clock shrinks on small screens so "12:45" always fits.
    val clockSize = minOf(84f, d * 0.27f).sp

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Extra inset: the watch screen has rounded corners that clip anything near them.
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status line, centred so the rounded corners can't cut it off.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val battery = state.battery.intValue
            if (battery >= 0) {
                Text(
                    (if (state.charging.value) "⚡" else "🔋") + "$battery%",
                    color = if (battery <= 15 && !state.charging.value) Color(0xFFEF5350) else Color.White,
                    fontSize = 15.sp,
                )
            }
            val weather = state.weather.value
            if (weather != null) {
                Text(
                    "${weather.icon} ${weather.temperature}${weather.unit}",
                    color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onWeatherTapped),
                )
            }
        }
        if (state.weather.value == null) {
            state.weatherStatus.value?.let {
                Text(
                    it, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clickable(onClick = onWeatherTapped)
                        .padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            now.format(DateTimeFormatter.ofPattern(timePattern)),
            color = Color.White,
            fontSize = clockSize,
            fontWeight = FontWeight.Light,
            maxLines = 1,
        )
        Text(
            now.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
            color = Accent,
            fontSize = 18.sp,
        )
        if (!DateFormat.is24HourFormat(context)) {
            Text(now.format(DateTimeFormatter.ofPattern("a")), color = Muted, fontSize = 13.sp)
        }

        Spacer(Modifier.height(14.dp))

        if (stepsAvailable) {
            var showDebug by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .background(Chip, RoundedCornerShape(50))
                    .clickable { showDebug = !showDebug }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("👟 ${state.steps.value ?: "–"} steps", color = Color.White, fontSize = 15.sp)
            }
            if (showDebug) {
                Text(
                    state.stepsDebug.value, color = Muted, fontSize = 10.sp, lineHeight = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp),
                )
            }
            state.stepsSince.value?.let { since ->
                val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(since))
                Text("counting since $time", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        ClaudeBubble(state, onOpenClaude, Modifier.widthIn(max = (d * 0.95f).dp))

        Spacer(Modifier.weight(1f))

        // Favourite apps (long-press an app in the drawer to pin it here).
        val favApps = state.favorites.value.mapNotNull { pkg -> state.apps.value.firstOrNull { it.packageName == pkg } }
            .take(Favorites.MAX)
        val iconSize = 48.dp
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            favApps.forEach { app ->
                Image(
                    bitmap = app.icon.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(iconSize)
                        .clickable { onLaunch(app) },
                )
            }
            AppsButton(onOpenApps)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AppsButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(Chip, RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("⋯", color = Color.White, fontSize = 24.sp)
    }
}

// ---------------------------------------------------------------- Drawer

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppDrawer(
    state: FaceState,
    onLaunch: (AppEntry) -> Unit,
    onToggleFavorite: (AppEntry) -> Unit,
    onToggleRaiseToWake: () -> Unit,
) {
    val context = LocalContext.current
    val round = isRoundScreen()
    val rp = roundListPadding()
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = 6.dp + rp.calculateLeftPadding(LayoutDirection.Ltr),
                end = 6.dp + rp.calculateRightPadding(LayoutDirection.Ltr),
                top = 4.dp + rp.calculateTopPadding(),
                bottom = 4.dp + rp.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Watch Face settings scroll with the list (so they can reach the middle of a round screen).
            item(span = { GridItemSpan(maxLineSpan) }) {
                SettingRow(
                    "✋ Raise to wake",
                    state.raiseToWake.value?.label ?: "OFF",
                    on = state.raiseToWake.value != null,
                    onClick = onToggleRaiseToWake,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SettingRow(
                    "◯ Screen shape",
                    RoundScreen.mode.value.name + if (RoundScreen.mode.value == RoundScreen.Mode.AUTO) {
                        if (round) " (round)" else " (square)"
                    } else "",
                    on = round,
                    onClick = { RoundScreen.cycle(context) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Apps · long-press to pin",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    textAlign = TextAlign.Center,
                )
            }
            items(state.apps.value, key = { it.packageName + "/" + it.activity }) { app ->
                val pinned = app.packageName in state.favorites.value
                Column(
                    Modifier
                        .combinedClickable(
                            onClick = { onLaunch(app) },
                            onLongClick = { onToggleFavorite(app) },
                        )
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(app.icon.asImageBitmap(), app.label, Modifier.size(52.dp))
                    Text(
                        (if (pinned) "★ " else "") + app.label,
                        color = if (pinned) Accent else Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** "✳ Claude" chip: last reply (or a prompt to ask), with an unread count. Tap to chat. */
@Composable
private fun ClaudeBubble(state: FaceState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val unread = state.claudeUnread.intValue
    Row(
        modifier
            .padding(horizontal = 8.dp)
            .background(Color(0xFF2A1D18), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✳", color = Color(0xFFD97757), fontSize = 15.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            state.claudeLast.value ?: "Ask Claude",
            color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (unread > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.background(Color(0xFFD97757), RoundedCornerShape(50)).padding(horizontal = 6.dp),
            ) { Text("$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .background(Chip, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (on) Accent else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Watch face for round screens: everything stacked down the middle, where the
 * circle is widest, and sized to the screen so nothing falls off the edge.
 */
@Composable
private fun RoundWatchFace(
    state: FaceState,
    stepsAvailable: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onOpenApps: () -> Unit,
    onWeatherTapped: () -> Unit,
    onOpenClaude: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val cfg = LocalConfiguration.current
    val d = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat()
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val small = (d * 0.056f).coerceIn(11f, 15f).sp

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = (d * 0.07f).dp, bottom = (d * 0.06f).dp, start = (d * 0.1f).dp, end = (d * 0.1f).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Battery and weather, short enough for the narrow top of the circle.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val battery = state.battery.intValue
            if (battery >= 0) {
                Text(
                    (if (state.charging.value) "⚡" else "🔋") + "$battery%",
                    color = if (battery <= 15 && !state.charging.value) Color(0xFFEF5350) else Color.White,
                    fontSize = small,
                )
            }
            val weather = state.weather.value
            Text(
                if (weather != null) "${weather.icon}${weather.temperature}°" else "🌡…",
                color = if (weather != null) Color.White else Muted, fontSize = small,
                modifier = Modifier.clickable(onClick = onWeatherTapped),
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            now.format(DateTimeFormatter.ofPattern(timePattern)),
            color = Color.White, fontSize = (d * 0.25f).sp, fontWeight = FontWeight.Light, maxLines = 1,
        )
        Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d")), color = Accent, fontSize = (d * 0.064f).sp)
        if (stepsAvailable) {
            var showDebug by remember { mutableStateOf(false) }
            Text(
                "👟 ${state.steps.value ?: "–"}",
                color = Color.White, fontSize = small,
                modifier = Modifier.clickable { showDebug = !showDebug }.padding(top = 2.dp),
            )
            if (showDebug) {
                Text(
                    state.stepsDebug.value, color = Muted, fontSize = 9.sp, lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        ClaudeBubble(state, onOpenClaude, Modifier.widthIn(max = (d * 0.7f).dp))

        Spacer(Modifier.weight(1f))
        // Bottom of the circle is narrow: two pinned apps either side of the app-list button.
        val favApps = state.favorites.value.mapNotNull { pkg -> state.apps.value.firstOrNull { it.packageName == pkg } }.take(2)
        val icon = (d * 0.16f).coerceIn(32f, 44f).dp
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            favApps.getOrNull(0)?.let { app ->
                Image(app.icon.asImageBitmap(), app.label, Modifier.size(icon).clickable { onLaunch(app) })
            }
            Box(
                Modifier.size(icon).background(Chip, RoundedCornerShape(50)).clickable(onClick = onOpenApps),
                contentAlignment = Alignment.Center,
            ) { Text("⋯", color = Color.White, fontSize = 20.sp) }
            favApps.getOrNull(1)?.let { app ->
                Image(app.icon.asImageBitmap(), app.label, Modifier.size(icon).clickable { onLaunch(app) })
            }
        }
    }
}
