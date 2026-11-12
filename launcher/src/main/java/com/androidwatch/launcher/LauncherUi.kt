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
import androidx.compose.foundation.lazy.grid.GridCells
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
                0 -> WatchFace(
                    state = state,
                    stepsAvailable = stepsAvailable,
                    onLaunch = onLaunch,
                    onOpenApps = { scope.launch { pager.animateScrollToPage(1) } },
                )
                else -> AppDrawer(state, onLaunch, onToggleFavorite)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status row: battery left, weather right.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val battery = state.battery.intValue
            Text(
                if (battery < 0) "" else (if (state.charging.value) "⚡$battery%" else "$battery%"),
                color = if (battery in 0..15 && !state.charging.value) Color(0xFFEF5350) else Muted,
                fontSize = 14.sp,
            )
            state.weather.value?.let {
                Text("${it.icon} ${it.temperature}${it.unit}", color = Muted, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            now.format(DateTimeFormatter.ofPattern(timePattern)),
            color = Color.White,
            fontSize = 84.sp,
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
            Box(
                Modifier
                    .background(Chip, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("👟 ${state.steps.value ?: "–"} steps", color = Color.White, fontSize = 15.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        // Favourite apps (long-press an app in the drawer to pin it here).
        val favApps = state.favorites.value.mapNotNull { pkg -> state.apps.value.firstOrNull { it.packageName == pkg } }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            favApps.forEach { app ->
                Image(
                    bitmap = app.icon.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(48.dp)
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
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Apps · long-press to pin",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            textAlign = TextAlign.Center,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
