package com.androidwatch.launcher

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
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
    heartAvailable: Boolean = false,
    onMeasureHeart: () -> Unit = {},
) {
    // Pages: weather (swipe right from the face) · face · apps (swipe left).
    val pager = rememberPagerState(initialPage = PAGE_FACE, pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // Home button / back gesture always return to the face.
    LaunchedEffect(state.homePresses.intValue) { pager.animateScrollToPage(PAGE_FACE) }
    BackHandler(enabled = pager.currentPage != PAGE_FACE) { scope.launch { pager.animateScrollToPage(PAGE_FACE) } }

    MaterialTheme(colorScheme = darkColorScheme()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) { page ->
            when (page) {
                PAGE_WEATHER -> WeatherPage(state, onWeatherTapped)
                PAGE_FACE -> if (isRoundScreen()) RoundWatchFace(
                    state = state,
                    stepsAvailable = stepsAvailable,
                    onLaunch = onLaunch,
                    onOpenApps = { scope.launch { pager.animateScrollToPage(PAGE_APPS) } },
                    onWeatherTapped = { scope.launch { pager.animateScrollToPage(PAGE_WEATHER) } },
                    onOpenClaude = onOpenClaude,
                    heart = if (heartAvailable) onMeasureHeart else null,
                ) else WatchFace(
                    state = state,
                    stepsAvailable = stepsAvailable,
                    onLaunch = onLaunch,
                    onOpenApps = { scope.launch { pager.animateScrollToPage(PAGE_APPS) } },
                    onWeatherTapped = { scope.launch { pager.animateScrollToPage(PAGE_WEATHER) } },
                    onOpenClaude = onOpenClaude,
                    heart = if (heartAvailable) onMeasureHeart else null,
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
    heart: (() -> Unit)? = null,
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
            heart?.let { HeartText(state, 15.sp, it) }
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
        ForecastLine(state, fontSize = 12.sp)
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
    heart: (() -> Unit)? = null,
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

    // Ring and content are layered (Box), not stacked.
    Box(Modifier.fillMaxSize()) {
    // Dial ring: next 12 h of rain/snow around the edge, plus steps progress.
    DialRing(
        forecast = state.forecast.value,
        nowMillis = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
        stepsFraction = if (stepsAvailable) state.steps.value?.let { it / STEP_GOAL.toFloat() } else null,
    )
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = (d * 0.07f).dp, bottom = (d * 0.085f).dp, start = (d * 0.11f).dp, end = (d * 0.11f).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Heart, battery and weather, short enough for the narrow top of the circle.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            heart?.let { HeartText(state, small, it) }
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
        ForecastLine(state, fontSize = small)
        // On small round screens the inner orange arc already shows steps; skip the number.
        if (stepsAvailable && d >= 280f) {
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
}

private const val PAGE_WEATHER = 0
private const val PAGE_FACE = 1
private const val PAGE_APPS = 2
private const val STEP_GOAL = 10_000
private val RainBlue = Color(0xFF4FC3F7)
private val SnowWhite = Color(0xFFE8F4FF)

/** "🌧 Rain from 3 PM (70%)": one line; nothing until a forecast has loaded. */
@Composable
private fun ForecastLine(state: FaceState, fontSize: androidx.compose.ui.unit.TextUnit) {
    val f = state.forecast.value ?: return
    val text = PrecipSummary.summarize(f)
    if (text.isBlank()) return
    Text(text, color = if (text.startsWith("☀")) Muted else RainBlue, fontSize = fontSize, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
}

/**
 * Round-face dial: the ring is the next 12 hours, positioned like a clock
 * (rain at 3 PM sits at the 3 o'clock position). Blue = rain, white = snow,
 * brighter = more likely. An orange dot marks now. The inner arc is steps
 * toward the daily goal.
 */
@Composable
private fun DialRing(forecast: Forecast?, nowMillis: Long, stepsFraction: Float?) {
    val zone = java.time.ZoneId.systemDefault()
    fun angleOf(t: Long): Float {
        val z = java.time.Instant.ofEpochMilli(t).atZone(zone)
        return ((z.hour % 12) * 30f + z.minute * 0.5f) - 90f
    }
    Canvas(Modifier.fillMaxSize()) {
        val d = minOf(size.width, size.height)
        val stroke = d * 0.03f
        val r = d / 2f - stroke / 2f - d * 0.008f
        val topLeft = Offset(center.x - r, center.y - r)
        val arcSize = Size(2 * r, 2 * r)

        // Hour ticks for orientation.
        for (h in 0 until 12) {
            val a = Math.toRadians(h * 30.0 - 90.0)
            val inner = r - stroke * (if (h % 3 == 0) 0.9f else 0.5f)
            drawLine(
                Color(0x55FFFFFF),
                Offset(center.x + (inner * Math.cos(a)).toFloat(), center.y + (inner * Math.sin(a)).toFloat()),
                Offset(center.x + ((r + stroke / 2) * Math.cos(a)).toFloat(), center.y + ((r + stroke / 2) * Math.sin(a)).toFloat()),
                strokeWidth = d * 0.006f,
            )
        }

        // Precipitation over the next 12 hours.
        val windowEnd = nowMillis + 12 * 3_600_000L - 60_000L
        forecast?.hours?.forEach { h ->
            val start = maxOf(h.time, nowMillis)
            val end = minOf(h.time + 3_600_000L, windowEnd)
            if (end <= start || (h.precipChance < 20 && h.precipMm < 0.1)) return@forEach
            val base = if (h.isSnow) SnowWhite else RainBlue
            val alpha = (0.3f + 0.7f * (h.precipChance.coerceIn(0, 100) / 100f)).coerceAtMost(1f)
            var sweep = (end - start) / 3_600_000f * 30f
            if (sweep < 1f) sweep = 1f
            drawArc(base.copy(alpha = alpha), angleOf(start), sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }

        // Now marker.
        val na = Math.toRadians(angleOf(nowMillis).toDouble())
        drawCircle(Color(0xFFFFA726), stroke * 0.75f, Offset(center.x + (r * Math.cos(na)).toFloat(), center.y + (r * Math.sin(na)).toFloat()))

        // Steps toward the goal, on a thin inner ring.
        stepsFraction?.let { fr ->
            val r2 = r - stroke * 1.4f
            val tl2 = Offset(center.x - r2, center.y - r2)
            val s2 = Size(2 * r2, 2 * r2)
            val w = stroke * 0.35f
            drawArc(Color(0x22FFFFFF), 0f, 360f, false, tl2, s2, style = Stroke(w))
            drawArc(Color(0xFFFFA726), -90f, 360f * fr.coerceIn(0f, 1f), false, tl2, s2, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** Swipe right from the face: today's high/low, the rain/snow summary and the next 12 hours. */
@Composable
internal fun WeatherPage(state: FaceState, onRetry: () -> Unit) {
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); nowTick = System.currentTimeMillis() } }
    val f = state.forecast.value
    val w = state.weather.value
    val timeFmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = roundListPadding().let { rp ->
            androidx.compose.foundation.layout.PaddingValues(
                start = rp.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = rp.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = rp.calculateTopPadding() + 12.dp,
                bottom = rp.calculateBottomPadding() + 12.dp,
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                if (w != null) "${w.icon} ${w.temperature}${w.unit} · ${w.description}" else "Weather",
                color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center,
            )
        }
        if (f == null) {
            item {
                Text(
                    state.weatherStatus.value ?: "Loading forecast…",
                    color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(onClick = onRetry).padding(8.dp),
                )
            }
            return@LazyColumn
        }
        item { Text("High ${f.high}° · Low ${f.low}°", color = Accent, fontSize = 13.sp) }
        item {
            val summary = PrecipSummary.summarize(f, nowTick)
            Text(summary, color = if (summary.startsWith("☀")) Muted else RainBlue, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
        }
        f.hours.filter { it.time + 3_600_000L > nowTick }.take(12).forEach { h -> item(key = h.time) {
            Row(
                Modifier.fillMaxWidth().background(Chip, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(timeFmt.format(java.util.Date(h.time)), color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(ForecastClient.icon(h.code), fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text("${h.temperature}°", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (h.precipChance > 0) "💧${h.precipChance}%" else "",
                    color = if (h.wet) RainBlue else Muted, fontSize = 12.sp,
                    modifier = Modifier.width(48.dp), textAlign = TextAlign.End,
                )
            }
        } }
        item {
            Text("Updated ${timeFmt.format(java.util.Date(f.fetchedAt))} · tap to refresh", color = Muted, fontSize = 10.sp,
                modifier = Modifier.clickable(onClick = onRetry).padding(6.dp))
        }
    }
}

/** ❤ 104: bright red while measuring, dim between measurements. Tap to measure again (30 s). */
@Composable
private fun HeartText(state: FaceState, fontSize: androidx.compose.ui.unit.TextUnit, onMeasure: () -> Unit) {
    val bpm = state.heartRate.value
    val measuring = state.heartMeasuring.value
    val text = when {
        bpm != null -> "❤$bpm"
        measuring -> "❤…"
        else -> "❤–"
    }
    Text(
        text,
        color = if (measuring && bpm != null) Color(0xFFFF5252) else Muted,
        fontSize = fontSize,
        modifier = Modifier.clickable(onClick = onMeasure),
    )
}
